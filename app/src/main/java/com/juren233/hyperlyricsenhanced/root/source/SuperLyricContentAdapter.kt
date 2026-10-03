/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.source

import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.source.LyricSink
import com.juren233.hyperlyricsenhanced.lyric.source.TimelineContent
import com.juren233.hyperlyricsenhanced.timeline.model.TrackIdentity

/** API 3.6 content adapter. All methods are owned by the source's main-thread callback queue. */
internal class SuperLyricContentAdapter(
    private val latestLyric: () -> SuperLyricData? = { null },
) {
    private var publisher: String? = null
    private var metadata = Metadata()
    private var fullSong: Song? = null
    private var lastPublished: TimelineContent? = null
    private var recoveryAttemptedFor: Metadata? = null

    fun publish(
        publisher: String,
        incoming: SuperLyricData,
        sink: LyricSink,
        playbackPosition: () -> Long? = { null },
    ) {
        val incomingMetadata = Metadata.from(incoming)
        if (this.publisher != publisher || !metadata.accepts(incomingMetadata)) {
            clear()
            this.publisher = publisher
        }
        metadata = metadata.merge(incomingMetadata)

        var full = incoming.takeIf { it.hasAllLyrics() }
        if (full == null && fullSong == null && recoveryAttemptedFor != metadata &&
            (incoming.hasLyricId() || incoming.hasCurrentLyricIndex())
        ) {
            // The SDK's global SuperLyricCache keys only by lyricId/title, not publisher, and
            // may fall back to title despite conflicting IDs. Keep our single publisher/track
            // snapshot instead; use the public recovery API only for a matching identity.
            recoveryAttemptedFor = metadata
            full = latestLyric()?.takeIf {
                it.hasAllLyrics() && metadata.matchesRecovery(Metadata.from(it))
            }
        }
        if (full != null) {
            metadata = Metadata.from(full).merge(metadata)
            val current = full.currentLyric
            fullSong = Song(
                lyrics = full.allLyrics.orEmpty().filterNotNull().map { line ->
                    convertLine(
                        line,
                        full.translation.takeIf { line === current },
                        full.secondary.takeIf { line === current },
                    )
                },
            )
        }

        val song = fullSong
        if (song != null) {
            val lines = updateCurrentLine(song.lyrics.orEmpty(), incoming)
            val updated = song.copy(
                id = metadata.id,
                name = metadata.title,
                artist = metadata.artist,
                duration = metadata.duration ?: 0L,
                lyrics = lines,
            )
            fullSong = updated
            // A fingerprint alone cannot establish which MediaSession song owns the rows.
            // Retain the snapshot until a metadata packet supplies its title.
            if (metadata.title.isNullOrBlank()) return
            val content = TimelineContent(
                sourceId = "superlyric",
                // lyricId is an API cache fingerprint, not the player's MediaSession mediaId.
                track = TrackIdentity(
                    packageName = publisher,
                    title = metadata.title.orEmpty(),
                    artist = metadata.artist.orEmpty(),
                    album = metadata.album.orEmpty(),
                    durationMs = metadata.duration ?: 0L,
                ),
                song = updated,
            )
            if (content != lastPublished) {
                // The renderer may enrich/mutate Song. Keep the deduplication snapshot private.
                sink.onTimelineContent(content.copy(song = updated.deepCopy()))
                lastPublished = content
            }
            return
        }

        // Older publishers still send a current line (or plain text) without allLyrics.
        sink.onMetadata(metadata.title, metadata.artist, metadata.album, publisher)
        val line = incoming.currentLyric ?: return
        val rich = convertLine(line, incoming.translation, incoming.secondary)
        if (line.startTime != 0L || line.endTime != 0L) {
            sink.onLyricLine(rich)
            return
        }
        @Suppress("DEPRECATION")
        val delay = line.delay
        val position = if (delay <= 0L) null else {
            if (incoming.hasPosition()) incoming.position else playbackPosition()
        }
        if (delay > 0L && position != null && position >= 0L) {
            sink.onLyricLine(rich.copy(begin = position, end = position + delay, duration = delay))
        } else {
            sink.onPlainText(listOfNotNull(rich.text, rich.translation).joinToString("\n"))
        }
    }

    /** Pause retains full lyrics so a delta-only resume can republish after the sink was cleared. */
    fun onStop(publisher: String): Boolean {
        if (this.publisher != publisher) return false
        lastPublished = null
        return true
    }

    fun clear() {
        publisher = null
        metadata = Metadata()
        fullSong = null
        lastPublished = null
        recoveryAttemptedFor = null
    }

    private fun updateCurrentLine(lines: List<RichLyricLine>, data: SuperLyricData): List<RichLyricLine> {
        val incoming = data.currentLyric
        val index = if (data.hasCurrentLyricIndex()) data.currentLyricIndex else {
            lines.indexOfFirst {
                incoming != null && it.text == incoming.text &&
                    it.begin == incoming.startTime && it.end == incoming.endTime
            }
        }
        val previous = lines.getOrNull(index) ?: return lines
        // An index/position update alone must never turn a full timeline into one current row.
        if (incoming != null && (incoming.text != previous.text ||
                incoming.startTime != previous.begin || incoming.endTime != previous.end)
        ) return lines
        val translated = data.translation
        val secondary = data.secondary
        if (incoming == null && translated == null && secondary == null) return lines
        val update = previous.copy(
            words = incoming?.words?.map { LyricWord(it.startTime, it.endTime, text = it.word) }
                ?: previous.words,
            translation = translated?.text ?: previous.translation,
            translationWords = translated?.words?.map { LyricWord(it.startTime, it.endTime, text = it.word) }
                ?: previous.translationWords.takeIf { translated == null || translated.text == previous.translation },
            secondary = secondary?.text ?: previous.secondary,
            secondaryWords = secondary?.words?.map { LyricWord(it.startTime, it.endTime, text = it.word) }
                ?: previous.secondaryWords.takeIf { secondary == null || secondary.text == previous.secondary },
        )
        if (update == previous) return lines
        return lines.toMutableList().apply { set(index, update) }
    }

    private fun convertLine(
        line: SuperLyricLine,
        translation: SuperLyricLine? = null,
        secondary: SuperLyricLine? = null,
    ) = RichLyricLine(
        begin = line.startTime,
        end = line.endTime,
        text = line.text,
        words = line.words?.map { LyricWord(it.startTime, it.endTime, text = it.word) },
        translation = translation?.text ?: line.translation,
        translationWords = translation?.words?.map { LyricWord(it.startTime, it.endTime, text = it.word) },
        secondary = secondary?.text ?: line.secondary,
        secondaryWords = secondary?.words?.map { LyricWord(it.startTime, it.endTime, text = it.word) },
    )

    private data class Metadata(
        val id: String? = null,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val duration: Long? = null,
    ) {
        fun accepts(other: Metadata): Boolean = listOf(
            id to other.id, title to other.title, artist to other.artist, album to other.album,
        ).all { (a, b) -> a == null || b == null || a == b }

        fun matchesRecovery(other: Metadata): Boolean = accepts(other) &&
            (id != null && id == other.id ||
                !title.isNullOrBlank() && title == other.title &&
                !artist.isNullOrBlank() && artist == other.artist)

        fun merge(newer: Metadata) = Metadata(
            newer.id ?: id,
            newer.title ?: title,
            newer.artist ?: artist,
            newer.album ?: album,
            newer.duration ?: duration,
        )

        companion object {
            fun from(data: SuperLyricData) = Metadata(
                id = data.lyricId?.takeIf { it.isNotBlank() },
                title = data.title,
                artist = data.artist,
                album = data.album,
                duration = data.duration.takeIf { data.hasDuration() },
            )
        }
    }
}
