package com.juren233.hyperlyricsenhanced.online

import android.content.Context
import com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.OnlineTranslationContentPolicy
import com.juren233.hyperlyricsenhanced.lyric.LrcLine
import com.juren233.hyperlyricsenhanced.online.model.LyricsResult
import com.juren233.hyperlyricsenhanced.online.model.SearchSource
import com.juren233.hyperlyricsenhanced.online.model.SongSearchResult
import com.juren233.hyperlyricsenhanced.online.model.Source
import com.juren233.hyperlyricsenhanced.online.utils.ChineseUtils
import com.juren233.hyperlyricsenhanced.utils.LogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import kotlin.math.abs

object OnlineLyricTargeter {
    private const val TIMEOUT_MS = 5000L
    private const val PASS_SCORE = 85
    private const val NEAR_MISS_MIN_SCORE = 50
    private const val STRONG_DURATION_TOLERANCE_MS = 1_500L

    /**
     * 低于 [PASS_SCORE] 但仍可能由强身份（标题精确相等 + 时长接近）
     * 和歌词重叠校验放行的候选。歌词已在此处抓取，由调用方做最终验证。
     */
    data class NearMissCandidate(
        val score: Int,
        val lines: List<LrcLine>,
        val durationVerified: Boolean,
    )

    data class FetchOutcome(
        val lines: List<LrcLine>? = null,
        val nearMiss: NearMissCandidate? = null,
    )

    suspend fun fetchBestLyric(
        context: Context,
        pkgName: String,
        title: String,
        artist: String,
        durationMs: Long,
        originalTitle: String? = null,
        originalArtist: String? = null,
        preferOriginalMetadata: Boolean = false,
        preferredSource: Source? = null,
        requireTranslation: Boolean = false,
        fallbackToOtherSources: Boolean = true,
        sourceOrder: List<Source>? = null,
        album: String? = null,
        originalAlbum: String? = null,
    ): List<LrcLine>? = fetchBestLyricWithNearMiss(
        context = context,
        pkgName = pkgName,
        title = title,
        artist = artist,
        durationMs = durationMs,
        originalTitle = originalTitle,
        originalArtist = originalArtist,
        preferOriginalMetadata = preferOriginalMetadata,
        preferredSource = preferredSource,
        requireTranslation = requireTranslation,
        fallbackToOtherSources = fallbackToOtherSources,
        sourceOrder = sourceOrder,
        album = album,
        originalAlbum = originalAlbum,
    ).lines

    suspend fun fetchBestLyricWithNearMiss(
        context: Context,
        pkgName: String,
        title: String,
        artist: String,
        durationMs: Long,
        originalTitle: String? = null,
        originalArtist: String? = null,
        preferOriginalMetadata: Boolean = false,
        preferredSource: Source? = null,
        requireTranslation: Boolean = false,
        fallbackToOtherSources: Boolean = true,
        sourceOrder: List<Source>? = null,
        album: String? = null,
        originalAlbum: String? = null,
    ): FetchOutcome {
        val outcome = performSearch(
            context = context,
            pkgName = pkgName,
            title = title,
            artist = artist,
            durationMs = durationMs,
            originalTitle = originalTitle,
            originalArtist = originalArtist,
            preferOriginalMetadata = preferOriginalMetadata,
            preferredSource = preferredSource,
            requireTranslation = requireTranslation,
            fallbackToOtherSources = fallbackToOtherSources,
            sourceOrder = sourceOrder,
            album = album,
            originalAlbum = originalAlbum,
        )
        if (outcome.lines != null) {
            return FetchOutcome(lines = outcome.lines)
        }
        val nearMiss = outcome.nearMiss ?: return FetchOutcome()
        val lyricsResult = withTimeoutOrNull(TIMEOUT_MS) {
            try {
                nearMiss.source.getLyrics(nearMiss.song)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogManager.w(
                    "OnlineTargeter",
                    "获取近失候选歌词异常: 源=${nearMiss.source.javaClass.simpleName}, ${e.message}",
                )
                null
            }
        }
        if (lyricsResult == null ||
            (lyricsResult.original.isEmpty() && lyricsResult.translated.isNullOrEmpty())
        ) {
            return FetchOutcome()
        }
        val lines = toLrcLines(lyricsResult)
        if (lines.isEmpty()) return FetchOutcome()
        if (requireTranslation && lines.none {
                OnlineTranslationContentPolicy.isMeaningful(it.translation)
            }
        ) {
            return FetchOutcome()
        }
        LogManager.d(
            "OnlineTargeter",
            "近失候选歌词: 源=${nearMiss.source.javaClass.simpleName}, " +
                "得分=${nearMiss.score}, 行数=${lines.size}, " +
                "时长已校验=${nearMiss.durationVerified}",
        )
        return FetchOutcome(
            nearMiss = NearMissCandidate(
                score = nearMiss.score,
                lines = lines,
                durationVerified = nearMiss.durationVerified,
            ),
        )
    }

