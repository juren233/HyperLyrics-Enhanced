/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.LrcLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song as LocalSong
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import com.juren233.hyperlyricsenhanced.online.model.Source

/**
 * 在线翻译匹配是否允许把 [song] 当作可用歌词基准。
 *
 * Apple 原生歌词得到确认后始终允许；否则只有已标记为「无歌词补充」且确实携带
 * 歌词的载荷才能进入匹配，避免把空歌词占位或普通兜底歌词误当成可匹配对象。
 */
internal fun hasAppleLyricsForOnlineEnrichment(
    song: LocalSong?,
    confirmedNativeLyrics: Boolean,
): Boolean = confirmedNativeLyrics || (
    song != null &&
        song.metadata
            ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT)
            .toBoolean() &&
        !song.lyrics.isNullOrEmpty()
    )

/**
 * Automatic fallback must not replace confirmed Apple-native lyrics. A user-initiated
 * source switch is different: the request explicitly asks for a third-party supplement,
 * even if an intervening Apple callback temporarily marks the same track as native again.
 */
internal fun acceptsAppleOnlineLyricResult(
    generation: Int,
    currentGeneration: Int,
    sameTrack: Boolean,
    currentNativeLyrics: Boolean,
    currentSongHasNativeLyrics: Boolean,
    manualSourceSwitch: Boolean,
    automaticLunaBeatOverride: Boolean = false,
    untimedNativeFallback: Boolean = false,
): Boolean = generation == currentGeneration &&
    sameTrack &&
    (!(currentNativeLyrics || currentSongHasNativeLyrics) ||
        manualSourceSwitch ||
        automaticLunaBeatOverride ||
        untimedNativeFallback)

/**
 * 纯文本无时间轴原生的在线补充与其它在线歌词增强同源，必须跟随
 * 「启用App」开关（该 App 允许使用三方在线源取词）。
 */
internal fun allowsUntimedAppleOnlineFallback(
    appOnlineEnabled: Boolean,
    untimedNative: Boolean,
): Boolean = appOnlineEnabled && untimedNative

/**
 * Native text remains available in Apple Music, but cannot drive timed SystemUI lyrics.
 * 必须携带真实歌词行：纯文本替换的正文重叠校验以原生文本为身份基准，
 * 「仅 UNTIMED 标志、歌词行为空」的竞态快照（如中央 lines=0 发布经元数据合并
 * 保留标志）不得进入该路径——无原生文本时重叠校验必然 matched=0 拒掉正确候选，
 * 应等待数毫秒后带词快照的后续调度。
 */
internal fun hasUntimedAppleNativeLyrics(song: LocalSong?): Boolean =
    song != null &&
        (song.metadata?.getString(LyricMetadataKeys.APPLE_LYRICS_CACHE_SOURCE) == "apple" ||
            song.metadata
                ?.getString(LyricMetadataKeys.APPLE_NATIVE_LYRICS_CONFIRMED)
                .toBoolean() ||
            song.metadata
                ?.getString(LyricMetadataKeys.APPLE_NATIVE_LYRICS_UNTIMED)
                .toBoolean()) &&
        !song.lyrics.isNullOrEmpty() &&
        !song.metadata
            ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT)
            .toBoolean() &&
        song.lyrics.orEmpty().none { line ->
            line.end > line.begin ||
                line.words.orEmpty().any { word -> word.end > word.begin }
        }

/** A source with every line at one timestamp cannot drive the island or AOD timeline. */
internal fun hasUsableTimedOnlineLyricsForUntimedApple(
    nativeLineCount: Int,
    durationMs: Long,
    lines: List<LrcLine>?,
): Boolean {
    val starts = lines.orEmpty()
        .asSequence()
        .filter { it.startTimeMs >= 0L && it.content.isNotBlank() }
        .map(LrcLine::startTimeMs)
        .distinct()
        .sorted()
        .toList()
    val minimumLines = (nativeLineCount / 8).coerceIn(2, 4)
    if (starts.size < minimumLines) return false
    val minimumSpanMs = if (durationMs > 0L) {
        (durationMs / 3L).coerceAtLeast(1_000L)
    } else {
        1_000L
    }
    return starts.last() - starts.first() >= minimumSpanMs
}

/**
 * 纯文本原生歌词的三方替换候选必须与原生文本行重叠达标才放行，防止同名错歌
 * 替换正文；覆盖率/置信度阈值复用在线翻译近失校验的既定标准。
 */
internal fun acceptsUntimedAppleOnlineLyrics(
    meaningfulNativeLineCount: Int,
    match: OnlineTranslationMatcher.Result,
): Boolean = meaningfulNativeLineCount > 0 &&
    match.matchedCount.toDouble() / meaningfulNativeLineCount >=
    AppleOnlineTranslationNearMissPolicy.MIN_COVERAGE &&
    match.averageMatchScore >= AppleOnlineTranslationNearMissPolicy.MIN_CONFIDENCE

internal fun hasConfirmedAppleNativeLyrics(song: LocalSong?): Boolean =
    song != null &&
        !song.lyrics.isNullOrEmpty() &&
        !song.metadata
            ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT)
            .toBoolean() &&
        song.metadata
            ?.getString(LyricMetadataKeys.APPLE_NATIVE_LYRICS_CONFIRMED)
            .toBoolean()

