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
import kotlin.math.abs

object OnlineLyricTargeter {
    private const val TIMEOUT_MS = 5000L
    private const val PASS_SCORE = 85

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
        fallbackToOtherSources: Boolean = true
    ): List<LrcLine>? {
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
                durationMs = durationMs,
                requireTranslation = requireTranslation,
                metadataLabel = metadata.label,
            )?.let { return it }
        }
        return null
    }

    private suspend fun searchSources(
        context: Context,
        sources: List<SearchSource>,
        title: String,
        artist: String,
        durationMs: Long,
        requireTranslation: Boolean,
        metadataLabel: String
    ): List<LrcLine>? {

        val keyword = "$title $artist"
        LogManager.d(
            "OnlineTargeter",
            "正在搜索: 类型=$metadataLabel, 关键词=\"$keyword\", " +
                "源顺序=${sources.joinToString { it.javaClass.simpleName }}"
        )

        val cleanLocalTitle = cleanString(context, title)
        val localArtists = splitArtists(artist).map { cleanString(context, it) }
        val featureKeywords = listOf("live", "remastered", "翻唱", "cover")
        val localFeatures = featureKeywords.filter { title.lowercase().contains(it) }
        
        var bestScore = -1

        for (source in sources) {
            val results = withTimeoutOrNull(TIMEOUT_MS) {
                try {
                    source.search(keyword, 1, "/", 20)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LogManager.w("OnlineTargeter", "搜索异常: 源=${source.javaClass.simpleName}, ${e.message}")
                    null
                }
            }
            if (results.isNullOrEmpty()) {
                LogManager.d("OnlineTargeter", "搜索结果为空: 源=${source.javaClass.simpleName}")
                continue
            }
            LogManager.d("OnlineTargeter", "搜索结果: 源=${source.javaClass.simpleName}, 数量=${results.size}")

            var localBestScore = -1
            var bestSong: SongSearchResult? = null

            for (song in results) {
                val score = calculateScore(
                    context,
                    song,
                    cleanLocalTitle,
                    localArtists,
                    localFeatures,
                    durationMs,
                )
                if (score > localBestScore) {
                    localBestScore = score
                    bestSong = song
                }
            }

            if (localBestScore > bestScore) bestScore = localBestScore
            LogManager.d(
                "OnlineTargeter",
                "评分: \"${bestSong?.title}\" - \"${bestSong?.artist}\", " +
                    "得分=$localBestScore, 阈值=$PASS_SCORE, 通过=${localBestScore >= PASS_SCORE}"
            )

            if (localBestScore >= PASS_SCORE && bestSong != null) {
                val lyricsResult = withTimeoutOrNull(TIMEOUT_MS) {
                    try {
                        source.getLyrics(bestSong)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LogManager.w("OnlineTargeter", "获取歌词异常: 源=${source.javaClass.simpleName}, ${e.message}")
                        null
                    }
                }
                
                if (lyricsResult != null && (lyricsResult.original.isNotEmpty() || !lyricsResult.translated.isNullOrEmpty())) {
                    val list = toLrcLines(lyricsResult)
                    if (list.isNotEmpty()) {
                        if (requireTranslation && list.none {
                                OnlineTranslationContentPolicy.isMeaningful(it.translation)
                            }
                        ) {
                            LogManager.d(
                                "OnlineTargeter",
                                "当前源无可用翻译，继续尝试后续源: " +
                                    "源=${source.javaClass.simpleName}"
                            )
                            continue
                        }
                        LogManager.d(
                            "OnlineTargeter",
                            "歌词命中: 类型=$metadataLabel, 源=${source.javaClass.simpleName}, " +
                                "得分=$bestScore, 行数=${list.size}"
                        )
                        return list
                    }
                }
            }
        }
        LogManager.d(
            "OnlineTargeter",
            "歌词未命中: 类型=$metadataLabel, 最佳得分=$bestScore < 阈值 $PASS_SCORE"
        )
        return null
    }

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
        localDurationMs: Long
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

        val songFeatures = listOf("live", "remastered", "翻唱", "cover").filter { song.title.lowercase().contains(it) }
        
        if (localFeatures.isNotEmpty() && songFeatures.isNotEmpty()) {
            val commonFeatures = localFeatures.intersect(songFeatures.toSet())
            if (commonFeatures.isNotEmpty()) {
                score += 20
            }
        }

        return score
    }

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
