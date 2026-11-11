/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.common.lyric.OnlineSourceContentState
import com.juren233.hyperlyricsenhanced.common.lyric.OnlineTranslationContentPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.OnlineTranslationMatchStat
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf

/**
 * What the user picked by hand in the always-visible source dialogs for one track.
 *
 * Automatic enrichment only fills the lines Apple left empty. A hand-picked source instead
 * replaces Apple's own translation or pronunciation wherever it has a line, and picking
 * Apple Music itself keeps that content purely native.
 */
internal data class OnlineSourceControl(
    /** False when no automatic switch asked for this search: only hand-picked content applies. */
    val automaticFill: Boolean = true,
    val translationNativeOnly: Boolean = false,
    val pronunciationNativeOnly: Boolean = false,
)

internal object OnlineSourceOverride {
    /** Source name used for Apple Music's own content in per-source stats and states. */
    const val APPLE_NATIVE = "APPLE"

    /** The native lines with translation and/or pronunciation blanked, so a source is matched against every line. */
    fun strip(song: Song, translation: Boolean, pronunciation: Boolean): Song {
        if (!translation && !pronunciation) return song
        return song.copy(
            lyrics = song.lyrics?.map { line ->
                line.copy(
                    translation = if (translation) null else line.translation,
                    translationWords = if (translation) null else line.translationWords,
                    roma = if (pronunciation) null else line.roma,
                    metadata = if (translation) line.withoutBackgroundTranslation() else line.metadata,
                )
            },
        )
    }

    /** Takes [source]'s translation on every line where it has one; other lines keep [target]'s. */
    fun overlayTranslation(target: Song, source: Song): Song = target.mergeLines(source) { line, picked ->
        val translation = OnlineTranslationContentPolicy.sanitize(picked.translation)
            ?: return@mergeLines line
        line.copy(
            translation = translation,
            translationWords = picked.translationWords,
            metadata = line.withBackgroundTranslation(
                picked.backgroundTranslation() ?: line.backgroundTranslation()
            ),
        )
    }

    /** Takes [source]'s pronunciation on every line where it has one; other lines keep [target]'s. */
    fun overlayPronunciation(target: Song, source: Song): Song = target.mergeLines(source) { line, picked ->
        val roma = picked.roma?.takeIf(String::isNotBlank) ?: return@mergeLines line
        line.copy(roma = roma)
    }

    /** Puts Apple's own translation back on every line, dropping whatever was filled in. */
    fun restoreNativeTranslation(target: Song, native: Song): Song = target.mergeLines(native) { line, original ->
        line.copy(
            translation = original.translation,
            translationWords = original.translationWords,
            metadata = line.withBackgroundTranslation(original.backgroundTranslation()),
        )
    }

    /** Puts Apple's own pronunciation back on every line, dropping whatever was filled in. */
    fun restoreNativePronunciation(target: Song, native: Song): Song =
        target.mergeLines(native) { line, original -> line.copy(roma = original.roma) }

    /** True when [result] shows a translation or pronunciation that differs from [base] on any line. */
    fun changesContent(base: Song, result: Song): Boolean {
        val baseLines = base.lyrics.orEmpty()
        val resultLines = result.lyrics.orEmpty()
        if (baseLines.size != resultLines.size) return false
        return baseLines.indices.any { index ->
            val before = baseLines[index]
            val after = resultLines[index]
            OnlineTranslationContentPolicy.sanitize(before.translation) !=
                OnlineTranslationContentPolicy.sanitize(after.translation) ||
                before.roma.orEmpty().trim() != after.roma.orEmpty().trim() ||
                OnlineTranslationContentPolicy.sanitize(before.backgroundTranslation()) !=
                OnlineTranslationContentPolicy.sanitize(after.backgroundTranslation())
        }
    }

    /** Share of the track's sung lines for which [song] carries a translation. */
    fun translationCoverage(song: Song): OnlineTranslationMatchStat = coverage(song) {
        OnlineTranslationContentPolicy.isMeaningful(it.translation)
    }

    /** Share of the track's sung lines for which [song] carries a pronunciation. */
    fun pronunciationCoverage(song: Song): OnlineTranslationMatchStat = coverage(song) {
        !it.roma.isNullOrBlank()
    }

    /** What Apple Music itself provides, reported next to the third-party sources. */
    fun nativeState(song: Song): OnlineSourceContentState = OnlineSourceContentState(
        hasTranslation = song.lyrics.orEmpty().any {
            OnlineTranslationContentPolicy.isMeaningful(it.translation)
        },
        hasPronunciation = song.lyrics.orEmpty().any { !it.roma.isNullOrBlank() },
    )

    fun Song.withMetadataFlag(key: String, enabled: Boolean): Song {
        val entries = metadata?.entries.orEmpty()
            .filterNot { it.key == key }
            .map { it.key to it.value }
            .toMutableList()
        if (enabled) entries += key to "true"
        return copy(
            metadata = entries.takeIf { it.isNotEmpty() }
                ?.let { lyricMetadataOf(*it.toTypedArray()) }
        )
    }

    fun Song.withMetadataValue(key: String, value: String?): Song {
        val entries = metadata?.entries.orEmpty()
            .filterNot { it.key == key }
            .map { it.key to it.value }
            .toMutableList()
        value?.let { entries += key to it }
        return copy(
            metadata = entries.takeIf { it.isNotEmpty() }
                ?.let { lyricMetadataOf(*it.toTypedArray()) }
        )
    }

    private fun coverage(song: Song, has: (RichLyricLine) -> Boolean): OnlineTranslationMatchStat {
        val sung = song.lyrics.orEmpty().filter { !it.text.isNullOrBlank() }
        return OnlineTranslationMatchStat(matchedLines = sung.count(has), totalLines = sung.size)
    }

    private inline fun Song.mergeLines(
        other: Song,
        merge: (line: RichLyricLine, otherLine: RichLyricLine) -> RichLyricLine,
    ): Song {
        val lines = lyrics ?: return this
        val otherLines = other.lyrics ?: return this
        if (lines.size != otherLines.size) return this
        return copy(lyrics = lines.mapIndexed { index, line -> merge(line, otherLines[index]) })
    }

    private fun RichLyricLine.backgroundTranslation(): String? =
        metadata?.getString(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION)

    private fun RichLyricLine.withoutBackgroundTranslation() = withBackgroundTranslation(null)

    private fun RichLyricLine.withBackgroundTranslation(value: String?) =
        metadata?.entries.orEmpty()
            .filterNot { it.key == LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION }
            .map { it.key to it.value }
            .toMutableList()
            .apply {
                OnlineTranslationContentPolicy.sanitize(value)?.let {
                    add(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION to it)
                }
            }
            .takeIf { it.isNotEmpty() }
            ?.let { lyricMetadataOf(*it.toTypedArray()) }
}
