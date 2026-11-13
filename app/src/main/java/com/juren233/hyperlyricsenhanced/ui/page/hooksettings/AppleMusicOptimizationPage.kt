package com.juren233.hyperlyricsenhanced.ui.page.hooksettings

import com.juren233.hyperlyricsenhanced.ui.component.SectionEnter
import com.juren233.hyperlyricsenhanced.ui.component.SectionExit
import com.juren233.hyperlyricsenhanced.common.FloatPreferences
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderCatalog
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderRepository
import com.juren233.hyperlyricsenhanced.ui.component.AppSettingsIconButton
import com.juren233.hyperlyricsenhanced.ui.component.CustomFontColorPreview
import com.juren233.hyperlyricsenhanced.ui.component.FloatRangeInputDialog
import com.juren233.hyperlyricsenhanced.ui.component.HeroCard
import com.juren233.hyperlyricsenhanced.ui.component.HeroTone
import com.juren233.hyperlyricsenhanced.ui.component.NumberRangeInputDialog
import com.juren233.hyperlyricsenhanced.ui.component.StatusGlyphs
import com.juren233.hyperlyricsenhanced.ui.component.SwipeableSegmentedTabs
import com.juren233.hyperlyricsenhanced.ui.component.settingsCard
import com.juren233.hyperlyricsenhanced.ui.component.settingsHeroCard
import com.juren233.hyperlyricsenhanced.ui.navigation.LocalNavigator
import com.juren233.hyperlyricsenhanced.ui.navigation.Route
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.XposedLyricSettingPage
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.rememberHookConfigSaver
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.rememberHookPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class AppleMusicPluginGate { Loading, Missing, Disabled, Ready }

/** 本页设置的分类标签，顺序即标签与分页的顺序。 */
private enum class AppleMusicSettingsTab(@param:StringRes val label: Int) {
    Lyrics(R.string.tab_apple_music_lyrics),
    Language(R.string.tab_apple_music_language),
    Playback(R.string.tab_apple_music_playback),
    Features(R.string.tab_apple_music_features),
}

