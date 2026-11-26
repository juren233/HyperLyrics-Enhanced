package com.juren233.hyperlyricsenhanced.ui.page

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.ui.component.SpecialFeaturesContent
import com.juren233.hyperlyricsenhanced.ui.component.IslandGlyph
import com.juren233.hyperlyricsenhanced.ui.component.SettingsTileGrid
import com.juren233.hyperlyricsenhanced.ui.component.SettingsTileSpec
import com.juren233.hyperlyricsenhanced.ui.component.settingsCard
import top.yukonga.miuix.kmp.icon.extended.Community
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Music
import com.juren233.hyperlyricsenhanced.common.FeatureEntryApplier
import com.juren233.hyperlyricsenhanced.common.FeatureEntryConfig
import com.juren233.hyperlyricsenhanced.common.LogLevelPreference
import com.juren233.hyperlyricsenhanced.common.PrefsBridge
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.ui.component.LogExportGuideDialog
import com.juren233.hyperlyricsenhanced.ui.component.rememberLogExportGuideState
import com.juren233.hyperlyricsenhanced.ui.component.LyricHookSwitchController
import com.juren233.hyperlyricsenhanced.ui.component.rememberLyricHookSwitchController
import com.juren233.hyperlyricsenhanced.ui.navigation.LocalNavigator
import com.juren233.hyperlyricsenhanced.ui.navigation.Route
import com.juren233.hyperlyricsenhanced.ui.page.log.LogAnalysisEntry
import com.juren233.hyperlyricsenhanced.ui.page.log.LogAnalysisStore
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.juren233.hyperlyricsenhanced.ui.utils.BlurredBar
import com.juren233.hyperlyricsenhanced.ui.utils.LocaleUtils
import com.juren233.hyperlyricsenhanced.ui.utils.pageScrollModifiers
import com.juren233.hyperlyricsenhanced.ui.utils.rememberBlurBackdrop
import com.juren233.hyperlyricsenhanced.utils.LogExportSource
import com.juren233.hyperlyricsenhanced.utils.rememberExportAllLogs
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
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsPage(scrollToFeatureSwitches: Boolean = false) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    val backupRestoreHelper = com.juren233.hyperlyricsenhanced.utils.rememberBackupRestoreHelper(snackbarHostState)
    val exportAllLogs = rememberExportAllLogs(snackbarHostState)
    val logExportGuide = rememberLogExportGuideState()

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
    // 入口状态跟随偏好刷新：「回顾基础设置流程页」等别处改了入口，返回设置页时立即对上。
    val featureEntries = rememberSettingsFeatureEntryState(
        prefs = prefs,
        xiaomiDevice = xiaomiDevice,
        appleMusicInstalled = appleMusicInstalled,
    )
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
    val homePageVisible = featureEntries.homePageVisible
    // 日志分析状态板常显：每次回到设置页都在后台刷新一次（10 秒内不重复跑），不阻塞页面。
    LifecycleResumeEffect(Unit) {
        LogAnalysisStore.refresh(context)
        onPauseOrDispose { }
    }

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
        // 从主页「去设置里打开入口」跳过来（scrollToFeatureSwitches）时定位到「功能入口」组：
        // 它前面是外观的标题、面板与「更多」入口三项，与 settingsSections 里的 key 顺序保持一致。
        val lazyListState = rememberLazyListState(
            initialFirstVisibleItemIndex = if (scrollToFeatureSwitches) FEATURE_ENTRIES_ITEM_INDEX else 0,
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
                        logExportGuide.request(context, LogExportSource.EXPORT_ALL, exportAllLogs)
                    },
                    lyricHookSwitches = lyricHookSwitches,
                    showMigratedHomeSections = !homePageVisible,
                    dynamicIslandEntryEnabled = featureEntries.dynamicIsland,
                    hyperIslandEntryEnabled = featureEntries.hyperIsland,
                    onHyperIslandEntryToggle = { enabled ->
                        toggleFeatureEntry(
                            UIConstants.KEY_FEATURE_ENTRY_HYPER_ISLAND,
                            enabled,
                            { featureEntries.hyperIsland = it },
                            RootConstants.KEY_HOOK_ENABLE_HYPER_ISLAND,
                        )
                    },
                    aodLyricsEntryEnabled = featureEntries.aodLyrics,
                    onAodLyricsEntryToggle = { enabled ->
                        toggleFeatureEntry(
                            UIConstants.KEY_FEATURE_ENTRY_AOD_LYRICS,
                            enabled,
                            { featureEntries.aodLyrics = it },
                            RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS,
                        )
                    },
                    onDynamicIslandEntryToggle = { enabled ->
                        toggleFeatureEntry(
                            UIConstants.KEY_FEATURE_ENTRY_DYNAMIC_ISLAND,
                            enabled,
                            { featureEntries.dynamicIsland = it },
                            RootConstants.KEY_HOOK_ENABLE_DYNAMIC_ISLAND,
                        )
                    },
                    appleMusicEntryEnabled = featureEntries.appleMusic,
                    onAppleMusicEntryToggle = { enabled ->
                        toggleFeatureEntry(
                            UIConstants.KEY_FEATURE_ENTRY_APPLE_MUSIC,
                            enabled,
                            { featureEntries.appleMusic = it },
                            null,
                        )
                    },
                )
            }
        }
    }

    LogExportGuideDialog(state = logExportGuide)
}

