/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online

import android.content.Context
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceStatus
import com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.OnlineTranslationContentPolicy
import com.juren233.hyperlyricsenhanced.lyric.LrcLine
import com.juren233.hyperlyricsenhanced.online.model.LyricsLine
import com.juren233.hyperlyricsenhanced.online.model.LyricsResult
import com.juren233.hyperlyricsenhanced.online.model.SearchSource
import com.juren233.hyperlyricsenhanced.online.model.SongSearchResult
import com.juren233.hyperlyricsenhanced.online.model.Source
import com.juren233.hyperlyricsenhanced.online.utils.ChineseUtils
import com.juren233.hyperlyricsenhanced.utils.LogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import kotlin.math.abs

internal fun OnlineLyricTargeter.nearMissFor(
    source: SearchSource,
    attempt: SourceAttempt,
    multiCredit: Boolean,
): NearMiss? {
    val song = attempt.song ?: return null
    return when {
        isNearMissEligible(attempt.score, attempt.titleMatched, attempt.durationClose) ->
            NearMiss(source, song, attempt.score, durationVerified = true)
        isLyricFallbackEligible(
            titleMatched = attempt.titleMatched,
            multiCredit = multiCredit,
            durationClose = attempt.durationClose,
        ) -> NearMiss(source, song, attempt.score, durationVerified = false)
        else -> null
    }
}

internal fun OnlineLyricTargeter.betterSourceAttempt(first: SourceAttempt, retry: SourceAttempt): SourceAttempt =
    when {
        retry.song == null -> first
        first.song == null -> retry
        retry.durationClose && !first.durationClose -> retry
        first.durationClose && !retry.durationClose -> first
        retry.score > first.score -> retry
        else -> first
    }

internal fun OnlineLyricTargeter.preferNearMiss(current: NearMiss, candidate: NearMiss): NearMiss = when {
    current.durationVerified && !candidate.durationVerified -> current
    !current.durationVerified && candidate.durationVerified -> candidate
    candidate.score > current.score -> candidate
    else -> current
}

internal data class SourceAttempt(
    val lines: List<LrcLine>? = null,
    val wordLines: List<LyricsLine>? = null,
    val song: SongSearchResult? = null,
    val score: Int = -1,
    val titleMatched: Boolean = false,
    val durationClose: Boolean = false,
    val artistMatched: Boolean = false,
    val passAttempted: Boolean = false,
    val status: AppleMissingLyricsSourceStatus = AppleMissingLyricsSourceStatus(
        source = "",
        searched = false,
    ),
)

internal data class NearMiss(
    val source: SearchSource,
    val song: SongSearchResult,
    val score: Int,
    val durationVerified: Boolean,
)

internal data class SearchOutcome(
    val lines: List<LrcLine>? = null,
    val wordLines: List<LyricsLine>? = null,
    val nearMiss: NearMiss? = null,
    val sourceStatuses: List<AppleMissingLyricsSourceStatus> = emptyList(),
    val selectedSource: Source? = null,
)

internal fun OnlineLyricTargeter.statusFor(
    source: Source,
    result: LyricsResult,
    lines: List<LrcLine>,
): AppleMissingLyricsSourceStatus {
    val wordTimed = result.original.any { line ->
        line.words.size > 1 && line.words.any { word -> word.end > word.start }
    }
    return AppleMissingLyricsSourceStatus(
        source = source.name,
        searched = true,
        found = lines.isNotEmpty(),
        wordTimed = wordTimed,
        lineCount = lines.size,
    )
}

internal fun MutableMap<Source, AppleMissingLyricsSourceStatus>.mergeStatus(
    status: AppleMissingLyricsSourceStatus,
) {
    if (status.source.isBlank()) return
    val source = runCatching { Source.valueOf(status.source) }.getOrNull() ?: return
    val previous = this[source]
    this[source] = when {
        previous == null -> status
        status.found && !previous.found -> status
        status.found && previous.found && status.lineCount > previous.lineCount -> status
        else -> previous.copy(searched = previous.searched || status.searched)
    }
}

