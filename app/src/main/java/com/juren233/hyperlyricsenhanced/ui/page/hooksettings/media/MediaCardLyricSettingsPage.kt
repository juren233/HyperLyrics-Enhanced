/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.hooksettings.media

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.root.mediacard.MediaCardLyricPreferences
import com.juren233.hyperlyricsenhanced.ui.component.FloatRangeInputDialog
import com.juren233.hyperlyricsenhanced.ui.component.NumberInputDialog
import com.juren233.hyperlyricsenhanced.ui.component.NumberRangeInputDialog
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.XposedLyricSettingPage
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.rememberHookConfigSaver
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.rememberHookPrefs
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MediaCardLyricSettingsPage() {
    val prefs = rememberHookPrefs()
    val saveConfig = rememberHookConfigSaver(prefs)
    val initial = remember(prefs) { MediaCardLyricPreferences.read(prefs) }

    var mainTextSize by remember { mutableIntStateOf(initial.mainTextSize) }
    var backingTextSize by remember { mutableIntStateOf(initial.backingTextSize) }
    var translationTextSize by remember { mutableIntStateOf(initial.translationTextSize) }
    var translationDisplayMode by remember {
        mutableIntStateOf(initial.translationDisplayMode)
    }
    var translationFallback by remember { mutableStateOf(initial.translationFallback) }
    var swapTranslation by remember { mutableStateOf(initial.swapTranslation) }
    var duetLyrics by remember { mutableStateOf(initial.duetLyrics) }
    var centerNonDuetSong by remember { mutableStateOf(initial.centerNonDuetSong) }
    var centerGroupVocals by remember { mutableStateOf(initial.centerGroupVocals) }
    var nextSongPreview by remember { mutableStateOf(initial.nextSongPreview) }
    var nextSongPreviewPosition by remember {
        mutableIntStateOf(initial.nextSongPreviewPosition)
    }
    var blurMode by remember { mutableIntStateOf(initial.blurMode) }
    val initialNativeRange = remember(prefs) {
        val minRadius = prefs.getFloat(
            RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_NATIVE_BLUR_MIN_RADIUS_DP,
            RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_NATIVE_BLUR_MIN_RADIUS_DP,
        ).coerceIn(
            RootConstants.MIN_HOOK_MEDIA_CARD_LYRIC_NATIVE_BLUR_RADIUS_DP,
            RootConstants.MAX_HOOK_MEDIA_CARD_LYRIC_NATIVE_BLUR_RADIUS_DP,
        )
        val maxRadius = prefs.getFloat(
            RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_NATIVE_BLUR_MAX_RADIUS_DP,
            RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_NATIVE_BLUR_MAX_RADIUS_DP,
        ).coerceIn(
            RootConstants.MIN_HOOK_MEDIA_CARD_LYRIC_NATIVE_BLUR_RADIUS_DP,
            RootConstants.MAX_HOOK_MEDIA_CARD_LYRIC_NATIVE_BLUR_RADIUS_DP,
        )
        minOf(minRadius, maxRadius) to maxOf(minRadius, maxRadius)
    }
    var nativeBlurMinRadiusDp by remember {
        mutableFloatStateOf(initialNativeRange.first)
    }
    var nativeBlurMaxRadiusDp by remember {
        mutableFloatStateOf(initialNativeRange.second)
    }
    val initialAdvancedRange = remember(prefs) {
        val minRadius = prefs.getInt(
            RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_ADVANCED_BLUR_MIN_RADIUS_PX,
            RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_ADVANCED_BLUR_MIN_RADIUS_PX,
        ).coerceIn(
            RootConstants.MIN_HOOK_MEDIA_CARD_LYRIC_ADVANCED_BLUR_RADIUS_PX,
            RootConstants.MAX_HOOK_MEDIA_CARD_LYRIC_ADVANCED_BLUR_RADIUS_PX,
        )
        val maxRadius = prefs.getInt(
            RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_ADVANCED_BLUR_MAX_RADIUS_PX,
            RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_ADVANCED_BLUR_MAX_RADIUS_PX,
        ).coerceIn(
            RootConstants.MIN_HOOK_MEDIA_CARD_LYRIC_ADVANCED_BLUR_RADIUS_PX,
            RootConstants.MAX_HOOK_MEDIA_CARD_LYRIC_ADVANCED_BLUR_RADIUS_PX,
        )
        minOf(minRadius, maxRadius) to maxOf(minRadius, maxRadius)
    }
    var advancedBlurMinRadiusPx by remember { mutableIntStateOf(initialAdvancedRange.first) }
    var advancedBlurMaxRadiusPx by remember { mutableIntStateOf(initialAdvancedRange.second) }

    var showMainTextSizeDialog by remember { mutableStateOf(false) }
    var showBackingTextSizeDialog by remember { mutableStateOf(false) }
    var showTranslationTextSizeDialog by remember { mutableStateOf(false) }
    var showBlurValuesDialog by remember { mutableStateOf(false) }

    NumberInputDialog(
        show = showMainTextSizeDialog,
        title = stringResource(R.string.title_aod_main_text_size),
        label = stringResource(
            R.string.format_aod_text_size_placeholder,
            RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_MAIN_TEXT_SIZE,
            RootConstants.MIN_HOOK_AOD_MAIN_TEXT_SIZE,
            RootConstants.MAX_HOOK_AOD_MAIN_TEXT_SIZE,
        ),
        initialValue = mainTextSize,
        min = RootConstants.MIN_HOOK_AOD_MAIN_TEXT_SIZE,
        max = RootConstants.MAX_HOOK_AOD_MAIN_TEXT_SIZE,
        onDismiss = { showMainTextSizeDialog = false },
        onConfirm = {
            mainTextSize = it
            saveConfig(RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_MAIN_TEXT_SIZE, it)
        },
    )
    NumberInputDialog(
        show = showBackingTextSizeDialog,
        title = stringResource(R.string.title_aod_backing_text_size),
        label = stringResource(
            R.string.format_aod_text_size_placeholder,
            RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_BACKING_TEXT_SIZE,
            RootConstants.MIN_HOOK_AOD_BACKING_TEXT_SIZE,
            RootConstants.MAX_HOOK_AOD_BACKING_TEXT_SIZE,
        ),
        initialValue = backingTextSize,
        min = RootConstants.MIN_HOOK_AOD_BACKING_TEXT_SIZE,
        max = RootConstants.MAX_HOOK_AOD_BACKING_TEXT_SIZE,
        onDismiss = { showBackingTextSizeDialog = false },
        onConfirm = {
            backingTextSize = it
            saveConfig(RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_BACKING_TEXT_SIZE, it)
        },
    )
    NumberInputDialog(
        show = showTranslationTextSizeDialog,
        title = stringResource(R.string.title_aod_translation_text_size),
        label = stringResource(
            R.string.format_aod_text_size_placeholder,
            RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_TRANSLATION_TEXT_SIZE,
            RootConstants.MIN_HOOK_AOD_TRANSLATION_TEXT_SIZE,
            RootConstants.MAX_HOOK_AOD_TRANSLATION_TEXT_SIZE,
        ),
        initialValue = translationTextSize,
        min = RootConstants.MIN_HOOK_AOD_TRANSLATION_TEXT_SIZE,
        max = RootConstants.MAX_HOOK_AOD_TRANSLATION_TEXT_SIZE,
        onDismiss = { showTranslationTextSizeDialog = false },
        onConfirm = {
            translationTextSize = it
            saveConfig(RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_TRANSLATION_TEXT_SIZE, it)
        },
    )
    FloatRangeInputDialog(
        show = showBlurValuesDialog &&
            blurMode == RootConstants.MEDIA_CARD_LYRIC_BLUR_EFFECT_NATIVE,
        title = stringResource(R.string.title_apple_music_lyrics_blur_custom_values),
        minLabel = stringResource(
            R.string.label_apple_music_lyrics_blur_min_value,
            "dp",
            RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_NATIVE_BLUR_MIN_RADIUS_DP,
            RootConstants.MIN_HOOK_MEDIA_CARD_LYRIC_NATIVE_BLUR_RADIUS_DP,
            RootConstants.MAX_HOOK_MEDIA_CARD_LYRIC_NATIVE_BLUR_RADIUS_DP,
        ),
        maxLabel = stringResource(
            R.string.label_apple_music_lyrics_blur_max_value,
            "dp",
            RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_NATIVE_BLUR_MAX_RADIUS_DP,
            RootConstants.MIN_HOOK_MEDIA_CARD_LYRIC_NATIVE_BLUR_RADIUS_DP,
            RootConstants.MAX_HOOK_MEDIA_CARD_LYRIC_NATIVE_BLUR_RADIUS_DP,
        ),
        initialMinValue = nativeBlurMinRadiusDp,
        initialMaxValue = nativeBlurMaxRadiusDp,
        allowedMin = RootConstants.MIN_HOOK_MEDIA_CARD_LYRIC_NATIVE_BLUR_RADIUS_DP,
        allowedMax = RootConstants.MAX_HOOK_MEDIA_CARD_LYRIC_NATIVE_BLUR_RADIUS_DP,
        onDismiss = { showBlurValuesDialog = false },
        onConfirm = { minRadius, maxRadius ->
            nativeBlurMinRadiusDp = minRadius
            nativeBlurMaxRadiusDp = maxRadius
            saveConfig(
                RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_NATIVE_BLUR_MIN_RADIUS_DP,
                minRadius,
            )
            saveConfig(
                RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_NATIVE_BLUR_MAX_RADIUS_DP,
                maxRadius,
            )
        },
    )
    NumberRangeInputDialog(
        show = showBlurValuesDialog &&
            blurMode == RootConstants.MEDIA_CARD_LYRIC_BLUR_EFFECT_ADVANCED_MATERIAL,
        title = stringResource(R.string.title_apple_music_lyrics_blur_custom_values),
        minLabel = stringResource(
            R.string.label_apple_music_lyrics_blur_min_value,
            "px",
            RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_ADVANCED_BLUR_MIN_RADIUS_PX,
            RootConstants.MIN_HOOK_MEDIA_CARD_LYRIC_ADVANCED_BLUR_RADIUS_PX,
            RootConstants.MAX_HOOK_MEDIA_CARD_LYRIC_ADVANCED_BLUR_RADIUS_PX,
        ),
        maxLabel = stringResource(
            R.string.label_apple_music_lyrics_blur_max_value,
            "px",
            RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_ADVANCED_BLUR_MAX_RADIUS_PX,
            RootConstants.MIN_HOOK_MEDIA_CARD_LYRIC_ADVANCED_BLUR_RADIUS_PX,
            RootConstants.MAX_HOOK_MEDIA_CARD_LYRIC_ADVANCED_BLUR_RADIUS_PX,
        ),
        initialMinValue = advancedBlurMinRadiusPx,
        initialMaxValue = advancedBlurMaxRadiusPx,
        allowedMin = RootConstants.MIN_HOOK_MEDIA_CARD_LYRIC_ADVANCED_BLUR_RADIUS_PX,
        allowedMax = RootConstants.MAX_HOOK_MEDIA_CARD_LYRIC_ADVANCED_BLUR_RADIUS_PX,
        onDismiss = { showBlurValuesDialog = false },
        onConfirm = { minRadius, maxRadius ->
            advancedBlurMinRadiusPx = minRadius
            advancedBlurMaxRadiusPx = maxRadius
            saveConfig(
                RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_ADVANCED_BLUR_MIN_RADIUS_PX,
                minRadius,
            )
            saveConfig(
                RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_ADVANCED_BLUR_MAX_RADIUS_PX,
                maxRadius,
            )
        },
    )

    XposedLyricSettingPage(title = stringResource(R.string.title_lyric_settings)) {
        item(key = "media_card_lyric_text") {
            SmallTitle(text = stringResource(R.string.title_text))
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .fillMaxWidth()
            ) {
                Column {
                    ArrowPreference(
                        title = stringResource(R.string.title_aod_main_text_size),
                        endActions = { MediaCardTextSizeValue(mainTextSize) },
                        onClick = { showMainTextSizeDialog = true },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.title_aod_backing_text_size),
                        endActions = { MediaCardTextSizeValue(backingTextSize) },
                        onClick = { showBackingTextSizeDialog = true },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.title_aod_translation_text_size),
                        endActions = { MediaCardTextSizeValue(translationTextSize) },
                        onClick = { showTranslationTextSizeDialog = true },
                    )
                }
            }
        }
        item(key = "media_card_lyric_display") {
            SmallTitle(text = stringResource(R.string.title_aod_display_style))
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .fillMaxWidth()
            ) {
                Column {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.title_translation_pronunciation_display),
                        items = listOf(
                            stringResource(R.string.option_translation_pronunciation_off),
                            stringResource(R.string.option_translation_pronunciation_translation),
                            stringResource(R.string.option_translation_pronunciation_pronunciation),
                        ),
                        selectedIndex = translationDisplayMode,
                        onSelectedIndexChange = {
                            translationDisplayMode = it
                            saveConfig(
                                RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_TRANSLATION_DISPLAY,
                                it,
                            )
                        },
                    )
                    AnimatedVisibility(
                        visible = translationDisplayMode !=
                            RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF,
                        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
                    ) {
                        Column {
                            SwitchPreference(
                                title = stringResource(
                                    R.string.title_translation_pronunciation_fallback
                                ),
                                checked = translationFallback,
                                onCheckedChange = {
                                    translationFallback = it
                                    saveConfig(
                                        RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_TRANSLATION_FALLBACK,
                                        it,
                                    )
                                },
                            )
                            SwitchPreference(
                                title = stringResource(R.string.title_swap_translation),
                                checked = swapTranslation,
                                onCheckedChange = {
                                    swapTranslation = it
                                    saveConfig(
                                        RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_SWAP_TRANSLATION,
                                        it,
                                    )
                                },
                            )
                        }
                    }
                    SwitchPreference(
                        title = stringResource(R.string.title_aod_duet_lyrics),
                        summary = stringResource(R.string.summary_aod_duet_lyrics),
                        checked = duetLyrics,
                        onCheckedChange = {
                            duetLyrics = it
                            saveConfig(
                                RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_DUET_LYRICS,
                                it,
                            )
                        },
                    )
                    AnimatedVisibility(
                        visible = duetLyrics,
                        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
                    ) {
                        Column {
                            SwitchPreference(
                                title = stringResource(R.string.title_aod_center_non_duet_song),
                                checked = centerNonDuetSong,
                                onCheckedChange = {
                                    centerNonDuetSong = it
                                    saveConfig(
                                        RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_CENTER_NON_DUET_SONG,
                                        it,
                                    )
                                },
                            )
                            SwitchPreference(
                                title = stringResource(R.string.title_center_group_vocals),
                                checked = centerGroupVocals,
                                onCheckedChange = {
                                    centerGroupVocals = it
                                    saveConfig(
                                        RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_CENTER_GROUP_VOCALS,
                                        it,
                                    )
                                },
                            )
                        }
                    }
                    SwitchPreference(
                        title = stringResource(R.string.title_aod_next_song_preview),
                        summary = stringResource(R.string.summary_island_next_song_preview),
                        checked = nextSongPreview,
                        onCheckedChange = {
                            nextSongPreview = it
                            saveConfig(
                                RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_NEXT_SONG_PREVIEW,
                                it,
                            )
                        },
                    )
                    AnimatedVisibility(
                        visible = nextSongPreview,
                        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
                    ) {
                        OverlayDropdownPreference(
                            title = stringResource(R.string.title_aod_next_song_preview_position),
                            items = listOf(
                                stringResource(R.string.option_aod_song_info_position_left),
                                stringResource(R.string.option_aod_song_info_position_center),
                                stringResource(R.string.option_aod_song_info_position_right),
                            ),
                            selectedIndex = nextSongPreviewPosition,
                            onSelectedIndexChange = {
                                nextSongPreviewPosition = it
                                saveConfig(
                                    RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_NEXT_SONG_PREVIEW_POSITION,
                                    it,
                                )
                            },
                        )
                    }
                }
            }
        }
        item(key = "media_card_lyric_blur") {
            SmallTitle(text = stringResource(R.string.title_media_card_lyric_blur))
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .fillMaxWidth()
            ) {
                Column {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.title_apple_music_lyrics_blur_effect),
                        items = listOf(
                            stringResource(R.string.option_apple_music_lyrics_blur_effect_off),
                            stringResource(R.string.option_apple_music_lyrics_blur_effect_native),
                            stringResource(
                                R.string.option_apple_music_lyrics_blur_effect_advanced_material
                            ),
                        ),
                        selectedIndex = blurMode,
                        onSelectedIndexChange = {
                            blurMode = it
                            saveConfig(
                                RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_BLUR_EFFECT,
                                it,
                            )
                        },
                    )
                    AnimatedVisibility(
                        visible = blurMode != RootConstants.MEDIA_CARD_LYRIC_BLUR_EFFECT_OFF,
                        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
                    ) {
                        ArrowPreference(
                            title = stringResource(
                                R.string.title_apple_music_lyrics_blur_custom_values
                            ),
                            endActions = {
                                if (blurMode == RootConstants.MEDIA_CARD_LYRIC_BLUR_EFFECT_NATIVE) {
                                    MediaCardBlurRangeValue(
                                        minRadius = nativeBlurMinRadiusDp,
                                        maxRadius = nativeBlurMaxRadiusDp,
                                        unit = "dp",
                                    )
                                } else {
                                    MediaCardBlurRangeValue(
                                        minRadius = advancedBlurMinRadiusPx.toFloat(),
                                        maxRadius = advancedBlurMaxRadiusPx.toFloat(),
                                        unit = "px",
                                    )
                                }
                            },
                            onClick = { showBlurValuesDialog = true },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaCardTextSizeValue(value: Int) {
    Text(
        text = value.toString(),
        fontSize = MiuixTheme.textStyles.body2.fontSize,
        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
    )
}

@Composable
private fun MediaCardBlurRangeValue(
    minRadius: Float,
    maxRadius: Float,
    unit: String,
) {
    Text(
        text = "${minRadius.displayValue()}–${maxRadius.displayValue()} $unit",
        fontSize = MiuixTheme.textStyles.body2.fontSize,
        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
    )
}

private fun Float.displayValue(): String =
    if (this % 1f == 0f) toInt().toString() else toString()