/** settingsSections 中「功能入口」标题的位置：前面是外观的标题、面板与「更多」入口。 */
internal const val FEATURE_ENTRIES_ITEM_INDEX = 3

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
    // 外观：深浅模式预览卡 + 莫奈取色/语言磁贴 + 强调色色块，放在最上面。
    item(key = "appearance_title") {
        SmallTitle(text = stringResource(R.string.title_appearance))
    }
    item(key = "appearance_content") {
        SettingsAppearancePanel()
    }
    // 更多：手势与界面、高级两组收进子页，入口挂在外观组末尾。
    item(key = "advanced_settings_entry") {
        val navigator = LocalNavigator.current
        Card(modifier = Modifier.settingsCard()) {
            ArrowPreference(
                title = stringResource(R.string.title_more_settings),
                summary = stringResource(R.string.summary_more_settings),
                onClick = { navigator.navigate(Route.MoreSettings) },
            )
        }
    }

    // 功能入口：磁贴，点一下显示/隐藏对应入口（行为与原来的开关相同）。
    item(key = "feature_entries_title") {
        SmallTitle(text = stringResource(R.string.title_feature_entries))
    }
    item(key = "feature_entries_tiles") {
        val shown = stringResource(R.string.feature_entry_state_shown)
        val hidden = stringResource(R.string.feature_entry_state_hidden)
        fun state(enabled: Boolean) = if (enabled) shown else hidden
        SettingsTileGrid(
            listOf(
                SettingsTileSpec(
                    key = "entry_hyper_island",
                    title = stringResource(R.string.title_miui_systemui_enhancement),
                    status = state(hyperIslandEntryEnabled),
                    active = hyperIslandEntryEnabled,
                    onClick = { onHyperIslandEntryToggle(!hyperIslandEntryEnabled) },
                ) { tint, modifier -> IslandGlyph(color = tint, modifier = modifier) },
                SettingsTileSpec(
                    key = "entry_aod_lyrics",
                    title = stringResource(R.string.title_aod_lyrics),
                    status = state(aodLyricsEntryEnabled),
                    active = aodLyricsEntryEnabled,
                    onClick = { onAodLyricsEntryToggle(!aodLyricsEntryEnabled) },
                ) { tint, modifier -> Icon(MiuixIcons.Lock, null, modifier, tint) },
                SettingsTileSpec(
                    key = "entry_dynamic_island",
                    title = stringResource(R.string.title_dynamic_island_lyrics),
                    status = state(dynamicIslandEntryEnabled),
                    active = dynamicIslandEntryEnabled,
                    onClick = { onDynamicIslandEntryToggle(!dynamicIslandEntryEnabled) },
                ) { tint, modifier -> Icon(MiuixIcons.Community, null, modifier, tint) },
                SettingsTileSpec(
                    key = "entry_apple_music",
                    title = stringResource(R.string.title_apple_music_optimization_page),
                    status = state(appleMusicEntryEnabled),
                    active = appleMusicEntryEnabled,
                    onClick = { onAppleMusicEntryToggle(!appleMusicEntryEnabled) },
                ) { tint, modifier -> Icon(MiuixIcons.Music, null, modifier, tint) },
            ),
        )
    }
    // 主页被隐藏（三个歌词入口都关闭）时，主页的歌词设置与特殊功能迁到这里；
    //    主页仍保留时这些内容留在主页，设置页不重复列出。
    //    通知型灵动岛歌词开关只在主页：它的入口一开启主页就必然保留，迁移区块里不放它。
    if (showMigratedHomeSections) {
        item(key = "global_features_title") {
            SmallTitle(text = stringResource(R.string.title_global_features))
        }
        item(key = "lyric_settings") {
            val navigator = LocalNavigator.current
            Card(modifier = Modifier.settingsCard()) {
                ArrowPreference(
                    title = stringResource(R.string.title_lyric_settings),
                    onClick = { navigator.navigate(Route.LyricSettings) },
                )
            }
        }
        item(key = "special_features_title") {
            SmallTitle(text = stringResource(R.string.title_special_features))
        }
        item(key = "special_features_content") {
            SpecialFeaturesContent(lyricHookSwitches)
        }
    }

    // 数据管理（备份 / 恢复）
    item(key = "backup_restore_title") {
        SmallTitle(text = stringResource(R.string.title_backup_restore))
    }
    item(key = "backup_restore_content") {
        Card(modifier = Modifier.settingsCard()) {
            Column {
                ArrowPreference(title = stringResource(R.string.title_backup), onClick = { backupRestoreHelper.launchBackup() })
                ArrowPreference(title = stringResource(R.string.title_restore), onClick = { backupRestoreHelper.launchRestore() })
            }
        }
    }

    // 日志
    item(key = "logs_title") {
        SmallTitle(text = stringResource(R.string.title_logs))
    }
    // 分析卡片放在日志组最上面，进设置页一眼就能看到状态。
    item(key = "logs_analysis") {
        LogAnalysisEntry()
    }
    item(key = "logs_content") {
        val navigator = LocalNavigator.current
        val context = LocalContext.current
        val prefs = remember { context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE) }
        var logLevel by remember { mutableIntStateOf(LogLevelPreference.read(prefs)) }
        // 导出引导弹窗里的「设为调试日志」也会改这个值，随偏好变化刷新显示。
        DisposableEffect(prefs) {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
                if (key == UIConstants.KEY_LOG_LEVEL) logLevel = LogLevelPreference.read(changed)
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        val logLevelOptions = listOf(stringResource(R.string.log_level_normal), stringResource(R.string.log_level_verbose))
        Card(modifier = Modifier.settingsCard()) {
            Column {
                WindowDropdownPreference(
                    title = stringResource(R.string.title_log_level),
                    items = logLevelOptions,
                    selectedIndex = logLevel,
                    onSelectedIndexChange = {
                        logLevel = it
                        LogLevelPreference.write(it)
                    }
                )
                ArrowPreference(
                    title = stringResource(R.string.title_view_logs),
                    onClick = { navigator.navigate(Route.Log) },
                )
                ArrowPreference(
                    title = stringResource(R.string.export_all_logs),
                    onClick = onExportAllLogs,
                )
            }
        }
    }

    // 杂项：与旧版一致，放在页面最底部。
    item(key = "misc_title") {
        SmallTitle(text = stringResource(R.string.title_misc))
    }
    item(key = "misc_content") {
        val navigator = LocalNavigator.current
        Card(modifier = Modifier.settingsCard()) {
            ArrowPreference(
                title = stringResource(R.string.title_review_setup),
                onClick = { navigator.navigate(Route.SetupReview) },
            )
        }
    }
}
