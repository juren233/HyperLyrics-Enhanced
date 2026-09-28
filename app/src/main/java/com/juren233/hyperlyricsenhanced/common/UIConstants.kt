package com.juren233.hyperlyricsenhanced.common

object UIConstants {
    const val PREF_NAME = "com.juren233.hyperlyricsenhanced_preferences"

    // ================= APP CORE KEYS =================
    const val KEY_WORK_MODE = "key_work_mode"
    const val KEY_SETUP_COMPLETED = "key_setup_completed"
    const val KEY_THEME_MODE = "key_theme_mode"
    const val KEY_APP_LANGUAGE = "key_app_language"
    const val KEY_MONET_COLOR = "key_monet_color"
    const val KEY_PREDICTIVE_BACK_GESTURE = "key_predictive_back_gesture"
    const val KEY_SWIPE_BACK_GESTURE = "key_swipe_back_gesture"
    const val KEY_FLOATING_NAV_BAR = "key_floating_nav_bar"
    const val KEY_TOP_BAR_PROGRESSIVE_BLUR = "key_top_bar_progressive_blur"
    const val KEY_TOP_BAR_PROGRESSIVE_BLUR_MODE = "key_top_bar_progressive_blur_mode"
    const val KEY_PARALLEL_WINDOW_UI = "key_parallel_window_ui"
    const val KEY_EXCLUDE_FROM_RECENTS = "key_exclude_from_recents"
    const val KEY_HIDE_APP_ICON = "key_hide_app_icon"
    const val KEY_SHOW_SETTINGS_ENTRY = "key_show_settings_entry"
    const val KEY_SETTINGS_ENTRY_POSITION = "key_settings_entry_position"
    const val KEY_LOG_LEVEL = "key_log_level"
    const val KEY_LOG_LEVEL_BUILD_KIND = "key_log_level_build_kind"
    const val KEY_LAST_SEEN_VERSION = "key_last_seen_version"
    const val KEY_FEATURE_ENTRY_HYPER_ISLAND = "key_feature_entry_hyper_island"
    const val KEY_FEATURE_ENTRY_AOD_LYRICS = "key_feature_entry_aod_lyrics"
    const val KEY_FEATURE_ENTRY_DYNAMIC_ISLAND = "key_feature_entry_dynamic_island"
    const val KEY_FEATURE_ENTRY_APPLE_MUSIC = "key_feature_entry_apple_music"
    /** 功能入口一次性初始化是否已执行（首次安装 / 首次升级到带入口的版本时写入）。 */
    const val KEY_FEATURE_ENTRY_INITIALIZED = "key_feature_entry_initialized"
    /** 上次观测到的 Apple Music 安装状态：用于检测安装/卸载并重新触发入口的自动开关。 */
    const val KEY_FEATURE_ENTRY_APPLE_MUSIC_INSTALL_SNAPSHOT =
        "key_feature_entry_apple_music_install_snapshot"
    /** 功能入口关闭时，被停用功能自身开关值的暂存前缀（键名 = 前缀 + 功能键）。 */
    const val KEY_FEATURE_ENTRY_STASH_PREFIX = "key_feature_entry_stash_"

    // ================= DEFAULTS =================
    const val DEFAULT_WORK_MODE = 0
    const val DEFAULT_SETUP_COMPLETED = false
    const val DEFAULT_THEME_MODE = 0
    const val DEFAULT_APP_LANGUAGE = 0
    const val DEFAULT_MONET_COLOR = 0
    const val DEFAULT_PREDICTIVE_BACK_GESTURE = false
    const val DEFAULT_SWIPE_BACK_GESTURE = true
    const val DEFAULT_FLOATING_NAV_BAR = false
    const val DEFAULT_TOP_BAR_PROGRESSIVE_BLUR = true
    const val DEFAULT_PARALLEL_WINDOW_UI = true
    const val DEFAULT_EXCLUDE_FROM_RECENTS = false
    const val DEFAULT_HIDE_APP_ICON = false
    const val DEFAULT_SHOW_SETTINGS_ENTRY = true
    const val DEFAULT_LOG_LEVEL = LogLevelPolicy.LEVEL_NORMAL // Legacy fallback only.
}
