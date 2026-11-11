/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.online.model.Source

/**
 * Owns the in-flight manual source-switch requests for Apple Music.
 *
 * 正文、翻译、发音分别表达：每个内容类型各自持有「请求中的来源」与「待完成请求」。
 * 已确认的正文来源仍由 [LyriconPublication] 持有，本 owner 不复制第二份权威选择。
 *
 * These fields were main-thread confined `var`s; the owner keeps plain fields and adds no
 * monitor, so request/confirm/clear ordering and the [OnlineSourceSwitchRequest.requestId]
 * identity checks are unchanged.
 */
internal class AppleManualSourceRequestState {
    private var temporaryTranslationSource: Source? = null
    private var temporaryPronunciationSource: Source? = null
    private var pendingTranslationRequest: OnlineSourceSwitchRequest? = null
    private var pendingPronunciationRequest: OnlineSourceSwitchRequest? = null
    private var pendingLyricsRequest: OnlineSourceSwitchRequest? = null
    private var translationNativeOnly = false
    private var pronunciationNativeOnly = false
    private var sourceStatusSongId: String? = null

    /** Content type used by [OnlineSourceSwitchRequest] for the lyrics/正文 track. */
    fun acceptLyricsRequest(request: OnlineSourceSwitchRequest) {
        pendingLyricsRequest = request
    }

    fun acceptTranslationRequest(request: OnlineSourceSwitchRequest, source: Source) {
        temporaryTranslationSource = source
        pendingTranslationRequest = request
        translationNativeOnly = false
    }

    fun acceptPronunciationRequest(request: OnlineSourceSwitchRequest, source: Source) {
        temporaryPronunciationSource = source
        pendingPronunciationRequest = request
        pronunciationNativeOnly = false
    }

    /** 用户在来源弹窗里选了「Apple Music 原生」：该内容类型本曲只用原生，不再由在线源补全。 */
    fun selectNativeTranslation() {
        temporaryTranslationSource = null
        pendingTranslationRequest = null
        translationNativeOnly = true
    }

    fun selectNativePronunciation() {
        temporaryPronunciationSource = null
        pendingPronunciationRequest = null
        pronunciationNativeOnly = true
    }

    fun isTranslationNativeOnly(): Boolean = translationNativeOnly

    fun isPronunciationNativeOnly(): Boolean = pronunciationNativeOnly

    /** 来源弹窗已为该曲请求过各来源状态；同曲后续的歌词更新要继续保持检索结果。 */
    fun noteSourceStatusRequest(songId: String) {
        sourceStatusSongId = songId
    }

    /**
     * 该曲是否有来源弹窗相关的手动状态：请求过来源状态、选过来源或选过原生。
     * 这些状态与「补全翻译与发音」等自动开关无关，检索与发布都要照常进行。
     */
    fun hasSourceControlInterest(songId: String?): Boolean =
        (songId != null && sourceStatusSongId == songId) ||
            hasTemporarySource || translationNativeOnly || pronunciationNativeOnly

    /** 请求中的翻译/发音来源；未请求时为空，已确认来源不在此表达。 */
    fun requestedTranslationSource(): Source? = temporaryTranslationSource

    fun requestedPronunciationSource(): Source? = temporaryPronunciationSource

    /**
     * [source] 是否正是尚未完成的翻译/发音切换请求所点选的来源。只有这一轮才允许绕过「未命中」缓存强制重查；
     * 请求完成后来源的选择仍保留，但之后的检索（如重开弹窗）不再重复强制重查。
     */
    fun isRetryRequested(source: Source): Boolean =
        pendingTranslationRequest?.requestedSource == source ||
            pendingPronunciationRequest?.requestedSource == source

    /** 任一在线（翻译/发音）手动请求的来源，用于 RACE 决策的优先顺序。 */
    fun requestedOnlineSource(): Source? =
        pendingTranslationRequest?.requestedSource
            ?: pendingPronunciationRequest?.requestedSource

    val hasTemporarySource: Boolean
        get() = temporaryTranslationSource != null || temporaryPronunciationSource != null

    /** 三个待完成请求，按歌词/翻译/发音顺序，供来源切换诊断挑选当前请求。 */
    fun pendingRequests(): List<OnlineSourceSwitchRequest> = listOfNotNull(
        pendingLyricsRequest,
        pendingTranslationRequest,
        pendingPronunciationRequest,
    )

