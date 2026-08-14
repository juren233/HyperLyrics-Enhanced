/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online.source.kuwo

import com.juren233.hyperlyricsenhanced.online.model.LyricsLine
import com.juren233.hyperlyricsenhanced.online.model.LyricsResult
import com.juren233.hyperlyricsenhanced.online.model.LyricsWord
import com.juren233.hyperlyricsenhanced.online.model.SearchSource
import com.juren233.hyperlyricsenhanced.online.model.SongSearchResult
import com.juren233.hyperlyricsenhanced.online.model.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.zip.InflaterInputStream

class KuwoSource : SearchSource {
    override val sourceType: Source = Source.KUWO

    override suspend fun search(
        keyword: String,
        page: Int,
        separator: String,
        pageSize: Int,
        durationMs: Long,
    ): List<SongSearchResult> = withContext(Dispatchers.IO) {
        KuwoNetwork.search(keyword, page, pageSize).map { candidate ->
            SongSearchResult(
                id = candidate.rid.toString(),
                title = candidate.title,
                artist = candidate.artist,
                album = candidate.album,
                duration = candidate.durationSeconds * 1_000L,
                source = Source.KUWO,
            )
        }
    }

    override suspend fun getLyrics(song: SongSearchResult): LyricsResult? =
        withContext(Dispatchers.IO) {
            val rid = song.id.toLongOrNull()?.takeIf { it > 0L } ?: return@withContext null
            KuwoLyricsParser.toLyricsResult(KuwoNetwork.fetchLyrics(rid))
        }
}

private data class KuwoCandidate(
    val rid: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Long,
)

private object KuwoNetwork {
    private const val SEARCH_URL =
        "https://www.kuwo.cn/openapi/v1/www/search/searchMusicBykeyWord"
    private const val OPEN_LYRIC_URL = "https://www.kuwo.cn/openapi/v1/www/lyric/getlyric"
    private const val LRCX_URL = "https://newlyric.kuwo.cn/newlyric.lrc"

    fun search(keyword: String, page: Int, pageSize: Int): List<KuwoCandidate> {
        val encoded = URLEncoder.encode(keyword, StandardCharsets.UTF_8.name())
        val json = JSONObject(
            requestText(
                "$SEARCH_URL?key=$encoded&pn=${page.coerceAtLeast(1)}&" +
                    "rn=${pageSize.coerceIn(1, 30)}&httpsStatus=1"
            )
        )
        val list = json.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        return buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val rid = item.optLong("rid").takeIf { it > 0L } ?: continue
                add(
                    KuwoCandidate(
                        rid = rid,
                        title = item.optString("name"),
                        artist = item.optString("artist"),
                        album = item.optString("album"),
                        durationSeconds = item.optLong("duration").coerceAtLeast(0L),
                    )
                )
            }
        }
    }

    fun fetchLyrics(rid: Long): String {
        runCatching { fetchLrcx(rid) }
            .getOrNull()
            ?.takeIf { KuwoLyricsParser.hasLyrics(it) }
            ?.let { return it }
        val json = JSONObject(requestText("$OPEN_LYRIC_URL?musicId=$rid&httpsStatus=1"))
        val list = json.optJSONObject("data")?.optJSONArray("lrclist") ?: return ""
        return buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val timeMs = item.optString("time").toDoubleOrNull()
                    ?.times(1_000.0)?.toLong() ?: continue
                add("[${formatTimestamp(timeMs)}]${item.optString("lineLyric")}")
            }
        }.joinToString("\n")
    }

    private fun fetchLrcx(rid: Long): String {
        val query = KuwoResponseDecoder.buildRequestQuery(rid)
        return KuwoResponseDecoder.decode(
            requestBytes("$LRCX_URL?$query", "okhttp/3.10.0")
        ).orEmpty()
    }

    private fun requestText(url: String): String =
        requestBytes(url).toString(StandardCharsets.UTF_8)

    private fun requestBytes(url: String, userAgent: String = "Mozilla/5.0"): ByteArray {
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Referer", "https://www.kuwo.cn/")
            setRequestProperty("Accept-Encoding", "identity")
        }
        return try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Kuwo HTTP ${connection.responseCode}"
            }
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun formatTimestamp(timeMs: Long): String = String.format(
        Locale.ROOT,
        "%02d:%02d.%03d",
        timeMs / 60_000L,
        timeMs / 1_000L % 60L,
        timeMs % 1_000L,
    )
}

internal object KuwoResponseDecoder {
    private val key = "yeelion".toByteArray(StandardCharsets.US_ASCII)
    private val separator = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)

    fun buildRequestQuery(rid: Long): String {
        val request = "user=12345,web,web,web&requester=localhost&req=1&rid=MUSIC_$rid&lrcx=1"
        return Base64.getEncoder().encodeToString(xor(request.toByteArray()))
    }

    fun decode(response: ByteArray): String? {
        val prefix = "tp=content".toByteArray(StandardCharsets.US_ASCII)
        if (response.size < prefix.size || prefix.indices.any { response[it] != prefix[it] }) {
            return null
        }
        val offset = response.indexOf(separator).takeIf { it >= 0 }?.plus(separator.size)
            ?: return null
        val inflated = runCatching {
            InflaterInputStream(
                ByteArrayInputStream(response, offset, response.size - offset)
            ).use { it.readBytes() }
        }.getOrNull() ?: return null
        val encrypted = runCatching {
            Base64.getMimeDecoder().decode(inflated.toString(StandardCharsets.US_ASCII).trim())
        }.getOrNull() ?: return null
        return xor(encrypted).toString(Charset.forName("GB18030")).takeIf(String::isNotBlank)
    }

    private fun xor(value: ByteArray): ByteArray = ByteArray(value.size) { index ->
        (value[index].toInt() xor key[index % key.size].toInt()).toByte()
    }

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        for (start in 0..size - needle.size) {
            if (needle.indices.all { offset -> this[start + offset] == needle[offset] }) return start
        }
        return -1
    }
}