    private suspend fun performSearch(
        context: Context,
        pkgName: String,
        title: String,
        artist: String,
        durationMs: Long,
        originalTitle: String?,
        originalArtist: String?,
        preferOriginalMetadata: Boolean,
        preferredSource: Source?,
        requireTranslation: Boolean,
        fallbackToOtherSources: Boolean,
        sourceOrder: List<Source>?,
        album: String?,
        originalAlbum: String?,
    ): SearchOutcome {
        val ne = LyricApiProvider.getNeSource(context)
        val qm = LyricApiProvider.qmSource
        val sourcesByType = mapOf(
            Source.NE to ne,
            Source.QM to qm,
            Source.KUWO to LyricApiProvider.kuwoSource,
            Source.KUGOU to LyricApiProvider.kugouSource,
        )
        val resolvedSourceOrder = sourceOrder?.distinct().orEmpty().ifEmpty {
            resolveSourceOrder(
                pkgName = pkgName,
                preferredSource = preferredSource,
                fallbackToOtherSources = fallbackToOtherSources,
            )
        }
        val sources = resolvedSourceOrder.mapNotNull(sourcesByType::get)

        val resolvedTitle = originalTitle?.takeIf { it.isNotBlank() } ?: title
        val resolvedArtist = originalArtist?.takeIf { it.isNotBlank() } ?: artist
        val hasDistinctOriginalMetadata = shouldRetryWithOriginalMetadata(
            title,
            artist,
            originalTitle,
            originalArtist,
        )
        val searches = resolveMetadataSearchOrder(
            preferOriginalMetadata = preferOriginalMetadata,
            hasDistinctOriginalMetadata = hasDistinctOriginalMetadata,
        ).map { useOriginalMetadata ->
            if (useOriginalMetadata) {
                SearchMetadata(resolvedTitle, resolvedArtist, "Apple 内部原名")
            } else {
                SearchMetadata(title, artist, "当前元数据")
            }
        }
        var bestNearMiss: NearMiss? = null
        searches.forEachIndexed { index, metadata ->
            if (index > 0 && metadata.label == "Apple 内部原名") {
                LogManager.d(
                    "OnlineTargeter",
                    "使用 Apple 内部原名重试: ${metadata.title} / ${metadata.artist}"
                )
            }
            val outcome = searchSources(
                context = context,
                sources = sources,
                title = metadata.title,
                artist = metadata.artist,
                durationMs = durationMs,
                requireTranslation = requireTranslation,
                metadataLabel = metadata.label,
                album = album,
                originalAlbum = originalAlbum,
            )
            if (outcome.lines != null) {
                return SearchOutcome(lines = outcome.lines)
            }
            if (outcome.nearMiss != null) {
                bestNearMiss = if (bestNearMiss == null) {
                    outcome.nearMiss
                } else {
                    preferNearMiss(bestNearMiss, outcome.nearMiss)
                }
            }
        }
        return SearchOutcome(nearMiss = bestNearMiss)
    }

