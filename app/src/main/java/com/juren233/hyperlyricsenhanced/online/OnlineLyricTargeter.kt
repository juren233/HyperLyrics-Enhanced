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
    private const val TOTAL_TIMEOUT_MS = 15_000L
    private const val PASS_SCORE = 80
    private const val TITLE_SCORE = 50
    private const val ARTIST_SCORE = 30
    private const val ALBUM_SCORE = 30
    private const val FEATURE_SCORE = 20
    private const val DURATION_CLOSE_SCORE = 15
    private const val DURATION_DRIFT_SCORE = 10
    private const val DURATION_MISMATCH_SCORE = -30
    private const val SEARCH_PAGE_SIZE = 50
    private const val MAX_LYRIC_CANDIDATES_PER_QUERY = 5

    suspend fun fetchBestLyric(
        context: Context,
        pkgName: String, 
        title: String, 
        artist: String, 
        durationMs: Long,
        album: String = "",
        originalTitle: String? = null,
        originalArtist: String? = null,
        originalAlbum: String? = null,
        preferOriginalMetadata: Boolean = false,
        preferredSource: Source? = null,
        requireTranslation: Boolean = false,
        fallbackToOtherSources: Boolean = true,
        diagnostic: ((String) -> Unit)? = null,
    ): List<LrcLine>? = withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
        val ne = LyricApiProvider.getNeSource(context)
        val qm = LyricApiProvider.qmSource
        val sourcesByType = mapOf(Source.NE to ne, Source.QM to qm)
        val sources = resolveSourceOrder(
            pkgName = pkgName,
            preferredSource = preferredSource,
            fallbackToOtherSources = fallbackToOtherSources
        ).mapNotNull(sourcesByType::get)

        val resolvedTitle = originalTitle?.takeIf { it.isNotBlank() } ?: title
        val resolvedArtist = originalArtist?.takeIf { it.isNotBlank() } ?: artist
        val resolvedAlbum = originalAlbum?.takeIf { it.isNotBlank() } ?: album
        val hasDistinctOriginalMetadata = shouldRetryWithOriginalMetadata(
            title = title,
            artist = artist,
            originalTitle = originalTitle,
            originalArtist = originalArtist,
            album = album,
            originalAlbum = originalAlbum,
        )
        val searches = resolveMetadataSearchOrder(
            preferOriginalMetadata = preferOriginalMetadata,
            hasDistinctOriginalMetadata = hasDistinctOriginalMetadata,
        ).map { useOriginalMetadata ->
            if (useOriginalMetadata) {
                SearchMetadata(resolvedTitle, resolvedArtist, resolvedAlbum, "Apple 内部原名")
            } else {
                SearchMetadata(title, artist, album, "当前元数据")
            }
        }
        searches.forEachIndexed { index, metadata ->
            if (index > 0 && metadata.label == "Apple 内部原名") {
                LogManager.d(
                    "OnlineTargeter",
                    "使用 Apple 内部原名重试: ${metadata.title} / ${metadata.artist}"
                )
            }
            searchSources(
                context = context,
                sources = sources,
                title = metadata.title,
                artist = metadata.artist,
                album = metadata.album,
                durationMs = durationMs,
                requireTranslation = requireTranslation,
                metadataLabel = metadata.label,
                diagnostic = diagnostic,
            )?.let { return@withTimeoutOrNull it }
        }
        null
    }

    private suspend fun searchSources(
        context: Context,
        sources: List<SearchSource>,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        requireTranslation: Boolean,
        metadataLabel: String,
        diagnostic: ((String) -> Unit)?,
    ): List<LrcLine>? {

        val cleanLocalTitle = cleanString(context, title)
        val localArtists = splitArtists(artist).map { cleanString(context, it) }
        val cleanLocalAlbum = cleanString(context, album)
        val featureKeywords = listOf("live", "remastered", "翻唱", "cover")
        val localFeatures = featureKeywords.filter { title.lowercase().contains(it) }
        val keywords = resolveSearchKeywords(title, artist, album)
        var bestScore = -1

        for (source in sources) {
            val attemptedSongIds = mutableSetOf<String>()
            for (keyword in keywords) {
                LogManager.d(
                    "OnlineTargeter",
                    "正在搜索: 类型=$metadataLabel, 关键词=\"$keyword\", " +
                        "源=${source.javaClass.simpleName}"
                )
                val results = withTimeoutOrNull(TIMEOUT_MS) {
                    try {
                        source.search(keyword, 1, "/", SEARCH_PAGE_SIZE)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LogManager.w(
                            "OnlineTargeter",
                            "搜索异常: 源=${source.javaClass.simpleName}, ${e.message}"
                        )
                        null
                    }
                }
                if (results.isNullOrEmpty()) {
                    LogManager.d(
                        "OnlineTargeter",
                        "搜索结果为空: 源=${source.javaClass.simpleName}, 关键词=\"$keyword\""
                    )
                    continue
                }
                LogManager.d(
                    "OnlineTargeter",
                    "搜索结果: 源=${source.javaClass.simpleName}, " +
                        "关键词=\"$keyword\", 数量=${results.size}"
                )

                val candidates = results
                    .map { song ->
                        song to calculateScore(
                            context,
                            song,
                            cleanLocalTitle,
                            localArtists,
                            cleanLocalAlbum,
                            localFeatures,
                            durationMs,
                        )
                    }
                    .sortedByDescending { (_, score) -> score.total }
                val localBest = candidates.firstOrNull()
                val localBestScore = localBest?.second?.total ?: -1
                if (localBestScore > bestScore) bestScore = localBestScore
                LogManager.d(
                    "OnlineTargeter",
                    "评分: \"${localBest?.first?.title}\" - \"${localBest?.first?.artist}\" / " +
                        "\"${localBest?.first?.album}\", " +
                        "得分=$localBestScore, 阈值=$PASS_SCORE, " +
                        "标题=${localBest?.second?.titleMatched}, " +
                        "歌手=${localBest?.second?.artistMatched}, " +
                        "专辑=${localBest?.second?.albumMatched}, " +
                        "通过=${localBest?.second?.isEligible == true}, 关键词=\"$keyword\""
                )
                diagnostic?.invoke(
                    "在线候选联合评分: metadata=$metadataLabel, " +
                        "query=\"$keyword\", source=${source.javaClass.simpleName}, " +
                        "candidateTitle=${localBest?.first?.title}, " +
                        "candidateArtist=${localBest?.first?.artist}, " +
                        "candidateAlbum=${localBest?.first?.album}, score=$localBestScore, " +
                        "localAlbumKey=$cleanLocalAlbum, " +
                        "candidateAlbumKey=${localBest?.first?.album?.let { cleanString(context, it) }.orEmpty()}, " +
                        "titleMatched=${localBest?.second?.titleMatched}, " +
                        "artistMatched=${localBest?.second?.artistMatched}, " +
                        "albumMatched=${localBest?.second?.albumMatched}, " +
                        "eligible=${localBest?.second?.isEligible == true}"
                )

                val eligibleCandidates = candidates
                    .asSequence()
                    .filter { (_, score) -> score.isEligible }
                    .filter { (song, _) -> attemptedSongIds.add(song.id) }
                    .take(MAX_LYRIC_CANDIDATES_PER_QUERY)
                    .toList()
                for ((candidate, candidateMatch) in eligibleCandidates) {
                    val resolvedCandidate = resolveCandidateArtistAlias(
                        context = context,
                        source = source,
                        candidate = candidate,
                        candidateMatch = candidateMatch,
                        cleanLocalTitle = cleanLocalTitle,
                        cleanLocalAlbum = cleanLocalAlbum,
                        localFeatures = localFeatures,
                        durationMs = durationMs,
                        metadataLabel = metadataLabel,
                        diagnostic = diagnostic,
                    ) ?: candidate
                    val lyricsResult = withTimeoutOrNull(TIMEOUT_MS) {
                        try {
                            source.getLyrics(resolvedCandidate)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            LogManager.w(
                                "OnlineTargeter",
                                "获取歌词异常: 源=${source.javaClass.simpleName}, " +
                                    "id=${resolvedCandidate.id}, ${e.message}"
                            )
                            null
                        }
                    }

                    if (lyricsResult == null ||
                        (lyricsResult.original.isEmpty() && lyricsResult.translated.isNullOrEmpty())
                    ) {
                        LogManager.d(
                            "OnlineTargeter",
                            "候选无可用歌词，尝试下一条: 源=${source.javaClass.simpleName}, " +
                                "id=${resolvedCandidate.id}, score=${candidateMatch.total}"
                        )
                        continue
                    }

                    val list = toLrcLines(lyricsResult)
                    if (list.isEmpty()) continue
                    if (requireTranslation && list.none {
                            OnlineTranslationContentPolicy.isMeaningful(it.translation)
                        }
                    ) {
                        LogManager.d(
                            "OnlineTargeter",
                            "当前候选无可用翻译，尝试下一条: " +
                                "源=${source.javaClass.simpleName}, id=${candidate.id}"
                        )
                        continue
                    }
                    LogManager.d(
                        "OnlineTargeter",
                        "歌词命中: 类型=$metadataLabel, 源=${source.javaClass.simpleName}, " +
                            "得分=${candidateMatch.total}, 行数=${list.size}, 关键词=\"$keyword\""
                    )
                    diagnostic?.invoke(
                        "在线候选联合命中: metadata=$metadataLabel, " +
                            "source=${source.javaClass.simpleName}, title=${candidate.title}, " +
                            "artist=${candidate.artist}, album=${candidate.album}, " +
                            "score=${candidateMatch.total}"
                    )
                    return list
                }
            }
        }
        LogManager.d(
            "OnlineTargeter",
            "歌词未命中: 类型=$metadataLabel, 最佳得分=$bestScore, 阈值=$PASS_SCORE, " +
                "要求=标题匹配且歌手或专辑至少一项匹配"
        )
        diagnostic?.invoke(
            "在线候选联合未命中: metadata=$metadataLabel, title=$title, artist=$artist, " +
                "album=$album, bestScore=$bestScore, requirement=title+(artist|album)"
        )
        return null
    }

    /**
     * A catalog may expose a creator under a different public name (for example KZ/livetune).
     * Once title+album identifies a candidate, use that candidate's artist as the next query
     * identity instead of maintaining provider-specific alias tables.
     */
    private suspend fun resolveCandidateArtistAlias(
        context: Context,
        source: SearchSource,
        candidate: SongSearchResult,
        candidateMatch: CandidateMatch,
        cleanLocalTitle: String,
        cleanLocalAlbum: String,
        localFeatures: List<String>,
        durationMs: Long,
        metadataLabel: String,
        diagnostic: ((String) -> Unit)?,
    ): SongSearchResult? {
        if (candidateMatch.artistMatched || !candidateMatch.albumMatched) return null
        val aliasQuery = listOf(candidate.title, candidate.artist, candidate.album)
            .filter(String::isNotBlank)
            .joinToString(" ")
        val aliasResults = withTimeoutOrNull(TIMEOUT_MS) {
            runCatching { source.search(aliasQuery, 1, "/", SEARCH_PAGE_SIZE) }.getOrNull()
        }.orEmpty()
        val canonicalArtists = splitArtists(candidate.artist)
            .map { cleanString(context, it) }
        val canonical = aliasResults
            .map { song ->
                song to calculateScore(
                    context,
                    song,
                    cleanLocalTitle,
                    canonicalArtists,
                    cleanLocalAlbum,
                    localFeatures,
                    durationMs,
                )
            }
            .filter { (_, score) -> score.isEligible }
            .maxByOrNull { (_, score) -> score.total }
            ?.first
        diagnostic?.invoke(
            "在线候选歌手回填: metadata=$metadataLabel, " +
                "source=${source.javaClass.simpleName}, " +
                "fromArtist=${candidate.artist}, query=\"$aliasQuery\", " +
                "resolvedArtist=${canonical?.artist ?: candidate.artist}, " +
                "resolved=${canonical != null}"
        )
        return canonical
    }

    internal fun shouldRetryWithOriginalMetadata(
        title: String,
        artist: String,
        originalTitle: String?,
        originalArtist: String?,
        album: String = "",
        originalAlbum: String? = null,
    ): Boolean {
        val resolvedTitle = originalTitle?.trim().orEmpty()
        val resolvedArtist = originalArtist?.trim().orEmpty()
        val resolvedAlbum = originalAlbum?.trim().orEmpty()
        if (resolvedTitle.isEmpty() && resolvedArtist.isEmpty() && resolvedAlbum.isEmpty()) return false
        return !resolvedTitle.equals(title.trim(), ignoreCase = true) ||
            !resolvedArtist.equals(artist.trim(), ignoreCase = true) ||
            !resolvedAlbum.equals(album.trim(), ignoreCase = true)
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
            null -> when (pkgName) {
                "com.netease.cloudmusic" -> listOf(Source.NE, Source.QM)
                "com.tencent.qqmusic" -> listOf(Source.QM, Source.NE)
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
        cleanLocalAlbum: String,
        localFeatures: List<String>,
        localDurationMs: Long
    ): CandidateMatch {
        var score = 0

        if (localDurationMs > 0 && song.duration > 0) {
            score += durationScore(localDurationMs, song.duration)
        }

        val cleanSongTitle = cleanString(context, song.title)

        val titleMatched = stringsMatch(cleanLocalTitle, cleanSongTitle)
        if (titleMatched) {
            score += TITLE_SCORE
        }

        val songArtists = splitArtists(song.artist).map { cleanString(context, it) }
        
        val artistMatched = localArtists.any { localArtist ->
            songArtists.any { songArtist -> stringsMatch(localArtist, songArtist) }
        }
        if (artistMatched) score += ARTIST_SCORE

        val cleanSongAlbum = cleanString(context, song.album)
        val albumMatched = cleanLocalAlbum.isNotEmpty() && cleanSongAlbum.isNotEmpty() &&
            stringsMatch(cleanLocalAlbum, cleanSongAlbum)
        if (albumMatched) score += ALBUM_SCORE

        val songFeatures = listOf("live", "remastered", "翻唱", "cover").filter { song.title.lowercase().contains(it) }
        
        if (localFeatures.isNotEmpty() && songFeatures.isNotEmpty()) {
            val commonFeatures = localFeatures.intersect(songFeatures.toSet())
            if (commonFeatures.isNotEmpty()) {
                score += FEATURE_SCORE
            }
        }

        return CandidateMatch(
            total = score,
            titleMatched = titleMatched,
            artistMatched = artistMatched,
            albumMatched = albumMatched,
        )
    }

    private fun cleanString(context: Context, input: String): String {
        val cleaned = normalizeWidth(input)
            .replace(BRACKETED_SEGMENT_REGEX, "")
            .trim()
            .lowercase()
        return compactWhitespace(
            normalizeMatchText(ChineseUtils.toSimplified(context, cleaned))
        )
    }

    /** Build the same identity key for catalog punctuation, spacing, and invisible format chars. */
    internal fun normalizeMatchText(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase()
            .replace(BRACKETED_SEGMENT_REGEX, "")
            .replace(MATCH_IGNORED_REGEX, "")

    internal fun normalizeWidth(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)

    internal fun durationScore(localDurationMs: Long, remoteDurationMs: Long): Int {
        val diffMs = abs(localDurationMs - remoteDurationMs)
        return when {
            diffMs > 5_000L -> DURATION_MISMATCH_SCORE
            diffMs < 1_500L -> DURATION_CLOSE_SCORE
            else -> DURATION_DRIFT_SCORE
        }
    }

    internal fun compactWhitespace(value: String): String = value.replace(Regex("\\s+"), "")

    /**
     * Search APIs are much less tolerant of voice-actor credits than the local scorer. Keep the
     * precise query first, then progressively reduce it while the scorer remains authoritative.
     */
    internal fun resolveSearchKeywords(
        title: String,
        artist: String,
        album: String = "",
    ): List<String> {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return emptyList()
        val cleanArtist = artist.trim()
        val cleanAlbum = album.trim()
        val albumPrecise = listOf(cleanTitle, cleanArtist, cleanAlbum)
            .filter(String::isNotEmpty)
            .joinToString(" ")
        val artistPrecise = listOf(cleanTitle, cleanArtist)
            .filter(String::isNotEmpty)
            .joinToString(" ")
        val artistQueries = splitArtists(artist)
            .asSequence()
            .map { value ->
                value.replace(BRACKETED_SEGMENT_REGEX, " ")
                    .replace(Regex("(?i)\\b(?:cv|feat|ft)\\.?\\s*[:.]?"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }
            .filter(String::isNotEmpty)
            .distinctBy(String::lowercase)
            .take(3)
            .map { "$cleanTitle $it" }
            .toList()
        return buildList {
            if (cleanAlbum.isNotEmpty()) add(albumPrecise)
            if (artistPrecise.isNotEmpty()) add(artistPrecise)
            addAll(artistQueries)
            if (cleanAlbum.isNotEmpty()) add("$cleanTitle $cleanAlbum")
            add(cleanTitle)
        }.distinctBy(String::lowercase)
    }

    private fun stringsMatch(first: String, second: String): Boolean =
        first.isNotEmpty() && second.isNotEmpty() &&
            (first == second || first.contains(second) || second.contains(first))

    private fun splitArtists(value: String): List<String> =
        value.split("&", ",", "，", "、", "/", "／")

    // Escape closing delimiters explicitly. Android's ICU regex rejects the Java-tolerated
    // forms `[.*?]` / `{.*?}` with PatternSyntaxException before any search request is sent.
    private val BRACKETED_SEGMENT_REGEX = Regex(
        "\\([^)]*\\)|\\[[^\\]]*\\]|\\{[^}]*\\}",
    )
    private val MATCH_IGNORED_REGEX = Regex("[\\p{P}\\p{S}\\p{Cf}\\s]")

    internal data class CandidateMatch(
        val total: Int,
        val titleMatched: Boolean,
        val artistMatched: Boolean,
        val albumMatched: Boolean,
    ) {
        val isEligible: Boolean
            get() = total >= PASS_SCORE && titleMatched && (artistMatched || albumMatched)
    }

    private data class SearchMetadata(
        val title: String,
        val artist: String,
        val album: String,
        val label: String,
    )

}
