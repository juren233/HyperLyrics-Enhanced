@file:OptIn(ExperimentalScrollBarApi::class)

package com.juren233.hyperlyricsenhanced.ui.page

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.withoutEventHandling
import androidx.compose.foundation.withoutVisualEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.ClassicAodSongInfoConfig
import com.juren233.hyperlyricsenhanced.common.FeatureEntryConfig
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.PrefsBridge
import com.juren233.hyperlyricsenhanced.root.RootApplication
import com.juren233.hyperlyricsenhanced.ui.component.EnhancedVersionNotice
import com.juren233.hyperlyricsenhanced.ui.component.LyricHookPermissionSheet
import com.juren233.hyperlyricsenhanced.ui.component.rememberLyricHookSwitchController
import com.juren233.hyperlyricsenhanced.utils.MigrationData
import com.juren233.hyperlyricsenhanced.utils.UpdateData
import com.juren233.hyperlyricsenhanced.service.LiveLyricService
import com.juren233.hyperlyricsenhanced.ui.navigation.LocalNavigator
import com.juren233.hyperlyricsenhanced.ui.navigation.PARALLEL_WINDOW_DIVIDER_ALPHA
import com.juren233.hyperlyricsenhanced.ui.navigation.Route
import com.juren233.hyperlyricsenhanced.ui.utils.blurFadeExtension
import com.juren233.hyperlyricsenhanced.ui.utils.rememberBlurBackdrop
import com.juren233.hyperlyricsenhanced.ui.utils.rememberIsWideScreen
import com.juren233.hyperlyricsenhanced.ui.utils.rememberProgressiveBlurEnabled
import com.juren233.hyperlyricsenhanced.ui.page.main.AboutPage
import com.juren233.hyperlyricsenhanced.ui.page.main.AboutHeroView
import com.juren233.hyperlyricsenhanced.ui.page.main.AboutHeroVisualState
import com.juren233.hyperlyricsenhanced.ui.page.main.AboutDebugLog
import com.juren233.hyperlyricsenhanced.ui.page.main.AboutDeviceInfoHelper
import com.juren233.hyperlyricsenhanced.ui.page.main.HomePage
import com.juren233.hyperlyricsenhanced.ui.page.main.MainTab
import com.juren233.hyperlyricsenhanced.ui.page.main.MainTabPolicy
import com.juren233.hyperlyricsenhanced.ui.page.main.UnsupportedDevicePage
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.AppleMusicOptimizationPage
import com.juren233.hyperlyricsenhanced.ui.page.main.OneTapRefreshHost
import com.juren233.hyperlyricsenhanced.ui.page.main.rememberOneTapRefreshController
import com.juren233.hyperlyricsenhanced.ui.page.main.rememberMainPagerState
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingToolbarDefaults
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailDefaults
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.rememberNavigationRailState
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.VerticalDivider
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import androidx.core.net.toUri

/** 底栏渐进模糊向上延伸的渐隐高度，与顶栏 PageUtils.TOP_BAR_BLUR_FADE_HEIGHT 一致。 */
private val BOTTOM_BAR_BLUR_FADE_HEIGHT = 16.dp

