package com.juren233.hyperlyricsenhanced.online.source.ne

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.juren233.hyperlyricsenhanced.common.net.HttpResult
import com.juren233.hyperlyricsenhanced.common.net.post
import okhttp3.OkHttpClient
import okhttp3.RequestBody

@Serializable
data class NeSearchResponse(
    @SerialName("code") val code: Int = 0,
    @SerialName("data") val data: NeSearchData? = null
)

@Serializable
data class NeSearchData(
    @SerialName("resources") val resources: List<NeSearchResource>? = null,
    @SerialName("totalCount") val totalCount: Int = 0
)

@Serializable
data class NeSearchResource(
    @SerialName("baseInfo") val baseInfo: NeBaseInfo
)

@Serializable
data class NeBaseInfo(
    @SerialName("simpleSongData") val simpleSongData: NeSongData
)

@Serializable
data class NeSongData(
    @SerialName("id") val id: Long,
    @SerialName("name") val name: String,
    @SerialName("alia") val alia: List<String>? = null,
    @SerialName("ar") val artists: List<NeArtist>,
    @SerialName("al") val album: NeAlbum,
    @SerialName("dt") val duration: Long,
    @SerialName("publishTime") val publishTime: Long? = null,
    @SerialName("no") val trackerNumber: String = ""
)

@Serializable
data class NeArtist(val name: String)

@Serializable
data class NeAlbum(val name: String, val picUrl: String = "")

@Serializable
data class NeLyricResponse(
    @SerialName("code") val code: Int = 0,
    @SerialName("yrc") val yrc: NeLyricContent? = null,
    @SerialName("lrc") val lrc: NeLyricContent? = null,
    @SerialName("tlyric") val tlyric: NeLyricContent? = null,
    @SerialName("romalrc") val romalrc: NeLyricContent? = null,
    @SerialName("lyricUser") val lyricUser: NeUser? = null,
    @SerialName("transUser") val transUser: NeUser? = null
)

@Serializable
data class NeLyricContent(
    @SerialName("lyric") val lyric: String? = null
)

@Serializable
data class NeUser(
    @SerialName("nickname") val nickname: String? = null
)

/** Netease's encrypted endpoints; the raw response bytes for a 2xx answer, else none. */
class NeApi internal constructor(private val client: OkHttpClient) {
    internal fun request(
        url: String,
        headers: Map<String, String>,
        body: RequestBody,
    ): HttpResult<ByteArray> = client.post(url, body, headers) { it.bytes() }
}
