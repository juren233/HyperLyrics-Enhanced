/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.contract

/**
 * Apple Music preference keys and limits shared by the core UI and the Apple Music Provider.
 *
 * Core RootConstants/UIConstants delegate here so the provider never reads host internals.
 */
object AppleMusicPreferenceKeys {
    const val CLASSIC_AOD_FOCUS_REFRESH_AUTHORITY =
        "com.juren233.hyperlyricsenhanced.classic_aod_focus_refresh"
    const val DEBUG_APPLE_PRONUNCIATION_DIAGNOSTIC_METHOD =
        "debug_apple_pronunciation_diagnostic"
    const val KEY_HOOK_ENABLE_AOD_LYRICS = "key_hook_enable_aod_lyrics"
    const val KEY_HOOK_APPLE_MUSIC_MATCH_ONLINE_TRANSLATION =
        "key_hook_apple_music_match_online_translation"
    const val KEY_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE =
        "key_hook_apple_music_content_ui_language"
    const val KEY_HOOK_APPLE_MUSIC_OVERRIDE_ACCOUNT_LANGUAGE =
        "key_hook_apple_music_override_account_language"
    const val KEY_HOOK_APPLE_MUSIC_LOCALIZED_METADATA_CACHE =
        "key_hook_apple_music_localized_metadata_cache"
    const val KEY_HOOK_APPLE_MUSIC_RESTORE_CJK_ORIGINAL_METADATA =
        "key_hook_apple_music_restore_cjk_original_metadata"
    const val KEY_HOOK_APPLE_MUSIC_NOTIFICATION_OPEN_FULL_PLAYER =
        "key_hook_apple_music_notification_open_full_player"
    const val KEY_HOOK_APPLE_MUSIC_FORCE_CELLULAR_DATA_ENTRY =
        "key_hook_apple_music_force_cellular_data_entry"
    const val KEY_HOOK_APPLE_MUSIC_VOLUME_BALANCE =
        "key_hook_apple_music_volume_balance"
    const val KEY_HOOK_APPLE_MUSIC_PREVENT_NETWORK_AUTO_SKIP =
        "key_hook_apple_music_prevent_network_auto_skip"
    const val KEY_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_CONTENT =
        "key_hook_apple_music_simplify_traditional_content"
    const val KEY_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION =
        "key_hook_apple_music_native_online_translation"
    const val KEY_HOOK_APPLE_MUSIC_FILL_MISSING_LYRICS =
        "key_hook_apple_music_fill_missing_lyrics"
    const val KEY_HOOK_APPLE_MUSIC_HIDE_MANDARIN_PINYIN =
        "key_hook_apple_music_hide_mandarin_pinyin"
    const val KEY_HOOK_APPLE_MUSIC_LINE_TO_WORD_LYRICS =
        "key_hook_apple_music_upgrade_line_to_word_lyrics"
    const val KEY_HOOK_APPLE_MUSIC_LYRICS_BLUR_EFFECT =
        "key_hook_apple_music_lyrics_blur_effect"
    const val KEY_HOOK_APPLE_MUSIC_LYRICS_BLUR_ANIMATION =
        "key_hook_apple_music_lyrics_blur_animation"
    const val KEY_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MIN_RADIUS_DP =
        "key_hook_apple_music_native_lyrics_blur_min_radius_dp"
    const val KEY_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MAX_RADIUS_DP =
        "key_hook_apple_music_native_lyrics_blur_max_radius_dp"
    const val KEY_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MIN_RADIUS_PX =
        "key_hook_apple_music_advanced_lyrics_blur_min_radius_px"
    const val KEY_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MAX_RADIUS_PX =
        "key_hook_apple_music_advanced_lyrics_blur_max_radius_px"
    const val KEY_HOOK_APPLE_MUSIC_FOLLOW_SYSTEM_FONT =
        "key_hook_apple_music_follow_system_font"
    const val KEY_HOOK_APPLE_MUSIC_FOLLOW_SYSTEM_FONT_WEIGHT =
        "key_hook_apple_music_follow_system_font_weight"
    const val APPLE_MUSIC_CONTENT_UI_LANGUAGE_NONE = 0
    const val APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_CN = 1
    const val APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_US = 2
    const val APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANT_HK = 3
    const val APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANT_TW = 4
    const val APPLE_MUSIC_CONTENT_UI_LANGUAGE_KO_KR = 5
    const val APPLE_MUSIC_CONTENT_UI_LANGUAGE_JA_JP = 6
    const val DEFAULT_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE =
        APPLE_MUSIC_CONTENT_UI_LANGUAGE_NONE
    const val DEFAULT_HOOK_APPLE_MUSIC_OVERRIDE_ACCOUNT_LANGUAGE = false
    const val DEFAULT_HOOK_APPLE_MUSIC_LOCALIZED_METADATA_CACHE = true
    const val DEFAULT_HOOK_APPLE_MUSIC_RESTORE_CJK_ORIGINAL_METADATA = false
    const val DEFAULT_HOOK_APPLE_MUSIC_NOTIFICATION_OPEN_FULL_PLAYER = false
    const val DEFAULT_HOOK_APPLE_MUSIC_FORCE_CELLULAR_DATA_ENTRY = false
    const val DEFAULT_HOOK_APPLE_MUSIC_VOLUME_BALANCE = false
    const val DEFAULT_HOOK_APPLE_MUSIC_PREVENT_NETWORK_AUTO_SKIP = false
    const val DEFAULT_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_CONTENT = false
    const val DEFAULT_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION = false
    const val DEFAULT_HOOK_APPLE_MUSIC_FILL_MISSING_LYRICS = false
    const val DEFAULT_HOOK_APPLE_MUSIC_HIDE_MANDARIN_PINYIN = false
    const val DEFAULT_HOOK_APPLE_MUSIC_LINE_TO_WORD_LYRICS = false
    const val APPLE_MUSIC_LYRICS_BLUR_EFFECT_OFF = 0
    const val DEFAULT_HOOK_APPLE_MUSIC_LYRICS_BLUR_EFFECT =
        APPLE_MUSIC_LYRICS_BLUR_EFFECT_OFF
    const val DEFAULT_HOOK_APPLE_MUSIC_LYRICS_BLUR_ANIMATION = false
    const val DEFAULT_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MIN_RADIUS_DP = 3f
    const val DEFAULT_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MAX_RADIUS_DP = 6.5f
    const val MIN_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_RADIUS_DP = 0f
    const val MAX_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_RADIUS_DP = 100f
    const val DEFAULT_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MIN_RADIUS_PX = 13
    const val DEFAULT_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MAX_RADIUS_PX = 26
    const val MIN_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_RADIUS_PX = 0
    const val MAX_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_RADIUS_PX = 100
    const val DEFAULT_HOOK_APPLE_MUSIC_FOLLOW_SYSTEM_FONT = false
    const val DEFAULT_HOOK_APPLE_MUSIC_FOLLOW_SYSTEM_FONT_WEIGHT = false
    const val DEFAULT_HOOK_ENABLE_AOD_LYRICS = false
    const val DEFAULT_HOOK_APPLE_MUSIC_MATCH_ONLINE_TRANSLATION = false
    const val PREFERENCES_NAME = "com.juren233.hyperlyricsenhanced_preferences"
    const val KEY_FEATURE_ENTRY_APPLE_MUSIC = "key_feature_entry_apple_music"
    const val KEY_HOOK_ONLINE_TRANSLATION_SOURCE_ORDER =
        "key_hook_online_translation_source_order"
    const val KEY_HOOK_ONLINE_TRANSLATION_AUTO_SELECT_BEST_SOURCE =
        "key_hook_online_translation_auto_select_best_source"
    const val KEY_HOOK_ONLINE_TRANSLATION_SOURCE_NETEASE =
        "key_hook_online_translation_source_netease"
    const val KEY_HOOK_ONLINE_TRANSLATION_SOURCE_QQ =
        "key_hook_online_translation_source_qq"
    const val KEY_HOOK_ONLINE_TRANSLATION_SOURCE_KUWO =
        "key_hook_online_translation_source_kuwo"
    const val KEY_HOOK_ONLINE_TRANSLATION_SOURCE_KUGOU =
        "key_hook_online_translation_source_kugou"
    const val KEY_HOOK_ONLINE_SOURCE_AMLL =
        "key_hook_online_source_amll"
    const val KEY_HOOK_ONLINE_SOURCE_LUNABEAT =
        "key_hook_online_source_lunabeat"
    const val KEY_HOOK_ONLINE_TRANSLATION_APP_QISHUI =
        "key_hook_online_translation_app_qishui"
    const val KEY_HOOK_ONLINE_TRANSLATION_APP_SPOTIFY =
        "key_hook_online_translation_app_spotify"
    const val KEY_HOOK_ONLINE_TRANSLATION_APP_SALT =
        "key_hook_online_translation_app_salt"
    const val DEFAULT_HOOK_ONLINE_TRANSLATION_SOURCE_ORDER =
        "AMLL,LB,NE,QM,KUWO,KUGOU"
    const val DEFAULT_HOOK_ONLINE_TRANSLATION_AUTO_SELECT_BEST_SOURCE = true
    const val DEFAULT_HOOK_ONLINE_TRANSLATION_SOURCE_NETEASE = true
    const val DEFAULT_HOOK_ONLINE_TRANSLATION_SOURCE_QQ = true
    const val DEFAULT_HOOK_ONLINE_TRANSLATION_SOURCE_KUWO = false
    const val DEFAULT_HOOK_ONLINE_TRANSLATION_SOURCE_KUGOU = false
    const val DEFAULT_HOOK_ONLINE_SOURCE_AMLL = true
    const val DEFAULT_HOOK_ONLINE_SOURCE_LUNABEAT = true
    const val DEFAULT_HOOK_ONLINE_TRANSLATION_APP_QISHUI = false
    const val DEFAULT_HOOK_ONLINE_TRANSLATION_APP_SPOTIFY = false
    const val DEFAULT_HOOK_ONLINE_TRANSLATION_APP_SALT = false

}