    private suspend fun searchSources(
        context: Context,
        sources: List<SearchSource>,
        title: String,
        artist: String,
        durationMs: Long,
        requireTranslation: Boolean,
        metadataLabel: String,
        album: String?,
        originalAlbum: String?,
    ): SearchOutcome {

        val keyword = "$title $artist"
        val albumKeyword = originalAlbum?.trim()?.takeIf(String::isNotEmpty)
        val fallbackKeyword = if (albumKeyword == null) title else "$title $albumKeyword"
        LogManager.d(
            "OnlineTargeter",
            "正在搜索: 类型=$metadataLabel, 关键词=\"$keyword\", " +
                "源顺序=${sources.joinToString { it.javaClass.simpleName }}"
        )

        val cleanLocalTitle = cleanString(context, title)
        val localArtists = splitArtists(artist).map { cleanString(context, it) }
        val multiCredit = isMultiCreditArtist(localArtists)
        val cleanLocalAlbum = normalizeAlbum(context, album.orEmpty())
        LogManager.d(
            "OnlineTargeter",
            "专辑比对: 类型=$metadataLabel, 原始专辑=\"$album\", " +
                "归一化=\"$cleanLocalAlbum\""
        )
        val featureKeywords = listOf("live", "remastered", "翻唱", "cover")
        val localFeatures = featureKeywords.filter { title.lowercase().contains(it) }

        var bestScore = -1
        var bestNearMiss: NearMiss? = null

        for (source in sources) {
            val sourceKeyword = if (source.sourceType == Source.KUGOU && artist.isNotBlank()) {
                "$artist - $title"
            } else {
                keyword
            }
            var attempt = scoreSource(
                context = context,
                source = source,
                keyword = sourceKeyword,
                durationMs = durationMs,
                requireTranslation = requireTranslation,
                metadataLabel = metadataLabel,
                cleanLocalTitle = cleanLocalTitle,
                localArtists = localArtists,
                localFeatures = localFeatures,
                cleanLocalAlbum = cleanLocalAlbum,
            )
            if (attempt.lines != null) {
                return SearchOutcome(lines = attempt.lines)
            }
            if (multiCredit &&
                !attempt.passAttempted &&
                (attempt.song == null || !attempt.artistMatched)
            ) {
                LogManager.d(
                    "OnlineTargeter",
                    "多人署名候选无歌手交集，使用歌曲名+原名专辑降级重试: " +
                        "源=${source.javaClass.simpleName}, 关键词=\"$fallbackKeyword\"",
                )
                val retry = scoreSource(
                    context = context,
                    source = source,
                    keyword = fallbackKeyword,
                    durationMs = durationMs,
                    requireTranslation = requireTranslation,
                    metadataLabel = metadataLabel,
                    cleanLocalTitle = cleanLocalTitle,
                    localArtists = localArtists,
                    localFeatures = localFeatures,
                    cleanLocalAlbum = cleanLocalAlbum,
                )
                if (retry.lines != null) {
                    return SearchOutcome(lines = retry.lines)
                }
                attempt = betterSourceAttempt(attempt, retry)
            }
            if (attempt.song != null) {
                if (attempt.score > bestScore) bestScore = attempt.score
                if (!attempt.passAttempted) {
                    nearMissFor(source, attempt, multiCredit)?.let { nearMiss ->
                        bestNearMiss = if (bestNearMiss == null) {
                            nearMiss
                        } else {
                            preferNearMiss(bestNearMiss, nearMiss)
                        }
                    }
                }
            }
        }
        LogManager.d(
            "OnlineTargeter",
            "歌词未命中: 类型=$metadataLabel, 最佳得分=$bestScore < 阈值 $PASS_SCORE"
        )
        return SearchOutcome(nearMiss = bestNearMiss)
    }

