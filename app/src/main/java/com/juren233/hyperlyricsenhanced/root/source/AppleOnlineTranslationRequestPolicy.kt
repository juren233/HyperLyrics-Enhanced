package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.model.Song

internal object AppleOnlineTranslationRequestPolicy {
    data class OriginalMetadataLookupPlan(
        val requestOriginalMetadata: Boolean,
        val waitForResult: Boolean,
    )

    /** When replacement is requested, do not race an online lookup against localized metadata. */
    fun originalMetadataLookupPlan(
        shouldRequestOriginalMetadata: Boolean,
    ): OriginalMetadataLookupPlan = OriginalMetadataLookupPlan(
        requestOriginalMetadata = shouldRequestOriginalMetadata,
        waitForResult = shouldRequestOriginalMetadata,
    )

    fun applyResolvedReplacement(song: Song?, enabled: Boolean): Song? {
        song ?: return null
        if (!enabled || !isOriginalMetadataResolved(song)) return song
        val title = song.metadata
            ?.getString(LyricMetadataKeys.APPLE_ORIGINAL_TITLE)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val artist = song.metadata
            ?.getString(LyricMetadataKeys.APPLE_ORIGINAL_ARTIST)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        if (title == null && artist == null) return song
        return song.copy(
            name = title ?: song.name,
            artist = artist ?: song.artist,
        )
    }

    fun effectiveAlbum(song: Song): String = sequenceOf(
        song.metadata?.getString(LyricMetadataKeys.APPLE_ORIGINAL_ALBUM),
        song.metadata?.getString(LyricMetadataKeys.APPLE_ALBUM),
    ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.firstOrNull().orEmpty()

    fun originalMetadataChanged(previous: Song?, current: Song?): Boolean =
        previous != null && current != null && originalMetadataKey(previous) != originalMetadataKey(current)

    fun attemptKey(song: Song): String {
        val trackKey = song.id?.trim()?.takeIf(String::isNotEmpty)?.let { "id:$it" }
            ?: "${normalize(song.name)}|${normalize(song.artist)}"
        return "$trackKey|${originalMetadataKey(song)}"
    }

    private fun originalMetadataKey(song: Song): String = listOf(
        song.metadata?.getString(LyricMetadataKeys.APPLE_ORIGINAL_TITLE),
        song.metadata?.getString(LyricMetadataKeys.APPLE_ORIGINAL_ARTIST),
        song.metadata?.getString(LyricMetadataKeys.APPLE_ORIGINAL_ALBUM),
    ).joinToString("|") { normalize(it) }

    private fun isOriginalMetadataResolved(song: Song): Boolean = song.metadata
        ?.getString(LyricMetadataKeys.APPLE_ORIGINAL_METADATA_RESOLVED)
        .toBoolean()

    private fun normalize(value: String?): String = value.orEmpty().trim().lowercase()
}
