/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard

import android.content.SharedPreferences
import com.juren233.hyperlyricsenhanced.common.RootConstants

internal data class MediaCardLyricConfig(
    val enabled: Boolean,
    val mainTextSize: Int,
    val backingTextSize: Int,
    val translationTextSize: Int,
    val translationDisplayMode: Int,
    val translationFallback: Boolean,
    val swapTranslation: Boolean,
    val duetLyrics: Boolean,
    val centerNonDuetSong: Boolean,
    val centerGroupVocals: Boolean,
    val nextSongPreview: Boolean,
    val nextSongPreviewPosition: Int,
    val blurMode: Int,
    val blurMinRadius: Float,
    val blurMaxRadius: Float,
)

internal object MediaCardLyricPreferences {
    private val keys = setOf(
        RootConstants.KEY_HOOK_ENABLE_MEDIA_CARD_LYRICS,
        RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_MAIN_TEXT_SIZE,
        RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_BACKING_TEXT_SIZE,
        RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_TRANSLATION_TEXT_SIZE,
        RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_TRANSLATION_DISPLAY,
        RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_TRANSLATION_FALLBACK,
        RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_SWAP_TRANSLATION,
        RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_DUET_LYRICS,
        RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_CENTER_NON_DUET_SONG,
        RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_CENTER_GROUP_VOCALS,
        RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_NEXT_SONG_PREVIEW,
        RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_NEXT_SONG_PREVIEW_POSITION,
        RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_BLUR_EFFECT,
        RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_NATIVE_BLUR_MIN_RADIUS_DP,
        RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_NATIVE_BLUR_MAX_RADIUS_DP,
        RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_ADVANCED_BLUR_MIN_RADIUS_PX,
        RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_ADVANCED_BLUR_MAX_RADIUS_PX,
    )

    fun contains(key: String?): Boolean = key in keys

