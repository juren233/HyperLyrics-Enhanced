package com.juren233.hyperlyricsenhanced.ui.page

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.FeatureEntryApplier
import com.juren233.hyperlyricsenhanced.common.FeatureEntryConfig
import com.juren233.hyperlyricsenhanced.common.LogLevelPolicy
import com.juren233.hyperlyricsenhanced.common.PrefsBridge
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.TopBarProgressiveBlurPreference
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.root.settings.SettingsEntryProfile
import com.juren233.hyperlyricsenhanced.ui.component.LyricHookPermissionSheet
import com.juren233.hyperlyricsenhanced.ui.component.LyricHookSwitchController
import com.juren233.hyperlyricsenhanced.ui.component.rememberLyricHookSwitchController
import com.juren233.hyperlyricsenhanced.ui.navigation.LocalNavigator
import com.juren233.hyperlyricsenhanced.ui.navigation.Route
import com.juren233.hyperlyricsenhanced.ui.page.main.MainTabPolicy
import com.juren233.hyperlyricsenhanced.ui.utils.AppUtils
import com.juren233.hyperlyricsenhanced.ui.utils.BlurredBar
import com.juren233.hyperlyricsenhanced.ui.utils.LocaleUtils
import com.juren233.hyperlyricsenhanced.ui.utils.pageScrollModifiers
import com.juren233.hyperlyricsenhanced.ui.utils.rememberBlurBackdrop
import com.juren233.hyperlyricsenhanced.ui.utils.rememberIsWideScreen
import com.juren233.hyperlyricsenhanced.utils.LOG_EXPORT_LEVEL_DEBUG
import com.juren233.hyperlyricsenhanced.utils.LogExportEnvironmentCollector
import com.juren233.hyperlyricsenhanced.utils.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private fun setExcludeFromRecents(context: Context, exclude: Boolean) {
    try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        am.appTasks?.forEach { it.setExcludeFromRecents(exclude) }
    } catch (_: Exception) { }
}

