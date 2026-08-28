/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard

import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.IRichLyricLine
import java.io.Closeable
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Immutable copy of one lyric line suitable for crossing the Root/SystemUI boundary.
 *
 * The bridge deliberately does not expose [IRichLyricLine] or [Song] directly here:
 * both models are mutable and their fields are updated independently while provider
 * callbacks are being delivered. A presentation consumer must be able to retain a
 * snapshot for the whole native transition without observing a half-written model.
 */
class MediaLyricWordSnapshot(
    val beginMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val text: String,
    metadata: Map<String, String?> = emptyMap(),
) {
    val metadata: Map<String, String?> = immutableMap(metadata)

    operator fun component1(): Long = beginMs
    operator fun component2(): Long = endMs
    operator fun component3(): Long = durationMs
    operator fun component4(): String = text
    operator fun component5(): Map<String, String?> = metadata

    fun copy(
        beginMs: Long = this.beginMs,
        endMs: Long = this.endMs,
        durationMs: Long = this.durationMs,
        text: String = this.text,
        metadata: Map<String, String?> = this.metadata,
    ): MediaLyricWordSnapshot = MediaLyricWordSnapshot(
        beginMs = beginMs,
        endMs = endMs,
        durationMs = durationMs,
        text = text,
        metadata = metadata,
    )

    override fun equals(other: Any?): Boolean = other is MediaLyricWordSnapshot &&
        beginMs == other.beginMs &&
        endMs == other.endMs &&
        durationMs == other.durationMs &&
        text == other.text &&
        metadata == other.metadata

    override fun hashCode(): Int = listOf(beginMs, endMs, durationMs, text, metadata).hashCode()

    override fun toString(): String = "MediaLyricWordSnapshot(" +
        "beginMs=$beginMs, endMs=$endMs, durationMs=$durationMs, text=$text, metadata=$metadata)"

    companion object {
        fun from(word: LyricWord): MediaLyricWordSnapshot = MediaLyricWordSnapshot(
            beginMs = word.begin,
            endMs = word.end,
            durationMs = word.duration,
            text = word.text.orEmpty(),
            metadata = word.metadata?.toMap().orEmpty(),
        )
    }
}

