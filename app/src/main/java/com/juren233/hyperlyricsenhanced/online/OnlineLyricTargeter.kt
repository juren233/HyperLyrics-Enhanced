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

object OnlineLyricTargeter {
    private const val TIMEOUT_MS = 5000L
    private const val PASS_SCORE = 85
    internal const val NEAR_MISS_MIN_SCORE = 50
    internal const val STRONG_DURATION_TOLERANCE_MS = 1_500L

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
        val wordLines: List<LyricsLine>? = null,
        val nearMiss: NearMissCandidate? = null,
        val sourceStatuses: List<AppleMissingLyricsSourceStatus> = emptyList(),
        val selectedSource: Source? = null,
        val rawAppleTtml: String? = null,
        val sourceLyricId: String? = null,
        val sourceLyricSha256: String? = null,
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
        statusSourceOrder: List<Source>? = null,
        album: String? = null,
        originalAlbum: String? = null,
        collectSourceStatuses: Boolean = false,
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
        statusSourceOrder = statusSourceOrder,
        album = album,
        originalAlbum = originalAlbum,
        collectSourceStatuses = collectSourceStatuses,
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
        statusSourceOrder: List<Source>? = null,
        album: String? = null,
        originalAlbum: String? = null,
        collectSourceStatuses: Boolean = false,
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
            statusSourceOrder = statusSourceOrder,
            album = album,
            originalAlbum = originalAlbum,
            collectSourceStatuses = collectSourceStatuses,
        )
        if (outcome.lines != null) {
            return FetchOutcome(
                lines = outcome.lines,
                wordLines = outcome.wordLines,
                sourceStatuses = outcome.sourceStatuses,
                selectedSource = outcome.selectedSource,
            )
        }
        val nearMiss = outcome.nearMiss ?: return FetchOutcome(
            sourceStatuses = outcome.sourceStatuses,
        )
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
            return FetchOutcome(sourceStatuses = outcome.sourceStatuses)
        }
        val lines = toLrcLines(lyricsResult)
        if (lines.isEmpty()) return FetchOutcome()
        if (requireTranslation && lines.none {
                OnlineTranslationContentPolicy.isMeaningful(it.translation)
            }
        ) {
            return FetchOutcome(sourceStatuses = outcome.sourceStatuses)
        }
        val wordLines = toWordLines(lyricsResult)
        LogManager.d(
            "OnlineTargeter",
            "近失候选歌词: 源=${nearMiss.source.javaClass.simpleName}, " +
                "得分=${nearMiss.score}, 行数=${lines.size}, " +
                "时长已校验=${nearMiss.durationVerified}",
        )
        return FetchOutcome(
            lines = lines,
            wordLines = wordLines,
            nearMiss = NearMissCandidate(
                score = nearMiss.score,
                lines = lines,
                durationVerified = nearMiss.durationVerified,
            ),
            sourceStatuses = mergeStatuses(
                outcome.sourceStatuses,
                statusFor(nearMiss.source.sourceType, lyricsResult, lines),
            ),
            selectedSource = nearMiss.source.sourceType,
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
        statusSourceOrder: List<Source>?,
        album: String?,
        originalAlbum: String?,
        collectSourceStatuses: Boolean,
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
        val searchedSourceTypes = resolvedSourceOrder.toSet()
        val statusOnlySources = statusSourceOrder
            ?.distinct()
            .orEmpty()
            .filterNot(searchedSourceTypes::contains)
            .mapNotNull(sourcesByType::get)

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
        val allSourceStatuses = linkedMapOf<Source, AppleMissingLyricsSourceStatus>()
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
                collectSourceStatuses = collectSourceStatuses,
                statusSources = if (index == 0) statusOnlySources else emptyList(),
            )
            if (collectSourceStatuses) {
                outcome.sourceStatuses.forEach { status -> allSourceStatuses.mergeStatus(status) }
            }
            if (outcome.lines != null) {
                if (!collectSourceStatuses) {
                    return SearchOutcome(
                        lines = outcome.lines,
                        wordLines = outcome.wordLines,
                        selectedSource = outcome.selectedSource,
                        sourceStatuses = allSourceStatuses.values.toList(),
                    )
                }
                return SearchOutcome(
                    lines = outcome.lines,
                    wordLines = outcome.wordLines,
                    sourceStatuses = allSourceStatuses.values.toList(),
                    selectedSource = outcome.selectedSource,
                )
            }
            if (outcome.nearMiss != null) {
                bestNearMiss = if (bestNearMiss == null) {
                    outcome.nearMiss
                } else {
                    preferNearMiss(bestNearMiss, outcome.nearMiss)
                }
            }
        }
        return SearchOutcome(
            nearMiss = bestNearMiss,
            sourceStatuses = allSourceStatuses.values.toList(),
            selectedSource = bestNearMiss?.source?.sourceType,
        )
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
        collectSourceStatuses: Boolean,
        statusSources: List<SearchSource> = emptyList(),
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

        val parallelEvaluations = if (collectSourceStatuses) {
            val candidateTypes = sources.mapTo(linkedSetOf()) { it.sourceType }
            val statusOnlyWork = statusSources
                .filterNot { it.sourceType in candidateTypes }
            val work = buildList {
                sources.forEach { add(it to true) }
                statusOnlyWork.forEach { add(it to false) }
            }
            coroutineScope {
                val evaluations = work.associate { (source, allowFallbackRetry) ->
                    source.sourceType to async {
                        source.sourceType to evaluateSource(
                            context = context,
                            source = source,
                            title = title,
                            keyword = keyword,
                            fallbackKeyword = fallbackKeyword,
                            artist = artist,
                            durationMs = durationMs,
                            requireTranslation = requireTranslation,
                            metadataLabel = metadataLabel,
                            cleanLocalTitle = cleanLocalTitle,
                            localArtists = localArtists,
                            localFeatures = localFeatures,
                            cleanLocalAlbum = cleanLocalAlbum,
                            multiCredit = multiCredit,
                            allowFallbackRetry = allowFallbackRetry,
                        )
                    }
                }
                if (
                    shouldWaitForStatusOnlySources(
                        candidateSourceCount = sources.size,
                        statusOnlySourceCount = statusOnlyWork.size,
                    )
                ) {
                    evaluations.values.awaitAll().toMap()
                } else {
                    // 严格歌词源切换只需要等待目标源。其他来源的状态已有历史值，
                    // 不能让无关网络请求把目标行长期卡在「获取中」。
                    val candidateResults = sources.associate { source ->
                        evaluations.getValue(source.sourceType).await()
                    }
                    statusOnlyWork.forEach { source ->
                        evaluations[source.sourceType]?.cancel()
                    }
                    candidateResults
                }
            }
        } else {
            emptyMap()
        }

        var bestScore = -1
        var bestNearMiss: NearMiss? = null
        var selectedSuccess: SourceAttempt? = null
        val sourceStatuses = linkedMapOf<Source, AppleMissingLyricsSourceStatus>()

        for (source in sources) {
            val evaluation = if (collectSourceStatuses) {
                parallelEvaluations.getValue(source.sourceType)
            } else {
                evaluateSource(
                    context = context,
                    source = source,
                    title = title,
                    keyword = keyword,
                    fallbackKeyword = fallbackKeyword,
                    artist = artist,
                    durationMs = durationMs,
                    requireTranslation = requireTranslation,
                    metadataLabel = metadataLabel,
                    cleanLocalTitle = cleanLocalTitle,
                    localArtists = localArtists,
                    localFeatures = localFeatures,
                    cleanLocalAlbum = cleanLocalAlbum,
                    multiCredit = multiCredit,
                    allowFallbackRetry = true,
                )
            }
            var attempt = evaluation.attempt
            if (collectSourceStatuses) {
                evaluation.statuses.forEach { status -> sourceStatuses.mergeStatus(status) }
            }
            if (attempt.lines != null) {
                if (selectedSuccess == null) selectedSuccess = attempt
                if (!collectSourceStatuses) {
                    return SearchOutcome(
                        lines = attempt.lines,
                        wordLines = attempt.wordLines,
                        selectedSource = source.sourceType,
                    )
                }
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
        if (collectSourceStatuses) {
            // 补充歌词弹窗要求每个来源都给出「已检索到」或「检索失败」，
            // 不能因为某来源不在候选顺序里就显示「未检索」。这里只补状态，
            // 不参与歌词/近失候选选择。
            statusSources.forEach { statusSource ->
                parallelEvaluations[statusSource.sourceType]
                    ?.statuses
                    ?.forEach { status -> sourceStatuses.mergeStatus(status) }
            }
        }
        if (selectedSuccess == null) {
            LogManager.d(
                "OnlineTargeter",
                "歌词未命中: 类型=$metadataLabel, 最佳得分=$bestScore, 阈值=$PASS_SCORE"
            )
        }
        return SearchOutcome(
            lines = selectedSuccess?.lines,
            wordLines = selectedSuccess?.wordLines,
            nearMiss = bestNearMiss,
            sourceStatuses = sourceStatuses.values.toList(),
            selectedSource = selectedSuccess?.song?.source ?: bestNearMiss?.source?.sourceType,
        )
    }

    private data class SourceEvaluation(
        val attempt: SourceAttempt,
        val statuses: List<AppleMissingLyricsSourceStatus>,
    )

    private suspend fun evaluateSource(
        context: Context,
        source: SearchSource,
        title: String,
        keyword: String,
        fallbackKeyword: String,
        artist: String,
        durationMs: Long,
        requireTranslation: Boolean,
        metadataLabel: String,
        cleanLocalTitle: String,
        localArtists: List<String>,
        localFeatures: List<String>,
        cleanLocalAlbum: String,
        multiCredit: Boolean,
        allowFallbackRetry: Boolean,
    ): SourceEvaluation {
        val sourceKeyword = if (source.sourceType == Source.KUGOU && artist.isNotBlank()) {
            "$artist - $title"
        } else {
            keyword
        }
        val statuses = mutableListOf<AppleMissingLyricsSourceStatus>()
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
        statuses += attempt.status
        if (
            allowFallbackRetry &&
            multiCredit &&
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
            statuses += retry.status
            attempt = betterSourceAttempt(attempt, retry)
        }
        return SourceEvaluation(attempt = attempt, statuses = statuses)
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
            return SourceAttempt(
                status = AppleMissingLyricsSourceStatus(
                    source = source.sourceType.name,
                    searched = true,
                )
            )
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
                            status = statusFor(source.sourceType, lyricsResult, list),
                        )
                    }
                    LogManager.d(
                        "OnlineTargeter",
                        "歌词命中: 类型=$metadataLabel, 源=${source.javaClass.simpleName}, " +
                            "关键词=\"$keyword\", 得分=$localBestScore, 行数=${list.size}",
                    )
                    return SourceAttempt(
                        lines = list,
                        wordLines = toWordLines(lyricsResult),
                        song = bestSong,
                        score = localBestScore,
                        status = statusFor(source.sourceType, lyricsResult, list),
                    )
                }
            }
            return SourceAttempt(
                song = bestSong,
                score = localBestScore,
                titleMatched = titleMatched,
                durationClose = durationClose,
                artistMatched = artistMatched,
                passAttempted = true,
                status = AppleMissingLyricsSourceStatus(
                    source = source.sourceType.name,
                    searched = true,
                ),
            )
        }
        return SourceAttempt(
            song = bestSong,
            score = localBestScore,
            titleMatched = titleMatched,
            durationClose = durationClose,
            artistMatched = artistMatched,
            status = AppleMissingLyricsSourceStatus(
                source = source.sourceType.name,
                searched = true,
            ),
        )
    }

}
