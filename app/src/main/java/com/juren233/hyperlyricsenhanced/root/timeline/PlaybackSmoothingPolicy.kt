/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.timeline

/**
 * 渲染层播放态合成（缓冲假暂停平滑）。
 *
 * 部分音乐 app（如 Apple Music）缓冲期间向 MediaSession 上报暂停态而非 BUFFERING；
 * 统一时间轴若只信 MediaSession，缓冲瞬间渲染层就会收到"暂停"，触发岛缩回
 * （2026-09-20 真机日志：GLORIA 缓冲 3.4s 假暂停 → 延迟恢复原生媒体岛执行 → 无内容）。
 * 旧架构用的是 app 进程内上报的播放态（缓冲期间仍为播放中），无此问题。
 *
 * 合成规则：MediaSession 播放中 → 播放；否则，来源侧（app 进程内）提示"播放中"
 * 且与锚点曲目同包 → 仍视为播放（缓冲平滑）。真正的用户暂停会让来源侧也转暂停，
 * 提示立即失效，不影响暂停语义。同一 app 切歌时保留该包级提示：
 * 来源回调可能早于 MediaSession 元数据到达，无条件清空会丢掉整个缓冲窗口的
 * 唯一播放意图证据。跨 app 或会话清空会立即作废旧提示。
 */
internal object PlaybackSmoothingPolicy {

    /** A stale source activity hint may bridge a brief false pause, but cannot own playback forever. */
    const val INACTIVE_ANCHOR_GRACE_MS = 8_000L
    const val SOURCE_PROGRESS_FRESH_MS = 7_000L

    fun allowsSourceHint(
        inactiveAnchorAgeMs: Long?,
        sourceProgressAgeMs: Long?,
    ): Boolean = inactiveAnchorAgeMs == null ||
        inactiveAnchorAgeMs in 0..INACTIVE_ANCHOR_GRACE_MS ||
        sourceProgressAgeMs?.let { it in 0..SOURCE_PROGRESS_FRESH_MS } == true

    enum class EmptyLyricsFallbackAction {
        KEEP_CURRENT_CONTENT,
        PRESERVE_HOST,
        FULL_RESET,
    }

    fun effectivePlaying(
        anchorPlaying: Boolean,
        hintPlaying: Boolean,
        hintPackage: String?,
        anchorPackage: String?,
    ): Boolean {
        if (anchorPlaying) return true
        if (!hintPlaying) return false
        if (hintPackage.isNullOrEmpty() || anchorPackage.isNullOrEmpty()) return false
        return hintPackage == anchorPackage
    }

    fun shouldRetainHint(
        hintPackage: String?,
        nextAnchorPackage: String?,
    ): Boolean {
        if (hintPackage.isNullOrEmpty() || nextAnchorPackage.isNullOrEmpty()) return false
        return hintPackage == nextAnchorPackage
    }

    /** 同包且仍有合成播放意图时，切歌只替换内容，不销毁当前岛宿主。 */
    fun shouldPreserveHostAcrossTrackChange(
        currentPackage: String?,
        nextPackage: String?,
        effectivePlaying: Boolean,
    ): Boolean {
        if (!effectivePlaying) return false
        if (currentPackage.isNullOrEmpty() || nextPackage.isNullOrEmpty()) return false
        return currentPackage == nextPackage
    }

    /**
     * 歌曲身份可能先于歌词到达。空歌词修订不能把仍在播放的同包切歌降级成完整停止，
     * 否则真实 sink 会进入暂停态，而内部合成播放态仍为 true，后续有词修订无法恢复。
     */
    fun emptyLyricsFallbackAction(
        appliedTrackKey: String?,
        nextTrackKey: String,
        currentPackage: String?,
        nextPackage: String?,
        renderedPlaying: Boolean?,
    ): EmptyLyricsFallbackAction {
        if (appliedTrackKey == nextTrackKey) {
            return EmptyLyricsFallbackAction.KEEP_CURRENT_CONTENT
        }
        return if (
            shouldPreserveHostAcrossTrackChange(
                currentPackage = currentPackage,
                nextPackage = nextPackage,
                effectivePlaying = renderedPlaying == true,
            )
        ) {
            EmptyLyricsFallbackAction.PRESERVE_HOST
        } else {
            EmptyLyricsFallbackAction.FULL_RESET
        }
    }

    /** 内部缓存态与真实消费端任一不一致时都必须补发，避免 sink 永久停在旧状态。 */
    fun shouldDispatchPlaybackState(
        effectivePlaying: Boolean,
        renderedPlaying: Boolean?,
        sinkPlaying: Boolean?,
    ): Boolean = effectivePlaying != renderedPlaying ||
        (sinkPlaying != null && sinkPlaying != effectivePlaying)

    /**
     * 位置循环跟随渲染层的合成播放态，而不是底层锚点某一帧是否正在推进。
     * BUFFERING 时循环保持存活并读到冻结位置；锚点恢复 PLAYING 后可自然继续前进。
     */
    fun shouldDrivePositionLoop(
        appliedTrackKey: String?,
        renderedPlaying: Boolean?,
    ): Boolean = appliedTrackKey != null && renderedPlaying == true
}