@Composable
fun SettingsPage(scrollToFeatureSwitches: Boolean = false) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    val backupRestoreHelper = com.juren233.hyperlyricsenhanced.utils.rememberBackupRestoreHelper(snackbarHostState)
    val exportHeader = stringResource(R.string.export_header)
    val exportEnvironmentTitle = stringResource(R.string.export_environment_title)
    val exportDeviceModelFormat = stringResource(R.string.format_export_device_model)
    val exportSystemVersionFormat = stringResource(R.string.format_export_system_version)
    val exportAndroidVersionFormat = stringResource(R.string.format_export_android_version)
    val exportCurrentMusicAppFormat = stringResource(R.string.format_export_current_music_app)
    val exportMusicAppVersionFormat = stringResource(R.string.format_export_music_app_version)
    val exportMusicAppInstallSourceFormat = stringResource(R.string.format_export_music_app_install_source)
    val exportNotDetected = stringResource(R.string.export_not_detected)
    val exportUnknown = stringResource(R.string.export_unknown)
    val exportTimeFormat = stringResource(R.string.format_export_time)
    val appLogsTitle = stringResource(R.string.title_app_logs)
    val moduleLogsTitle = stringResource(R.string.title_module_logs)
    val noLogsFoundMsg = stringResource(R.string.no_logs_found)
    val exportSuccessMsg = stringResource(R.string.export_success)
    val exportFailedMsg = stringResource(R.string.format_export_failed)
    val exportAllLogsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
        onResult = { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val output = context.contentResolver.openOutputStream(uri, "wt")
                        ?: throw IOException("Unable to open the selected file")
                    output.bufferedWriter(Charsets.UTF_8).use { writer ->
                        writer.appendLine(exportHeader)
                        writer.appendLine(
                            String.format(
                                exportTimeFormat,
                                LocalDateTime.now().format(
                                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                                )
                            )
                        )
                        writer.appendLine()

                        val environment = LogExportEnvironmentCollector.collect(context)
                        val musicApp = environment.musicApp
                        val musicAppIdentity = musicApp?.let { "${it.label} (${it.packageName})" }
                            ?: exportNotDetected
                        val musicAppVersion = musicApp?.let { app ->
                            val versionName = app.versionName.ifBlank { exportUnknown }
                            app.versionCode?.let { "$versionName ($it)" } ?: versionName
                        } ?: exportNotDetected
                        val installSource = when {
                            musicApp == null -> exportNotDetected
                            musicApp.installSourcePackageName == null -> exportUnknown
                            else -> {
                                val packageName = musicApp.installSourcePackageName
                                val label = musicApp.installSourceLabel
                                    ?.ifBlank { packageName }
                                    ?: packageName
                                "$label ($packageName)"
                            }
                        }

                        writer.appendLine("========== $exportEnvironmentTitle ==========")
                        writer.appendLine(String.format(exportDeviceModelFormat, environment.deviceModel))
                        writer.appendLine(String.format(exportSystemVersionFormat, environment.systemVersion))
                        writer.appendLine(String.format(exportAndroidVersionFormat, environment.androidVersion))
                        writer.appendLine(String.format(exportCurrentMusicAppFormat, musicAppIdentity))
                        writer.appendLine(String.format(exportMusicAppVersionFormat, musicAppVersion))
                        writer.appendLine(String.format(exportMusicAppInstallSourceFormat, installSource))
                        writer.appendLine()

                        writer.appendLine("========== $appLogsTitle ==========")
                        val appEntries = LogManager.exportLogs(
                            context = context,
                            isAppLog = true,
                            selectedLevel = LOG_EXPORT_LEVEL_DEBUG,
                            writer = writer,
                        )
                        if (appEntries == 0) writer.appendLine(noLogsFoundMsg)
                        writer.appendLine()

                        writer.appendLine("========== $moduleLogsTitle ==========")
                        val moduleEntries = LogManager.exportLogs(
                            context = context,
                            isAppLog = false,
                            selectedLevel = LOG_EXPORT_LEVEL_DEBUG,
                            writer = writer,
                        )
                        if (moduleEntries == 0) writer.appendLine(noLogsFoundMsg)
                    }
                    withContext(Dispatchers.Main) {
                        snackbarHostState.showSnackbar(
                            message = exportSuccessMsg,
                            duration = top.yukonga.miuix.kmp.basic.SnackbarDuration.Custom(2000L),
                        )
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        snackbarHostState.showSnackbar(
                            message = String.format(exportFailedMsg, e.message),
                            duration = top.yukonga.miuix.kmp.basic.SnackbarDuration.Custom(2000L),
                        )
                    }
                }
            }
        }
    )

    val prefs = remember(context) {
        context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
    }
    // 歌词相关开关：主页保留时留在主页，主页被隐藏（两个米系入口都关闭）时迁到设置页顶部。
    // 状态与副作用由控制器统一持有，与主页共用同一份实现。
    val lyricHookSwitches = rememberLyricHookSwitchController()

    // --- 功能入口开关 ---
    val xiaomiDevice = remember { FeatureEntryConfig.isXiaomiOrRedmiDevice() }
    val appleMusicInstalled = remember(context) {
        FeatureEntryConfig.isAppleMusicInstalled(context)
    }
    var hyperIslandEntryEnabled by remember {
        mutableStateOf(
            prefs.getBoolean(UIConstants.KEY_FEATURE_ENTRY_HYPER_ISLAND, xiaomiDevice)
        )
    }
    var aodLyricsEntryEnabled by remember {
        mutableStateOf(
            prefs.getBoolean(UIConstants.KEY_FEATURE_ENTRY_AOD_LYRICS, xiaomiDevice)
        )
    }
    var dynamicIslandEntryEnabled by remember {
        mutableStateOf(
            prefs.getBoolean(UIConstants.KEY_FEATURE_ENTRY_DYNAMIC_ISLAND, true)
        )
    }
    var appleMusicEntryEnabled by remember {
        mutableStateOf(
            prefs.getBoolean(UIConstants.KEY_FEATURE_ENTRY_APPLE_MUSIC, appleMusicInstalled)
        )
    }
    // 关闭入口等于停用对应功能：功能自身开关在关闭期间暂存并置为停用，重新打开入口后原样恢复，
    // 因此关闭入口不会丢失关闭前的设置。
    val toggleFeatureEntry: (String, Boolean, (Boolean) -> Unit, String?) -> Unit =
        { entryKey, enabled, onChanged, featureKey ->
            onChanged(enabled)
            val writes = FeatureEntryApplier.setEntryEnabled(
                prefs = prefs,
                entryKey = entryKey,
                enabled = enabled,
                featureKey = featureKey,
            )
            // 同步到宿主进程，入口状态改变后功能立即生效/停用。
            writes.forEach { (key, value) -> PrefsBridge.putBoolean(key, value) }
        }
    // 主页是否保留：三个歌词入口至少有一个开启。主页保留时不在设置页重复列出它的内容。
    val homePageVisible = MainTabPolicy.isHomePageVisible(
        hyperIslandEntryEnabled = hyperIslandEntryEnabled,
        aodLyricsEntryEnabled = aodLyricsEntryEnabled,
        dynamicIslandEntryEnabled = dynamicIslandEntryEnabled,
    )

    Scaffold(
        snackbarHost = { SnackbarHost(state = snackbarHostState) },
        topBar = {
            BlurredBar(backdrop, blurActive) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.title_settings_page),
                    scrollBehavior = topAppBarScrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(imageVector = MiuixIcons.Back, contentDescription = stringResource(R.string.back))
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        // “功能开关”分组前的条目数，与 settingsSections 里的 key 顺序保持一致：
        // 主页隐藏时其迁移区块（4 项 + 通知型灵动岛 1 项）排在最前，另有个性化标题与内容 2 项。
        val featureSwitchesItemIndex = (
            if (!homePageVisible) {
                4 + if (dynamicIslandEntryEnabled) 1 else 0
            } else {
                0
            }
            ) + 2
        val lazyListState = rememberLazyListState(
            initialFirstVisibleItemIndex = if (scrollToFeatureSwitches) featureSwitchesItemIndex else 0,
        )
        val top = innerPadding.calculateTopPadding()
        val bottom = innerPadding.calculateBottomPadding()
        val contentPadding = remember(top, bottom) {
            PaddingValues(top = top, start = 0.dp, end = 0.dp, bottom = bottom + 16.dp)
        }
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.pageScrollModifiers(
                    enableScrollEndHaptic = true,
                    showTopAppBar = true,
                    topAppBarScrollBehavior = topAppBarScrollBehavior
                ),
                contentPadding = contentPadding,
            ) {
                settingsSections(
                    backupRestoreHelper = backupRestoreHelper,
                    onExportAllLogs = {
                        val dateTime = LocalDateTime.now().format(
                            DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")
                        )
                        exportAllLogsLauncher.launch(
                            "hyperlyricsenhanced_all_logs_$dateTime.txt"
                        )
                    },
                    lyricHookSwitches = lyricHookSwitches,
                    showMigratedHomeSections = !homePageVisible,
                    dynamicIslandEntryEnabled = dynamicIslandEntryEnabled,
                    hyperIslandEntryEnabled = hyperIslandEntryEnabled,
                    onHyperIslandEntryToggle = { enabled ->
                        toggleFeatureEntry(
                            UIConstants.KEY_FEATURE_ENTRY_HYPER_ISLAND,
                            enabled,
                            { hyperIslandEntryEnabled = it },
                            RootConstants.KEY_HOOK_ENABLE_HYPER_ISLAND,
                        )
                    },
                    aodLyricsEntryEnabled = aodLyricsEntryEnabled,
                    onAodLyricsEntryToggle = { enabled ->
                        toggleFeatureEntry(
                            UIConstants.KEY_FEATURE_ENTRY_AOD_LYRICS,
                            enabled,
                            { aodLyricsEntryEnabled = it },
                            RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS,
                        )
                    },
                    onDynamicIslandEntryToggle = { enabled ->
                        toggleFeatureEntry(
                            UIConstants.KEY_FEATURE_ENTRY_DYNAMIC_ISLAND,
                            enabled,
                            { dynamicIslandEntryEnabled = it },
                            RootConstants.KEY_HOOK_ENABLE_DYNAMIC_ISLAND,
                        )
                    },
                    appleMusicEntryEnabled = appleMusicEntryEnabled,
                    onAppleMusicEntryToggle = { enabled ->
                        toggleFeatureEntry(
                            UIConstants.KEY_FEATURE_ENTRY_APPLE_MUSIC,
                            enabled,
                            { appleMusicEntryEnabled = it },
                            null,
                        )
                    },
                )
            }
        }
    }

    LyricHookPermissionSheet(controller = lyricHookSwitches)
}

