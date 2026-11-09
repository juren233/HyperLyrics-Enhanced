package com.juren233.hyperlyricsenhanced.online.source.qm


import android.util.Base64
import com.juren233.hyperlyricsenhanced.common.lyric.SongMetadataQueryPolicy
import com.juren233.hyperlyricsenhanced.online.model.LyricsResult
import com.juren233.hyperlyricsenhanced.online.model.LyricsData
import com.juren233.hyperlyricsenhanced.online.model.SearchSource
import com.juren233.hyperlyricsenhanced.online.model.SongSearchResult
import com.juren233.hyperlyricsenhanced.online.model.Source
import com.juren233.hyperlyricsenhanced.online.utils.QmCryptoUtils
import com.juren233.hyperlyricsenhanced.online.utils.QrcParser
import com.juren233.hyperlyricsenhanced.utils.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random

class QmSource(
    private val api: QmApi
) : SearchSource {
    override val sourceType: Source = Source.QM

    private val comm = QmClientIdentity.comm
    private val catalog = QmSongCatalog(api)

    /**
     * 搜索服务被限流（2001）时仍能取到候选：用按歌名匹配接口直接找在线条目，按
     * [SongMetadataQueryPolicy.metadataQueries] 的组合（原歌名/去括号歌名 × 整串歌手/单个歌手）逐个尝试，
     * 取第一组有结果的。返回的只是候选，接纳与否仍由 OnlineLyricTargeter 的打分和严格版本匹配
     * 决定。
     */
    override suspend fun searchByMetadata(
        title: String,
        artist: String,
        durationMs: Long,
        separator: String,
    ): List<SongSearchResult> = withContext(Dispatchers.IO) {
        if (artist.isBlank()) return@withContext emptyList()
        for ((candidateTitle, candidateArtist) in SongMetadataQueryPolicy.metadataQueries(title, artist)) {
            val match = catalog.matchByMetadata(candidateTitle, candidateArtist, durationMs) ?: continue
            val ids = match.orderedIds
            val details = catalog.details(ids)
            val results = ids.mapNotNull { id -> details[id]?.toSearchResult(separator) }
            if (results.isNotEmpty()) return@withContext results
        }
        emptyList()
    }

    private fun QmTrackDetail.toSearchResult(separator: String) = SongSearchResult(
        id = id.toString(),
        title = name,
        artist = singers.joinToString(separator),
        album = album,
        duration = durationMs,
        source = Source.QM,
        date = timePublic,
        trackerNumber = albumIndex.toString(),
        picUrl = if (album.isNotEmpty() && albumMid.isNotEmpty()) {
            "https://y.gtimg.cn/music/photo_new/T002R800x800M000$albumMid.jpg"
        } else {
            ""
        },
    )

    override suspend fun search(
        keyword: String,
        page: Int,
        separator: String,
        pageSize: Int,
        durationMs: Long,
    ): List<SongSearchResult> = withContext(Dispatchers.IO) {
        val param = buildJsonObject {
            put("search_id", Random.nextLong(10000000000000000L, 90000000000000000L).toString())
            put("remoteplace", "search.android.keyboard")
            put("query", keyword)
            put("search_type", 0)
            put("num_per_page", pageSize)
            put("page_num", page)
            put("highlight", 0)
            put("nqc_flag", 0)
            put("page_id", 1)
            put("grp", 1)
        }

        val reqBody = QmRequestBody(
            comm = comm,
            req0 = QmRequestModule(
                method = "DoSearchForQQMusicLite",
                module = "music.search.SearchCgiService",
                param = param
            )
        )

        try {
            val response = api.searchSong(reqBody).execute()
            if (!response.isSuccessful) {
                LogManager.w("QmSource", "搜索 HTTP 失败: code=${response.code()}")
                return@withContext emptyList()
            }
            val resp = response.body() ?: return@withContext emptyList()
            val songs = resp.req0.data?.body?.songs ?: emptyList()

            songs.map { item ->
                val singerList = item.singer.map { it.name }
                val picUrl = if (item.album.name.isNotEmpty()) {
                    "https://y.gtimg.cn/music/photo_new/T002R800x800M000${item.album.mid}.jpg"
                } else {
                    ""
                }
                SongSearchResult(
                    id = item.id.toString(),
                    title = item.title,
                    artist = singerList.joinToString(separator),
                    album = item.album.name,
                    duration = item.interval * 1000L,
                    source = Source.QM,
                    date = item.timePublic ?: "",
                    trackerNumber = item.trackerNumber.toString(),
                    picUrl = picUrl
                )
            }
        } catch (e: Exception) {
            LogManager.w("QmSource", "搜索异常", e)
            emptyList()
        }
    }

    override suspend fun getLyrics(song: SongSearchResult): LyricsResult? = withContext(Dispatchers.IO) {
        if (song.id == "0") return@withContext null

        val param = buildJsonObject {
            put("songID", song.id.toLong())
            put("songName", Base64.encodeToString(song.title.toByteArray(), Base64.NO_WRAP))
            put("albumName", Base64.encodeToString(song.album.toByteArray(), Base64.NO_WRAP))
            put("singerName", Base64.encodeToString(song.artist.toByteArray(), Base64.NO_WRAP))
            put("crypt", 1)
            put("qrc", 1)
            put("trans", 1)
            put("roma", 1)
            put("cv", 2111)
            put("ct", 19)
            put("lrc_t", 0)
            put("qrc_t", 0)
            put("roma_t", 0)
            put("trans_t", 0)
            put("type", 0)
            put("interval", song.duration / 1000)
        }

        val reqBody = QmRequestBody(
            comm = comm,
            req0 = QmRequestModule(
                method = "GetPlayLyricInfo",
                module = "music.musichallSong.PlayLyricInfo",
                param = param
            )
        )

        try {
            val response = api.getLyrics(reqBody).execute()
            if (!response.isSuccessful) {
                LogManager.w("QmSource", "歌词 HTTP 失败: code=${response.code()}")
                return@withContext null
            }
            val resp = response.body() ?: return@withContext null
            val data = resp.req0.data ?: return@withContext null

            val lyricsData = withContext(Dispatchers.Default) {
                val qrcText = if (data.lyric.isNotEmpty()) QmCryptoUtils.decryptQrc(data.lyric) else ""
                val transText = if (data.trans.isNotEmpty()) QmCryptoUtils.decryptQrc(data.trans) else null
                val romaText = if (data.roma.isNotEmpty()) QmCryptoUtils.decryptQrc(data.roma) else null

                LyricsData(
                    original = qrcText.ifEmpty { null },
                    translated = transText,
                    type = if (qrcText.isNotEmpty()) "qrc" else "lrc",
                    romanization = romaText
                )
            }

            QrcParser.parse(lyricsData)

        } catch (e: Exception) {
            LogManager.w("QmSource", "歌词获取异常", e)
            null
        }
    }
}