    /**
     * 该曲是否仍有未完成的在线（翻译/发音）手动请求。
     *
     * A missing request never matches, and a request whose song id is non-null never matches a
     * null [songId]; this mirrors the original `takeIf { it.songId == songId } != null` checks.
     */
    fun hasPendingOnlineRequest(songId: String?): Boolean =
        pendingTranslationRequest?.takeIf { it.songId == songId } != null ||
            pendingPronunciationRequest?.takeIf { it.songId == songId } != null

    /**
     * 取得应完成的歌词请求：无请求或请求目标与 [requestedSource] 不符时返回 null。
     * 只读取，不清理；完成后由 [clearLyricsRequest] 按身份清除。
     */
    fun lyricsRequestToComplete(requestedSource: Source?): OnlineSourceSwitchRequest? {
        val request = pendingLyricsRequest ?: return null
        if (requestedSource != null && request.requestedSource != requestedSource) return null
        return request
    }

    fun clearLyricsRequest(request: OnlineSourceSwitchRequest) {
        if (pendingLyricsRequest === request) pendingLyricsRequest = null
    }

    /** 取得本次要发布的翻译/发音请求与解析出的实际来源，只读取。 */
    fun onlineRequestsToComplete(
        translationSource: Source?,
        pronunciationSource: Source?,
    ): List<Pair<OnlineSourceSwitchRequest, Source?>> = listOfNotNull(
        pendingTranslationRequest?.let { it to translationSource },
        pendingPronunciationRequest?.let { it to pronunciationSource },
    )

    /** 发布后清除两个在线请求；不清除临时来源，与切歌前的保留行为一致。 */
    fun clearOnlineRequests() {
        pendingTranslationRequest = null
        pendingPronunciationRequest = null
    }

    /** 发布前失败：按 contentType + requestId 身份清除，旧回调不得清理新请求。 */
    fun failRequest(request: OnlineSourceSwitchRequest) {
        when (request.contentType) {
            "translation" -> {
                if (pendingTranslationRequest?.requestId == request.requestId) {
                    pendingTranslationRequest = null
                }
            }
            "pronunciation" -> {
                if (pendingPronunciationRequest?.requestId == request.requestId) {
                    pendingPronunciationRequest = null
                }
            }
        }
    }

    /**
     * 本曲的手动选择与尚未完成的翻译/发音切换请求。应用内 Apple 歌曲在活动播放者变化时，凭它把同一首歌的
     * 选择在 [AppleInAppSong] 与正式状态之间移交，不让用户刚选的来源丢失、也不让待完成的请求悬空。
     * 歌词来源请求不在其中：歌词检索任务不随歌曲移交，转移时直接以失败结束。
     */
    data class Selection(
        val translationSource: Source? = null,
        val pronunciationSource: Source? = null,
        val translationNativeOnly: Boolean = false,
        val pronunciationNativeOnly: Boolean = false,
        val sourceStatusSongId: String? = null,
        val pendingTranslation: OnlineSourceSwitchRequest? = null,
        val pendingPronunciation: OnlineSourceSwitchRequest? = null,
    ) {
        val isEmpty: Boolean
            get() = this == Selection()
    }

    fun selection(): Selection = Selection(
        translationSource = temporaryTranslationSource,
        pronunciationSource = temporaryPronunciationSource,
        translationNativeOnly = translationNativeOnly,
        pronunciationNativeOnly = pronunciationNativeOnly,
        sourceStatusSongId = sourceStatusSongId,
        pendingTranslation = pendingTranslationRequest,
        pendingPronunciation = pendingPronunciationRequest,
    )

    /** 恢复 [selection]（含其中待完成的翻译/发音请求）。 */
    fun restoreSelection(selection: Selection) {
        temporaryTranslationSource = selection.translationSource
        temporaryPronunciationSource = selection.pronunciationSource
        translationNativeOnly = selection.translationNativeOnly
        pronunciationNativeOnly = selection.pronunciationNativeOnly
        sourceStatusSongId = selection.sourceStatusSongId
        pendingTranslationRequest = selection.pendingTranslation
        pendingPronunciationRequest = selection.pendingPronunciation
    }

    /** 切歌清理：清空全部请求槽位与本曲的手动选择。 */
    fun clearForTrackChange() {
        temporaryTranslationSource = null
        temporaryPronunciationSource = null
        pendingTranslationRequest = null
        pendingPronunciationRequest = null
        pendingLyricsRequest = null
        translationNativeOnly = false
        pronunciationNativeOnly = false
        sourceStatusSongId = null
    }
}