    fun read(prefs: SharedPreferences?): MediaCardLyricConfig {
        val blurMode = intValue(
            prefs,
            RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_BLUR_EFFECT,
            RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_BLUR_EFFECT,
        ).coerceIn(
            RootConstants.MEDIA_CARD_LYRIC_BLUR_EFFECT_OFF,
            RootConstants.MEDIA_CARD_LYRIC_BLUR_EFFECT_ADVANCED_MATERIAL,
        )
        val rawBlurRange = if (blurMode == RootConstants.MEDIA_CARD_LYRIC_BLUR_EFFECT_NATIVE) {
            floatValue(
                prefs,
                RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_NATIVE_BLUR_MIN_RADIUS_DP,
                RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_NATIVE_BLUR_MIN_RADIUS_DP,
            ).coerceIn(
                RootConstants.MIN_HOOK_MEDIA_CARD_LYRIC_NATIVE_BLUR_RADIUS_DP,
                RootConstants.MAX_HOOK_MEDIA_CARD_LYRIC_NATIVE_BLUR_RADIUS_DP,
            ) to floatValue(
                prefs,
                RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_NATIVE_BLUR_MAX_RADIUS_DP,
                RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_NATIVE_BLUR_MAX_RADIUS_DP,
            ).coerceIn(
                RootConstants.MIN_HOOK_MEDIA_CARD_LYRIC_NATIVE_BLUR_RADIUS_DP,
                RootConstants.MAX_HOOK_MEDIA_CARD_LYRIC_NATIVE_BLUR_RADIUS_DP,
            )
        } else {
            intValue(
                prefs,
                RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_ADVANCED_BLUR_MIN_RADIUS_PX,
                RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_ADVANCED_BLUR_MIN_RADIUS_PX,
            ).coerceIn(
                RootConstants.MIN_HOOK_MEDIA_CARD_LYRIC_ADVANCED_BLUR_RADIUS_PX,
                RootConstants.MAX_HOOK_MEDIA_CARD_LYRIC_ADVANCED_BLUR_RADIUS_PX,
            ).toFloat() to intValue(
                prefs,
                RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_ADVANCED_BLUR_MAX_RADIUS_PX,
                RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_ADVANCED_BLUR_MAX_RADIUS_PX,
            ).coerceIn(
                RootConstants.MIN_HOOK_MEDIA_CARD_LYRIC_ADVANCED_BLUR_RADIUS_PX,
                RootConstants.MAX_HOOK_MEDIA_CARD_LYRIC_ADVANCED_BLUR_RADIUS_PX,
            ).toFloat()
        }
        return MediaCardLyricConfig(
            enabled = booleanValue(
                prefs,
                RootConstants.KEY_HOOK_ENABLE_MEDIA_CARD_LYRICS,
                RootConstants.DEFAULT_HOOK_ENABLE_MEDIA_CARD_LYRICS,
            ),
            mainTextSize = intValue(
                prefs,
                RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_MAIN_TEXT_SIZE,
                RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_MAIN_TEXT_SIZE,
            ).coerceIn(
                RootConstants.MIN_HOOK_AOD_MAIN_TEXT_SIZE,
                RootConstants.MAX_HOOK_AOD_MAIN_TEXT_SIZE,
            ),
            backingTextSize = intValue(
                prefs,
                RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_BACKING_TEXT_SIZE,
                RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_BACKING_TEXT_SIZE,
            ).coerceIn(
                RootConstants.MIN_HOOK_AOD_BACKING_TEXT_SIZE,
                RootConstants.MAX_HOOK_AOD_BACKING_TEXT_SIZE,
            ),
            translationTextSize = intValue(
                prefs,
                RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_TRANSLATION_TEXT_SIZE,
                RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_TRANSLATION_TEXT_SIZE,
            ).coerceIn(
                RootConstants.MIN_HOOK_AOD_TRANSLATION_TEXT_SIZE,
                RootConstants.MAX_HOOK_AOD_TRANSLATION_TEXT_SIZE,
            ),
            translationDisplayMode = intValue(
                prefs,
                RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_TRANSLATION_DISPLAY,
                RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_TRANSLATION_DISPLAY_MODE,
            ).coerceIn(
                RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF,
                RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_PRONUNCIATION,
            ),
            translationFallback = booleanValue(
                prefs,
                RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_TRANSLATION_FALLBACK,
                RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_TRANSLATION_FALLBACK,
            ),
            swapTranslation = booleanValue(
                prefs,
                RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_SWAP_TRANSLATION,
                RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_SWAP_TRANSLATION,
            ),
            duetLyrics = booleanValue(
                prefs,
                RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_DUET_LYRICS,
                RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_DUET_LYRICS,
            ),
            centerNonDuetSong = booleanValue(
                prefs,
                RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_CENTER_NON_DUET_SONG,
                RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_CENTER_NON_DUET_SONG,
            ),
            centerGroupVocals = booleanValue(
                prefs,
                RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_CENTER_GROUP_VOCALS,
                RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_CENTER_GROUP_VOCALS,
            ),
            nextSongPreview = booleanValue(
                prefs,
                RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_NEXT_SONG_PREVIEW,
                RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_NEXT_SONG_PREVIEW,
            ),
            nextSongPreviewPosition = intValue(
                prefs,
                RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_NEXT_SONG_PREVIEW_POSITION,
                RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_NEXT_SONG_PREVIEW_POSITION,
            ).coerceIn(
                RootConstants.MEDIA_CARD_LYRIC_NEXT_SONG_PREVIEW_POSITION_LEFT,
                RootConstants.MEDIA_CARD_LYRIC_NEXT_SONG_PREVIEW_POSITION_RIGHT,
            ),
            blurMode = blurMode,
            blurMinRadius = minOf(rawBlurRange.first, rawBlurRange.second),
            blurMaxRadius = maxOf(rawBlurRange.first, rawBlurRange.second),
        )
    }

    private fun booleanValue(
        prefs: SharedPreferences?,
        key: String,
        defaultValue: Boolean,
    ): Boolean = runCatching { prefs?.getBoolean(key, defaultValue) }
        .getOrNull() ?: defaultValue

    private fun intValue(
        prefs: SharedPreferences?,
        key: String,
        defaultValue: Int,
    ): Int = runCatching { prefs?.getInt(key, defaultValue) }
        .getOrNull() ?: defaultValue

    private fun floatValue(
        prefs: SharedPreferences?,
        key: String,
        defaultValue: Float,
    ): Float = runCatching { prefs?.getFloat(key, defaultValue) }
        .getOrNull() ?: defaultValue
}