    private suspend fun scoreSource(
        context: Context,
        source: SearchSource,
        keyword: String,
        durationMs: Long,
        requireTranslation: Boolean,
        metadataLabel: String,
        cleanLocalTitle: String,
        localArtists: List<String>,
        localFeatures: List<String>,
        cleanLocalAlbum: String,
    ): SourceAttempt {
        val results = withTimeoutOrNull(TIMEOUT_MS) {
            try {
                source.search(keyword, 1, "/", 20, durationMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogManager.w(
                    "OnlineTargeter",
                    "搜索异常: 源=${source.javaClass.simpleName}, ${e.message}",
                )
                null
            }
        }
        if (results.isNullOrEmpty()) {
            LogManager.d(
                "OnlineTargeter",
                "搜索结果为空: 源=${source.javaClass.simpleName}, 关键词=\"$keyword\"",
            )
            return SourceAttempt()
        }
        LogManager.d(
            "OnlineTargeter",
            "搜索结果: 源=${source.javaClass.simpleName}, 关键词=\"$keyword\", " +
                "数量=${results.size}",
        )

        var localBestScore = -1
        var bestSong: SongSearchResult? = null
        var titleMatched = false
        var durationClose = false
        var artistMatched = false

        for (song in results) {
            val score = calculateScore(
                context,
                song,
                cleanLocalTitle,
                localArtists,
                localFeatures,
                durationMs,
                cleanLocalAlbum,
            )
            val songTitleMatches = isStrongTitleMatch(
                cleanLocalTitle,
                cleanString(context, song.title),
            )
            val songDurationClose = isStrongDurationMatch(durationMs, song.duration)
            val songArtistMatches = hasCommonArtist(
                localArtists,
                splitArtists(song.artist).map { cleanString(context, it) },
            )
            if (score > localBestScore) {
                localBestScore = score
                bestSong = song
                titleMatched = songTitleMatches
                durationClose = songDurationClose
                artistMatched = songArtistMatches
            }
        }

        LogManager.d(
            "OnlineTargeter",
            "评分: \"${bestSong?.title}\" - \"${bestSong?.artist}\", 关键词=\"$keyword\", " +
                "得分=$localBestScore, 阈值=$PASS_SCORE, 通过=${localBestScore >= PASS_SCORE}",
        )

        if (localBestScore >= PASS_SCORE && bestSong != null) {
            val lyricsResult = withTimeoutOrNull(TIMEOUT_MS) {
                try {
                    source.getLyrics(bestSong)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LogManager.w(
                        "OnlineTargeter",
                        "获取歌词异常: 源=${source.javaClass.simpleName}, ${e.message}",
                    )
                    null
                }
            }
            if (lyricsResult != null &&
                (lyricsResult.original.isNotEmpty() || !lyricsResult.translated.isNullOrEmpty())
            ) {
                val list = toLrcLines(lyricsResult)
                if (list.isNotEmpty()) {
                    if (requireTranslation && list.none {
                            OnlineTranslationContentPolicy.isMeaningful(it.translation)
                        }
                    ) {
                        LogManager.d(
                            "OnlineTargeter",
                            "当前源无可用翻译，继续尝试后续源: " +
                                "源=${source.javaClass.simpleName}",
                        )
                        return SourceAttempt(
                            song = bestSong,
                            score = localBestScore,
                            titleMatched = titleMatched,
                            durationClose = durationClose,
                            artistMatched = artistMatched,
                            passAttempted = true,
                        )
                    }
                    LogManager.d(
                        "OnlineTargeter",
                        "歌词命中: 类型=$metadataLabel, 源=${source.javaClass.simpleName}, " +
                            "关键词=\"$keyword\", 得分=$localBestScore, 行数=${list.size}",
                    )
                    return SourceAttempt(lines = list, song = bestSong, score = localBestScore)
                }
            }
            return SourceAttempt(
                song = bestSong,
                score = localBestScore,
                titleMatched = titleMatched,
                durationClose = durationClose,
                artistMatched = artistMatched,
                passAttempted = true,
            )
        }
        return SourceAttempt(
            song = bestSong,
            score = localBestScore,
            titleMatched = titleMatched,
            durationClose = durationClose,
            artistMatched = artistMatched,
        )
    }

    private fun nearMissFor(
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

    private fun betterSourceAttempt(first: SourceAttempt, retry: SourceAttempt): SourceAttempt =
        when {
            retry.song == null -> first
            first.song == null -> retry
            retry.durationClose && !first.durationClose -> retry
            first.durationClose && !retry.durationClose -> first
            retry.score > first.score -> retry
            else -> first
        }

    private fun preferNearMiss(current: NearMiss, candidate: NearMiss): NearMiss = when {
        current.durationVerified && !candidate.durationVerified -> current
        !current.durationVerified && candidate.durationVerified -> candidate
        candidate.score > current.score -> candidate
        else -> current
    }

    private data class SourceAttempt(
        val lines: List<LrcLine>? = null,
        val song: SongSearchResult? = null,
        val score: Int = -1,
        val titleMatched: Boolean = false,
        val durationClose: Boolean = false,
        val artistMatched: Boolean = false,
        val passAttempted: Boolean = false,
    )

    private data class NearMiss(
        val source: SearchSource,
        val song: SongSearchResult,
        val score: Int,
        val durationVerified: Boolean,
    )

    private data class SearchOutcome(
        val lines: List<LrcLine>? = null,
        val nearMiss: NearMiss? = null,
    )

    internal fun isStrongTitleMatch(localTitle: String, remoteTitle: String): Boolean =
        localTitle.isNotEmpty() && (
            localTitle == remoteTitle ||
                remoteTitle.contains(localTitle) ||
                localTitle.contains(remoteTitle)
            )

    internal fun isStrongDurationMatch(localDurationMs: Long, remoteDurationMs: Long): Boolean =
        localDurationMs <= 0L ||
            abs(localDurationMs - remoteDurationMs) < STRONG_DURATION_TOLERANCE_MS

    internal fun isNearMissEligible(
        score: Int,
        titleMatched: Boolean,
        durationClose: Boolean,
    ): Boolean = score >= NEAR_MISS_MIN_SCORE && titleMatched && durationClose

    internal fun isMultiCreditArtist(artists: List<String>): Boolean =
        artists.count(String::isNotBlank) >= 2

    internal fun hasCommonArtist(
        localArtists: List<String>,
        remoteArtists: List<String>,
    ): Boolean = localArtists.any { local ->
        local.isNotBlank() && remoteArtists.any { remote ->
            remote.isNotBlank() &&
                (local == remote || remote.contains(local) || local.contains(remote))
        }
    }

    internal fun isLyricFallbackEligible(
        titleMatched: Boolean,
        multiCredit: Boolean,
        durationClose: Boolean,
    ): Boolean = titleMatched && multiCredit && !durationClose

    internal fun shouldRetryWithOriginalMetadata(
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

    internal fun resolveMetadataSearchOrder(
        preferOriginalMetadata: Boolean,
        hasDistinctOriginalMetadata: Boolean,
    ): List<Boolean> = when {
        !hasDistinctOriginalMetadata -> listOf(false)
        preferOriginalMetadata -> listOf(true, false)
        else -> listOf(false, true)
    }

    internal fun resolveSourceOrder(
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
            null -> when (pkgName) {
                "com.netease.cloudmusic" -> listOf(Source.NE, Source.QM)
                "com.tencent.qqmusic", "com.tencent.qqmusicpad" ->
                    listOf(Source.QM, Source.NE)
                else -> listOf(Source.QM, Source.NE)
            }
        }
    }

    internal fun toLrcLines(lyricsResult: LyricsResult): List<LrcLine> {
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

    private fun calculateScore(
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

    internal fun albumScore(cleanLocalAlbum: String, cleanRemoteAlbum: String): Int {
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
    internal fun normalizeAlbumCharacters(input: String): String =
        Normalizer.normalize(input, Normalizer.Form.NFKC)

    private fun normalizeAlbum(context: Context, input: String): String =
        cleanString(context, normalizeAlbumCharacters(input))

    /**
     * 去掉末尾的版本/录音标记，让“原曲 / 现场 / 不插电 / 翻唱 / 豪华版”等
     * 同一首歌词的变体在专辑维度不被误判成不同专辑。只从尾部逐段剥离，避免
     * 误伤普通专辑名（例如 Greatest Hits 不会被拆成 Greatest）。
     */
    internal fun stripAlbumVersionSuffixes(value: String): String {
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

    private fun stripTrailingVersionSuffix(value: String, suffix: String): String {
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

    private fun Char.isCjkUnifiedIdeograph(): Boolean =
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

    private fun cleanString(context: Context, input: String): String {
        val cleaned = input.replace(Regex("\\(.*?\\)|\\[.*?]|\\{.*?\\}"), "").trim().lowercase()
        return compactWhitespace(ChineseUtils.toSimplified(context, cleaned))
    }

    internal fun durationScore(localDurationMs: Long, remoteDurationMs: Long): Int {
        val diffMs = abs(localDurationMs - remoteDurationMs)
        return when {
            diffMs > 5_000L -> -30
            diffMs < 1_500L -> 15
            else -> 10
        }
    }

    internal fun compactWhitespace(value: String): String = value.replace(Regex("\\s+"), "")

    private fun splitArtists(value: String): List<String> =
        value.split("&", ",", "，", "、", "/", "／")

    private data class SearchMetadata(
        val title: String,
        val artist: String,
        val label: String,
    )

}