@Composable
fun AppleMusicOptimizationPage(
    outerPadding: PaddingValues = PaddingValues(),
    showNavigationIcon: Boolean = true,
    embeddedInMainPage: Boolean = false,
    collapseTitleToAppName: Boolean = false,
    showRefreshAction: Boolean = false,
    onRefreshClick: () -> Unit = {},
    onAppSettingsClick: () -> Unit = {},
) {
    val prefs = rememberHookPrefs()
    val saveConfig = rememberHookConfigSaver(prefs)
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val coroutineScope = rememberCoroutineScope()
    var lyricSource by remember {
        mutableStateOf(
            prefs.getString(
                RootConstants.KEY_HOOK_LYRIC_SOURCE,
                RootConstants.DEFAULT_HOOK_LYRIC_SOURCE,
            ) ?: RootConstants.DEFAULT_HOOK_LYRIC_SOURCE
        )
    }
    var pluginGate by remember { mutableStateOf(AppleMusicPluginGate.Loading) }
    var pluginVersionName by remember { mutableStateOf<String?>(null) }
    // 插件目录里比已装版本新的兼容版本名，与 Lyricon 配置页的更新按钮同一判断（OfficialProviderItem.updateAvailable）。
    var pluginUpdateVersionName by remember { mutableStateOf<String?>(null) }
    val refreshPluginGate: () -> Unit = {
        coroutineScope.launch {
            val item = withContext(Dispatchers.IO) {
                OfficialProviderRepository.loadInstalledItems(context)
                    .firstOrNull {
                        it.catalog.id == OfficialProviderCatalog.APPLE_MUSIC_PLUGIN_ID
                    }
            }
            pluginVersionName = item?.displayVersionName
            pluginGate = when {
                item == null -> AppleMusicPluginGate.Missing
                !item.enabled -> AppleMusicPluginGate.Disabled
                else -> AppleMusicPluginGate.Ready
            }
            if (item == null) {
                pluginUpdateVersionName = null
                return@launch
            }
            // 读插件目录（联网，失败时回退本地缓存）；不读目标进程状态，避免触发 root 授权。
            val catalogItem = withContext(Dispatchers.IO) {
                runCatching {
                    OfficialProviderRepository.loadItems(context, includeTargetState = false)
                        .firstOrNull { it.catalog.id == OfficialProviderCatalog.APPLE_MUSIC_PLUGIN_ID }
                }.getOrNull()
            }
            pluginUpdateVersionName = catalogItem
                ?.takeIf { it.updateAvailable }
                ?.let { it.updateVersionName ?: context.getString(R.string.unknown) }
        }
    }
    LaunchedEffect(Unit) { refreshPluginGate() }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                RootConstants.KEY_HOOK_LYRIC_SOURCE -> {
                    lyricSource = prefs.getString(
                        RootConstants.KEY_HOOK_LYRIC_SOURCE,
                        RootConstants.DEFAULT_HOOK_LYRIC_SOURCE,
                    ) ?: RootConstants.DEFAULT_HOOK_LYRIC_SOURCE
                }
                OfficialProviderCatalog.installedVersionKey(
                    OfficialProviderCatalog.APPLE_MUSIC_PLUGIN_ID,
                ),
                OfficialProviderCatalog.enabledKey(
                    OfficialProviderCatalog.APPLE_MUSIC_PLUGIN_ID,
                ),
                OfficialProviderCatalog.removedKey(
                    OfficialProviderCatalog.APPLE_MUSIC_PLUGIN_ID,
                ) -> refreshPluginGate()
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val lyricSourceReady = lyricSource == "lyricon"
    val featuresEnabled = pluginGate == AppleMusicPluginGate.Ready && lyricSourceReady
    val requirementNotice = when {
        !lyricSourceReady -> R.string.apple_music_optimization_notice_lyric_source
        pluginGate == AppleMusicPluginGate.Missing ->
            R.string.apple_music_optimization_notice_add_provider
        pluginGate == AppleMusicPluginGate.Disabled ->
            R.string.apple_music_optimization_notice_enable_provider
        else -> null
    }
    val listState = rememberLazyListState()
    val noticeVisible = requirementNotice != null
    LaunchedEffect(noticeVisible) {
        if (noticeVisible) listState.animateScrollToItem(0)
    }
    var forceCellularDataEntry by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_APPLE_MUSIC_FORCE_CELLULAR_DATA_ENTRY,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_FORCE_CELLULAR_DATA_ENTRY,
            )
        )
    }
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
    var lyricsImageExport by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_APPLE_MUSIC_LYRICS_IMAGE_EXPORT,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LYRICS_IMAGE_EXPORT,
            )
        )
    }
    var lyricsImageBackground by remember {
        mutableIntStateOf(
            prefs.getInt(
                RootConstants.KEY_HOOK_APPLE_MUSIC_LYRICS_IMAGE_BACKGROUND,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LYRICS_IMAGE_BACKGROUND,
            )
        )
    }
    var lyricsImageBackgroundColor by remember {
        mutableIntStateOf(
            prefs.getInt(
                RootConstants.KEY_HOOK_APPLE_MUSIC_LYRICS_IMAGE_BACKGROUND_COLOR,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LYRICS_IMAGE_BACKGROUND_COLOR,
            )
        )
    }
    var lyricsImageQrCode by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_APPLE_MUSIC_LYRICS_IMAGE_QR_CODE,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LYRICS_IMAGE_QR_CODE,
            )
        )
    }
    // Edited on its own page; keeps the swatch here current when coming back.
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == RootConstants.KEY_HOOK_APPLE_MUSIC_LYRICS_IMAGE_BACKGROUND_COLOR) {
                lyricsImageBackgroundColor = prefs.getInt(
                    RootConstants.KEY_HOOK_APPLE_MUSIC_LYRICS_IMAGE_BACKGROUND_COLOR,
                    RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LYRICS_IMAGE_BACKGROUND_COLOR,
                )
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var volumeBalance by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_APPLE_MUSIC_VOLUME_BALANCE,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_VOLUME_BALANCE,
            )
        )
    }
    var preventNetworkAutoSkip by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_APPLE_MUSIC_PREVENT_NETWORK_AUTO_SKIP,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_PREVENT_NETWORK_AUTO_SKIP,
            )
        )
    }
    var simplifyTraditionalLyrics by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_CONTENT,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_CONTENT,
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
    var alwaysShowSourceSwitch by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_APPLE_MUSIC_ALWAYS_SHOW_SOURCE_SWITCH,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_ALWAYS_SHOW_SOURCE_SWITCH,
            )
        )
    }
    var sourceSearchTiming by remember {
        mutableIntStateOf(
            prefs.getInt(
                RootConstants.KEY_HOOK_APPLE_MUSIC_SOURCE_SEARCH_TIMING,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_SOURCE_SEARCH_TIMING,
            ).coerceIn(
                RootConstants.APPLE_MUSIC_SOURCE_SEARCH_TIMING_ON_DIALOG_OPEN,
                RootConstants.APPLE_MUSIC_SOURCE_SEARCH_TIMING_ON_PLAYBACK_START,
            )
        )
    }
    var lineToWordLyrics by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_APPLE_MUSIC_LINE_TO_WORD_LYRICS,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LINE_TO_WORD_LYRICS,
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
    var lyricsBlurAnimation by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_APPLE_MUSIC_LYRICS_BLUR_ANIMATION,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LYRICS_BLUR_ANIMATION,
            )
        )
    }
    val initialNativeLyricsBlurRadiusRange = remember(prefs) {
        val configuredMin = FloatPreferences.read(prefs, 
            RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MIN_RADIUS_DP,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MIN_RADIUS_DP,
        ).coerceIn(
            RootConstants.MIN_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_RADIUS_DP,
            RootConstants.MAX_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_RADIUS_DP,
        )
        val configuredMax = FloatPreferences.read(prefs, 
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
    var followSystemFont by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_APPLE_MUSIC_FOLLOW_SYSTEM_FONT,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_FOLLOW_SYSTEM_FONT,
            )
        )
    }
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
    val sourceSearchTimingOptions = listOf(
        stringResource(R.string.option_apple_music_source_search_timing_on_dialog_open),
        stringResource(R.string.option_apple_music_source_search_timing_on_playback_start),
    )
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

    val lyricsImageBackgroundOptions = listOf(
        stringResource(R.string.option_apple_music_lyrics_image_background_cover_blur),
        stringResource(R.string.option_apple_music_lyrics_image_background_cover_color),
        stringResource(R.string.option_apple_music_lyrics_image_background_custom_color),
        stringResource(R.string.option_apple_music_lyrics_image_background_custom_image),
    )

    val tabPagerState = rememberPagerState { AppleMusicSettingsTab.entries.size }

    XposedLyricSettingPage(
        title = stringResource(R.string.title_apple_music_optimization_page),
        subtitle = stringResource(R.string.summary_apple_music_optimization_page),
        outerPadding = outerPadding,
        showNavigationIcon = showNavigationIcon,
        listState = listState,
        // 仅当本页作为主页面首页（主页被隐藏）时，标题收回顶栏后只显示应用名；
        // 主页仍在时保持原有的标题 + 副标题。
        collapsedTitle = if (collapseTitleToAppName) stringResource(R.string.app_name) else null,
        // 内嵌在主页面且本页即首页（主页被隐藏）时，左上角提供应用设置入口；
        // 主页仍在时设置入口由主页提供，这里不重复显示。
        leadingContent = if (embeddedInMainPage) {
            { AppSettingsIconButton(onClick = onAppSettingsClick) }
        } else {
            null
        },
        // 主页隐藏时本页即首页，顶栏右侧同样提供一键刷新入口。
        trailingContent = if (showRefreshAction) {
            {
                IconButton(onClick = onRefreshClick) {
                    Icon(
                        imageVector = MiuixIcons.Refresh,
                        contentDescription = stringResource(R.string.title_one_tap_refresh),
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        } else {
            null
        },
    ) {
        item(key = "apple_music_hero") {
            val ready = requirementNotice == null
            val updateVersion = pluginUpdateVersionName?.takeIf { ready }
            HeroCard(
                overline = "Apple Music",
                title = stringResource(
                    when {
                        !ready -> R.string.apple_music_hero_unavailable
                        updateVersion != null -> R.string.apple_music_hero_update_available
                        else -> R.string.apple_music_hero_ready
                    }
                ),
                subtitle = requirementNotice?.let { stringResource(it) }
                    ?: updateVersion?.let { stringResource(R.string.apple_music_hero_new_version, it) },
                // 插件版本放在头卡左下角。
                notes = listOfNotNull(
                    pluginVersionName?.let { stringResource(R.string.apple_music_hero_plugin_version, it) },
                ),
                // 就绪为绿色音符；插件未添加为红色错误；未选 Lyricon 歌词源或插件未开启为琥珀色警告。
                tone = when {
                    ready -> HeroTone.Success
                    lyricSourceReady && pluginGate == AppleMusicPluginGate.Missing -> HeroTone.Error
                    else -> HeroTone.Warning
                },
                decorationIcon = when {
                    ready -> MiuixIcons.Music
                    lyricSourceReady && pluginGate == AppleMusicPluginGate.Missing -> StatusGlyphs.Error
                    else -> StatusGlyphs.Warning
                },
                onClick = {
                    navigator.navigate(
                        if (lyricSourceReady) Route.LyricProvider else Route.LyricSettings
                    )
                },
                modifier = Modifier.settingsHeroCard(),
            )
        }
        item(key = "apple_music_tabs") {
            // 分类标签的滑块跟随下方分页实时移动，也可直接拖动或点按切换分类。
            SwipeableSegmentedTabs(
                labels = AppleMusicSettingsTab.entries.map { stringResource(it.label) },
                pagerState = tabPagerState,
                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
            )
        }
        item(key = "apple_music_tab_pages") {
            HorizontalPager(
                state = tabPagerState,
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                beyondViewportPageCount = 1,
            ) { page ->
                when (AppleMusicSettingsTab.entries[page]) {
                    AppleMusicSettingsTab.Lyrics -> Column {
                            SmallTitle(text = stringResource(R.string.title_apple_music_group_lyrics_content))
                            Card(modifier = Modifier.settingsCard()) {
                                SwitchPreference(
                                    enabled = featuresEnabled,
                                    title = stringResource(R.string.title_apple_music_fill_missing_lyrics),
                                    summary = stringResource(R.string.summary_apple_music_fill_missing_lyrics),
                                    checked = fillMissingLyrics,
                                    onCheckedChange = { enabled ->
                                        fillMissingLyrics = enabled
                                        saveConfig(RootConstants.KEY_HOOK_APPLE_MUSIC_FILL_MISSING_LYRICS, enabled)
                                    },
                                )
                                SwitchPreference(
                                    enabled = featuresEnabled,
                                    title = stringResource(R.string.title_apple_music_native_online_translation),
                                    summary = stringResource(R.string.summary_apple_music_native_online_translation),
                                    checked = nativeOnlineTranslation,
                                    onCheckedChange = { enabled ->
                                        nativeOnlineTranslation = enabled
                                        saveConfig(RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION, enabled)
                                    },
                                )
                                SwitchPreference(
                                    enabled = featuresEnabled,
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
                                    enabled = featuresEnabled,
                                    title = stringResource(
                                        R.string.title_apple_music_line_to_word_lyrics
                                    ),
                                    summary = stringResource(
                                        R.string.summary_apple_music_line_to_word_lyrics
                                    ),
                                    checked = lineToWordLyrics,
                                    onCheckedChange = { enabled ->
                                        lineToWordLyrics = enabled
                                        saveConfig(
                                            RootConstants.KEY_HOOK_APPLE_MUSIC_LINE_TO_WORD_LYRICS,
                                            enabled,
                                        )
                                    },
                                )
                                SwitchPreference(
                                    enabled = featuresEnabled,
                                    title = stringResource(
                                        R.string.title_apple_music_always_show_source_switch
                                    ),
                                    checked = alwaysShowSourceSwitch,
                                    onCheckedChange = { enabled ->
                                        alwaysShowSourceSwitch = enabled
                                        saveConfig(
                                            RootConstants.KEY_HOOK_APPLE_MUSIC_ALWAYS_SHOW_SOURCE_SWITCH,
                                            enabled,
                                        )
                                    },
                                )
                                AnimatedVisibility(
                                    visible = alwaysShowSourceSwitch,
                                    enter = SectionEnter,
                                    exit = SectionExit,
                                ) {
                                    OverlayDropdownPreference(
                                        enabled = featuresEnabled,
                                        title = stringResource(R.string.title_apple_music_source_search_timing),
                                        items = sourceSearchTimingOptions,
                                        selectedIndex = sourceSearchTiming,
                                        onSelectedIndexChange = { selected ->
                                            val value = selected.coerceIn(
                                                RootConstants.APPLE_MUSIC_SOURCE_SEARCH_TIMING_ON_DIALOG_OPEN,
                                                RootConstants.APPLE_MUSIC_SOURCE_SEARCH_TIMING_ON_PLAYBACK_START,
                                            )
                                            sourceSearchTiming = value
                                            saveConfig(
                                                RootConstants.KEY_HOOK_APPLE_MUSIC_SOURCE_SEARCH_TIMING,
                                                value,
                                            )
                                        },
                                    )
                                }
                            }
                            SmallTitle(text = stringResource(R.string.title_apple_music_group_lyrics_display))
                            Card(modifier = Modifier.settingsCard()) {
                                OverlayDropdownPreference(
                                    enabled = featuresEnabled,
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
                                        RootConstants.APPLE_MUSIC_LYRICS_BLUR_EFFECT_OFF,
                                    enter = SectionEnter,
                                    exit = SectionExit,
                                ) {
                                    ArrowPreference(
                                        enabled = featuresEnabled,
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
                                AnimatedVisibility(
                                    visible = lyricsBlurEffect !=
                                        RootConstants.APPLE_MUSIC_LYRICS_BLUR_EFFECT_OFF,
                                    enter = SectionEnter,
                                    exit = SectionExit,
                                ) {
                                    SwitchPreference(
                                        enabled = featuresEnabled,
                                        title = stringResource(
                                            R.string.title_apple_music_lyrics_blur_animation
                                        ),
                                        summary = stringResource(
                                            R.string.summary_apple_music_lyrics_blur_animation
                                        ),
                                        checked = lyricsBlurAnimation,
                                        onCheckedChange = { enabled ->
                                            lyricsBlurAnimation = enabled
                                            saveConfig(
                                                RootConstants.KEY_HOOK_APPLE_MUSIC_LYRICS_BLUR_ANIMATION,
                                                enabled,
                                            )
                                        },
                                    )
                                }
                                SwitchPreference(
                                    enabled = featuresEnabled,
                                    title = stringResource(
                                        R.string.title_apple_music_follow_system_font
                                    ),
                                    summary = stringResource(
                                        R.string.summary_apple_music_follow_system_font
                                    ),
                                    checked = followSystemFont,
                                    onCheckedChange = { enabled ->
                                        followSystemFont = enabled
                                        saveConfig(
                                            RootConstants.KEY_HOOK_APPLE_MUSIC_FOLLOW_SYSTEM_FONT,
                                            enabled,
                                        )
                                    },
                                )
                                AnimatedVisibility(
                                    visible = !followSystemFont,
                                    enter = SectionEnter,
                                    exit = SectionExit,
                                ) {
                                    SwitchPreference(
                                        enabled = featuresEnabled,
                                        title = stringResource(
                                            R.string.title_apple_music_follow_system_font_weight
                                        ),
                                        summary = stringResource(
                                            R.string.summary_apple_music_follow_system_font_weight
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
                    AppleMusicSettingsTab.Language -> Column {
                            Card(modifier = Modifier.settingsCard()) {
                                OverlayDropdownPreference(
                                    enabled = featuresEnabled,
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
                                AnimatedVisibility(
                                    visible = contentUiLanguage != RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_NONE,
                                    enter = SectionEnter,
                                    exit = SectionExit,
                                ) {
                                    SwitchPreference(
                                        enabled = featuresEnabled,
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
                                    enabled = featuresEnabled,
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
                                AnimatedVisibility(
                                    visible = metadataLookupEnabled,
                                    enter = SectionEnter,
                                    exit = SectionExit,
                                ) {
                                    SwitchPreference(
                                        enabled = featuresEnabled,
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
                            Card(modifier = Modifier.settingsCard()) {
                                SwitchPreference(
                                    enabled = featuresEnabled,
                                    title = stringResource(R.string.title_apple_music_simplify_traditional_content),
                                    checked = simplifyTraditionalLyrics,
                                    onCheckedChange = { enabled ->
                                        simplifyTraditionalLyrics = enabled
                                        saveConfig(RootConstants.KEY_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_CONTENT, enabled)
                                    },
                                )
                            }
                    }
                    AppleMusicSettingsTab.Playback -> Column {
                            Card(modifier = Modifier.settingsCard()) {
                                SwitchPreference(
                                    enabled = featuresEnabled,
                                    title = stringResource(R.string.title_apple_music_prevent_network_auto_skip),
                                    summary = stringResource(R.string.summary_apple_music_prevent_network_auto_skip),
                                    checked = preventNetworkAutoSkip,
                                    onCheckedChange = { enabled ->
                                        preventNetworkAutoSkip = enabled
                                        saveConfig(RootConstants.KEY_HOOK_APPLE_MUSIC_PREVENT_NETWORK_AUTO_SKIP, enabled)
                                    },
                                )
                                SwitchPreference(
                                    enabled = featuresEnabled,
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
                            }
                    }
                    AppleMusicSettingsTab.Features -> Column {
                            Card(modifier = Modifier.settingsCard()) {
                                SwitchPreference(
                                    enabled = featuresEnabled,
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
                                    enabled = featuresEnabled,
                                    title = stringResource(R.string.title_apple_music_force_cellular_data_entry),
                                    checked = forceCellularDataEntry,
                                    onCheckedChange = { enabled ->
                                        forceCellularDataEntry = enabled
                                        saveConfig(
                                            RootConstants.KEY_HOOK_APPLE_MUSIC_FORCE_CELLULAR_DATA_ENTRY,
                                            enabled,
                                        )
                                    },
                                )
                            }
                            Card(modifier = Modifier.settingsCard()) {
                                SwitchPreference(
                                    enabled = featuresEnabled,
                                    title = stringResource(R.string.title_apple_music_lyrics_image_export),
                                    summary = stringResource(R.string.summary_apple_music_lyrics_image_export),
                                    checked = lyricsImageExport,
                                    onCheckedChange = { enabled ->
                                        lyricsImageExport = enabled
                                        saveConfig(
                                            RootConstants.KEY_HOOK_APPLE_MUSIC_LYRICS_IMAGE_EXPORT,
                                            enabled,
                                        )
                                    },
                                )
                                AnimatedVisibility(
                                    visible = lyricsImageExport,
                                    enter = SectionEnter,
                                    exit = SectionExit,
                                ) {
                                    Column {
                                        OverlayDropdownPreference(
                                            enabled = featuresEnabled,
                                            title = stringResource(R.string.title_apple_music_lyrics_image_background),
                                            items = lyricsImageBackgroundOptions,
                                            selectedIndex = lyricsImageBackground,
                                            onSelectedIndexChange = { selected ->
                                                val value = selected.coerceIn(
                                                    RootConstants.APPLE_MUSIC_LYRICS_IMAGE_BACKGROUND_COVER_BLUR,
                                                    RootConstants.APPLE_MUSIC_LYRICS_IMAGE_BACKGROUND_CUSTOM_IMAGE,
                                                )
                                                lyricsImageBackground = value
                                                saveConfig(
                                                    RootConstants.KEY_HOOK_APPLE_MUSIC_LYRICS_IMAGE_BACKGROUND,
                                                    value,
                                                )
                                            },
                                        )
                                        AnimatedVisibility(
                                            visible = lyricsImageBackground ==
                                                RootConstants.APPLE_MUSIC_LYRICS_IMAGE_BACKGROUND_CUSTOM_COLOR,
                                            enter = SectionEnter,
                                            exit = SectionExit,
                                        ) {
                                            ArrowPreference(
                                                enabled = featuresEnabled,
                                                title = stringResource(
                                                    R.string.title_apple_music_lyrics_image_background_color
                                                ),
                                                endActions = {
                                                    CustomFontColorPreview(color = lyricsImageBackgroundColor)
                                                },
                                                onClick = {
                                                    navigator.navigate(Route.AppleLyricsImageBackgroundColor)
                                                },
                                            )
                                        }
                                        AnimatedVisibility(
                                            visible = lyricsImageBackground ==
                                                RootConstants.APPLE_MUSIC_LYRICS_IMAGE_BACKGROUND_CUSTOM_IMAGE,
                                            enter = SectionEnter,
                                            exit = SectionExit,
                                        ) {
                                            ArrowPreference(
                                                enabled = featuresEnabled,
                                                title = stringResource(
                                                    R.string.title_apple_music_lyrics_image_background_image
                                                ),
                                                onClick = {
                                                    navigator.navigate(Route.AppleLyricsImageBackground)
                                                },
                                            )
                                        }
                                        SwitchPreference(
                                            enabled = featuresEnabled,
                                            title = stringResource(R.string.title_apple_music_lyrics_image_qr_code),
                                            checked = lyricsImageQrCode,
                                            onCheckedChange = { enabled ->
                                                lyricsImageQrCode = enabled
                                                saveConfig(
                                                    RootConstants.KEY_HOOK_APPLE_MUSIC_LYRICS_IMAGE_QR_CODE,
                                                    enabled,
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                    }
                }
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
