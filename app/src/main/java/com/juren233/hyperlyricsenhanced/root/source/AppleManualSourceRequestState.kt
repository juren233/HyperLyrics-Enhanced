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

    /** Content type used by [OnlineSourceSwitchRequest] for the lyrics/正文 track. */
    fun acceptLyricsRequest(request: OnlineSourceSwitchRequest) {
        pendingLyricsRequest = request
    }

    fun acceptTranslationRequest(request: OnlineSourceSwitchRequest, source: Source) {
        temporaryTranslationSource = source
        pendingTranslationRequest = request
    }

    fun acceptPronunciationRequest(request: OnlineSourceSwitchRequest, source: Source) {
        temporaryPronunciationSource = source
        pendingPronunciationRequest = request
    }

    /** 请求中的翻译/发音来源；未请求时为空，已确认来源不在此表达。 */
    fun requestedTranslationSource(): Source? = temporaryTranslationSource

    fun requestedPronunciationSource(): Source? = temporaryPronunciationSource

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

    /** 切歌清理：按现有顺序清空全部五个槽位。 */
    fun clearForTrackChange() {
        temporaryTranslationSource = null
        temporaryPronunciationSource = null
        pendingTranslationRequest = null
        pendingPronunciationRequest = null
        pendingLyricsRequest = null
    }
}
