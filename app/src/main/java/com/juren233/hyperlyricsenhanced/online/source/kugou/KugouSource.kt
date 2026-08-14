/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online.source.kugou

import com.juren233.hyperlyricsenhanced.online.model.LyricsLine
import com.juren233.hyperlyricsenhanced.online.model.LyricsResult
import com.juren233.hyperlyricsenhanced.online.model.LyricsWord
import com.juren233.hyperlyricsenhanced.online.model.SearchSource
import com.juren233.hyperlyricsenhanced.online.model.SongSearchResult
import com.juren233.hyperlyricsenhanced.online.model.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.zip.InflaterInputStream

class KugouSource : SearchSource {
    override val sourceType: Source = Source.KUGOU

    override suspend fun search(
        keyword: String,
        page: Int,
        separator: String,
        pageSize: Int,
        durationMs: Long,
    ): List<SongSearchResult> = withContext(Dispatchers.IO) {
        KugouNetwork.search(keyword, durationMs, pageSize).map { candidate ->
            SongSearchResult(
                id = candidate.downloadId,
                title = candidate.title,
                artist = candidate.artist,
                album = "",
                duration = candidate.durationMs,
                source = Source.KUGOU,
                extras = mapOf(
                    "accessKey" to candidate.accessKey,
                    "contentType" to candidate.contentType.toString(),
                ),
            )
        }
    }

    override suspend fun getLyrics(song: SongSearchResult): LyricsResult? =
        withContext(Dispatchers.IO) {
            val accessKey = song.extras["accessKey"] ?: return@withContext null
            val contentType = song.extras["contentType"]?.toIntOrNull() ?: 0
            val raw = KugouNetwork.download(song.id, accessKey, contentType)
            KugouLyricsParser.parse(raw, song.duration)
        }
}

private data class KugouCandidate(
    val downloadId: String,
    val accessKey: String,
    val contentType: Int,
    val title: String,
    val artist: String,
    val durationMs: Long,
)

private object KugouNetwork {
    private const val SEARCH_URL = "https://lyrics.kugou.com/v2/search"
    private const val DOWNLOAD_URL = "https://lyrics.kugou.com/v2/download"
    private const val APP_ID = "1005"
    private const val CLIENT_VERSION = "20759"
    private val mid = KugouApiProtocol.clientMid(
        "HyperLyrics-Enhanced-online-translation"
    )

    fun search(keyword: String, durationMs: Long, pageSize: Int): List<KugouCandidate> {
        val parameters = mapOf(
            "album_audio_id" to "0",
            "appid" to APP_ID,
            "clientver" to CLIENT_VERSION,
            "duration" to (durationMs.coerceAtLeast(0L) / 1_000L * 1_000L).toString(),
            "hash" to "",
            "keyword" to keyword.take(200),
            "lrctxt" to "1",
            "man" to "yes",
            "query_copyright" to "1",
        )
        val json = requestJson("$SEARCH_URL?${KugouApiProtocol.signedQuery(parameters)}")
        val array = json.optJSONObject("data")?.optJSONArray("candidates") ?: return emptyList()
        return buildList {
            for (index in 0 until minOf(array.length(), pageSize.coerceIn(1, 30))) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("download_id").ifBlank { item.optString("id") }
                val accessKey = item.optString("accesskey")
                if (id.isBlank() || accessKey.isBlank()) continue
                add(
                    KugouCandidate(
                        downloadId = id,
                        accessKey = accessKey,
                        contentType = item.optInt("contenttype", 0),
                        title = item.optString("song"),
                        artist = item.optString("singer"),
                        durationMs = item.optLong("duration").coerceAtLeast(0L),
                    )
                )
            }
        }
    }

    fun download(downloadId: String, accessKey: String, contentType: Int): ByteArray {
        val parameters = mapOf(
            "accesskey" to accessKey,
            "appid" to APP_ID,
            "clientver" to CLIENT_VERSION,
            "contenttype" to contentType.toString(),
            "download_id" to downloadId,
        )
        val json = requestJson("$DOWNLOAD_URL?${KugouApiProtocol.signedQuery(parameters)}")
        val content = json.optJSONObject("data")?.optString("content").orEmpty()
        return Base64.getDecoder().decode(content)
    }

    private fun requestJson(url: String): JSONObject {
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "Android-KuGou/$CLIENT_VERSION")
            setRequestProperty("clienttime", System.currentTimeMillis().toString())
            setRequestProperty("mid", mid)
            setRequestProperty("dfid", "-")
            setRequestProperty("uuid", mid)
            setRequestProperty("userid", "0")
            setRequestProperty("token", "")
        }
        return try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Kugou HTTP ${connection.responseCode}"
            }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

}

internal object KugouApiProtocol {
    private const val SIGNING_SECRET = "OIlwieks28dk2k092lksi2UIkp"

    fun signedQuery(parameters: Map<String, String>): String {
        val signed = parameters.toSortedMap().toMutableMap()
        signed["signature"] = signature(parameters)
        return signed.toSortedMap().entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
    }

    fun signature(parameters: Map<String, String>): String {
        val joined = parameters.toSortedMap().entries.joinToString("") { (key, value) ->
            "$key=$value"
        }
        return md5Hex("$SIGNING_SECRET$joined$SIGNING_SECRET")
    }

