package com.juren233.hyperlyricsenhanced.online

import android.content.Context
import com.juren233.hyperlyricsenhanced.lyric.LrcLine
import com.juren233.hyperlyricsenhanced.online.model.LyricsResult
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

        val keyword = "$title $artist"
        LogManager.d("OnlineTargeter", "正在搜索: 关键词=\"$keyword\", 源顺序=${sources.joinToString { it.javaClass.simpleName }}")

        val cleanLocalTitle = cleanString(context, title)
        val localArtists = artist.split("&", ",", "，", "、").map { cleanString(context, it) }
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
                val score = calculateScore(context, song, cleanLocalTitle, localArtists, localFeatures, durationMs)
                if (score > localBestScore) {
                    localBestScore = score
                    bestSong = song
                }
            }

            if (localBestScore > bestScore) bestScore = localBestScore
            LogManager.d("OnlineTargeter", "评分: \"${bestSong?.title}\" - \"${bestSong?.artist}\", 得分=$localBestScore, 阈值=$PASS_SCORE, 通过=${localBestScore >= PASS_SCORE}")

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
                        if (requireTranslation && list.none { !it.translation.isNullOrBlank() }) {
                            LogManager.d(
                                "OnlineTargeter",
                                "当前源无可用翻译，继续尝试后续源: " +
                                    "源=${source.javaClass.simpleName}"
                            )
                            continue
                        }
                        LogManager.d("OnlineTargeter", "歌词命中: 源=${source.javaClass.simpleName}, 得分=$bestScore, 行数=${list.size}")
                        return list
                    }
                }
            }
        }
        LogManager.d("OnlineTargeter", "歌词未命中: 最佳得分=$bestScore < 阈值 $PASS_SCORE")
        return null
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
        return lyricsResult.original.mapNotNull { line ->
            val content = line.words.joinToString("") { it.text }.trim()
            if (content.isEmpty()) return@mapNotNull null
            LrcLine(
                startTimeMs = line.start,
                content = content,
                translation = translationsByStart[line.start]?.takeIf(String::isNotEmpty)
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
            val diffMs = abs(localDurationMs - song.duration)
            if (diffMs > 5000) {
                score -= 30
            } else if (diffMs < 1500) {
                score += 15
            }
        }

        val cleanSongTitle = cleanString(context, song.title)

        if (cleanLocalTitle == cleanSongTitle || cleanSongTitle.contains(cleanLocalTitle) || cleanLocalTitle.contains(cleanSongTitle)) {
            score += 50
        }

        val songArtists = song.artist.split("&", ",", "，", "、").map { cleanString(context, it) }
        
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
        return ChineseUtils.toSimplified(context, cleaned)
    }
}