internal fun OnlineLyricTargeter.mergeStatuses(
    statuses: List<AppleMissingLyricsSourceStatus>,
    status: AppleMissingLyricsSourceStatus,
): List<AppleMissingLyricsSourceStatus> {
    val merged = linkedMapOf<Source, AppleMissingLyricsSourceStatus>()
    statuses.forEach { existing -> merged.mergeStatus(existing) }
    merged.mergeStatus(status)
    return merged.values.toList()
}

internal fun OnlineLyricTargeter.isStrongTitleMatch(localTitle: String, remoteTitle: String): Boolean =
    localTitle.isNotEmpty() && (
        localTitle == remoteTitle ||
            remoteTitle.contains(localTitle) ||
            localTitle.contains(remoteTitle)
        )

internal fun OnlineLyricTargeter.isStrongDurationMatch(localDurationMs: Long, remoteDurationMs: Long): Boolean =
    localDurationMs <= 0L ||
        abs(localDurationMs - remoteDurationMs) < OnlineLyricTargeter.STRONG_DURATION_TOLERANCE_MS

internal fun OnlineLyricTargeter.isNearMissEligible(
    score: Int,
    titleMatched: Boolean,
    durationClose: Boolean,
): Boolean = score >= OnlineLyricTargeter.NEAR_MISS_MIN_SCORE && titleMatched && durationClose

internal fun OnlineLyricTargeter.isMultiCreditArtist(artists: List<String>): Boolean =
    artists.count(String::isNotBlank) >= 2

internal fun OnlineLyricTargeter.hasCommonArtist(
    localArtists: List<String>,
    remoteArtists: List<String>,
): Boolean = localArtists.any { local ->
    local.isNotBlank() && remoteArtists.any { remote ->
        remote.isNotBlank() &&
            (local == remote || remote.contains(local) || local.contains(remote))
    }
}

internal fun OnlineLyricTargeter.isLyricFallbackEligible(
    titleMatched: Boolean,
    multiCredit: Boolean,
    durationClose: Boolean,
): Boolean = titleMatched && multiCredit && !durationClose

internal fun OnlineLyricTargeter.shouldRetryWithOriginalMetadata(
    title: String,
    artist: String,
    originalTitle: String?,
    originalArtist: String?
): Boolean {
    val resolvedTitle = originalTitle?.trim().orEmpty()
    val resolvedArtist = originalArtist?.trim().orEmpty()
    if (resolvedTitle.isEmpty() && resolvedArtist.isEmpty()) return false
    return !resolvedTitle.equals(title.trim(), ignoreCase = true) ||
        !resolvedArtist.equals(artist.trim(), ignoreCase = true)
}

internal fun OnlineLyricTargeter.resolveMetadataSearchOrder(
    preferOriginalMetadata: Boolean,
    hasDistinctOriginalMetadata: Boolean,
): List<Boolean> = when {
    !hasDistinctOriginalMetadata -> listOf(false)
    preferOriginalMetadata -> listOf(true, false)
    else -> listOf(false, true)
}

internal fun OnlineLyricTargeter.resolveSourceOrder(
    pkgName: String,
    preferredSource: Source?,
    fallbackToOtherSources: Boolean = true
): List<Source> {
    if (!fallbackToOtherSources && preferredSource != null) {
        return listOf(preferredSource)
    }
    return when (preferredSource) {
        Source.NE -> listOf(Source.NE, Source.QM)
        Source.QM -> listOf(Source.QM, Source.NE)
        Source.KUWO -> listOf(Source.KUWO, Source.NE, Source.QM)
        Source.KUGOU -> listOf(Source.KUGOU, Source.NE, Source.QM)
        Source.LB -> listOf(Source.LB)
        null -> when (pkgName) {
            "com.netease.cloudmusic" -> listOf(Source.NE, Source.QM)
            "com.tencent.qqmusic", "com.tencent.qqmusicpad" ->
                listOf(Source.QM, Source.NE)
            else -> listOf(Source.QM, Source.NE)
        }
    }
}

internal fun OnlineLyricTargeter.shouldWaitForStatusOnlySources(
    candidateSourceCount: Int,
    statusOnlySourceCount: Int,
): Boolean = candidateSourceCount != 1 || statusOnlySourceCount == 0