internal object KuwoLyricsParser {
    private val timestamp = Regex("^\\[(\\d{1,3}):([0-5]\\d)(?:[.:]([0-9]{1,3}))?]")
    private val wordMarker = Regex("<(-?\\d+),(-?\\d+)>([^<]*)")
    private val timingScale = Regex("\\[kuwo:([0-7]+)]", RegexOption.IGNORE_CASE)

    fun hasLyrics(raw: String): Boolean = timestamp.containsMatchIn(raw)

    fun toLyricsResult(raw: String): LyricsResult? {
        val scale = parseScale(raw)
        val parsed = raw.lineSequence().mapNotNull { parseRawLine(it, scale) }.toList()
        if (parsed.isEmpty()) return null
        val lines = mutableListOf<MutableLine>()
        parsed.forEachIndexed { index, line ->
            when (line.kind) {
                LineKind.AUXILIARY -> lines.lastOrNull()?.let { previous ->
                    if (previous.translation == null) previous.translation = line.text
                    else if (previous.roma == null) previous.roma = line.text
                }
                LineKind.PLAIN -> {
                    val next = parsed.getOrNull(index + 1)
                    if (lines.isNotEmpty() && next?.kind == LineKind.PLAIN && next.begin == line.begin) {
                        if (lines.last().translation == null) lines.last().translation = line.text
                    } else if (line.text.isNotBlank()) {
                        lines += MutableLine(line.begin, line.text, line.words)
                    }
                }
                LineKind.TIMED -> if (line.text.isNotBlank()) {
                    lines += MutableLine(line.begin, line.text, line.words)
                }
            }
        }
        val originals = lines.mapIndexed { index, line ->
            val end = maxOf(
                line.begin + 1L,
                line.words.maxOfOrNull(LyricsWord::end)
                    ?: lines.getOrNull(index + 1)?.begin
                    ?: line.begin + 5_000L,
            )
            LyricsLine(
                start = line.begin,
                end = end,
                words = line.words.ifEmpty { listOf(LyricsWord(line.begin, end, line.text)) },
            )
        }
        fun secondary(selector: (MutableLine) -> String?): List<LyricsLine>? {
            val result = lines.mapIndexedNotNull { index, line ->
                selector(line)?.takeIf(String::isNotBlank)?.let { text ->
                    LyricsLine(
                        start = line.begin,
                        end = originals[index].end,
                        words = listOf(LyricsWord(line.begin, originals[index].end, text)),
                    )
                }
            }
            return result.takeIf(List<LyricsLine>::isNotEmpty)
        }
        return LyricsResult(
            tags = emptyMap(),
            original = originals,
            translated = secondary(MutableLine::translation),
            romanization = secondary(MutableLine::roma),
        )
    }

    private fun parseRawLine(raw: String, scale: Scale): RawLine? {
        val match = timestamp.find(raw) ?: return null
        val begin = match.toMillis()
        val payload = raw.substring(match.range.last + 1)
        val markers = wordMarker.findAll(payload).toList()
        if (markers.isEmpty()) return RawLine(begin, payload.trim(), emptyList(), LineKind.PLAIN)
        val text = markers.joinToString("") { it.groupValues[3] }.trim()
        if (markers.none { it.groupValues[1] != "0" || it.groupValues[2] != "0" }) {
            return RawLine(begin, text, emptyList(), LineKind.AUXILIARY)
        }
        val words = markers.mapNotNull { marker ->
            val first = marker.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val second = marker.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val content = marker.groupValues[3]
            val relative = Math.floorDiv(first + second, scale.beginDivisor)
            val duration = Math.floorDiv(first - second, scale.durationDivisor)
            val wordBegin = begin + relative
            if (content.isBlank() || duration <= 0 || wordBegin < begin) return@mapNotNull null
            LyricsWord(wordBegin, wordBegin + duration, content)
        }
        return RawLine(begin, text, words, LineKind.TIMED)
    }

    private fun parseScale(raw: String): Scale {
        val encoded = raw.lineSequence().firstNotNullOfOrNull { line ->
            timingScale.find(line)?.groupValues?.getOrNull(1)
        }?.toIntOrNull(8) ?: return Scale.DEFAULT
        val begin = encoded / 10
        val duration = encoded % 10
        return if (begin > 0 && duration > 0) Scale(begin * 2L, duration * 2L) else Scale.DEFAULT
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

    private data class RawLine(
        val begin: Long,
        val text: String,
        val words: List<LyricsWord>,
        val kind: LineKind,
    )

    private data class MutableLine(
        val begin: Long,
        val text: String,
        val words: List<LyricsWord>,
        var translation: String? = null,
        var roma: String? = null,
    )

    private enum class LineKind { TIMED, AUXILIARY, PLAIN }
    private data class Scale(val beginDivisor: Long, val durationDivisor: Long) {
        companion object { val DEFAULT = Scale(2L, 2L) }
    }
}
