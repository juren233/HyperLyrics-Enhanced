package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.common.lyric.OnlineTranslationContentPolicy
import com.juren233.hyperlyricsenhanced.lyric.model.Song

internal class AppleNativeOnlineTranslationStore {
    private data class TimingKey(
        val begin: Long,
        val end: Long,
    )

    private data class LineKey(
        val timing: TimingKey,
        val text: String,
    )

    private data class Content(
        val translation: String?,
        val pronunciation: String?,
    )

    private data class Entry(
        val text: String,
        val content: Content,
    )

    private data class Overlay(
        val songId: String,
        val exactContent: Map<LineKey, Content>,
        val translationsByTiming: Map<TimingKey, List<Entry>>,
        val hasTranslation: Boolean,
        val hasPronunciation: Boolean,
        val translationSource: String?,
        val pronunciationSource: String?,
    )

    @Volatile
    private var overlay: Overlay? = null

    @Volatile
    private var contentRevision = 0L

    @Synchronized
    fun update(song: Song): Boolean {
        val songId = song.id?.takeIf(String::isNotBlank) ?: return false
        val entries = song.lyrics.orEmpty().mapNotNull { line ->
            val content = Content(
                translation = sanitizeContent(line.translation),
                pronunciation = RomanizationPolicy.sanitize(line.text, line.roma),
            )
            if (content.translation == null && content.pronunciation == null) {
                return@mapNotNull null
            }
            val timing = TimingKey(line.begin, line.end)
            LineKey(timing, normalizeText(line.text)) to content
        }
        if (entries.isEmpty()) return false

        val updatedOverlay = Overlay(
            songId = songId,
            exactContent = entries.toMap(),
            translationsByTiming = entries.groupBy(
                keySelector = { it.first.timing },
                valueTransform = { Entry(it.first.text, it.second) },
            ),
            hasTranslation = entries.any { it.second.translation != null },
            hasPronunciation = entries.any { it.second.pronunciation != null },
            translationSource = song.metadata
                ?.getString(LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE),
            pronunciationSource = song.metadata
                ?.getString(LyricMetadataKeys.ONLINE_PRONUNCIATION_SOURCE),
        )
        if (overlay == updatedOverlay) return false

        overlay = updatedOverlay
        contentRevision += 1
        return true
    }

    @Synchronized
    fun clear(songId: String? = null): Boolean {
        val current = overlay ?: return false
        if (!songId.isNullOrBlank() && current.songId != songId) return false
        overlay = null
        contentRevision += 1
        return true
    }

    fun revision(): Long = contentRevision

    @Synchronized
    fun isCurrentRevision(songId: String?, revision: Long): Boolean =
        contentRevision == revision &&
            !songId.isNullOrBlank() &&
            overlay?.songId == songId

    fun hasTranslation(songId: String?): Boolean =
        !songId.isNullOrBlank() &&
            overlay?.let { it.songId == songId && it.hasTranslation } == true

    fun hasPronunciation(songId: String?): Boolean =
        !songId.isNullOrBlank() &&
            overlay?.let { it.songId == songId && it.hasPronunciation } == true

    fun translationSource(songId: String?): String? =
        overlay?.takeIf { it.songId == songId && it.hasTranslation }?.translationSource

    fun pronunciationSource(songId: String?): String? =
        overlay?.takeIf { it.songId == songId && it.hasPronunciation }?.pronunciationSource

    fun translation(
        songId: String?,
        begin: Long,
        end: Long,
        text: String?,
    ): String? = content(songId, begin, end, text)?.translation

    fun pronunciation(
        songId: String?,
        begin: Long,
        end: Long,
        text: String?,
    ): String? = content(songId, begin, end, text)?.pronunciation

    private fun content(
        songId: String?,
        begin: Long,
        end: Long,
        text: String?,
    ): Content? {
        val current = overlay ?: return null
        if (songId.isNullOrBlank() || current.songId != songId) return null

        val timing = TimingKey(begin, end)
        current.exactContent[LineKey(timing, normalizeText(text))]?.let { return it }
        return current.translationsByTiming[timing]
            ?.singleOrNull()
            ?.content
    }

    companion object {
        val WHITESPACE = Regex("\\s+")

        fun sanitizeTranslation(text: String?): String? =
            sanitizeContent(text)

        fun sanitizeContent(text: String?): String? =
            OnlineTranslationContentPolicy.sanitize(text)

        fun normalizeText(text: String?): String =
            text.orEmpty().replace(WHITESPACE, " ").trim()
    }
}