class MediaLyricLineSnapshot(
    val beginMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val text: String,
    val secondary: String,
    val translation: String,
    val roma: String,
    val alignedRight: Boolean,
    metadata: Map<String, String?> = emptyMap(),
    words: List<MediaLyricWordSnapshot> = emptyList(),
    secondaryWords: List<MediaLyricWordSnapshot> = emptyList(),
    translationWords: List<MediaLyricWordSnapshot> = emptyList(),
) {
    val metadata: Map<String, String?> = immutableMap(metadata)
    val words: List<MediaLyricWordSnapshot> = immutableList(words)
    val secondaryWords: List<MediaLyricWordSnapshot> = immutableList(secondaryWords)
    val translationWords: List<MediaLyricWordSnapshot> = immutableList(translationWords)

    operator fun component1(): Long = beginMs
    operator fun component2(): Long = endMs
    operator fun component3(): Long = durationMs
    operator fun component4(): String = text
    operator fun component5(): String = secondary
    operator fun component6(): String = translation
    operator fun component7(): String = roma
    operator fun component8(): Boolean = alignedRight
    operator fun component9(): Map<String, String?> = metadata
    operator fun component10(): List<MediaLyricWordSnapshot> = words
    operator fun component11(): List<MediaLyricWordSnapshot> = secondaryWords
    operator fun component12(): List<MediaLyricWordSnapshot> = translationWords

    val isBlank: Boolean
        get() = text.isBlank() && secondary.isBlank() && translation.isBlank() && roma.isBlank() &&
            words.none { it.text.isNotBlank() } &&
            secondaryWords.none { it.text.isNotBlank() } &&
            translationWords.none { it.text.isNotBlank() }

    fun metadataValue(key: String): String? = metadata[key]

    fun copy(
        beginMs: Long = this.beginMs,
        endMs: Long = this.endMs,
        durationMs: Long = this.durationMs,
        text: String = this.text,
        secondary: String = this.secondary,
        translation: String = this.translation,
        roma: String = this.roma,
        alignedRight: Boolean = this.alignedRight,
        metadata: Map<String, String?> = this.metadata,
        words: List<MediaLyricWordSnapshot> = this.words,
        secondaryWords: List<MediaLyricWordSnapshot> = this.secondaryWords,
        translationWords: List<MediaLyricWordSnapshot> = this.translationWords,
    ): MediaLyricLineSnapshot = MediaLyricLineSnapshot(
        beginMs = beginMs,
        endMs = endMs,
        durationMs = durationMs,
        text = text,
        secondary = secondary,
        translation = translation,
        roma = roma,
        alignedRight = alignedRight,
        metadata = metadata,
        words = words,
        secondaryWords = secondaryWords,
        translationWords = translationWords,
    )

    override fun equals(other: Any?): Boolean = other is MediaLyricLineSnapshot &&
        beginMs == other.beginMs &&
        endMs == other.endMs &&
        durationMs == other.durationMs &&
        text == other.text &&
        secondary == other.secondary &&
        translation == other.translation &&
        roma == other.roma &&
        alignedRight == other.alignedRight &&
        metadata == other.metadata &&
        words == other.words &&
        secondaryWords == other.secondaryWords &&
        translationWords == other.translationWords

    override fun hashCode(): Int = listOf(
        beginMs,
        endMs,
        durationMs,
        text,
        secondary,
        translation,
        roma,
        alignedRight,
        metadata,
        words,
        secondaryWords,
        translationWords,
    ).hashCode()

    override fun toString(): String = "MediaLyricLineSnapshot(" +
        "beginMs=$beginMs, endMs=$endMs, durationMs=$durationMs, text=$text, " +
        "secondary=$secondary, translation=$translation, roma=$roma, alignedRight=$alignedRight, " +
        "metadata=$metadata, words=$words, secondaryWords=$secondaryWords, " +
        "translationWords=$translationWords)"

    companion object {
        fun from(line: IRichLyricLine?): MediaLyricLineSnapshot? = line?.let {
            MediaLyricLineSnapshot(
                beginMs = it.begin,
                endMs = it.end,
                durationMs = it.duration,
                text = it.text.orEmpty(),
                secondary = it.secondary.orEmpty(),
                translation = it.translation.orEmpty(),
                roma = it.roma.orEmpty(),
                alignedRight = it.isAlignedRight,
                metadata = it.metadata?.toMap().orEmpty(),
                words = it.words.orEmpty().map(MediaLyricWordSnapshot::from),
                secondaryWords = it.secondaryWords.orEmpty().map(MediaLyricWordSnapshot::from),
                translationWords = it.translationWords.orEmpty().map(MediaLyricWordSnapshot::from),
            )
        }
    }
}

private fun immutableMap(source: Map<String, String?>): Map<String, String?> =
    Collections.unmodifiableMap(LinkedHashMap(source))

private fun <T> immutableList(source: List<T>): List<T> =
    Collections.unmodifiableList(ArrayList(source))

data class MediaLyricSongIdentity(
    val id: String?,
    val title: String?,
    val artist: String?,
    val durationMs: Long,
) {
    val stableKey: String
        get() = id?.trim()?.takeIf { it.isNotEmpty() }
            ?: listOf(title.orEmpty().trim(), artist.orEmpty().trim())
                .joinToString("\u0000")

    companion object {
        fun from(song: Song?): MediaLyricSongIdentity? = song?.let {
            MediaLyricSongIdentity(
                id = it.id,
                title = it.name,
                artist = it.artist,
                durationMs = it.duration,
            )
        }
    }
}

data class MediaLyricSnapshot(
    val sequence: Long,
    val song: MediaLyricSongIdentity?,
    val packageName: String?,
    val positionMs: Long,
    val isPlaying: Boolean?,
    val isTextMode: Boolean,
    val songHasDuet: Boolean,
    val current: MediaLyricLineSnapshot?,
    val next: MediaLyricLineSnapshot?,
    val nextNext: MediaLyricLineSnapshot?,
) {
    val hasContent: Boolean
        get() = isTextMode || listOf(current, next, nextNext).any { line ->
            line != null && !line.isBlank
        }

    val songKey: String?
        get() = song?.stableKey

    companion object {
        fun empty(sequence: Long = 0L): MediaLyricSnapshot = MediaLyricSnapshot(
            sequence = sequence,
            song = null,
            packageName = null,
            positionMs = 0L,
            isPlaying = null,
            isTextMode = false,
            songHasDuet = false,
            current = null,
            next = null,
            nextNext = null,
        )

        fun from(
            sequence: Long,
            song: Song?,
            packageName: String?,
            positionMs: Long,
            isPlaying: Boolean?,
            isTextMode: Boolean,
            current: IRichLyricLine?,
            next: IRichLyricLine?,
            nextNext: IRichLyricLine?,
        ): MediaLyricSnapshot = MediaLyricSnapshot(
            sequence = sequence,
            song = MediaLyricSongIdentity.from(song),
            packageName = packageName?.trim()?.takeIf { it.isNotEmpty() },
            positionMs = positionMs,
            isPlaying = isPlaying,
            isTextMode = isTextMode,
            songHasDuet = song?.lyrics.orEmpty().any { it.isAlignedRight },
            current = MediaLyricLineSnapshot.from(current),
            next = MediaLyricLineSnapshot.from(next),
            nextNext = MediaLyricLineSnapshot.from(nextNext),
        )
    }
}