internal fun shouldKeepRunningAppleEnrichment(
    sameTrack: Boolean,
    authoritativeNativeTransition: Boolean,
    hasLyrics: Boolean,
    needsEnrichment: Boolean,
    originalMetadataChanged: Boolean,
    enrichmentRunning: Boolean,
): Boolean = sameTrack &&
    !authoritativeNativeTransition &&
    hasLyrics &&
    needsEnrichment &&
    !originalMetadataChanged &&
    enrichmentRunning

internal fun shouldClearAppleOnlineTranslationAttempt(
    sameTrack: Boolean,
    authoritativeNativeTransition: Boolean,
    needsEnrichment: Boolean,
    originalMetadataChanged: Boolean,
): Boolean = authoritativeNativeTransition ||
    !sameTrack ||
    !needsEnrichment ||
    originalMetadataChanged

/**
 * 同曲重复回调是否允许重新调度在线翻译。请求仍在运行、结果已查到但尚未在主线程应用、
 * 或已确认匹配时,既有请求有效,不得重启;否则(key 相同但请求已死亡)允许重启,
 * 避免旧 attempt key 永久封死同曲的重试与兜底结果的重新调度。
 */
internal fun isAppleOnlineTranslationAttemptAlive(
    attemptMatches: Boolean,
    requestRunning: Boolean,
    resultReady: Boolean,
    matchedActive: Boolean,
): Boolean = attemptMatches && (requestRunning || resultReady || matchedActive)

/**
 * 正文与来源以合并结果为准;当合并沿用了旧歌曲对象(如保留 LunaBeat 正文)时,
 * 把最新 Apple 回调中非空且不同的标题/歌手同步到该对象上,避免晚到的显示元数据丢失。
 * 歌词、正文来源、翻译标记与位置不受影响;非同曲或无可同步字段时原样返回。
 */
internal fun mergeRetainedSongDisplayMetadata(
    mergedSong: LocalSong?,
    previousSong: LocalSong?,
    incomingSong: LocalSong?,
): LocalSong? = if (mergedSong === previousSong) {
    AppleSongUpdatePolicy.refreshDisplayMetadata(previousSong, incomingSong) ?: mergedSong
} else {
    mergedSong
}

/**
 * Central Apple callbacks can rebuild the same lyric model from TTML and drop the
 * process-independent supplement metadata. Keep the already confirmed supplement
 * marker and source description attached to that same track so it is not promoted
 * back to an Apple-native song before source recovery completes.
 */
internal fun mergeMissingLyricsSupplementMetadata(
    previousSong: LocalSong?,
    incomingSong: LocalSong?,
    sameTrack: Boolean,
    authoritativeSource: String? = null,
): LocalSong? {
    if (!sameTrack || previousSong == null || incomingSong == null) return incomingSong
    val previousMetadata = previousSong.metadata ?: return incomingSong
    val previousSource = previousMetadata
        .getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE)
    if (hasConfirmedAppleNativeLyrics(incomingSong)) {
        return if (
            authoritativeSource == Source.LB.name &&
            previousSource == Source.LB.name &&
            previousMetadata
                .getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT)
                .toBoolean()
        ) {
            previousSong
        } else {
            incomingSong
        }
    }
    if (!previousMetadata.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT).toBoolean()) {
        return incomingSong
    }
    val incomingIsSupplement = incomingSong.metadata
        ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT)
        .toBoolean()
    val incomingSource = incomingSong.metadata
        ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE)
    if (
        incomingIsSupplement &&
        !authoritativeSource.isNullOrBlank() &&
        previousSource == authoritativeSource &&
        !incomingSource.isNullOrBlank() &&
        incomingSource != authoritativeSource
    ) {
        // 手动切换成功后，旧来源的同曲异步回调不得把正文、时间轴和来源一起回滚。
        return previousSong
    }
    if (incomingIsSupplement && (
            authoritativeSource.isNullOrBlank() || incomingSource == authoritativeSource
        )
    ) {
        return incomingSong
    }
    val merged = linkedMapOf<String, String?>()
    incomingSong.metadata?.forEach { (key, value) -> merged[key] = value }
    merged[LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT] = "true"
    listOf(
        LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE,
        LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE_STATUSES,
        LyricMetadataKeys.LUNA_BEAT_RAW_TTML,
        LyricMetadataKeys.LUNA_BEAT_HUB_ID,
        LyricMetadataKeys.LUNA_BEAT_TTML_SHA256,
    ).forEach { key ->
        if (merged[key].isNullOrBlank()) {
            previousMetadata.getString(key)?.let { merged[key] = it }
        }
    }
    authoritativeSource?.takeIf(String::isNotBlank)?.let {
        merged[LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE] = it
    }
    return incomingSong.copy(
        metadata = lyricMetadataOf(*merged.entries.map { it.key to it.value }.toTypedArray())
    )
}

internal data class OnlineSourceSwitchRequest(
    val requestId: Long,
    val songId: String,
    val contentType: String,
    val requestedSource: Source,
    val startedAtMs: Long,
)

internal data class ConfirmedLyricsSourceSelection(
    val songId: String,
    val source: String,
)
