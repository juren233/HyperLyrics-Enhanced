package com.juren233.hyperlyricsenhanced.online.source.qm


import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.juren233.hyperlyricsenhanced.common.net.HttpResult
import com.juren233.hyperlyricsenhanced.common.net.post
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody


@Serializable
data class QmRequestBody(
    val comm: Map<String, String>,
    @SerialName("req_0") val req0: QmRequestModule
)

@Serializable
data class QmRequestModule(
    val method: String,
    val module: String,
    val param: Map<String, JsonElement>
)


@Serializable
data class QmBaseWrapper<T>(
    @SerialName("req_0") val req0: QmResponseModule<T>
)

@Serializable
data class QmResponseModule<T>(
    val code: Int = 0,
    val data: T? = null
)

@Serializable
data class QmSearchData(
    val body: QmSearchBody? = null
)

@Serializable
data class QmSearchBody(
    @SerialName("item_song") val songs: List<QmSongItem> = emptyList()
)

@Serializable
data class QmSongItem(
    val id: Long,
    val mid: String,
    val title: String,
    val singer: List<QmSinger> = emptyList(),
    val album: QmAlbum,
    val interval: Int,
    @SerialName("index_album") val trackerNumber: Int = 0,
    @SerialName("genre") val genre: Int = 0,
    @SerialName("time_public") val timePublic: String? = null
)

@Serializable
data class QmSinger(val name: String)

@Serializable
data class QmAlbum(val name: String, val mid: String = "")

@Serializable
data class QmLyricsData(
    val lyric: String = "",
    val trans: String = "",
    val roma: String = ""
)

/** QQ Music's `musicu.fcg` gateway; JSON in and out with the shared [json] configuration. */
class QmApi internal constructor(
    private val client: OkHttpClient,
    private val json: Json,
) {
    internal fun searchSong(body: QmRequestBody): HttpResult<QmBaseWrapper<QmSearchData>> =
        post(body, QmRequestBody.serializer(), QmBaseWrapper.serializer(QmSearchData.serializer()))

    internal fun getLyrics(body: QmRequestBody): HttpResult<QmBaseWrapper<QmLyricsData>> =
        post(body, QmRequestBody.serializer(), QmBaseWrapper.serializer(QmLyricsData.serializer()))

    /** 多模块合并请求（模块键由调用方决定），用于 [QmSongCatalog]。 */
    internal fun request(body: JsonObject): HttpResult<JsonObject> =
        post(body, JsonObject.serializer(), JsonObject.serializer())

    // Same body and Content-Type ("application/json; charset=utf-8") the Retrofit converter sent.
    private fun <B, T> post(
        body: B,
        writer: SerializationStrategy<B>,
        reader: DeserializationStrategy<T>,
    ): HttpResult<T> = client.post(URL, json.encodeToString(writer, body).toRequestBody(JSON)) {
        json.decodeFromString(reader, it.string())
    }

    private companion object {
        const val URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        val JSON = "application/json".toMediaType()
    }
}
