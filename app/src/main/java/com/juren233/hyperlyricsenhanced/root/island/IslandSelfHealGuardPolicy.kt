/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

/**
 * 超级岛「自愈误杀」守卫判定（2026-09-29 09:14 真机日志定稿的断供根因）。
 *
 * 原生媒体岛管线：MediaSession 事件 → MediaSortUtils 选 topMediaData →
 * MiuiIslandMediaControllerImpl 上岛。跨 App 岛交接后短时间内，另一 App 的
 * MediaData 重载会触发同 key 冗余更新，窗口层重建岛容器出现瞬态（旧
 * DynamicIslandBackgroundView 已移出窗口、bigIslandStateHandler 仍持有它）；
 * 插件 DynamicIslandEventCoordinator.checkError 在每次事件后做父链健康检查，
 * 把该瞬态判为「状态错乱」，强制派发 DeletedDynamicIsland——**把正在显示的
 * 活岛一并删除**。此后媒体控制器认为 key 未变不再走 add，岛断供直到下一个
 * 媒体数据事件（切歌）。
 *
 * 守卫语义：仅当「被强制删除的 key 属于正在播放的歌词包」且「近期没有该 key
 * 的合法移除（用户划掉/控制器主动 remove）」时，否决这次强制删除事件；其余
 * 一切删除路径（合法移除、非歌词包、未在播）保持原生行为。瞬时数据投喂、
 * 模板管线均不涉及。
 */
internal object IslandSelfHealGuardPolicy {


    /** 合法移除标记的有效窗口：窗口内的同 key 删除视为原生正常移除，不否决。 */
    const val LEGIT_REMOVE_WINDOW_MS = 3_000L

    /**
     * 重建恢复对同 key 合法移除的保护窗口：窗口内不重建（尊重用户主动划掉
     * 岛/通知的意图；媒体岛交接被误拆场景中同 key 从未有合法移除，不受影响）。
     */
    const val LEGIT_REMOVE_RECOVERY_BLOCK_MS = 30_000L

    /** 两次原生重建之间的最小间隔。 */
    const val REBUILD_THROTTLE_MS = 3_000L

    /** 同一 key 一次断供事件内最多重建尝试次数，防无效循环。 */
    const val MAX_REBUILD_ATTEMPTS = 5

    /** notificationKey 形如 `0|cn.kuwo.player|67331|null|10466`，包名在第 2 段。 */
    fun packageNameOfIslandKey(islandKey: String?): String? =
        islandKey
            ?.takeIf { it.isNotEmpty() }
            ?.split('|')
            ?.getOrNull(1)
            ?.takeIf { it.isNotEmpty() }


    fun shouldVetoForceDelete(
        flaggedIslandKey: String?,
        lyricPackageName: String?,
        playbackActive: Boolean,
        lastLegitRemoveKey: String?,
        lastLegitRemoveAtMs: Long,
        nowMs: Long,
    ): Boolean {
        if (flaggedIslandKey.isNullOrEmpty()) return false
        if (!playbackActive) return false
        if (lyricPackageName.isNullOrEmpty()) return false
        if (packageNameOfIslandKey(flaggedIslandKey) != lyricPackageName) return false
        if (lastLegitRemoveKey == flaggedIslandKey &&
            nowMs - lastLegitRemoveAtMs in 0 until LEGIT_REMOVE_WINDOW_MS
        ) {
            return false
        }
        return true
    }

    /**
     * 守卫否决后是否还应触发原生重建：基础守卫条件成立，且被拆 key 没有
     * 30 秒内的合法移除（区别于否决用的 3 秒窗——重建必须更保守，避免把
     * 用户刚划掉的岛立刻加回来）。
     */
    fun shouldRecoverAfterDetach(
        detachedIslandKey: String?,
        lyricPackageName: String?,
        playbackActive: Boolean,
        lastLegitRemoveKey: String?,
        lastLegitRemoveAtMs: Long,
        nowMs: Long,
    ): Boolean {
        if (!shouldVetoForceDelete(
                flaggedIslandKey = detachedIslandKey,
                lyricPackageName = lyricPackageName,
                playbackActive = playbackActive,
                lastLegitRemoveKey = lastLegitRemoveKey,
                lastLegitRemoveAtMs = lastLegitRemoveAtMs,
                nowMs = nowMs,
            )
        ) {
            return false
        }
        if (lastLegitRemoveKey == detachedIslandKey &&
            nowMs - lastLegitRemoveAtMs in 0 until LEGIT_REMOVE_RECOVERY_BLOCK_MS
        ) {
            return false
        }
        return true
    }

    /** 重建尝试节流：间隔不足或同 key 次数用尽则不再尝试。 */
    fun shouldAttemptRebuild(
        rebuildKey: String?,
        lastRebuildKey: String?,
        lastRebuildAtMs: Long,
        consecutiveAttempts: Int,
        nowMs: Long,
    ): Boolean {
        if (rebuildKey.isNullOrEmpty()) return false
        if (rebuildKey != lastRebuildKey) return true
        if (consecutiveAttempts >= MAX_REBUILD_ATTEMPTS) return false
        return nowMs - lastRebuildAtMs >= REBUILD_THROTTLE_MS
    }
}