private fun LazyListScope.settingsSections(
    backupRestoreHelper: com.juren233.hyperlyricsenhanced.utils.BackupRestoreHelper,
    onExportAllLogs: () -> Unit,
    lyricHookSwitches: LyricHookSwitchController,
    showMigratedHomeSections: Boolean,
    dynamicIslandEntryEnabled: Boolean,
    hyperIslandEntryEnabled: Boolean,
    onHyperIslandEntryToggle: (Boolean) -> Unit,
    aodLyricsEntryEnabled: Boolean,
    onAodLyricsEntryToggle: (Boolean) -> Unit,
    onDynamicIslandEntryToggle: (Boolean) -> Unit,
    appleMusicEntryEnabled: Boolean,
    onAppleMusicEntryToggle: (Boolean) -> Unit,
) {
    // 主页被隐藏（三个歌词入口都关闭）时，主页的歌词设置与特殊功能迁到设置页顶部；
    // 主页仍保留时这些内容留在主页，设置页不重复列出。
    // 通知型灵动岛歌词卡片由 dynamicIslandEntryEnabled 单独门控：入口开启时主页必然保留，不会走到这里。
    if (showMigratedHomeSections) {
        item(key = "global_features_title") {
            SmallTitle(text = stringResource(R.string.title_global_features))
        }
        item(key = "lyric_settings") {
            val navigator = LocalNavigator.current
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
                ArrowPreference(
                    title = stringResource(R.string.title_lyric_settings),
                    onClick = { navigator.navigate(Route.LyricSettings) },
                )
            }
        }
        if (dynamicIslandEntryEnabled) item(key = "dynamic_island_lyrics") {
            val navigator = LocalNavigator.current
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
                Column {
                    SwitchPreference(
                        title = stringResource(R.string.title_dynamic_island_lyrics),
                        summary = stringResource(R.string.summary_dynamic_island_lyrics),
                        checked = lyricHookSwitches.enableDynamicIsland,
                        onCheckedChange = lyricHookSwitches::onDynamicIslandToggle,
                    )
                    AnimatedVisibility(visible = lyricHookSwitches.enableDynamicIsland) {
                        ArrowPreference(
                            title = stringResource(R.string.title_dynamic_island_config),
                            onClick = { navigator.navigate(Route.DynamicIslandNotification) },
                        )
                    }
                }
            }
        }
        item(key = "special_features_title") {
            SmallTitle(text = stringResource(R.string.title_special_features))
        }
        item(key = "special_features_content") {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
                Column {
                    SwitchPreference(
                        title = stringResource(R.string.title_unlock_island_length),
                        checked = lyricHookSwitches.unlockIslandLength,
                        onCheckedChange = lyricHookSwitches::onUnlockIslandLengthToggle,
                    )
                    SwitchPreference(
                        title = stringResource(R.string.title_remove_focus_whitelist),
                        summary = stringResource(R.string.summary_remove_focus_whitelist),
                        checked = lyricHookSwitches.removeFocusWhitelist,
                        onCheckedChange = lyricHookSwitches::onRemoveFocusWhitelistToggle,
                    )
                    SwitchPreference(
                        title = stringResource(R.string.title_remove_island_whitelist),
                        checked = lyricHookSwitches.removeIslandWhitelist,
                        onCheckedChange = lyricHookSwitches::onRemoveIslandWhitelistToggle,
                    )
                }
            }
        }
    }

    item(key = "personalization_title") {
        SmallTitle(text = stringResource(R.string.title_personalization))
    }
    item(key = "personalization_content") {
        val context = LocalContext.current
        val activity = androidx.activity.compose.LocalActivity.current
        val prefs = remember { context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE) }
        var themeMode by remember { mutableIntStateOf(prefs.getInt(UIConstants.KEY_THEME_MODE, UIConstants.DEFAULT_THEME_MODE)) }
        val themeOptions = listOf(stringResource(R.string.theme_system), stringResource(R.string.theme_light), stringResource(R.string.theme_dark), stringResource(R.string.theme_system_monet), stringResource(R.string.theme_light_monet), stringResource(R.string.theme_dark_monet))
        var appLanguage by remember { mutableIntStateOf(prefs.getInt(UIConstants.KEY_APP_LANGUAGE, UIConstants.DEFAULT_APP_LANGUAGE)) }
        val languageOptions = listOf(stringResource(R.string.language_system), stringResource(R.string.language_simplified_chinese), stringResource(R.string.language_english))

        Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
            Column {
                WindowDropdownPreference(title = stringResource(R.string.title_theme), items = themeOptions, selectedIndex = themeMode, onSelectedIndexChange = { themeMode = it; prefs.edit { putInt(UIConstants.KEY_THEME_MODE, it) } })
                WindowDropdownPreference(
                    title = stringResource(R.string.title_app_language),
                    items = languageOptions,
                    selectedIndex = appLanguage,
                    onSelectedIndexChange = {
                        if (appLanguage == it) return@WindowDropdownPreference
                        appLanguage = it
                        prefs.edit { putInt(UIConstants.KEY_APP_LANGUAGE, it) }
                    }
                )
                if (themeMode >= 3) {
                    var monetColorIndex by remember { mutableIntStateOf(prefs.getInt(UIConstants.KEY_MONET_COLOR, UIConstants.DEFAULT_MONET_COLOR)) }
                    val monetOptions = listOf(stringResource(R.string.monet_default), stringResource(R.string.monet_blue), stringResource(R.string.monet_green), stringResource(R.string.monet_red), stringResource(R.string.monet_yellow), stringResource(R.string.monet_orange), stringResource(R.string.monet_purple), stringResource(R.string.monet_pink))
                    WindowDropdownPreference(title = stringResource(R.string.title_monet), items = monetOptions, selectedIndex = monetColorIndex, onSelectedIndexChange = { monetColorIndex = it; prefs.edit { putInt(UIConstants.KEY_MONET_COLOR, it) } })
                }
                var predictiveBackGestureEnabled by remember { mutableStateOf(prefs.getBoolean(UIConstants.KEY_PREDICTIVE_BACK_GESTURE, UIConstants.DEFAULT_PREDICTIVE_BACK_GESTURE)) }
                SwitchPreference(title = stringResource(R.string.title_predictive_back), checked = predictiveBackGestureEnabled, onCheckedChange = {
                    predictiveBackGestureEnabled = it; prefs.edit { putBoolean(UIConstants.KEY_PREDICTIVE_BACK_GESTURE, it) }
                    runCatching { org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback"); val m = android.content.pm.ApplicationInfo::class.java.getDeclaredMethod("setEnableOnBackInvokedCallback", Boolean::class.javaPrimitiveType); m.isAccessible = true; m.invoke(context.applicationInfo, it) }
                    activity?.recreate()
                })
                var swipeBackGestureEnabled by remember { mutableStateOf(prefs.getBoolean(UIConstants.KEY_SWIPE_BACK_GESTURE, UIConstants.DEFAULT_SWIPE_BACK_GESTURE)) }
                SwitchPreference(title = stringResource(R.string.title_swipe_back_gesture), checked = swipeBackGestureEnabled, onCheckedChange = { swipeBackGestureEnabled = it; prefs.edit { putBoolean(UIConstants.KEY_SWIPE_BACK_GESTURE, it) } })
                var parallelWindowUiEnabled by remember { mutableStateOf(prefs.getBoolean(UIConstants.KEY_PARALLEL_WINDOW_UI, UIConstants.DEFAULT_PARALLEL_WINDOW_UI)) }
                val isWideScreen = rememberIsWideScreen()
                // 平行窗口 UI 仅平板（宽屏）提供；开启期间底部栏导航不可用，隐藏悬浮底栏开关
                if (isWideScreen) {
                    SwitchPreference(title = stringResource(R.string.title_parallel_window_ui), checked = parallelWindowUiEnabled, onCheckedChange = { parallelWindowUiEnabled = it; prefs.edit { putBoolean(UIConstants.KEY_PARALLEL_WINDOW_UI, it) } })
                }
                var floatingNavBarEnabled by remember { mutableStateOf(prefs.getBoolean(UIConstants.KEY_FLOATING_NAV_BAR, UIConstants.DEFAULT_FLOATING_NAV_BAR)) }
                var topBarProgressiveBlurEnabled by remember {
                    mutableStateOf(TopBarProgressiveBlurPreference.read(prefs))
                }
                SwitchPreference(
                    title = stringResource(R.string.title_top_bar_progressive_blur),
                    checked = topBarProgressiveBlurEnabled,
                    onCheckedChange = {
                        topBarProgressiveBlurEnabled = it
                        prefs.edit {
                            putBoolean(UIConstants.KEY_TOP_BAR_PROGRESSIVE_BLUR, it)
                            remove(UIConstants.KEY_TOP_BAR_PROGRESSIVE_BLUR_MODE)
                        }
                    },
                )
                if (!(isWideScreen && parallelWindowUiEnabled)) {
                    SwitchPreference(title = stringResource(R.string.title_floating_nav), checked = floatingNavBarEnabled, onCheckedChange = { floatingNavBarEnabled = it; prefs.edit { putBoolean(UIConstants.KEY_FLOATING_NAV_BAR, it) } })
                }
                var excludeFromRecents by remember { mutableStateOf(prefs.getBoolean(UIConstants.KEY_EXCLUDE_FROM_RECENTS, UIConstants.DEFAULT_EXCLUDE_FROM_RECENTS)) }
                SwitchPreference(title = stringResource(R.string.title_exclude_from_recents), checked = excludeFromRecents, onCheckedChange = { excludeFromRecents = it; prefs.edit { putBoolean(UIConstants.KEY_EXCLUDE_FROM_RECENTS, it) }; setExcludeFromRecents(context, it) })
                var hideAppIcon by remember { mutableStateOf(prefs.getBoolean(UIConstants.KEY_HIDE_APP_ICON, UIConstants.DEFAULT_HIDE_APP_ICON)) }
                SwitchPreference(title = stringResource(R.string.title_hide_app_icon), checked = hideAppIcon, onCheckedChange = { hideAppIcon = it; PrefsBridge.putBoolean(UIConstants.KEY_HIDE_APP_ICON, it); AppUtils.applyLauncherIconVisibility(context, it) })
                AnimatedVisibility(
                    visible = hideAppIcon,
                    enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                    exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
                ) {
                    var settingsEntryPosition by remember {
                        mutableStateOf(
                            SettingsEntryProfile.resolveEntryPosition(
                                positionExists = prefs.contains(UIConstants.KEY_SETTINGS_ENTRY_POSITION),
                                positionValue = prefs.getInt(UIConstants.KEY_SETTINGS_ENTRY_POSITION, 0),
                                legacyShowEntryExists = prefs.contains(UIConstants.KEY_SHOW_SETTINGS_ENTRY),
                                legacyShowEntry = prefs.getBoolean(UIConstants.KEY_SHOW_SETTINGS_ENTRY, UIConstants.DEFAULT_SHOW_SETTINGS_ENTRY),
                            )
                        )
                    }
                    val settingsEntryPositionOptions = listOf(
                        stringResource(R.string.settings_entry_position_hidden),
                        stringResource(R.string.settings_entry_position_top),
                        stringResource(R.string.settings_entry_position_middle),
                        stringResource(R.string.settings_entry_position_bottom),
                    )
                    WindowDropdownPreference(
                        title = stringResource(R.string.title_show_settings_entry),
                        summary = stringResource(R.string.summary_show_settings_entry),
                        items = settingsEntryPositionOptions,
                        selectedIndex = settingsEntryPosition,
                        onSelectedIndexChange = {
                            settingsEntryPosition = it
                            PrefsBridge.putInt(UIConstants.KEY_SETTINGS_ENTRY_POSITION, it)
                        },
                    )
                }
            }
        }
    }
    item(key = "feature_entries_title") {
        // 该分组统称“功能开关”（原“功能入口”）。
        SmallTitle(text = stringResource(R.string.title_feature_switches))
    }
    item(key = "feature_entries_content") {
        Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
            Column {
                SwitchPreference(
                    title = stringResource(R.string.title_feature_entry_hyper_island),
                    checked = hyperIslandEntryEnabled,
                    onCheckedChange = onHyperIslandEntryToggle,
                )
                SwitchPreference(
                    title = stringResource(R.string.title_feature_entry_aod_lyrics),
                    checked = aodLyricsEntryEnabled,
                    onCheckedChange = onAodLyricsEntryToggle,
                )
                SwitchPreference(
                    title = stringResource(R.string.title_feature_entry_dynamic_island),
                    checked = dynamicIslandEntryEnabled,
                    onCheckedChange = onDynamicIslandEntryToggle,
                )
                SwitchPreference(
                    title = stringResource(R.string.title_feature_entry_apple_music),
                    checked = appleMusicEntryEnabled,
                    onCheckedChange = onAppleMusicEntryToggle,
                )
            }
        }
    }
    item(key = "config_management_title") {
        SmallTitle(text = stringResource(R.string.title_config_management))
    }
    item(key = "config_management_content") {
        Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
            Column {
                ArrowPreference(title = stringResource(R.string.title_backup), onClick = { backupRestoreHelper.launchBackup() })
                ArrowPreference(title = stringResource(R.string.title_restore), onClick = { backupRestoreHelper.launchRestore() })
            }
        }
    }
    item(key = "debug_info_title") {
        SmallTitle(text = stringResource(R.string.title_debug_info))
    }
    item(key = "debug_info_content") {
        val navigator = LocalNavigator.current
        val context = LocalContext.current
        val prefs = remember { context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE) }
        var logLevel by remember {
            mutableIntStateOf(
                prefs.getInt(
                    UIConstants.KEY_LOG_LEVEL,
                    LogLevelPolicy.defaultLevel(BuildConfig.DEBUG),
                )
            )
        }
        val logLevelOptions = listOf(stringResource(R.string.log_level_normal), stringResource(R.string.log_level_verbose))
        Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
            Column {
                WindowDropdownPreference(
                    title = stringResource(R.string.title_log_level), 
                    items = logLevelOptions, 
                    selectedIndex = logLevel, 
                    onSelectedIndexChange = { 
                        logLevel = it; 
                        prefs.edit { putInt(UIConstants.KEY_LOG_LEVEL, it) }; 
                        PrefsBridge.putInt(UIConstants.KEY_LOG_LEVEL, it) 
                        }
                )
                ArrowPreference(
                    title = stringResource(R.string.title_view_logs), 
                    onClick = { 
                        navigator.navigate(Route.Log) 
                    }
                )
                ArrowPreference(
                    title = stringResource(R.string.export_all_logs),
                    onClick = onExportAllLogs,
                )
            }
        }
    }
}
