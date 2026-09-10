/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

internal fun selectLyricsViewModelPlaybackItem(
    expectedSongId: String,
    expectedType: Class<*>,
    candidates: List<Any?>,
    registeredSongId: (Any) -> String?,
    runtimeSongId: (Any) -> String?,
): Any? = candidates.asSequence()
    .filterNotNull()
    .firstOrNull { candidate ->
        expectedType.isInstance(candidate) &&
            (registeredSongId(candidate)?.takeIf(String::isNotBlank)
                ?: runtimeSongId(candidate)?.takeIf(String::isNotBlank)) == expectedSongId
    }

internal const val APPLE_LYRICS_REQUEST_SOURCE_APPLE = "apple"
internal const val APPLE_LYRICS_REQUEST_SOURCE_MODULE = "module"
internal const val APPLE_LYRICS_DIAGNOSTIC_SOURCE_MODULE_NATIVE = "module_native"

/**
 * 模块自建 ViewModel 仍调用 Apple 的真实 loadLyrics。只有请求 ID、返回模型 ID 与
 * 当前队列身份一致，且返回指针不是模块补充指针时，才能把这次 module 请求提升为
 * Apple 原生内容；快速切歌的旧结果和补充 TTML 必须继续保留 module 身份。
 */
internal fun shouldTreatModuleLyricsResultAsAppleNative(
    requestSource: String,
    requestedSongId: String?,
    resultSongId: String?,
    currentPlaybackSongId: String?,
    supplementPointer: Boolean,
    hasNativeLines: Boolean,
): Boolean {
    if (
        requestSource != APPLE_LYRICS_REQUEST_SOURCE_MODULE ||
        supplementPointer ||
        !hasNativeLines
    ) {
        return false
    }
    val requested = requestedSongId?.takeIf(String::isNotBlank) ?: return false
    val result = resultSongId?.takeIf(String::isNotBlank) ?: return false
    if (requested != result) return false
    val current = currentPlaybackSongId?.takeIf(String::isNotBlank)
    return current == null || current == result
}

/**
 * 判断带「无歌词补充」标记的在线翻译回传是否应该继续走补充歌词链路。
 *
 * 同一首歌可能在模块歌词先到、Apple 原生歌词后到的窗口里把标记合并到原生载荷上。
 * 如果当前歌词页已经是 Apple 原生指针、或本进程已登记该曲 Apple 原生歌词，翻译必须
 * 走原生在线翻译 Store，否则补充链会因 `native_lyrics_present` 丢弃翻译。
 */
internal fun shouldRouteAppleTranslationAsMissingSupplement(
    markedAsSupplement: Boolean,
    knownNativeLyrics: Boolean,
    visiblePageIsSupplement: Boolean?,
): Boolean {
    if (!markedAsSupplement || knownNativeLyrics) return false
    return visiblePageIsSupplement != false
}

internal fun shouldKeepAppleLyricsScrollSnapshot(
    existingPosition: Int?,
    capturedPosition: Int,
    presentationInFlight: Boolean,
): Boolean = presentationInFlight &&
    capturedPosition == 0 &&
    existingPosition != null &&
    existingPosition > 0

internal data class AppleLyricsRestoreAnchor(
    val position: Int,
    val offset: Int,
    val activePosition: Int?,
)

internal fun selectAppleLyricsPlaybackAdapterPosition(
    lineBeginsMs: List<Long?>,
    playbackPositionMs: Long,
    itemCount: Int,
): Int? {
    if (itemCount <= 0) return null
    val logicalLinePosition = lineBeginsMs.indexOfLast { beginMs ->
        beginMs != null && beginMs <= playbackPositionMs
    }
    return logicalLinePosition
        .takeIf { it >= 0 }
        ?.coerceIn(0, itemCount - 1)
}

internal fun selectAppleLyricsRestoreAnchor(
    savedPosition: Int,
    savedOffset: Int,
    savedActivePosition: Int?,
    savedActiveOffset: Int?,
    currentActivePositions: Iterable<Int>,
    itemCount: Int,
    playbackMappedPosition: Int? = null,
): AppleLyricsRestoreAnchor? {
    if (itemCount <= 0) return null
    val currentActivePosition = currentActivePositions
        .filter { it in 0 until itemCount }
        .maxOrNull()
    if (currentActivePosition != null && savedActiveOffset != null) {
        return AppleLyricsRestoreAnchor(
            position = currentActivePosition,
            offset = savedActiveOffset,
            activePosition = currentActivePosition,
        )
    }
    if (currentActivePosition != null && savedActivePosition != null) {
        val activeDistanceFromFirst = savedActivePosition - savedPosition
        return AppleLyricsRestoreAnchor(
            position = (currentActivePosition - activeDistanceFromFirst)
                .coerceIn(0, itemCount - 1),
            offset = savedOffset,
            activePosition = currentActivePosition,
        )
    }
    val mappedPosition = playbackMappedPosition
        ?.takeIf { it >= 0 }
        ?.coerceIn(0, itemCount - 1)
    if (mappedPosition != null) {
        return AppleLyricsRestoreAnchor(
            position = mappedPosition,
            offset = savedActiveOffset ?: savedOffset,
            activePosition = mappedPosition,
        )
    }
    return AppleLyricsRestoreAnchor(
        position = savedPosition.coerceIn(0, itemCount - 1),
        offset = savedOffset,
        activePosition = null,
    )
}

internal fun visibleAdapterRange(positions: Iterable<Int>): IntRange? {
    val valid = positions.filter { it >= 0 }.toList()
    if (valid.isEmpty()) return null
    val first = valid.minOrNull() ?: return null
    val last = valid.maxOrNull() ?: return null
    return first..last
}