data class MediaLyricSnapshotDraft(
    val song: MediaLyricSongIdentity?,
    val packageName: String?,
    val positionMs: Long,
    val isPlaying: Boolean?,
    val isTextMode: Boolean,
    val songHasDuet: Boolean,
    val current: MediaLyricLineSnapshot?,
    val next: MediaLyricLineSnapshot?,
    val nextNext: MediaLyricLineSnapshot?,
) {
    fun asSnapshot(sequence: Long): MediaLyricSnapshot = MediaLyricSnapshot(
        sequence = sequence,
        song = song,
        packageName = packageName,
        positionMs = positionMs,
        isPlaying = isPlaying,
        isTextMode = isTextMode,
        songHasDuet = songHasDuet,
        current = current,
        next = next,
        nextNext = nextNext,
    )

    companion object {
        fun empty(): MediaLyricSnapshotDraft = MediaLyricSnapshot.empty().toDraft()
    }
}

fun MediaLyricSnapshot.toDraft(): MediaLyricSnapshotDraft = MediaLyricSnapshotDraft(
    song = song,
    packageName = packageName,
    positionMs = positionMs,
    isPlaying = isPlaying,
    isTextMode = isTextMode,
    songHasDuet = songHasDuet,
    current = current,
    next = next,
    nextNext = nextNext,
)

/**
 * Thread-safe, monotonic snapshot publication point for media-card consumers.
 *
 * [publish] always assigns a new sequence while [accept] is intentionally strict:
 * a callback carrying an older sequence is ignored. This gives a transition session
 * a stable snapshot and prevents a late provider/translation callback from replacing
 * a newer song or lyric generation.
 */
class MediaLyricSnapshotStore(
    initial: MediaLyricSnapshot = MediaLyricSnapshot.empty(),
) {
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(MediaLyricSnapshot) -> Unit>()
    private var nextSequence = initial.sequence.coerceAtLeast(0L)

    @Volatile
    private var latest = initial

    fun current(): MediaLyricSnapshot = latest

    fun publish(draft: MediaLyricSnapshotDraft): MediaLyricSnapshot {
        val snapshot = synchronized(lock) {
            val next = ++nextSequence
            draft.asSnapshot(next).also { latest = it }
        }
        notifyListeners(snapshot)
        return snapshot
    }

    fun update(transform: (MediaLyricSnapshot) -> MediaLyricSnapshotDraft): MediaLyricSnapshot {
        val snapshot = synchronized(lock) {
            val next = ++nextSequence
            transform(latest).asSnapshot(next).also { latest = it }
        }
        notifyListeners(snapshot)
        return snapshot
    }

    /**
     * Accept an externally sequenced snapshot only when it is newer than the current
     * one. The operation is useful for a session replay/import boundary and is never
     * used to synthesize a new sequence for ordinary bridge updates.
     */
    fun accept(snapshot: MediaLyricSnapshot): Boolean {
        val accepted = synchronized(lock) {
            if (snapshot.sequence <= latest.sequence) {
                false
            } else {
                nextSequence = snapshot.sequence
                latest = snapshot
                true
            }
        }
        if (accepted) notifyListeners(snapshot)
        return accepted
    }

    fun clear(): MediaLyricSnapshot = publish(MediaLyricSnapshotDraft.empty())

    fun subscribe(listener: (MediaLyricSnapshot) -> Unit): Closeable {
        listeners += listener
        listener(current())
        return Closeable { listeners -= listener }
    }

    private fun notifyListeners(snapshot: MediaLyricSnapshot) {
        listeners.forEach { listener ->
            runCatching { listener(snapshot) }
        }
    }

    companion object {
        /** Process-local store consumed by the SystemUI media-card bridge. */
        val global: MediaLyricSnapshotStore = MediaLyricSnapshotStore()
    }
}