/** 保留来源逐词时间轴的行列表，供需要逐字渲染的消费方使用。 */
internal fun OnlineLyricTargeter.toWordLines(lyricsResult: LyricsResult): List<LyricsLine> =
    lyricsResult.original.filter { line ->
        line.words.joinToString("") { it.text }.trim().isNotEmpty()
    }

internal fun OnlineLyricTargeter.toLrcLines(lyricsResult: LyricsResult): List<LrcLine> {
    val translationsByStart = lyricsResult.translated.orEmpty().associate { line ->
        line.start to line.words.joinToString("") { it.text }.trim()
    }
    val romanizationsByStart = lyricsResult.romanization.orEmpty().associate { line ->
        line.start to line.words
            .map { it.text.trim() }
            .filter(String::isNotEmpty)
            .joinToString(" ")
    }
    return lyricsResult.original.mapNotNull { line ->
        val content = line.words.joinToString("") { it.text }.trim()
        if (content.isEmpty()) return@mapNotNull null
        LrcLine(
            startTimeMs = line.start,
            content = content,
            translation = OnlineTranslationContentPolicy.sanitize(
                translationsByStart[line.start]
            ),
            romanization = RomanizationPolicy.sanitize(
                originalText = content,
                pronunciation = romanizationsByStart[line.start],
            ),
        )
    }
}

internal fun OnlineLyricTargeter.calculateScore(
    context: Context,
    song: SongSearchResult,
    cleanLocalTitle: String,
    localArtists: List<String>,
    localFeatures: List<String>,
    localDurationMs: Long,
    cleanLocalAlbum: String,
): Int {
    var score = 0

    if (localDurationMs > 0 && song.duration > 0) {
        score += durationScore(localDurationMs, song.duration)
    }

    val cleanSongTitle = cleanString(context, song.title)

    if (cleanLocalTitle == cleanSongTitle || cleanSongTitle.contains(cleanLocalTitle) || cleanLocalTitle.contains(cleanSongTitle)) {
        score += 50
    }

    val songArtists = splitArtists(song.artist).map { cleanString(context, it) }
    
    val hasCommonArtist = localArtists.any { lArtist -> songArtists.any { sArtist -> lArtist == sArtist || sArtist.contains(lArtist) || lArtist.contains(sArtist) } }
    if (hasCommonArtist) {
        score += 30
    }

    val remoteAlbum = normalizeAlbum(context, song.album)
    val delta = albumScore(cleanLocalAlbum, remoteAlbum)
    score += delta
    LogManager.d(
        "OnlineTargeter",
        "专辑比对: 本地=\"$cleanLocalAlbum\", 候选=\"$remoteAlbum\", " +
            "源=${song.source}, 得分=$delta"
    )

    val songFeatures = listOf("live", "remastered", "翻唱", "cover").filter { song.title.lowercase().contains(it) }
    
    if (localFeatures.isNotEmpty() && songFeatures.isNotEmpty()) {
        val commonFeatures = localFeatures.intersect(songFeatures.toSet())
        if (commonFeatures.isNotEmpty()) {
            score += 20
        }
    }

    return score
}

internal fun OnlineLyricTargeter.albumScore(cleanLocalAlbum: String, cleanRemoteAlbum: String): Int {
    val localBase = stripAlbumVersionSuffixes(cleanLocalAlbum)
    val remoteBase = stripAlbumVersionSuffixes(cleanRemoteAlbum)
    return when {
        cleanLocalAlbum.isEmpty() || cleanRemoteAlbum.isEmpty() -> 0
        cleanLocalAlbum == cleanRemoteAlbum -> 10
        localBase.isNotEmpty() && localBase == remoteBase -> 5
        else -> 0
    }
}

/**
 * 专辑名参与评分前的字符归一化。NFKC 会把全角字母/数字/括号统一成半角，
 * 随后 [cleanString] 继续去掉括号内容、压缩空白、转简体并小写。
 */
internal fun OnlineLyricTargeter.normalizeAlbumCharacters(input: String): String =
    Normalizer.normalize(input, Normalizer.Form.NFKC)

internal fun OnlineLyricTargeter.normalizeAlbum(context: Context, input: String): String =
    cleanString(context, normalizeAlbumCharacters(input))

