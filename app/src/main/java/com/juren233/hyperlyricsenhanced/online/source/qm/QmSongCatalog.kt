/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online.source.qm

import android.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** QQ 音乐统一网关的客户端标识（轻量版），QQ 源的全部请求共用这一份。 */
internal object QmClientIdentity {
    val comm: Map<String, String> = mapOf(
        "ct" to "11",
        "cv" to "1003006",
        "v" to "1003006",
        "os_ver" to "15",
        "phonetype" to "24122RKC7C",
        "tmeAppID" to "qqmusiclight",
        "nettype" to "NETWORK_WIFI",
    )
}

/** 在线库里一首歌的身份信息。 */
internal data class QmTrackDetail(
    val id: Long,
    val name: String,
    val singers: List<String>,
    val album: String,
    val albumMid: String,
    val durationMs: Long,
    val timePublic: String,
    val albumIndex: Int,
)

/** 按歌名匹配的结果：[songId] 是服务端选中的条目（时长对不上时可能为空），[candidates] 是同曲候选。 */
internal data class QmMetadataMatch(val songId: Long?, val candidates: List<Long>) {
    val orderedIds: List<Long> get() = (listOfNotNull(songId) + candidates).distinct()
}

/**
 * 不经过搜索服务的两个 QQ 音乐接口（2026-11-09 实测连发数十次均无限流，同期搜索服务
 * `music.search.SearchCgiService` 频繁返回 2001）：
 * - 按歌名匹配 `music.musichallSong.PlayLyricInfo / GetPlayLyricInfo`：songID 填 0，歌名/歌手
 *   Base64 提交。这是 QQ 音乐 App 给未关联在线歌的本地歌取词时的请求形态（20.9.5.8 反编译）。
 *   歌手对不上返回 24001；歌手为空会模糊匹配到别的歌，所以调用方必须带歌手。
 * - 歌曲详情 `music.pf_song_detail_svr / get_song_detail_yqq`：按数字 ID 取歌名/歌手/时长/专辑，
 *   多个 ID 合并在一次网关请求里。
 * 这里只取身份信息，是否采纳由调用方按项目现有的匹配规则决定。
 */
internal class QmSongCatalog(private val api: QmApi) {

    /** 无匹配、网关错误或请求异常时返回 null；[artist] 为空时不发请求。 */
    fun matchByMetadata(title: String, artist: String, durationMs: Long): QmMetadataMatch? {
        if (title.isBlank() || artist.isBlank()) return null
        val body = buildJsonObject {
            put("comm", JsonObject(QmClientIdentity.comm.mapValues { JsonPrimitive(it.value) }))
            put("req_0", buildJsonObject {
                put("module", "music.musichallSong.PlayLyricInfo")
                put("method", "GetPlayLyricInfo")
                put("param", buildJsonObject {
                    put("songID", 0)
                    put("type", 0)
                    put("songName", base64(title))
                    put("singerName", base64(artist))
                    put("albumName", "")
                    put("interval", durationMs.coerceAtLeast(0L) / 1_000L)
                    put("crypt", 0)
                    put("qrc", 0)
                    put("trans", 0)
                    put("roma", 0)
                })
            })
        }
        val response = api.request(body).execute()
        if (!response.isSuccessful) return null
        return parseMetadataMatch(response.body() ?: return null)
    }

    /** 查不到的 ID 不出现在结果里；网关错误时返回空表。 */
    fun details(ids: List<Long>): Map<Long, QmTrackDetail> {
        val wanted = ids.filter { it > 0L }.distinct()
        if (wanted.isEmpty()) return emptyMap()
        val body = buildJsonObject {
            put("comm", JsonObject(QmClientIdentity.comm.mapValues { JsonPrimitive(it.value) }))
            wanted.forEachIndexed { index, id ->
                put(detailKey(index), buildJsonObject {
                    put("module", "music.pf_song_detail_svr")
                    put("method", "get_song_detail_yqq")
                    put("param", buildJsonObject { put("song_id", id) })
                })
            }
        }
        val response = api.request(body).execute()
        if (!response.isSuccessful) return emptyMap()
        return parseDetails(response.body() ?: return emptyMap(), wanted.size)
    }

    private fun base64(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    companion object {
        fun detailKey(index: Int) = "detail_$index"

        fun parseMetadataMatch(root: JsonObject): QmMetadataMatch? {
            if (root.int("code") != 0) return null
            val module = root["req_0"] as? JsonObject ?: return null
            // 24001 是「没有匹配的歌词条目」，与其他非 0 码一样视为无结果
            if (module.int("code") != 0) return null
            val data = module["data"] as? JsonObject ?: return null
            val songId = data.long("songID")?.takeIf { it > 0L }
            val candidates = (data["vecSongID"] as? JsonArray).orEmpty()
                .mapNotNull { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }
                .filter { it > 0L }
                .distinct()
            if (songId == null && candidates.isEmpty()) return null
            return QmMetadataMatch(songId, candidates)
        }

        fun parseDetails(root: JsonObject, count: Int): Map<Long, QmTrackDetail> {
            if (root.int("code") != 0) return emptyMap()
            return (0 until count).mapNotNull { index ->
                val module = root[detailKey(index)] as? JsonObject ?: return@mapNotNull null
                if (module.int("code") != 0) return@mapNotNull null
                val track = (module["data"] as? JsonObject)?.get("track_info") as? JsonObject
                    ?: return@mapNotNull null
                val id = track.long("id")?.takeIf { it > 0L } ?: return@mapNotNull null
                val name = track.string("name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val album = track["album"] as? JsonObject
                QmTrackDetail(
                    id = id,
                    name = name,
                    singers = (track["singer"] as? JsonArray).orEmpty()
                        .mapNotNull { (it as? JsonObject)?.string("name")?.takeIf(String::isNotBlank) },
                    album = album?.string("name").orEmpty(),
                    albumMid = album?.string("mid").orEmpty(),
                    durationMs = (track.int("interval") ?: 0) * 1_000L,
                    timePublic = track.string("time_public").orEmpty(),
                    albumIndex = track.int("index_album") ?: 0,
                ).let { id to it }
            }.toMap()
        }

        private fun JsonObject.int(key: String): Int? =
            runCatching { (this[key] as? JsonPrimitive)?.intOrNull }.getOrNull()

        private fun JsonObject.long(key: String): Long? =
            runCatching { (this[key] as? JsonPrimitive)?.longOrNull }.getOrNull()

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
