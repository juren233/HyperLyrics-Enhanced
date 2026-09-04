/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSES/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

/**
 * 模块 app 侧发布、SystemUI 侧消费的“当前存在 MediaSession 的包名集合”快照。
 *
 * 数据源是 app 侧 `MetadataSource` 的 `MediaSessionManager.getActiveSessions` 真值，
 * 与任何 Provider 的发布内容无关。编码格式为 `"<发布时刻 wall clock 毫秒>|<逗号分隔的包名>"`。
 * 快照携带时间戳：发布方周期性重发同一集合以表明监控仍存活，
 * 消费方在快照过期时按 fail-open 处理，避免 app 进程死亡后永久阻断。
 */
object ActiveMediaSessionSnapshot {
    private const val SEPARATOR = '|'
    private const val PACKAGE_SEPARATOR = ','

    /** 快照超过该时长未被刷新时视为监控失联，消费方不得据此阻断。 */
    const val STALE_AFTER_MS = 300_000L

    data class Snapshot(
        val publishedAtMs: Long,
        val packages: Set<String>,
    ) {
        fun isStale(nowWallClockMs: Long): Boolean =
            nowWallClockMs - publishedAtMs > STALE_AFTER_MS
    }

    fun encode(publishedAtMs: Long, packages: Set<String>): String {
        val names = packages
            .filter { name ->
                name.indexOf(SEPARATOR) < 0 && name.indexOf(PACKAGE_SEPARATOR) < 0
            }
            .sorted()
        return buildString {
            append(publishedAtMs)
            append(SEPARATOR)
            names.joinTo(this, PACKAGE_SEPARATOR.toString())
        }
    }

    fun decode(raw: String?): Snapshot? {
        if (raw.isNullOrEmpty()) return null
        val separatorIndex = raw.indexOf(SEPARATOR)
        if (separatorIndex <= 0) return null
        val publishedAt = raw.take(separatorIndex).toLongOrNull() ?: return null
        val packages = raw.substring(separatorIndex + 1)
            .split(PACKAGE_SEPARATOR)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
        return Snapshot(publishedAtMs = publishedAt, packages = packages)
    }
}