/**
 * 去掉末尾的版本/录音标记，让“原曲 / 现场 / 不插电 / 翻唱 / 豪华版”等
 * 同一首歌词的变体在专辑维度不被误判成不同专辑。只从尾部逐段剥离，避免
 * 误伤普通专辑名（例如 Greatest Hits 不会被拆成 Greatest）。
 */
internal fun OnlineLyricTargeter.stripAlbumVersionSuffixes(value: String): String {
    var result = value.trim()
    var changed: Boolean
    do {
        changed = false
        for (suffix in ALBUM_VERSION_SUFFIXES.sortedByDescending { it.length }) {
            val stripped = stripTrailingVersionSuffix(result, suffix)
            if (stripped != result) {
                result = stripped
                changed = true
                break
            }
        }
    } while (changed)
    return result
}

internal fun OnlineLyricTargeter.stripTrailingVersionSuffix(value: String, suffix: String): String {
    if (suffix.isEmpty() || !value.endsWith(suffix, ignoreCase = true)) return value
    if (value.length == suffix.length) return ""

    val boundary = value.length - suffix.length
    val preceding = value[boundary - 1]
    // 中文后缀（如“豪华版”）是独立词，可直接附着在英文专辑名后。
    // 英文后缀要求前方是分隔符，避免把 "Alive" 误剥成 "live"。
    val suffixIsCjk = suffix.any { it.isCjkUnifiedIdeograph() }
    if (!suffixIsCjk && preceding.isLetterOrDigit()) return value

    return value.substring(0, boundary).trimEnd { it in ALBUM_SUFFIX_SEPARATOR_CHARS }
}

internal fun Char.isCjkUnifiedIdeograph(): Boolean =
    this.code in 0x4E00..0x9FFF

private val ALBUM_SUFFIX_SEPARATOR_CHARS = setOf(' ', '-', '_', '~', '·', '|', '/')

private val ALBUM_VERSION_SUFFIXES = listOf(
    // 英文版本 / 录音标记，长后缀在前。
    "live version", "acoustic version", "piano version", "studio version",
    "radio version", "deluxe edition", "full version", "clean version",
    "radio edit", "tv size", "hi-res", "320k",
    "live", "acoustic", "unplugged", "cover", "remastered", "remaster",
    "remix", "remixes", "deluxe", "explicit", "clean", "edited",
    "instrumental", "piano", "demo", "full", "studio", "radio", "edit",
    "single", "ep", "flac", "lossless",
    // 中文版本 / 录音标记。
    "现场版", "演唱会版", "不插电版", "木吉他版", "翻唱版", "重制版",
    "重置版", "混音版", "豪华版", "伴奏版", "纯音乐版", "钢琴版",
    "试听版", "完整版", "录音室版", "电台版", "单曲版", "无损版",
    "高音质版", "cover版", "remix版", "tv版", "短版", "剪辑版",
    "现场", "演唱会", "不插电", "吉他版", "翻唱", "重制", "重置",
    "混音", "豪华", "伴奏", "纯音乐", "试听", "录音室", "电台",
    "单曲", "无损", "高音质", "版",
)

internal fun OnlineLyricTargeter.cleanString(context: Context, input: String): String {
    val cleaned = input.replace(Regex("\\(.*?\\)|\\[.*?]|\\{.*?\\}"), "").trim().lowercase()
    return compactWhitespace(ChineseUtils.toSimplified(context, cleaned))
}

internal fun OnlineLyricTargeter.durationScore(localDurationMs: Long, remoteDurationMs: Long): Int {
    val diffMs = abs(localDurationMs - remoteDurationMs)
    return when {
        diffMs > 5_000L -> -30
        diffMs < 1_500L -> 15
        else -> 10
    }
}

internal fun OnlineLyricTargeter.compactWhitespace(value: String): String = value.replace(Regex("\\s+"), "")

internal fun OnlineLyricTargeter.splitArtists(value: String): List<String> =
    value.split("&", ",", "，", "、", "/", "／")

internal data class SearchMetadata(
    val title: String,
    val artist: String,
    val label: String,
)

