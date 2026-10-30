/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

/**
 * 目标进程加载插件前的崩溃计数标记（HLE 插件化计划 1.4）。
 *
 * 标记在加载前写入目标 App 自己的 filesDir（libxposed 在被 Hook 进程里
 * 只提供只读的远程偏好/远程文件，写入必须走目标进程自身的存储）、进程
 * 健康运行一段时间后清除；进程在清除前死亡则标记留存，下次启动读到同
 * 版本标记即视为一次未正常退出的加载。累计达到 [DEFAULT_MAX_STRIKES] 次
 * 后放弃该版本，改载上一版本并把该版本记入坏版本文件。
 *
 * 标记含最近一次尝试的时间戳：超过 [STRIKE_DECAY_MS] 的旧计数衰减为 1，
 * 避免很久以前的两次异常（例如用户两次快速强停）在重装同版本后仍触发回退。
 *
 * secondary process 生命周期天然更短，不参与计数（由调用方保证）。
 */
object ProviderLoadMarker {
    const val DEFAULT_MAX_STRIKES = 2

    /** 旧计数过期时间：7 天。 */
    const val STRIKE_DECAY_MS = 7L * 24 * 60 * 60 * 1000

    data class MarkerState(
        val versionCode: Int,
        val uncleanCount: Int,
        val lastAttemptAtMs: Long,
        /** 写入标记的进程 pid；旧格式标记没有，为 0。用于核对该进程上次的退出原因。 */
        val pid: Int = 0,
    )

    sealed interface Decision {
        /** 正常加载 [versionCode]，加载前应把标记覆写为 [newMarker]。 */
        data class LoadNormally(
            val versionCode: Int,
            val newMarker: MarkerState,
        ) : Decision

        /**
         * [badVersionCode] 累计未正常退出次数达到上限；若有可用上一版本则
         * [fallbackVersionCode] 非空，否则只能跳过该插件。
         */
        data class Rollback(
            val badVersionCode: Int,
            val fallbackVersionCode: Int?,
        ) : Decision
    }

    fun encode(state: MarkerState): String =
        "${state.versionCode}:${state.uncleanCount}:${state.lastAttemptAtMs}" +
            if (state.pid > 0) ":${state.pid}" else ""

    fun decode(raw: String?): MarkerState? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.trim().split(':')
        if (parts.size != 3 && parts.size != 4) return null
        val versionCode = parts[0].toIntOrNull() ?: return null
        val count = parts[1].toIntOrNull() ?: return null
        val lastAttemptAtMs = parts[2].toLongOrNull() ?: return null
        val pid = if (parts.size == 4) parts[3].toIntOrNull() ?: return null else 0
        if (versionCode <= 0 || count <= 0 || lastAttemptAtMs <= 0 || pid < 0) return null
        return MarkerState(versionCode, count, lastAttemptAtMs, pid)
    }

    /**
     * [badVersionCodes] 是目标进程已记录的坏版本：一经记录就不再加载（不随标记衰减恢复），
     * 只有用户手动重装该版本时由 App 侧清除。上一版本同样不能是坏版本。
     */
    fun decide(
        pendingVersionCode: Int,
        marker: MarkerState?,
        previousVersionCode: Int,
        badVersionCodes: Set<Int> = emptySet(),
        /**
         * 留存标记的那次进程退出是否为应用自身崩溃。用户划掉、强制停止、系统回收等
         * 不是插件导致的异常，不应计入崩溃次数；此时按「该次加载正常」处理。
         */
        previousExitWasCrash: Boolean = true,
        maxStrikes: Int = DEFAULT_MAX_STRIKES,
        nowMs: Long = System.currentTimeMillis(),
    ): Decision {
        val fallbackVersionCode = previousVersionCode
            .takeIf { it > 0 && it != pendingVersionCode && it !in badVersionCodes }
        if (pendingVersionCode in badVersionCodes) {
            return Decision.Rollback(pendingVersionCode, fallbackVersionCode)
        }
        if (marker != null && marker.versionCode == pendingVersionCode) {
            val decayed = nowMs - marker.lastAttemptAtMs > STRIKE_DECAY_MS
            // 标记里的计数包含上一次加载本身；该次不是崩溃退出时把它扣掉。
            val crashStrikes = if (previousExitWasCrash) marker.uncleanCount else marker.uncleanCount - 1
            if (!decayed && crashStrikes >= maxStrikes) {
                return Decision.Rollback(
                    badVersionCode = pendingVersionCode,
                    fallbackVersionCode = fallbackVersionCode,
                )
            }
            val nextCount = if (decayed) 1 else crashStrikes + 1
            return Decision.LoadNormally(
                versionCode = pendingVersionCode,
                newMarker = MarkerState(pendingVersionCode, nextCount, nowMs),
            )
        }
        // 没有标记（该版本首次加载）、残留了旧版本的标记、或格式损坏，都从 1 重新计数。
        return Decision.LoadNormally(
            versionCode = pendingVersionCode,
            newMarker = MarkerState(pendingVersionCode, 1, nowMs),
        )
    }
}