@Composable
fun MainPage() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val availableUpdate by UpdateData.availableUpdate.collectAsState()
    val isWideScreen = rememberIsWideScreen()
    val navigationRailState = rememberNavigationRailState()
    // 侧边栏文字透明度：展开渐显、收起渐隐，文字始终位于展开位，不参与 miuix 的形变布局
    val railLabelAlpha = remember { Animatable(0f) }
    LaunchedEffect(navigationRailState.isExpanded) {
        railLabelAlpha.animateTo(
            targetValue = if (navigationRailState.isExpanded) 1f else 0f,
            animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        )
    }

    // --- 功能入口（应用设置中可切换，决定主页面保留哪些页面） ---
    val prefs = remember { context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE) }
    val xiaomiDevice = remember { FeatureEntryConfig.isXiaomiOrRedmiDevice() }
    val appleMusicInstalled = remember(context) {
        FeatureEntryConfig.isAppleMusicInstalled(context)
    }
    var superIslandEntryEnabled by remember {
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
    val mainTabs = remember(
        superIslandEntryEnabled,
        aodLyricsEntryEnabled,
        dynamicIslandEntryEnabled,
        appleMusicEntryEnabled,
    ) {
        MainTabPolicy.tabs(
            superIslandEntryEnabled = superIslandEntryEnabled,
            aodLyricsEntryEnabled = aodLyricsEntryEnabled,
            dynamicIslandEntryEnabled = dynamicIslandEntryEnabled,
            appleMusicEntryEnabled = appleMusicEntryEnabled,
        )
    }
    // 主页是否保留：三个歌词入口至少有一个开启。主页隐藏时 Apple Music 体验优化页即为首页。
    val homePageVisible = MainTabPolicy.isHomePageVisible(
        superIslandEntryEnabled = superIslandEntryEnabled,
        aodLyricsEntryEnabled = aodLyricsEntryEnabled,
        dynamicIslandEntryEnabled = dynamicIslandEntryEnabled,
    )

    // --- pager ---
    val pagerState = rememberPagerState(pageCount = { mainTabs.size })
    val mainPagerState = rememberMainPagerState(pagerState)
    val pagerOverscrollEffect = rememberOverscrollEffect()
    val pagerOverscrollEvents = remember(pagerOverscrollEffect) {
        pagerOverscrollEffect?.withoutVisualEffect()
    }
    val sharedOverscrollVisual = remember(pagerOverscrollEffect) {
        pagerOverscrollEffect?.withoutEventHandling()
    }
    var aboutHeroVisualState by remember { mutableStateOf(AboutHeroVisualState()) }
    LaunchedEffect(mainPagerState.pagerState.currentPage) {
        mainPagerState.syncPage()
    }
    // 入口变化会改变页面集合，回到第一页避免停留在已移除的页面上；
    // 页面集合未变（例如从设置页返回）时保留原位置。
    val tabsSignature = mainTabs.joinToString(separator = ",")
    var lastTabsSignature by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(tabsSignature) {
        if (lastTabsSignature != null && lastTabsSignature != tabsSignature) {
            if (pagerState.currentPage != 0) {
                pagerState.scrollToPage(0)
            }
            mainPagerState.syncPage()
        }
        lastTabsSignature = tabsSignature
    }

    // --- toast messages ---
    val msgXposedNotActive = stringResource(R.string.toast_xposed_module_not_active)

    // --- prefs & state ---
    var floatingNavBarEnabled by remember {
        mutableStateOf(prefs.getBoolean(UIConstants.KEY_FLOATING_NAV_BAR, UIConstants.DEFAULT_FLOATING_NAV_BAR))
    }
    var parallelWindowUiEnabled by remember {
        mutableStateOf(prefs.getBoolean(UIConstants.KEY_PARALLEL_WINDOW_UI, UIConstants.DEFAULT_PARALLEL_WINDOW_UI))
    }
    // 平行窗口 UI：仅宽屏且开关开启时启用（侧栏 + 双栏场景），关闭后回落为底部栏单栏布局
    val parallelWindowUi = isWideScreen && parallelWindowUiEnabled
    var enableSuperIsland by remember {
        mutableStateOf(prefs.getBoolean(RootConstants.KEY_HOOK_ENABLE_HYPER_ISLAND, RootConstants.DEFAULT_HOOK_ENABLE_HYPER_ISLAND))
    }
    var enableAodLyrics by remember {
        mutableStateOf(prefs.getBoolean(RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS, RootConstants.DEFAULT_HOOK_ENABLE_AOD_LYRICS))
    }

    // 歌词相关开关：主页保留时展示在主页，主页被隐藏时由设置页展示同一份状态。
    val lyricHookSwitches = rememberLyricHookSwitchController()

    // --- dialogs ---
    // 一键刷新：主页隐藏时（Apple Music 体验优化页即首页）只保留系统界面与 Apple Music 两个选项。
    val oneTapRefresh = rememberOneTapRefreshController(snackbarHostState)
    val onOneTapRefreshClick: () -> Unit = {
        oneTapRefresh.open(appleMusicOnly = !homePageVisible)
    }

    // --- pref listener ---
    val listener = remember {
        SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            when (key) {
                UIConstants.KEY_FLOATING_NAV_BAR ->
                    floatingNavBarEnabled = p.getBoolean(UIConstants.KEY_FLOATING_NAV_BAR, UIConstants.DEFAULT_FLOATING_NAV_BAR)
                UIConstants.KEY_PARALLEL_WINDOW_UI ->
                    parallelWindowUiEnabled = p.getBoolean(UIConstants.KEY_PARALLEL_WINDOW_UI, UIConstants.DEFAULT_PARALLEL_WINDOW_UI)
                RootConstants.KEY_HOOK_ENABLE_HYPER_ISLAND ->
                    enableSuperIsland = p.getBoolean(RootConstants.KEY_HOOK_ENABLE_HYPER_ISLAND, RootConstants.DEFAULT_HOOK_ENABLE_HYPER_ISLAND)
                RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS ->
                    enableAodLyrics = p.getBoolean(RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS, RootConstants.DEFAULT_HOOK_ENABLE_AOD_LYRICS)
                UIConstants.KEY_FEATURE_ENTRY_HYPER_ISLAND ->
                    superIslandEntryEnabled = p.getBoolean(UIConstants.KEY_FEATURE_ENTRY_HYPER_ISLAND, xiaomiDevice)
                UIConstants.KEY_FEATURE_ENTRY_AOD_LYRICS ->
                    aodLyricsEntryEnabled = p.getBoolean(UIConstants.KEY_FEATURE_ENTRY_AOD_LYRICS, xiaomiDevice)
                UIConstants.KEY_FEATURE_ENTRY_DYNAMIC_ISLAND ->
                    dynamicIslandEntryEnabled = p.getBoolean(UIConstants.KEY_FEATURE_ENTRY_DYNAMIC_ISLAND, true)
                UIConstants.KEY_FEATURE_ENTRY_APPLE_MUSIC ->
                    appleMusicEntryEnabled = p.getBoolean(UIConstants.KEY_FEATURE_ENTRY_APPLE_MUSIC, appleMusicInstalled)
            }
        }
    }

    DisposableEffect(prefs) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    LaunchedEffect(Unit) {
        val hasListenerPermission = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        val isDynamicIslandEnabled = prefs.getBoolean(RootConstants.KEY_HOOK_ENABLE_DYNAMIC_ISLAND, RootConstants.DEFAULT_HOOK_ENABLE_DYNAMIC_ISLAND)
        val isClassicAodSongInfoEnabled = prefs.getBoolean(
            RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS,
            RootConstants.DEFAULT_HOOK_ENABLE_AOD_LYRICS,
        ) && ClassicAodSongInfoConfig.displayStyle(prefs) ==
            RootConstants.AOD_SONG_INFO_DISPLAY_STYLE_FOCUS_NOTIFICATION
        if (hasListenerPermission && (isDynamicIslandEnabled || isClassicAodSongInfoEnabled)) {
            LiveLyricService.ensureListenerBound(context)
        }
    }

    // --- system back ---
    BackHandler(enabled = mainPagerState.selectedPage != 0) {
        mainPagerState.animateToPage(0)
    }

    // --- callbacks (remembered for reference stability) ---
    val toggleSuperIsland: (Boolean) -> Unit = remember { { isChecked ->
        if (isChecked) {
            if (RootApplication.xposedService != null) {
                enableSuperIsland = true
                prefs.edit { putBoolean(RootConstants.KEY_HOOK_ENABLE_HYPER_ISLAND, true) }
                PrefsBridge.putBoolean(RootConstants.KEY_HOOK_ENABLE_HYPER_ISLAND, true)
            } else {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = msgXposedNotActive,
                        duration = SnackbarDuration.Custom(2000L)
                    )
                }
            }
        } else {
            enableSuperIsland = false
            prefs.edit { putBoolean(RootConstants.KEY_HOOK_ENABLE_HYPER_ISLAND, false) }
            PrefsBridge.putBoolean(RootConstants.KEY_HOOK_ENABLE_HYPER_ISLAND, false)
        }
    } }

    val toggleAodLyrics: (Boolean) -> Unit = remember { { checked ->
        if (checked) {
            if (RootApplication.xposedService != null) {
                enableAodLyrics = true
                prefs.edit { putBoolean(RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS, true) }
                PrefsBridge.putBoolean(RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS, true)
            } else {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = msgXposedNotActive,
                        duration = SnackbarDuration.Custom(2000L)
                    )
                }
            }
        } else {
            enableAodLyrics = false
            prefs.edit { putBoolean(RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS, false) }
            PrefsBridge.putBoolean(RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS, false)
        }
    } }

    // --- migration check ---
    var migrationNotes by remember { mutableStateOf<List<com.juren233.hyperlyricsenhanced.utils.MigrationNote>>(emptyList()) }
    var showMigrationDialog by remember { mutableStateOf(false) }
    val migrationTitle = stringResource(R.string.migration_dialog_title)
    LaunchedEffect(Unit) {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val currentVersion = PackageInfoCompat.getLongVersionCode(pInfo)
            val lastSeen = (prefs.all[UIConstants.KEY_LAST_SEEN_VERSION] as? Number)
                ?.toLong()
                ?: 0L
            val matched = MigrationData.notesForUpgrade(
                lastSeenVersionCode = lastSeen,
                currentVersionCode = currentVersion,
                currentVersionName = pInfo.versionName.orEmpty(),
            )
            if (matched.isNotEmpty()) {
                migrationNotes = matched
                showMigrationDialog = true
            }
        } catch (_: Exception) {}
    }

    // --- about page data ---
    val aboutAppVersion: String? = remember {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionName = pInfo.versionName ?: return@remember null
            "$versionName-${PackageInfoCompat.getLongVersionCode(pInfo)}"
        } catch (_: Exception) {
            null
        }
    }
    val aboutDeviceModel = remember { AboutDeviceInfoHelper.resolveDeviceModel() }
    var aboutDeviceName by remember { mutableStateOf(AboutDeviceInfoHelper.resolveDeviceName(context)) }
    LaunchedEffect(Unit) {
        val rootName = withContext(Dispatchers.IO) {
            AboutDeviceInfoHelper.resolveDeviceNameWithRoot(context)
        }
        if (!rootName.isNullOrBlank()) {
            aboutDeviceName = rootName
        }
    }
    val aboutOsVersion = remember { AboutDeviceInfoHelper.getSystemProperty("ro.build.version.incremental") ?: Build.DISPLAY }
    val aboutAndroidVersion = Build.VERSION.RELEASE

    // --- nav items ---
    val homeLabel = stringResource(R.string.home)
    val appleMusicOptimizationLabel = stringResource(R.string.apple_music_optimization_nav)
    val aboutLabel = stringResource(R.string.about)
    val navItems = remember(mainTabs, homeLabel, appleMusicOptimizationLabel, aboutLabel) {
        mainTabs.mapNotNull { tab ->
            when (tab) {
                MainTab.Home -> NavigationItem(homeLabel, MiuixIcons.Tune)
                MainTab.AppleMusic -> NavigationItem(appleMusicOptimizationLabel, MiuixIcons.Music)
                MainTab.About -> NavigationItem(aboutLabel, MiuixIcons.Info)
                MainTab.Unsupported -> null
            }
        }
    }
    // 仅剩引导页时不再显示底部导航与侧栏。
    val showMainNavigation = mainTabs.size > 1

    // --- outer backdrop (bottom bar blur) ---
    val outerBackdrop = rememberBlurBackdrop()
    val outerBlurActive = outerBackdrop != null
    val outerBarColor = if (outerBlurActive) Color.Transparent else MiuixTheme.colorScheme.surface
    val appName = stringResource(R.string.app_name)
    val darkMode = isSystemInDarkTheme()
    val aboutPageIndex = mainTabs.indexOf(MainTab.About)
    val hasAboutPage = aboutPageIndex >= 0
    val aboutPageOffsetFraction =
        if (hasAboutPage) {
            (pagerState.currentPage - aboutPageIndex + pagerState.currentPageOffsetFraction)
                .coerceIn(-1f, 1f)
        } else {
            1f
        }
    val aboutPageInvolved = hasAboutPage && (
        aboutPageOffsetFraction > -0.999f ||
            pagerState.currentPage == aboutPageIndex ||
            pagerState.settledPage == aboutPageIndex ||
            pagerState.targetPage == aboutPageIndex
        )
    val aboutHeroEntryAlpha = if (
        hasAboutPage &&
        pagerState.settledPage == aboutPageIndex - 1 && aboutPageOffsetFraction < 0f
    ) {
        (1f + aboutPageOffsetFraction).coerceIn(0f, 1f)
    } else {
        1f
    }
    SideEffect {
        AboutDebugLog.pager(
            active = aboutPageInvolved,
            offsetFraction = aboutPageOffsetFraction,
            currentPage = pagerState.currentPage,
            settledPage = pagerState.settledPage,
            targetPage = pagerState.targetPage,
            involved = aboutPageInvolved,
            entryAlpha = aboutHeroEntryAlpha,
        )
    }

    // --- dialogs at outer level ---
    OneTapRefreshHost(controller = oneTapRefresh)

    // --- migration dialog ---
    WindowDialog(
        title = migrationTitle,
        show = showMigrationDialog,
        onDismissRequest = {},
        onDismissFinished = { migrationNotes = emptyList() },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EnhancedVersionNotice(modifier = Modifier.fillMaxWidth())
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    migrationNotes.flatMap { it.items }.forEach { item ->
                        if (item.url != null) {
                            BasicComponent(
                                title = item.text,
                                summary = item.summary,
                                onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, item.url.toUri()))
                                }
                            )
                        } else {
                            BasicComponent(title = item.text, summary = item.summary)
                        }
                    }
                }
            }
            TextButton(
                text = stringResource(R.string.confirm),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    val currentVersion = PackageInfoCompat.getLongVersionCode(pInfo)
                    prefs.edit { putLong(UIConstants.KEY_LAST_SEEN_VERSION, currentVersion) }
                    showMigrationDialog = false
                }
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(state = snackbarHostState) },
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            AnimatedVisibility(
                visible = showMainNavigation && !floatingNavBarEnabled && !parallelWindowUi,
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
            ) {
                val progressiveBlurEnabled = rememberProgressiveBlurEnabled()
                Box(
                    modifier = Modifier
                        .then(
                            if (outerBlurActive) {
                                if (progressiveBlurEnabled) {
                                    Modifier
                                        // 渐隐带不计入测量高度：渐变仍按「栏高 + 渐隐带」计算，导航项仍位于原来的坐标，
                                        // 页面内容不再被推下；溢出的渐隐带作为浮层盖在内容区底部。
                                        .blurFadeExtension(extension = BOTTOM_BAR_BLUR_FADE_HEIGHT, extendUp = true)
                                        .progressiveTextureBlur(
                                            backdrop = outerBackdrop,
                                            shape = RectangleShape,
                                            blurRadius = 12f,
                                            gradient = ProgressiveBlur.Bottom.copy(startFraction = 0.5f),
                                            colors = BlurColors(
                                                blendColors = listOf(
                                                    BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.8f)),
                                                ),
                                            ),
                                        )
                                        .padding(top = BOTTOM_BAR_BLUR_FADE_HEIGHT)
                                } else {
                                    Modifier.textureBlur(
                                        backdrop = outerBackdrop,
                                        shape = RectangleShape,
                                        blurRadius = 12f,
                                        colors = BlurColors(
                                            blendColors = listOf(
                                                BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.8f)),
                                            ),
                                        ),
                                    )
                                }
                            } else {
                                Modifier
                            },
                        )
                        .background(outerBarColor)
                ) {
                    NavigationBar(color = outerBarColor, showDivider = false) {
                        navItems.forEachIndexed { index, item ->
                            NavigationBarItem(
                                selected = mainPagerState.selectedPage == index,
                                onClick = { mainPagerState.animateToPage(index) },
                                icon = item.icon,
                                label = item.label
                            )
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = showMainNavigation && floatingNavBarEnabled && !parallelWindowUi,
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
            ) {
                val floatingBarColor = if (outerBlurActive) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer
                val floatingBarShape = RoundedCornerShape(FloatingToolbarDefaults.CornerRadius)
                val isDark = isSystemInDarkTheme()
                val floatingHighlight = remember(isDark) {
                    if (isDark) Highlight.GlassStrokeSmallDark else Highlight.GlassStrokeSmallLight
                }
                FloatingNavigationBar(
                    modifier = (if (outerBlurActive) {
                        Modifier
                            .textureBlur(
                                backdrop = outerBackdrop,
                                shape = floatingBarShape,
                                blurRadius = 12f,
                                colors = BlurColors(
                                    blendColors = listOf(
                                        BlendColorEntry(color = MiuixTheme.colorScheme.surfaceContainer.copy(0.6f)),
                                    ),
                                ),
                                highlight = floatingHighlight,
                            )
                    } else {
                        Modifier
                    }).padding(horizontal = 12.dp),
                    color = floatingBarColor,
                ) {
                    navItems.forEachIndexed { index, item ->
                        FloatingNavigationBarItem(
                            selected = mainPagerState.selectedPage == index,
                            onClick = { mainPagerState.animateToPage(index) },
                            icon = item.icon,
                            label = item.label
                        )
                        if (index < navItems.size - 1) {
                            Spacer(Modifier.width(12.dp))
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Row(modifier = Modifier.fillMaxSize()) {
            if (parallelWindowUi && showMainNavigation) {
                Box {
                    NavigationRail(state = navigationRailState, showDivider = false) {
                        navItems.forEachIndexed { index, item ->
                            RailNavItem(
                                labelAlpha = { railLabelAlpha.value },
                                item = item,
                                selected = mainPagerState.selectedPage == index,
                                onClick = { mainPagerState.animateToPage(index) },
                            )
                        }
                    }
                    // miuix 自带的侧栏分割线取纯主题色，无法调淡；关闭后自绘一条与栏间分割线同色的
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight(),
                    ) {
                        VerticalDivider(
                            color = MiuixTheme.colorScheme.dividerLine.copy(alpha = PARALLEL_WINDOW_DIVIDER_ALPHA),
                        )
                    }
                }
            }
            Box(
                modifier = (if (outerBackdrop != null) Modifier.layerBackdrop(outerBackdrop) else Modifier)
                    .clipToBounds()
                    .overscroll(sharedOverscrollVisual)
                    .then(if (parallelWindowUi) Modifier.weight(1f) else Modifier),
            ) {
                AndroidView(
                    factory = { context -> AboutHeroView(context) },
                    update = { view ->
                        view.bind(appName, darkMode)
                        view.updateVisualState(
                            active = aboutPageInvolved,
                            backgroundAlpha = aboutHeroVisualState.backgroundAlpha,
                            logoAlpha = aboutHeroVisualState.logoAlpha * aboutHeroEntryAlpha,
                            logoScale = aboutHeroVisualState.logoScale,
                            scrollOffsetPx = aboutHeroVisualState.scrollOffsetPx,
                            pageOffsetFraction = aboutPageOffsetFraction,
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.imePadding(),
                    beyondViewportPageCount = 1,
                    verticalAlignment = Alignment.Top,
                    overscrollEffect = pagerOverscrollEvents,
                ) { page ->
                    when (mainTabs.getOrNull(page)) {
                        MainTab.Home -> HomePage(
                            outerPadding = innerPadding,
                            availableUpdateVersion = availableUpdate?.displayVersion,
                            showSuperIslandEntry = superIslandEntryEnabled,
                            showAodLyricsEntry = aodLyricsEntryEnabled,
                            showDynamicIslandEntry = dynamicIslandEntryEnabled,
                            lyricHookSwitches = lyricHookSwitches,
                            enableSuperIsland = enableSuperIsland,
                            onSuperIslandToggle = toggleSuperIsland,
                            enableAodLyrics = enableAodLyrics,
                            onAodLyricsToggle = toggleAodLyrics,
                            onSuperIslandConfigClick = { navigator.navigate(Route.HookSettings) },
                            onMediaCardConfigClick = { navigator.navigate(Route.MediaCardSettings) },
                            onLyricSettingsClick = { navigator.navigate(Route.LyricSettings) },
                            onDynamicIslandConfigClick = { navigator.navigate(Route.DynamicIslandNotification) },
                            onLockScreenAodConfigClick = { navigator.navigate(Route.LockScreenAodSettings) },
                            onClassicAodConfigClick = { navigator.navigate(Route.ClassicAodSettings) },
                            onRefreshClick = onOneTapRefreshClick,
                            onAppSettingsClick = { navigator.navigate(Route.Settings()) },
                        )
                        MainTab.AppleMusic -> AppleMusicOptimizationPage(
                            outerPadding = innerPadding,
                            showNavigationIcon = false,
                            // 主页仍在时左上角设置入口由主页提供，本页不重复显示；主页隐藏（本页即首页）时才显示。
                            embeddedInMainPage = !homePageVisible,
                            // 主页仍在时保持本页原有的标题 + 副标题，只有主页被隐藏（本页即首页）时才收起为应用名。
                            collapseTitleToAppName = !homePageVisible,
                            // 主页隐藏时本页即首页，顶栏右侧同样提供一键刷新入口。
                            showRefreshAction = !homePageVisible,
                            onRefreshClick = onOneTapRefreshClick,
                            onAppSettingsClick = { navigator.navigate(Route.Settings()) },
                        )
                        MainTab.About -> AboutPage(
                            outerPadding = innerPadding,
                            aboutAppVersion = aboutAppVersion,
                            availableUpdateVersion = availableUpdate?.displayVersion,
                            aboutDeviceName = aboutDeviceName,
                            aboutDeviceModel = aboutDeviceModel,
                            aboutOsVersion = aboutOsVersion,
                            aboutAndroidVersion = aboutAndroidVersion,
                            onHelpClick = { navigator.navigate(Route.Help) },
                            onLicensesClick = { navigator.navigate(Route.Licenses) },
                            onChangelogClick = { navigator.navigate(Route.Changelog) },
                            onContributorsClick = { navigator.navigate(Route.Contributors) },
                            onHeroStateChanged = { state ->
                                if (aboutHeroVisualState != state) {
                                    aboutHeroVisualState = state
                                }
                            },
                        )
                        MainTab.Unsupported -> UnsupportedDevicePage(
                            onEnterAppSettings = { navigator.navigate(Route.Settings(scrollToFeatureSwitches = true)) },
                        )
                        null -> Unit
                    }
                }
            }
        }
    }

    LyricHookPermissionSheet(controller = lyricHookSwitches)

}

/** 侧边栏展开弹簧动画的参考时长，动画落定后才显示条目文字。 */
private const val RAIL_LABEL_SHOW_DELAY = 500L

/**
 * 平板侧边栏条目：miuix 条目固定传空 label（其形变动画不会带动文字），
 * 展开位文字由本组件自行叠加，透明度跟随展开状态渐显渐隐，与原版视觉参数一致。
 */
@Composable
private fun RailNavItem(
    labelAlpha: () -> Float,
    item: NavigationItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        NavigationRailItem(
            selected = selected,
            onClick = onClick,
            icon = item.icon,
            label = "",
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = item.label,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .padding(
                    start = NavigationRailDefaults.ExpandedItemHorizontalMargin +
                        NavigationRailDefaults.ExpandedItemContentHorizontalPadding +
                        NavigationRailDefaults.IconSize +
                        NavigationRailDefaults.ExpandedItemIconTextSpacing,
                    end = NavigationRailDefaults.ExpandedItemHorizontalMargin +
                        NavigationRailDefaults.ExpandedItemContentHorizontalPadding,
                )
                .graphicsLayer { alpha = labelAlpha() },
            color = MiuixTheme.colorScheme.onSurfaceContainer,
            fontSize = NavigationRailDefaults.ExpandedLabelFontSize,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 读取系统属性，优先反射 android.os.SystemProperties，失败时回退到 getprop 进程。 */
private fun getSystemProperty(key: String): String? {
    return AboutDeviceInfoHelper.getSystemProperty(key)
}