    fun clientMid(seed: String): String = md5Hex(seed)

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun md5Hex(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
}

internal object KugouLyricsParser {
    private val krcKey = byteArrayOf(
        64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45,
        206.toByte(), 210.toByte(), 110, 105,
    )
    private val krcLine = Regex("^\\[(\\d+)\\s*,\\s*(\\d+)](.*)$")
    private val krcWord = Regex("<(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)>")
    private val lrcTimestamp = Regex("\\[(\\d{1,3})[:.]([0-5]\\d)(?:[:.]([0-9]{1,3}))?]")
    private val languageTag = Regex("^\\[language:(.*)]$", RegexOption.IGNORE_CASE)

    fun parse(input: ByteArray, durationMs: Long): LyricsResult? {
        val content = if (input.startsWithKrcHeader()) decryptKrc(input) else input.toString(Charsets.UTF_8)
        val originals = if (input.startsWithKrcHeader()) {
            parseKrc(content)
        } else {
            parseLrc(content, durationMs)
        }
        if (originals.isEmpty()) return null
        val translations = parseTranslations(content, originals)
        return LyricsResult(
            tags = emptyMap(),
            original = originals,
            translated = translations,
            romanization = null,
        )
    }

    private fun decryptKrc(input: ByteArray): String = runCatching {
        val decoded = ByteArray(input.size - 4) { index ->
            (input[index + 4].toInt() xor krcKey[index % krcKey.size].toInt()).toByte()
        }
        InflaterInputStream(decoded.inputStream()).bufferedReader().use { it.readText() }
    }.getOrDefault("")

    private fun parseKrc(content: String): List<LyricsLine> = content.lineSequence()
        .mapNotNull { raw ->
            val match = krcLine.matchEntire(raw.trim()) ?: return@mapNotNull null
            val begin = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val duration = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val body = match.groupValues[3]
            val tags = krcWord.findAll(body).toList()
            val words = if (tags.isEmpty()) {
                listOf(LyricsWord(begin, begin + duration, body.trim()))
            } else {
                tags.mapIndexed { index, tag ->
                    val textStart = tag.range.last + 1
                    val textEnd = tags.getOrNull(index + 1)?.range?.first ?: body.length
                    val offset = tag.groupValues[1].toLongOrNull() ?: 0L
                    val wordDuration = tag.groupValues[2].toLongOrNull() ?: 0L
                    LyricsWord(
                        start = begin + offset,
                        end = begin + offset + wordDuration,
                        text = body.substring(textStart, textEnd),
                    )
                }
            }
            LyricsLine(begin, begin + duration, words)
        }
        .sortedBy(LyricsLine::start)
        .toList()

    private fun parseLrc(content: String, durationMs: Long): List<LyricsLine> {
        val rows = content.lineSequence().flatMap { raw ->
            val matches = lrcTimestamp.findAll(raw).toList()
            if (matches.isEmpty() || matches.first().range.first != 0) emptySequence()
            else {
                val text = raw.substring(matches.last().range.last + 1).trim()
                matches.asSequence().map { match -> match.toMillis() to text }
            }
        }.toList().sortedBy { it.first }
        return rows.mapIndexed { index, (begin, text) ->
            val end = rows.getOrNull(index + 1)?.first
                ?: durationMs.takeIf { it > begin }
                ?: begin + 5_000L
            LyricsLine(begin, end, listOf(LyricsWord(begin, end, text)))
        }
    }

    private fun parseTranslations(
        content: String,
        originals: List<LyricsLine>,
    ): List<LyricsLine>? {
        val encoded = content.lineSequence().map(String::trim).firstNotNullOfOrNull { line ->
            languageTag.matchEntire(line)?.groupValues?.getOrNull(1)
        } ?: return null
        val root = runCatching {
            JSONObject(Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8))
        }.getOrNull() ?: return null
        val sections = root.optJSONArray("content") ?: return null
        for (index in 0 until sections.length()) {
            val section = sections.optJSONObject(index) ?: continue
            if (section.optInt("type", -1) != 1) continue
            val contentLines = section.optJSONArray("lyricContent") ?: continue
            if (contentLines.length() != originals.size) return null
            return List(originals.size) { lineIndex ->
                val chunks = contentLines.optJSONArray(lineIndex)
                val text = buildString {
                    for (chunkIndex in 0 until (chunks?.length() ?: 0)) {
                        append(chunks?.optString(chunkIndex).orEmpty())
                    }
                }.trim()
                val original = originals[lineIndex]
                LyricsLine(
                    start = original.start,
                    end = original.end,
                    words = listOf(LyricsWord(original.start, original.end, text)),
                )
            }
        }
        return null
    }

    private fun MatchResult.toMillis(): Long {
        val fraction = groupValues.getOrNull(3).orEmpty()
        val millis = when (fraction.length) {
            1 -> fraction.toLong() * 100L
            2 -> fraction.toLong() * 10L
            3 -> fraction.toLong()
            else -> 0L
        }
        return groupValues[1].toLong() * 60_000L + groupValues[2].toLong() * 1_000L + millis
    }

    private fun ByteArray.startsWithKrcHeader(): Boolean = size >= 4 &&
        this[0] == 'k'.code.toByte() &&
        this[1] == 'r'.code.toByte() &&
        this[2] == 'c'.code.toByte() &&
        this[3] == '1'.code.toByte()
}
