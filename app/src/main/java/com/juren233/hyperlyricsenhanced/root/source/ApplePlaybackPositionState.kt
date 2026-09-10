/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hyperlyricsenhanced.root.source

/**
 * Owns the Apple playback position/session state as one minimal consistency unit.
 *
 * 包含：当前曲目代次、中央/直连位置参照、当前直连歌曲 ID、最后调整位置，以及媒体会话
 * 观察值（mediaKey、播放状态）。这些值此前散落在 Source、MediaMonitor、DirectBridge、
 * AppleSongHandling 与 Fallback 五处直接读写；owner 提供具名入口与一致快照，但**不改变**：
 *
 * - 三种时钟来源（central/direct/media）的区分与 `AppleCentralPositionPolicy` 决策；
 * - delay、暂停/seek、原生/兜底位置抑制与同曲替换恢复规则；
 * - 参照对象的 volatile 可见性（writer 可能来自 binder/回调线程）。
 */
internal class ApplePlaybackPositionState {
    /** 一致快照：一次性读取代次与两个参照，避免拼接跨代次 getter。 */
    data class Snapshot(
        val songGeneration: Int,
        val mediaReference: AppleCentralPositionPolicy.MediaReference?,
        val directReference: AppleCentralPositionPolicy.DirectReference?,
        val lastAdjustedPosition: Long,
    )

    @Volatile
    private var songGeneration = 0

    @Volatile
    private var mediaReference: AppleCentralPositionPolicy.MediaReference? = null

    @Volatile
    private var directReference: AppleCentralPositionPolicy.DirectReference? = null

    @Volatile
    private var directSongId: String? = null

    private var observedMediaKey: String? = null
    private var mediaPlaybackState: Boolean? = null

    /** lastAdjustedPosition 只由 Source 发布路径与直连/中央回调写入，保持主线程语义。 */
    var lastAdjustedPosition: Long = 0L

    fun songGeneration(): Int = songGeneration

    fun snapshot(): Snapshot = Snapshot(
        songGeneration = songGeneration,
        mediaReference = mediaReference,
        directReference = directReference,
        lastAdjustedPosition = lastAdjustedPosition,
    )

    fun mediaReference(): AppleCentralPositionPolicy.MediaReference? = mediaReference

    fun setMediaReference(value: AppleCentralPositionPolicy.MediaReference?) {
        mediaReference = value
    }

    fun directReference(): AppleCentralPositionPolicy.DirectReference? = directReference

    fun setDirectReference(value: AppleCentralPositionPolicy.DirectReference?) {
        directReference = value
    }

    fun directSongId(): String? = directSongId

    fun setDirectSongId(value: String?) {
        directSongId = value
    }

    fun observedMediaKey(): String? = observedMediaKey

    fun setObservedMediaKey(value: String?) {
        observedMediaKey = value
    }

    fun mediaPlaybackState(): Boolean? = mediaPlaybackState

    fun setMediaPlaybackState(value: Boolean?) {
        mediaPlaybackState = value
    }

    /**
     * 开始新曲目：推进代次并清空位置参照与调整位置。
     *
     * 与原实现一致，**不清空** direct song id：切歌只重置位置参照，直连歌曲身份保留到下一次
     * `onDirectSongChanged` 覆写，否则会误伤同一动画/回调窗口内仍在进行的直连匹配。
     */
    fun beginSongGeneration(): Int {
        songGeneration += 1
        mediaReference = null
        directReference = null
        lastAdjustedPosition = 0L
        return songGeneration
    }

    /** 是否有已记录的直连歌曲 ID。 */
    val hasDirectSongId: Boolean get() = directSongId != null

    /** 停止/清理：清空参照与直连 ID，但不推进代次（与 reset 语义一致）。 */
    fun clearReferences() {
        mediaReference = null
        directReference = null
        directSongId = null
    }

    /**
     * 计算新的可恢复位置并写入。保持 `AppleCentralPositionPolicy.restorablePosition`
     * 的调用位置与参数顺序。
     */
    fun applyRestorablePosition(
        resolution: AppleCentralPositionPolicy.Resolution,
    ): Long {
        val next = AppleCentralPositionPolicy.restorablePosition(
            previousPosition = lastAdjustedPosition,
            resolution = resolution,
        )
        lastAdjustedPosition = next
        return next
    }
}
