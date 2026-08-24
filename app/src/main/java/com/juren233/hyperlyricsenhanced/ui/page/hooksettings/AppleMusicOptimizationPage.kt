package com.juren233.hyperlyricsenhanced.ui.page.hooksettings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.ui.component.FloatRangeInputDialog
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
fun AppleMusicOptimizationPage(
    outerPadding: PaddingValues = PaddingValues(),
    showNavigationIcon: Boolean = true,
) {
    val prefs = rememberHookPrefs()
    val saveConfig = rememberHookConfigSaver(prefs)
    var contentUiLanguage by remember {
        mutableIntStateOf(
            prefs.getInt(
                RootConstants.KEY_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE,
            ).coerceIn(
                RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_NONE,
                RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_JA_JP,
            )
        )
    }
    var overrideAccountLanguage by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_APPLE_MUSIC_OVERRIDE_ACCOUNT_LANGUAGE,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_OVERRIDE_ACCOUNT_LANGUAGE,
            )
        )
    }
    var localizedMetadataCache by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_APPLE_MUSIC_LOCALIZED_METADATA_CACHE,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LOCALIZED_METADATA_CACHE,
            )
        )
    }
    var restoreCjkOriginalMetadata by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_APPLE_MUSIC_RESTORE_CJK_ORIGINAL_METADATA,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_RESTORE_CJK_ORIGINAL_METADATA,
            )
        )
    }
    var notificationOpenFullPlayer by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_APPLE_MUSIC_NOTIFICATION_OPEN_FULL_PLAYER,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_NOTIFICATION_OPEN_FULL_PLAYER,
            )
        )
    }
    var volumeBalance by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_APPLE_MUSIC_VOLUME_BALANCE,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_VOLUME_BALANCE,
            )
        )
    }
    var simplifyTraditionalLyrics by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_LYRICS,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_LYRICS,
            )
        )
    }
    var nativeOnlineTranslation by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION,
            )
        )
    }
    var fillMissingLyrics by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_APPLE_MUSIC_FILL_MISSING_LYRICS,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_FILL_MISSING_LYRICS,
            )
        )
    }
    var hideMandarinPinyin by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_APPLE_MUSIC_HIDE_MANDARIN_PINYIN,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_HIDE_MANDARIN_PINYIN,
            )
        )
    }
    var lunaBeatWordLyrics by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_APPLE_MUSIC_LUNABEAT_WORD_LYRICS,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LUNABEAT_WORD_LYRICS,
            )
        )
    }
    var lyricsBlurEffect by remember {
        mutableIntStateOf(
            prefs.getInt(
                RootConstants.KEY_HOOK_APPLE_MUSIC_LYRICS_BLUR_EFFECT,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LYRICS_BLUR_EFFECT,
            ).coerceIn(
                RootConstants.APPLE_MUSIC_LYRICS_BLUR_EFFECT_OFF,
                RootConstants.APPLE_MUSIC_LYRICS_BLUR_EFFECT_ADVANCED_MATERIAL,
            )
        )
    }
    val initialNativeLyricsBlurRadiusRange = remember(prefs) {
        val configuredMin = prefs.getFloat(
            RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MIN_RADIUS_DP,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MIN_RADIUS_DP,
        ).coerceIn(
            RootConstants.MIN_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_RADIUS_DP,
            RootConstants.MAX_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_RADIUS_DP,
        )
        val configuredMax = prefs.getFloat(
            RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MAX_RADIUS_DP,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MAX_RADIUS_DP,
        ).coerceIn(
            RootConstants.MIN_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_RADIUS_DP,
            RootConstants.MAX_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_RADIUS_DP,
        )
        minOf(configuredMin, configuredMax) to maxOf(configuredMin, configuredMax)
    }
    var nativeLyricsBlurMinRadiusDp by remember {
        mutableFloatStateOf(initialNativeLyricsBlurRadiusRange.first)
    }
    var nativeLyricsBlurMaxRadiusDp by remember {
        mutableFloatStateOf(initialNativeLyricsBlurRadiusRange.second)
    }
    val initialAdvancedLyricsBlurRadiusRange = remember(prefs) {
        val configuredMin = prefs.getInt(
            RootConstants.KEY_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MIN_RADIUS_PX,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MIN_RADIUS_PX,
        ).coerceIn(
            RootConstants.MIN_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_RADIUS_PX,
            RootConstants.MAX_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_RADIUS_PX,
        )
        val configuredMax = prefs.getInt(
            RootConstants.KEY_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MAX_RADIUS_PX,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MAX_RADIUS_PX,
        ).coerceIn(
            RootConstants.MIN_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_RADIUS_PX,
            RootConstants.MAX_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_RADIUS_PX,
        )
        minOf(configuredMin, configuredMax) to maxOf(configuredMin, configuredMax)
    }
    var advancedLyricsBlurMinRadiusPx by remember {
        mutableIntStateOf(initialAdvancedLyricsBlurRadiusRange.first)
    }
    var advancedLyricsBlurMaxRadiusPx by remember {
        mutableIntStateOf(initialAdvancedLyricsBlurRadiusRange.second)
    }
    var showLyricsBlurValuesDialog by remember { mutableStateOf(false) }
    var followSystemFontWeight by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_APPLE_MUSIC_FOLLOW_SYSTEM_FONT_WEIGHT,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_FOLLOW_SYSTEM_FONT_WEIGHT,
            )
        )
    }
    val languageOptions = listOf(
        stringResource(R.string.option_apple_music_content_ui_language_none),
        stringResource(R.string.option_apple_music_content_ui_language_zh_hans_cn),
        stringResource(R.string.option_apple_music_content_ui_language_zh_hans_us),
        stringResource(R.string.option_apple_music_content_ui_language_zh_hant_hk),
        stringResource(R.string.option_apple_music_content_ui_language_zh_hant_tw),
        stringResource(R.string.option_apple_music_content_ui_language_ko_kr),
        stringResource(R.string.option_apple_music_content_ui_language_ja_jp),
    )
    val regionReplacementEnabled =
        contentUiLanguage != RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_NONE &&
            overrideAccountLanguage
    val metadataLookupEnabled = regionReplacementEnabled || restoreCjkOriginalMetadata
    val lyricsBlurEffectOptions = listOf(
        stringResource(R.string.option_apple_music_lyrics_blur_effect_off),
        stringResource(R.string.option_apple_music_lyrics_blur_effect_native),
        stringResource(R.string.option_apple_music_lyrics_blur_effect_advanced_material),
    )

    FloatRangeInputDialog(
        show = showLyricsBlurValuesDialog &&
            lyricsBlurEffect == RootConstants.APPLE_MUSIC_LYRICS_BLUR_EFFECT_NATIVE,
        title = stringResource(R.string.title_apple_music_lyrics_blur_custom_values),
        minLabel = stringResource(
            R.string.label_apple_music_lyrics_blur_min_value,
            "dp",
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MIN_RADIUS_DP,
            RootConstants.MIN_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_RADIUS_DP,
            RootConstants.MAX_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_RADIUS_DP,
        ),
        maxLabel = stringResource(
            R.string.label_apple_music_lyrics_blur_max_value,
            "dp",
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MAX_RADIUS_DP,
            RootConstants.MIN_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_RADIUS_DP,
            RootConstants.MAX_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_RADIUS_DP,
        ),
        initialMinValue = nativeLyricsBlurMinRadiusDp,
        initialMaxValue = nativeLyricsBlurMaxRadiusDp,
        allowedMin = RootConstants.MIN_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_RADIUS_DP,
        allowedMax = RootConstants.MAX_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_RADIUS_DP,
        onDismiss = { showLyricsBlurValuesDialog = false },
        onConfirm = { minRadiusDp, maxRadiusDp ->
            nativeLyricsBlurMinRadiusDp = minRadiusDp
            nativeLyricsBlurMaxRadiusDp = maxRadiusDp
            saveConfig(
                RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MIN_RADIUS_DP,
                minRadiusDp,
            )
            saveConfig(
                RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MAX_RADIUS_DP,
                maxRadiusDp,
            )
        },
    )
    NumberRangeInputDialog(
        show = showLyricsBlurValuesDialog &&
            lyricsBlurEffect ==
                RootConstants.APPLE_MUSIC_LYRICS_BLUR_EFFECT_ADVANCED_MATERIAL,
        title = stringResource(R.string.title_apple_music_lyrics_blur_custom_values),
        minLabel = stringResource(
            R.string.label_apple_music_lyrics_blur_min_value,
            "px",
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MIN_RADIUS_PX,
            RootConstants.MIN_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_RADIUS_PX,
            RootConstants.MAX_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_RADIUS_PX,
        ),
        maxLabel = stringResource(
            R.string.label_apple_music_lyrics_blur_max_value,
            "px",
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MAX_RADIUS_PX,
            RootConstants.MIN_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_RADIUS_PX,
            RootConstants.MAX_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_RADIUS_PX,
        ),
        initialMinValue = advancedLyricsBlurMinRadiusPx,
        initialMaxValue = advancedLyricsBlurMaxRadiusPx,
        allowedMin = RootConstants.MIN_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_RADIUS_PX,
        allowedMax = RootConstants.MAX_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_RADIUS_PX,
        onDismiss = { showLyricsBlurValuesDialog = false },
        onConfirm = { minRadiusPx, maxRadiusPx ->
            advancedLyricsBlurMinRadiusPx = minRadiusPx
            advancedLyricsBlurMaxRadiusPx = maxRadiusPx
            saveConfig(
                RootConstants.KEY_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MIN_RADIUS_PX,
                minRadiusPx,
            )
            saveConfig(
                RootConstants.KEY_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MAX_RADIUS_PX,
                maxRadiusPx,
            )
        },
    )

    XposedLyricSettingPage(
        title = stringResource(R.string.title_apple_music_optimization_page),
        subtitle = stringResource(R.string.summary_apple_music_optimization_page),
        outerPadding = outerPadding,
        showNavigationIcon = showNavigationIcon,
    ) {
        item(key = "app_content_title") {
            SmallTitle(text = stringResource(R.string.title_apple_music_app_content))
        }
        item(key = "apple_music_content_ui_language") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
            ) {
                OverlayDropdownPreference(
                    title = stringResource(R.string.title_apple_music_content_ui_language),
                    summary = stringResource(R.string.summary_apple_music_content_ui_language),
                    items = languageOptions,
                    selectedIndex = contentUiLanguage,
                    onSelectedIndexChange = { selected ->
                        val value = selected.coerceIn(
                            RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_NONE,
                            RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_JA_JP,
                        )
                        contentUiLanguage = value
                        saveConfig(RootConstants.KEY_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE, value)
                    },
                )
                if (contentUiLanguage != RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_NONE) {
                    SwitchPreference(
                        title = stringResource(
                            R.string.title_apple_music_override_account_language
                        ),
                        checked = overrideAccountLanguage,
                        onCheckedChange = { enabled ->
                            overrideAccountLanguage = enabled
                            saveConfig(
                                RootConstants.KEY_HOOK_APPLE_MUSIC_OVERRIDE_ACCOUNT_LANGUAGE,
                                enabled,
                            )
                        },
                    )
                }
                SwitchPreference(
                    title = stringResource(
                        R.string.title_apple_music_restore_cjk_original_metadata
                    ),
                    checked = restoreCjkOriginalMetadata,
                    onCheckedChange = { enabled ->
                        restoreCjkOriginalMetadata = enabled
                        saveConfig(
                            RootConstants.KEY_HOOK_APPLE_MUSIC_RESTORE_CJK_ORIGINAL_METADATA,
                            enabled,
                        )
                    },
                )
                if (metadataLookupEnabled) {
                    SwitchPreference(
                        title = stringResource(
                            R.string.title_apple_music_localized_metadata_cache
                        ),
                        summary = stringResource(
                            R.string.summary_apple_music_localized_metadata_cache
                        ),
                        checked = localizedMetadataCache,
                        onCheckedChange = { enabled ->
                            localizedMetadataCache = enabled
                            saveConfig(
                                RootConstants.KEY_HOOK_APPLE_MUSIC_LOCALIZED_METADATA_CACHE,
                                enabled,
                            )
                        },
                    )
                }
            }
        }
        item(key = "app_features_title") {
            SmallTitle(text = stringResource(R.string.title_apple_music_app_features))
        }
        item(key = "apple_music_notification_open_full_player") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
            ) {
                SwitchPreference(
                    title = stringResource(R.string.title_apple_music_volume_balance),
                    summary = stringResource(R.string.summary_apple_music_volume_balance),
                    checked = volumeBalance,
                    onCheckedChange = { enabled ->
                        volumeBalance = enabled
                        saveConfig(
                            RootConstants.KEY_HOOK_APPLE_MUSIC_VOLUME_BALANCE,
                            enabled,
                        )
                    },
                )
                SwitchPreference(
                    title = stringResource(
                        R.string.title_apple_music_notification_open_full_player
                    ),
                    checked = notificationOpenFullPlayer,
                    onCheckedChange = { enabled ->
                        notificationOpenFullPlayer = enabled
                        saveConfig(
                            RootConstants.KEY_HOOK_APPLE_MUSIC_NOTIFICATION_OPEN_FULL_PLAYER,
                            enabled,
                        )
                    },
                )
                SwitchPreference(
                    title = stringResource(
                        R.string.title_apple_music_fill_missing_lyrics
                    ),
                    summary = stringResource(
                        R.string.summary_apple_music_fill_missing_lyrics
                    ),
                    checked = fillMissingLyrics,
                    onCheckedChange = { enabled ->
                        fillMissingLyrics = enabled
                        saveConfig(
                            RootConstants.KEY_HOOK_APPLE_MUSIC_FILL_MISSING_LYRICS,
                            enabled,
                        )
                    },
                )
                SwitchPreference(
                    title = stringResource(
                        R.string.title_apple_music_native_online_translation
                    ),
                    summary = stringResource(
                        R.string.summary_apple_music_native_online_translation
                    ),
                    checked = nativeOnlineTranslation,
                    onCheckedChange = { enabled ->
                        nativeOnlineTranslation = enabled
                        saveConfig(
                            RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION,
                            enabled,
                        )
                    },
                )
                SwitchPreference(
                    title = stringResource(
                        R.string.title_apple_music_hide_mandarin_pinyin
                    ),
                    checked = hideMandarinPinyin,
                    onCheckedChange = { enabled ->
                        hideMandarinPinyin = enabled
                        saveConfig(
                            RootConstants.KEY_HOOK_APPLE_MUSIC_HIDE_MANDARIN_PINYIN,
                            enabled,
                        )
                    },
                )
                SwitchPreference(
                    title = stringResource(
                        R.string.title_apple_music_lunabeat_word_lyrics
                    ),
                    summary = stringResource(
                        R.string.summary_apple_music_lunabeat_word_lyrics
                    ),
                    checked = lunaBeatWordLyrics,
                    onCheckedChange = { enabled ->
                        lunaBeatWordLyrics = enabled
                        saveConfig(
                            RootConstants.KEY_HOOK_APPLE_MUSIC_LUNABEAT_WORD_LYRICS,
                            enabled,
                        )
                    },
                )
            }
        }
        item(key = "app_display_title") {
            SmallTitle(text = stringResource(R.string.title_apple_music_app_display))
        }
        item(key = "apple_music_app_display") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
            ) {
                SwitchPreference(
                    title = stringResource(
                        R.string.title_apple_music_simplify_traditional_lyrics
                    ),
                    checked = simplifyTraditionalLyrics,
                    onCheckedChange = { enabled ->
                        simplifyTraditionalLyrics = enabled
                        saveConfig(
                            RootConstants.KEY_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_LYRICS,
                            enabled,
                        )
                    },
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.title_apple_music_lyrics_blur_effect),
                    items = lyricsBlurEffectOptions,
                    selectedIndex = lyricsBlurEffect,
                    onSelectedIndexChange = { selected ->
                        val value = selected.coerceIn(
                            RootConstants.APPLE_MUSIC_LYRICS_BLUR_EFFECT_OFF,
                            RootConstants.APPLE_MUSIC_LYRICS_BLUR_EFFECT_ADVANCED_MATERIAL,
                        )
                        lyricsBlurEffect = value
                        saveConfig(
                            RootConstants.KEY_HOOK_APPLE_MUSIC_LYRICS_BLUR_EFFECT,
                            value,
                        )
                    },
                )
                AnimatedVisibility(
                    visible = lyricsBlurEffect !=
                        RootConstants.APPLE_MUSIC_LYRICS_BLUR_EFFECT_OFF
                ) {
                    ArrowPreference(
                        title = stringResource(
                            R.string.title_apple_music_lyrics_blur_custom_values
                        ),
                        endActions = {
                            if (
                                lyricsBlurEffect ==
                                RootConstants.APPLE_MUSIC_LYRICS_BLUR_EFFECT_NATIVE
                            ) {
                                AppleLyricsBlurRangeValue(
                                    minRadius = nativeLyricsBlurMinRadiusDp,
                                    maxRadius = nativeLyricsBlurMaxRadiusDp,
                                    unit = "dp",
                                )
                            } else {
                                AppleLyricsBlurRangeValue(
                                    minRadius = advancedLyricsBlurMinRadiusPx.toFloat(),
                                    maxRadius = advancedLyricsBlurMaxRadiusPx.toFloat(),
                                    unit = "px",
                                )
                            }
                        },
                        onClick = { showLyricsBlurValuesDialog = true },
                    )
                }
                SwitchPreference(
                    title = stringResource(
                        R.string.title_apple_music_follow_system_font_weight
                    ),
                    checked = followSystemFontWeight,
                    onCheckedChange = { enabled ->
                        followSystemFontWeight = enabled
                        saveConfig(
                            RootConstants.KEY_HOOK_APPLE_MUSIC_FOLLOW_SYSTEM_FONT_WEIGHT,
                            enabled,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun AppleLyricsBlurRangeValue(
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
