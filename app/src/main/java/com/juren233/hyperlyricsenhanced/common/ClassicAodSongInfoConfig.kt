package com.juren233.hyperlyricsenhanced.common

import android.content.SharedPreferences

object ClassicAodSongInfoConfig {
    fun displayStyle(prefs: SharedPreferences): Int {
        if (prefs.contains(RootConstants.KEY_HOOK_CLASSIC_AOD_SONG_INFO_DISPLAY_STYLE)) {
            return prefs.getInt(
                RootConstants.KEY_HOOK_CLASSIC_AOD_SONG_INFO_DISPLAY_STYLE,
                RootConstants.DEFAULT_HOOK_CLASSIC_AOD_SONG_INFO_DISPLAY_STYLE
            ).coerceIn(
                RootConstants.AOD_SONG_INFO_DISPLAY_STYLE_NONE,
                RootConstants.AOD_SONG_INFO_DISPLAY_STYLE_TEXT_EMBEDDED
            )
        }

        // Preserve the previous focus-notification configuration after upgrading.
        return if (prefs.getInt(
                RootConstants.KEY_HOOK_CLASSIC_AOD_SONG_INFO_FORMAT,
                RootConstants.AOD_SONG_INFO_FORMAT_NONE
            ) != RootConstants.AOD_SONG_INFO_FORMAT_NONE
        ) {
            RootConstants.AOD_SONG_INFO_DISPLAY_STYLE_FOCUS_NOTIFICATION
        } else {
            RootConstants.AOD_SONG_INFO_DISPLAY_STYLE_NONE
        }
    }

    fun format(prefs: SharedPreferences): Int = prefs.getInt(
        RootConstants.KEY_HOOK_CLASSIC_AOD_SONG_INFO_FORMAT,
        RootConstants.DEFAULT_HOOK_CLASSIC_AOD_SONG_INFO_FORMAT
    ).coerceIn(
        RootConstants.AOD_SONG_INFO_FORMAT_TITLE,
        RootConstants.AOD_SONG_INFO_FORMAT_ARTIST_TITLE
    )

    fun embeddedPosition(prefs: SharedPreferences): Int = prefs.getInt(
        RootConstants.KEY_HOOK_CLASSIC_AOD_SONG_INFO_POSITION,
        RootConstants.DEFAULT_HOOK_CLASSIC_AOD_SONG_INFO_POSITION
    ).let(::normalizeEmbeddedPosition)

    fun normalizeEmbeddedPosition(position: Int): Int = position.coerceIn(
        RootConstants.AOD_SONG_INFO_POSITION_LEFT,
        RootConstants.AOD_SONG_INFO_POSITION_RIGHT
    )

    fun embeddedTextSize(prefs: SharedPreferences): Int = sanitizeEmbeddedTextSize(
        prefs.getInt(
            RootConstants.KEY_HOOK_CLASSIC_AOD_SONG_INFO_TEXT_SIZE,
            RootConstants.DEFAULT_HOOK_CLASSIC_AOD_SONG_INFO_TEXT_SIZE
        )
    )

    fun sanitizeEmbeddedTextSize(textSize: Int): Int = textSize.coerceIn(
        RootConstants.MIN_HOOK_AOD_TRANSLATION_TEXT_SIZE,
        RootConstants.MAX_HOOK_AOD_TRANSLATION_TEXT_SIZE
    )

    fun showsEmbeddedIcon(prefs: SharedPreferences): Boolean = prefs.getBoolean(
        RootConstants.KEY_HOOK_CLASSIC_AOD_SONG_INFO_SHOW_ICON,
        RootConstants.DEFAULT_HOOK_CLASSIC_AOD_SONG_INFO_SHOW_ICON
    )

    fun formatSongInfo(title: String, artist: String, format: Int): String = when (format) {
        RootConstants.AOD_SONG_INFO_FORMAT_TITLE -> title
        RootConstants.AOD_SONG_INFO_FORMAT_TITLE_ARTIST ->
            listOf(title, artist).filter { it.isNotBlank() }.joinToString(" - ")
        RootConstants.AOD_SONG_INFO_FORMAT_ARTIST_TITLE ->
            listOf(artist, title).filter { it.isNotBlank() }.joinToString(" - ")
        else -> ""
    }
}
