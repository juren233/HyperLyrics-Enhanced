package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.model.Song

internal object AppleOnlineTranslationRequestPolicy {
    fun originalMetadataChanged(previous: Song?, current: Song?): Boolean =
        previous != null && current != null && originalMetadataKey(previous) != originalMetadataKey(current)

    fun attemptKey(song: Song): String {
        val trackKey = song.id?.trim()?.takeIf(String::isNotEmpty)?.let { "id:$it" }
            ?: "${normalize(song.name)}|${normalize(song.artist)}"
        return "$trackKey|${originalMetadataKey(song)}"
    }

    private fun originalMetadataKey(song: Song): String = listOf(
        song.metadata?.getString(LyricMetadataKeys.APPLE_ORIGINAL_TITLE),
        song.metadata?.getString(LyricMetadataKeys.APPLE_ORIGINAL_ARTIST)
    ).joinToString("|") { normalize(it) }

    private fun normalize(value: String?): String = value.orEmpty().trim().lowercase()
}
