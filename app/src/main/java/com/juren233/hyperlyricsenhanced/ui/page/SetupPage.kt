package com.juren233.hyperlyricsenhanced.ui.page

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.BatteryOptimization
import com.juren233.hyperlyricsenhanced.common.FeatureEntryApplier
import com.juren233.hyperlyricsenhanced.common.InstalledAppsPermission
import com.juren233.hyperlyricsenhanced.common.PrefsBridge
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.SetupFlowVersion
import com.juren233.hyperlyricsenhanced.utils.LogManager
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.lyric.ConfigRepository
import com.juren233.hyperlyricsenhanced.lyric.commonMusicApps
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderItem
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderRepository
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderUiState
import com.juren233.hyperlyricsenhanced.root.RootApplication
import com.juren233.hyperlyricsenhanced.ui.component.EnhancedVersionNotice
import com.juren233.hyperlyricsenhanced.ui.component.ProComponent
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.localizeProviderError
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.rememberProviderAutoUpdateChoice
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.rememberProviderErrorTexts
import com.juren233.hyperlyricsenhanced.ui.page.setup.ISLAND_LOGO_ASPECT
import com.juren233.hyperlyricsenhanced.ui.page.setup.LocalSetupOutro
import com.juren233.hyperlyricsenhanced.ui.page.setup.IslandLogoMark
import com.juren233.hyperlyricsenhanced.ui.page.setup.SetupCheckMark
import com.juren233.hyperlyricsenhanced.ui.page.setup.SETUP_EXIT_DURATION_MILLIS
import com.juren233.hyperlyricsenhanced.ui.page.setup.SETUP_EXIT_STAGGER_MILLIS
import com.juren233.hyperlyricsenhanced.ui.page.setup.SetupCompletionMark
import com.juren233.hyperlyricsenhanced.ui.page.setup.SetupIntro
import com.juren233.hyperlyricsenhanced.ui.page.setup.SetupPageScope
import com.juren233.hyperlyricsenhanced.ui.page.setup.SetupPageTitle
import com.juren233.hyperlyricsenhanced.ui.page.setup.SetupStepIndicator
import com.juren233.hyperlyricsenhanced.ui.page.setup.setupStagger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.juren233.hyperlyricsenhanced.ui.utils.BlurredBar
import com.juren233.hyperlyricsenhanced.ui.utils.BlurredBottomBar
import com.juren233.hyperlyricsenhanced.ui.utils.rememberBlurBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import kotlin.time.Duration.Companion.milliseconds

/** 工作模式：米系超级岛歌词（Hook 系统界面）。 */
private const val SETUP_MODE_MI_HYPER_ISLAND = 0

/** 工作模式：通知型灵动岛歌词（通知监听，无需 root）。 */
private const val SETUP_MODE_DYNAMIC_ISLAND = 1

/** 工作模式：非米系设备，仅使用 Apple Music 体验优化，其余功能入口隐藏并禁用。 */
private const val SETUP_MODE_APPLE_MUSIC_ONLY = 2

@Composable
fun SetupPage(onNavigateToMain: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE) }
    val snackbarHostState = remember { SnackbarHostState() }
    val msgXposedNotActive = stringResource(R.string.toast_xposed_module_not_active)

    var workMode by remember {
        val initialMode = prefs.getInt(UIConstants.KEY_WORK_MODE, UIConstants.DEFAULT_WORK_MODE)
        mutableIntStateOf(initialMode)
    }

    var selectedSource by remember {
        mutableStateOf(prefs.getString(RootConstants.KEY_HOOK_LYRIC_SOURCE, RootConstants.DEFAULT_HOOK_LYRIC_SOURCE) ?: "lyricon")
    }

    // --- 授权状态：“授予必要权限”页要求全部授权完成后才能进入下一步 ---
    val installedAppsPermission = remember(context) { InstalledAppsPermission.resolve(context) }
    var isNotificationListenerGranted by remember { mutableStateOf(false) }
    var isPostNotificationGranted by remember { mutableStateOf(false) }
    var isBatteryExempt by remember { mutableStateOf(BatteryOptimization.isSatisfied(context)) }
    var isAppListGranted by remember {
        mutableStateOf(InstalledAppsPermission.isGranted(context, installedAppsPermission))
    }
    val appListPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        isAppListGranted = InstalledAppsPermission.isGranted(context, installedAppsPermission)
    }
    val requestAppListPermission: () -> Unit = {
        // 非米系系统上没有该权限（resolve 为 null），无需任何操作。
        if (installedAppsPermission != null) {
            appListPermissionLauncher.launch(installedAppsPermission)
        }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            isNotificationListenerGranted = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
            isPostNotificationGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            isAppListGranted = InstalledAppsPermission.isGranted(context, installedAppsPermission)
            isBatteryExempt = BatteryOptimization.isSatisfied(context)
            delay(1000.milliseconds)
        }
    }

    val completeSetup = {
        prefs.edit {
            putBoolean(UIConstants.KEY_SETUP_COMPLETED, true)
            SetupFlowVersion.record(this)
        }
        onNavigateToMain()
    }

    // 页数随工作模式变化：
    // 米系超级岛歌词：模式选择 → 免责声明 → 授予必要权限 → 歌词源 → 完成；
    // 通知型灵动岛歌词：模式选择 → 授予必要权限 → 音乐App白名单 → 完成；
    // 仅 Apple Music：模式选择 → 授予必要权限 → 完成。
    val pageCount = when (workMode) {
        SETUP_MODE_MI_HYPER_ISLAND -> 5
        SETUP_MODE_DYNAMIC_ISLAND -> 4
        else -> 3
    }
    val pagerState = rememberPagerState(pageCount = { pageCount })

    // 授权页要求全部权限授予后才能下一步；未完成授权时“下一步”保持禁用。
    val onPermissionPage = when (workMode) {
        SETUP_MODE_MI_HYPER_ISLAND -> pagerState.currentPage == 2
        SETUP_MODE_DYNAMIC_ISLAND, SETUP_MODE_APPLE_MUSIC_ONLY -> pagerState.currentPage == 1
        else -> false
    }
    val permissionPageCompleted = when (workMode) {
        SETUP_MODE_DYNAMIC_ISLAND ->
            isNotificationListenerGranted && isPostNotificationGranted && isAppListGranted && isBatteryExempt
        else -> isAppListGranted && isPostNotificationGranted && isBatteryExempt
    }

    // 首启动画：系统关闭动画（动画时长缩放为 0）时直接显示引导内容。
    val animationsDisabled = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    var introFinished by rememberSaveable { mutableStateOf(animationsDisabled) }
    var contentRevealed by rememberSaveable { mutableStateOf(introFinished) }
    val reveal = remember { Animatable(if (introFinished) 1f else 0f) }
    var headerLogoCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    // 完成转场：顶栏图标交给导航外层的转场继续演出，背景盖住后才切换页面。
    val outro = LocalSetupOutro.current
    var finishing by remember { mutableStateOf(false) }
    // 退场顺序与浮现相反：底栏先走，页面内容按序号从大到小，步骤指示最后。
    val bottomBarExit by animateFloatAsState(
        targetValue = if (finishing) 0f else 1f,
        animationSpec = tween(durationMillis = SETUP_EXIT_DURATION_MILLIS, easing = FastOutSlowInEasing),
        label = "setup_bottom_bar_exit",
    )
    val indicatorExit by animateFloatAsState(
        targetValue = if (finishing) 0f else 1f,
        animationSpec = tween(
            durationMillis = SETUP_EXIT_DURATION_MILLIS,
            delayMillis = (6 * SETUP_EXIT_STAGGER_MILLIS).toInt(),
            easing = FastOutSlowInEasing,
        ),
        label = "setup_indicator_exit",
    )
    val onFinish: () -> Unit = {
        val from = headerLogoCoordinates?.takeIf { it.isAttached }?.boundsInWindow()
        if (outro != null && from != null && !animationsDisabled) {
            if (!finishing) {
                finishing = true
                outro.start(fromInWindow = from, onCovered = completeSetup)
            }
        } else {
            completeSetup()
        }
    }

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
    ) {
        Scaffold(
            modifier = Modifier.graphicsLayer { alpha = reveal.value },
            snackbarHost = { SnackbarHost(state = snackbarHostState) },
            topBar = {
                BlurredBar(backdrop, blurActive) {
                    Box(Modifier.background(barColor)) {
                        SetupHeader(
                            pageCount = pagerState.pageCount,
                            currentPage = pagerState.targetPage,
                            logoVisible = introFinished && !finishing,
                            onLogoPlaced = { headerLogoCoordinates = it },
                            indicatorAlpha = { indicatorExit },
                        )
                    }
                }
            },
            bottomBar = {
                val isLastPage = pagerState.currentPage == pagerState.pageCount - 1
                Box(Modifier.graphicsLayer { alpha = bottomBarExit }) {
                    BlurredBottomBar(backdrop, blurActive) {
                        Box(Modifier.background(barColor)) {
                            SetupBottomBar(
                                showBack = pagerState.targetPage > 0,
                                nextText = if (isLastPage) stringResource(R.string.confirm) else stringResource(R.string.next),
                                nextEnabled = !onPermissionPage || permissionPageCompleted,
                                onBack = {
                                    scope.launch {
                                        pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                    }
                                },
                                onNext = {
                                    if (isLastPage) {
                                        onFinish()
                                    } else if (pagerState.currentPage == 0 &&
                                        workMode == SETUP_MODE_MI_HYPER_ISLAND &&
                                        RootApplication.xposedService == null
                                    ) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                message = msgXposedNotActive,
                                                duration = SnackbarDuration.Custom(2000L)
                                            )
                                        }
                                    } else {
                                        if (pagerState.currentPage == 0) {
                                            // 模式 0 默认处于选中态，用户可能未点按卡片直接下一步：
                                            // 前进时统一持久化选择并落定入口联动，保证“选择米系超级岛歌词”一定生效。
                                            prefs.edit { putInt(UIConstants.KEY_WORK_MODE, workMode) }
                                            applySetupModeEntrySideEffects(prefs, workMode)
                                        }
                                        scope.launch {
                                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        ) { padding ->
            // 内容铺满到两栏下面，由各页把栏高留作内边距，滚动时内容从栏下经过，渐进模糊才有内容可模糊。
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
                userScrollEnabled = false
            ) { page ->
                CompositionLocalProvider(LocalSetupBarPadding provides padding) {
                    SetupPageScope(
                        active = contentRevealed && pagerState.targetPage == page,
                        exiting = finishing && pagerState.targetPage == page,
                    ) {
                        when (page) {
                            0 -> ModeSelectionPage(
                                selectedMode = workMode,
                                onModeSelected = { mode ->
                                    workMode = mode
                                    prefs.edit { putInt(UIConstants.KEY_WORK_MODE, mode) }
                                    applySetupModeEntrySideEffects(prefs, mode)
                                }
                            )
                            1 -> when (workMode) {
                                SETUP_MODE_MI_HYPER_ISLAND -> DisclaimerPage()
                                SETUP_MODE_DYNAMIC_ISLAND -> PermissionPage(
                                    isNotificationGranted = isNotificationListenerGranted,
                                    isPostNotificationGranted = isPostNotificationGranted,
                                    isAppListGranted = isAppListGranted,
                                    isBatteryExempt = isBatteryExempt,
                                    onRequestAppListPermission = requestAppListPermission,
                                )
                                else -> AppListPermissionPage(
                                    isAppListGranted = isAppListGranted,
                                    isPostNotificationGranted = isPostNotificationGranted,
                                    isBatteryExempt = isBatteryExempt,
                                    onRequestAppListPermission = requestAppListPermission,
                                )
                            }
                            2 -> when (workMode) {
                                SETUP_MODE_MI_HYPER_ISLAND -> AppListPermissionPage(
                                    isAppListGranted = isAppListGranted,
                                    isPostNotificationGranted = isPostNotificationGranted,
                                    isBatteryExempt = isBatteryExempt,
                                    onRequestAppListPermission = requestAppListPermission,
                                )
                                SETUP_MODE_DYNAMIC_ISLAND -> WhitelistPage()
                                else -> CompletionPage(appleMusicOnly = true)
                            }
                            3 -> if (workMode == SETUP_MODE_MI_HYPER_ISLAND) LyricSourceSelectionPage(
                                selectedSource = selectedSource,
                                snackbarHostState = snackbarHostState,
                                onSourceSelected = { source ->
                                    selectedSource = source
                                    prefs.edit { putString(RootConstants.KEY_HOOK_LYRIC_SOURCE, source) }
                                }
                            ) else CompletionPage()
                            else -> CompletionPage()
                        }
                    }
                }
            }
        }

        if (!introFinished) {
            SetupIntro(
                target = { headerLogoCoordinates },
                reveal = reveal,
                onReveal = { contentRevealed = true },
                onFinished = { introFinished = true },
            )
        }
    }
}

/** 顶栏：左侧歌词岛 Logo（首启动画的落点），右侧步骤指示。 */
@Composable
private fun SetupHeader(
    pageCount: Int,
    currentPage: Int,
    logoVisible: Boolean,
    onLogoPlaced: (LayoutCoordinates) -> Unit,
    indicatorAlpha: () -> Float,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 28.dp, end = 28.dp, top = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IslandLogoMark(
            modifier = Modifier
                .width(56.dp)
                .aspectRatio(ISLAND_LOGO_ASPECT)
                .onGloballyPositioned(onLogoPlaced)
                .graphicsLayer { alpha = if (logoVisible) 1f else 0f }
        )
        Spacer(modifier = Modifier.weight(1f))
        SetupStepIndicator(
            count = pageCount,
            current = currentPage,
            modifier = Modifier.graphicsLayer { alpha = indicatorAlpha() },
        )
    }
}

/** 底栏：首页只有“下一步”，之后“返回”从左侧展开，与“下一步”平分宽度。 */
@Composable
private fun SetupBottomBar(
    showBack: Boolean,
    nextText: String,
    nextEnabled: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    val backFraction by animateFloatAsState(
        targetValue = if (showBack) 1f else 0f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "setup_back_fraction",
    )
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        val gap = 12.dp
        val backWidth = (maxWidth - gap) / 2
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (backFraction > 0f) {
                Box(
                    modifier = Modifier
                        .width((backWidth + gap) * backFraction)
                        .clipToBounds()
                        .graphicsLayer { alpha = backFraction }
                ) {
                    TextButton(
                        text = stringResource(R.string.back),
                        onClick = onBack,
                        enabled = showBack,
                        modifier = Modifier
                            .wrapContentWidth(Alignment.Start, unbounded = true)
                            .width(backWidth)
                    )
                }
            }
            TextButton(
                text = nextText,
                colors = ButtonDefaults.textButtonColorsPrimary(),
                enabled = nextEnabled,
                onClick = onNext,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 顶栏与底栏的高度：内容铺到两栏下面，各页把它留作内边距。 */
private val LocalSetupBarPadding = staticCompositionLocalOf { PaddingValues() }

/** 引导各页的统一骨架：大标题在上，内容依次浮现。 */
@Composable
private fun SetupPageColumn(
    title: String,
    summary: String? = null,
    content: LazyListScope.() -> Unit,
) {
    val bars = LocalSetupBarPadding.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = bars.calculateTopPadding() + 4.dp,
            bottom = bars.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "setup_page_title") {
            SetupPageTitle(title = title, summary = summary, modifier = Modifier.setupStagger(0))
        }
        content()
    }
}

/**
 * 引导页选择工作模式后，按选择落定对应功能入口的开关状态。
 *
 * 关闭入口沿用“功能开关”的停用规则：功能自身开关置为停用，关闭前的值被暂存，
 * 重新打开入口后原样恢复。选择“通知型灵动岛歌词”时会把该入口恢复为开启，
 * 解除此前选择“米系超级岛歌词”等留下的关闭，保证后续流程正常。
 * 写入同步到宿主进程，退出引导后立即生效。
 */
private fun applySetupModeEntrySideEffects(prefs: SharedPreferences, mode: Int) {
    val entryClosures = when (mode) {
        // 米系超级岛歌词：通知型灵动岛歌词入口关闭并禁用。
        SETUP_MODE_MI_HYPER_ISLAND -> listOf(
            UIConstants.KEY_FEATURE_ENTRY_DYNAMIC_ISLAND to RootConstants.KEY_HOOK_ENABLE_DYNAMIC_ISLAND,
        )
        // 仅 Apple Music：其余功能入口隐藏并禁用。
        SETUP_MODE_APPLE_MUSIC_ONLY -> listOf(
            UIConstants.KEY_FEATURE_ENTRY_HYPER_ISLAND to RootConstants.KEY_HOOK_ENABLE_HYPER_ISLAND,
            UIConstants.KEY_FEATURE_ENTRY_AOD_LYRICS to RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS,
            UIConstants.KEY_FEATURE_ENTRY_DYNAMIC_ISLAND to RootConstants.KEY_HOOK_ENABLE_DYNAMIC_ISLAND,
        )
        else -> emptyList()
    }
    // 选择通知型灵动岛歌词：恢复正常流程，解除此前选择对该入口的关闭（暂存的原值随之恢复）。
    val entryReopens = if (mode == SETUP_MODE_DYNAMIC_ISLAND) listOf(
        UIConstants.KEY_FEATURE_ENTRY_DYNAMIC_ISLAND to RootConstants.KEY_HOOK_ENABLE_DYNAMIC_ISLAND,
    ) else {
        emptyList()
    }
    if (BuildConfig.DEBUG) {
        LogManager.i(
            "SetupPage",
            "apply_mode_entry_effects mode=$mode close=${entryClosures.map { it.first }} reopen=${entryReopens.map { it.first }}",
        )
    }
    entryReopens.forEach { (entryKey, featureKey) ->
        val writes = FeatureEntryApplier.setEntryEnabled(
            prefs = prefs,
            entryKey = entryKey,
            enabled = true,
            featureKey = featureKey,
        )
        writes.forEach { (key, value) -> PrefsBridge.putBoolean(key, value) }
    }
    entryClosures.forEach { (entryKey, featureKey) ->
        val writes = FeatureEntryApplier.setEntryEnabled(
            prefs = prefs,
            entryKey = entryKey,
            enabled = false,
            featureKey = featureKey,
        )
        writes.forEach { (key, value) -> PrefsBridge.putBoolean(key, value) }
    }
}

@Composable
private fun ModeSelectionPage(selectedMode: Int, onModeSelected: (Int) -> Unit) {
    SetupPageColumn(title = stringResource(R.string.title_select_work_mode)) {
        item(key = "setup_mode_mi_hyper_island") {
            SetupModeCard(
                title = stringResource(R.string.setup_mode_mi_hyper_island_title),
                summary = stringResource(R.string.setup_mode_mi_hyper_island_summary),
                selected = selectedMode == SETUP_MODE_MI_HYPER_ISLAND,
                onClick = { onModeSelected(SETUP_MODE_MI_HYPER_ISLAND) },
                modifier = Modifier.setupStagger(1),
                glyph = { tint -> IslandGlyph(tint) },
            )
        }
        item(key = "setup_mode_dynamic_island") {
            SetupModeCard(
                title = stringResource(R.string.title_dynamic_island_lyrics),
                summary = stringResource(R.string.summary_dynamic_island_lyrics),
                selected = selectedMode == SETUP_MODE_DYNAMIC_ISLAND,
                onClick = { onModeSelected(SETUP_MODE_DYNAMIC_ISLAND) },
                modifier = Modifier.setupStagger(2),
                glyph = { tint ->
                    Icon(imageVector = MiuixIcons.Messages, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                },
            )
        }
        item(key = "setup_mode_apple_music_only") {
            SetupModeCard(
                title = stringResource(R.string.setup_mode_apple_music_only_title),
                summary = stringResource(R.string.setup_mode_apple_music_only_summary),
                selected = selectedMode == SETUP_MODE_APPLE_MUSIC_ONLY,
                onClick = { onModeSelected(SETUP_MODE_APPLE_MUSIC_ONLY) },
                modifier = Modifier.setupStagger(3),
                glyph = { tint ->
                    Icon(imageVector = MiuixIcons.Music, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                },
            )
        }
    }
}

/** 模式卡片：左侧图形、中间标题与说明、右侧选中标记；选中时整卡染上浅主题色。 */
@Composable
private fun SetupModeCard(
    title: String,
    summary: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: @Composable (tint: Color) -> Unit,
) {
    val primary = MiuixTheme.colorScheme.primary
    val surfaceContainer = MiuixTheme.colorScheme.surfaceContainer
    val spec = tween<Color>(durationMillis = 300, easing = FastOutSlowInEasing)
    val containerColor by animateColorAsState(
        targetValue = if (selected) primary.copy(alpha = 0.12f).compositeOver(surfaceContainer) else surfaceContainer,
        animationSpec = spec,
        label = "setup_mode_container",
    )
    val badgeColor by animateColorAsState(
        targetValue = if (selected) primary else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f),
        animationSpec = spec,
        label = "setup_mode_badge",
    )
    val glyphTint by animateColorAsState(
        targetValue = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceSecondary,
        animationSpec = spec,
        label = "setup_mode_glyph",
    )
    val titleColor by animateColorAsState(
        targetValue = if (selected) primary else MiuixTheme.colorScheme.onSurface,
        animationSpec = spec,
        label = "setup_mode_title",
    )
    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        pressFeedbackType = PressFeedbackType.Tilt,
        colors = CardDefaults.defaultColors(color = containerColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(badgeColor),
                contentAlignment = Alignment.Center,
            ) {
                glyph(glyphTint)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp)
            ) {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor
                )
                Text(
                    text = summary,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            SetupCheckMark(checked = selected)
        }
    }
}

/** 米系超级岛歌词的图形：一颗左侧带封面点的小胶囊。 */
@Composable
private fun IslandGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(width = 24.dp, height = 11.dp)) {
        val stroke = 1.6.dp.toPx()
        drawRoundRect(
            color = tint,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius((size.height - stroke) / 2f),
            style = Stroke(stroke),
        )
        val dot = size.height - stroke * 4f
        drawRoundRect(
            color = tint,
            topLeft = Offset(stroke * 2f, stroke * 2f),
            size = Size(dot, dot),
            cornerRadius = CornerRadius(dot / 2f),
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(stroke * 2f + dot + stroke * 1.2f, size.height / 2f - stroke / 2f),
            size = Size(size.width * 0.32f, stroke),
            cornerRadius = CornerRadius(stroke / 2f),
        )
    }
}

private fun Context.openAppNotificationSettings() {
    startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
    )
}

/** 授权行：点按跳转授权，右侧标记随授权结果自动打勾。 */
@Composable
private fun SetupPermissionRow(
    title: String,
    summary: String,
    granted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicComponent(
        modifier = modifier,
        title = title,
        summary = summary,
        onClick = onClick,
        endActions = { SetupCheckMark(checked = granted) },
    )
}

/**
 * 忽略电池优化：让插件自动更新的后台周期任务能按时运行。授权弹窗不可用时，
 * [BatteryOptimization.request] 回退到系统的电池优化列表。
 */
@Composable
private fun BatteryOptimizationRow(granted: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    SetupPermissionRow(
        title = stringResource(R.string.title_battery_optimization),
        summary = if (granted) {
            stringResource(R.string.toast_permission_granted)
        } else {
            stringResource(R.string.summary_permission_battery_optimization)
        },
        granted = granted,
        onClick = { BatteryOptimization.request(context) },
        modifier = modifier,
    )
}

@Composable
private fun PermissionPage(
    isNotificationGranted: Boolean,
    isPostNotificationGranted: Boolean,
    isAppListGranted: Boolean,
    isBatteryExempt: Boolean,
    onRequestAppListPermission: () -> Unit,
) {
    val context = LocalContext.current

    SetupPageColumn(title = stringResource(R.string.title_grant_permissions)) {
        item(key = "setup_permissions") {
            Card(modifier = Modifier.fillMaxWidth()) {
                SetupPermissionRow(
                    title = stringResource(R.string.title_permission_listener),
                    summary = if (isNotificationGranted) stringResource(R.string.toast_permission_granted) else stringResource(R.string.summary_permission_listener),
                    granted = isNotificationGranted,
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    modifier = Modifier.setupStagger(1),
                )
                SetupPermissionRow(
                    title = stringResource(R.string.title_permission_post_notification),
                    summary = if (isPostNotificationGranted) stringResource(R.string.toast_permission_granted) else stringResource(R.string.summary_permission_post_notification),
                    granted = isPostNotificationGranted,
                    onClick = { context.openAppNotificationSettings() },
                    modifier = Modifier.setupStagger(2),
                )
                SetupPermissionRow(
                    title = stringResource(R.string.title_permission_app_list),
                    summary = if (isAppListGranted) {
                        stringResource(R.string.toast_app_list_permission_granted)
                    } else {
                        stringResource(R.string.summary_permission_app_list)
                    },
                    granted = isAppListGranted,
                    onClick = onRequestAppListPermission,
                    modifier = Modifier.setupStagger(3),
                )
                BatteryOptimizationRow(
                    granted = isBatteryExempt,
                    modifier = Modifier.setupStagger(4),
                )
            }
        }
    }
}

/**
 * “授予必要权限”页（米系超级岛歌词与仅 Apple Music 模式）：需要获取应用列表权限，
 * 仅用于模块本地识别音乐App；通知权限，用于插件自动更新后的提醒；
 * 以及忽略电池优化，让插件自动更新的后台任务能按时运行。
 */
@Composable
private fun AppListPermissionPage(
    isAppListGranted: Boolean,
    isPostNotificationGranted: Boolean,
    isBatteryExempt: Boolean,
    onRequestAppListPermission: () -> Unit,
) {
    val context = LocalContext.current
    SetupPageColumn(title = stringResource(R.string.title_grant_permissions)) {
        item(key = "setup_permission_app_list") {
            Card(modifier = Modifier.fillMaxWidth()) {
                SetupPermissionRow(
                    title = stringResource(R.string.title_permission_app_list),
                    summary = if (isAppListGranted) {
                        stringResource(R.string.toast_app_list_permission_granted)
                    } else {
                        stringResource(R.string.summary_permission_app_list)
                    },
                    granted = isAppListGranted,
                    onClick = onRequestAppListPermission,
                    modifier = Modifier.setupStagger(1),
                )
                // 通知权限用于插件自动更新后的提醒，与应用列表权限一样授权后才能进入下一步。
                SetupPermissionRow(
                    title = stringResource(R.string.title_permission_plugin_update_notification),
                    summary = if (isPostNotificationGranted) {
                        stringResource(R.string.toast_permission_granted)
                    } else {
                        stringResource(R.string.summary_permission_plugin_update_notification)
                    },
                    granted = isPostNotificationGranted,
                    onClick = { context.openAppNotificationSettings() },
                    modifier = Modifier.setupStagger(2),
                )
                BatteryOptimizationRow(
                    granted = isBatteryExempt,
                    modifier = Modifier.setupStagger(3),
                )
            }
        }
    }
}

@Composable
private fun WhitelistPage() {
    val context = LocalContext.current
    val whitelistSet by ConfigRepository.whitelistState.collectAsState()

    LaunchedEffect(Unit) {
        ConfigRepository.initWhitelist(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(LocalSetupBarPadding.current)
            .padding(horizontal = 12.dp)
            .padding(top = 4.dp, bottom = 12.dp)
    ) {
        SetupPageTitle(
            title = stringResource(R.string.title_add_whitelist),
            summary = stringResource(R.string.summary_add_whitelist),
            modifier = Modifier
                .setupStagger(0)
                .padding(bottom = 4.dp),
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 8.dp)
                .setupStagger(1)
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                commonMusicApps.forEach { (pkg: String, name: String) ->
                    item(key = pkg) {
                        val isChecked = whitelistSet.contains(pkg)
                        BasicComponent(
                            title = name,
                            summary = pkg,
                            onClick = {
                                if (isChecked) {
                                    ConfigRepository.removePackageFromWhitelist(context, pkg)
                                } else {
                                    ConfigRepository.addPackageToWhitelist(context, pkg)
                                }
                            },
                            endActions = {
                                Checkbox(
                                    state = ToggleableState(isChecked),
                                    onClick = {
                                        val checked = !isChecked
                                        if (checked) {
                                            ConfigRepository.addPackageToWhitelist(context, pkg)
                                        } else {
                                            ConfigRepository.removePackageFromWhitelist(context, pkg)
                                        }
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricSourceSelectionPage(
    selectedSource: String,
    snackbarHostState: SnackbarHostState,
    onSourceSelected: (String) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val providerStateFlow = remember { MutableStateFlow(OfficialProviderUiState()) }
    val providerState = providerStateFlow.collectAsState()
    val providerErrorTexts = rememberProviderErrorTexts()
    val unknownErrorText = stringResource(R.string.unknown)
    val sourceOptions = listOf(
        stringResource(R.string.lyric_source_lyricon),
        stringResource(R.string.lyric_source_superlyric),
        stringResource(R.string.lyric_source_lyricinfo),
    )
    val sourceIds = listOf("lyricon", "superlyric", "lyricinfo")
    val addSuccessFormat = stringResource(R.string.provider_add_success)
    val addFailedFormat = stringResource(R.string.provider_add_failed)
    val unknownText = stringResource(R.string.unknown)

    LaunchedEffect(selectedSource) {
        if (selectedSource == "lyricon" && providerStateFlow.value.items.isEmpty()) {
            refreshSetupProviders(context, providerStateFlow)
        }
    }

    val installProvider: (OfficialProviderItem) -> Unit = { item ->
        val pluginId = item.catalog.id
        if (
            !item.installed &&
            item.catalog.available &&
            pluginId !in providerStateFlow.value.busyPluginIds
        ) {
            providerStateFlow.update { state ->
                state.copy(busyPluginIds = state.busyPluginIds + pluginId)
            }
            coroutineScope.launch {
                runCatching {
                    OfficialProviderRepository.downloadAndInstall(context, item)
                }.onSuccess { manifest ->
                    providerStateFlow.update { state ->
                        state.copy(
                            items = state.items.map { current ->
                                if (current.catalog.id == pluginId) {
                                    current.copy(
                                        installedVersionCode = manifest.versionCode,
                                        installedVersionName = manifest.versionName,
                                        displayVersionName = manifest.versionName,
                                        enabled = true,
                                    )
                                } else {
                                    current
                                }
                            },
                            busyPluginIds = state.busyPluginIds - pluginId,
                        )
                    }
                    snackbarHostState.showSnackbar(
                        message = addSuccessFormat.replace("%1\$s", item.catalog.displayName),
                        duration = SnackbarDuration.Custom(2500L),
                    )
                }.onFailure { error ->
                    providerStateFlow.update { state ->
                        state.copy(busyPluginIds = state.busyPluginIds - pluginId)
                    }
                    snackbarHostState.showSnackbar(
                        message = addFailedFormat.replace(
                            "%1\$s",
                            error.message ?: unknownText,
                        ),
                        duration = SnackbarDuration.Custom(3500L),
                    )
                }
            }
        }
    }

    SetupPageColumn(title = stringResource(R.string.setup_select_lyric_source)) {
        item(key = "setup_lyric_source") {
            Card(modifier = Modifier
                .fillMaxWidth()
                .setupStagger(1)) {
                OverlayDropdownPreference(
                    title = stringResource(R.string.title_lyric_source),
                    summary = stringResource(R.string.summary_lyric_source_apple_music_builtin),
                    items = sourceOptions,
                    selectedIndex = sourceIds.indexOf(selectedSource).coerceAtLeast(0),
                    onSelectedIndexChange = { index ->
                        onSourceSelected(sourceIds[index])
                    },
                )
                // Lyricon 源使用内置插件：在这里就能设好插件的自动更新间隔。
                AnimatedVisibility(
                    visible = selectedSource == "lyricon",
                    enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                    exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
                ) {
                    val autoUpdate = rememberProviderAutoUpdateChoice()
                    OverlayDropdownPreference(
                        title = stringResource(R.string.provider_auto_update_title),
                        summary = autoUpdate.summary,
                        items = autoUpdate.items,
                        selectedIndex = autoUpdate.selectedIndex,
                        onSelectedIndexChange = autoUpdate.select,
                    )
                }
            }
        }
        item(key = "setup_lyric_source_detail") {
            val detailModifier = Modifier
                .fillMaxWidth()
                .setupStagger(2)
            when (selectedSource) {
                "lyricon" -> {
                    Card(modifier = detailModifier) {
                        BasicComponent(
                            title = stringResource(R.string.setup_apple_music_ready),
                            summary = stringResource(R.string.setup_apple_music_ready_summary),
                        )
                    }
                }

                "superlyric" -> {
                    Card(modifier = detailModifier) {
                        ArrowPreference(
                            title = stringResource(R.string.setup_download_superlyric),
                            summary = stringResource(R.string.setup_download_superlyric_summary),
                            onClick = {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        "https://github.com/HChenX/SuperLyric".toUri(),
                                    ),
                                )
                            },
                        )
                    }
                }

                "lyricinfo" -> {
                    Card(modifier = detailModifier) {
                        BasicComponent(
                            title = stringResource(R.string.setup_no_dependency),
                            summary = stringResource(R.string.setup_no_dependency_summary),
                        )
                    }
                }
            }
        }
        if (selectedSource == "lyricon") {
            item {
                Text(
                    text = stringResource(R.string.setup_available_provider_plugins),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .setupStagger(3)
                        .padding(start = 16.dp, top = 8.dp),
                )
            }
            item(key = "setup_provider_apple_music") {
                Card(modifier = Modifier
                    .fillMaxWidth()
                    .setupStagger(4)) {
                    ProComponent(
                        title = "Apple Music",
                        summary = stringResource(R.string.setup_apple_music_plugin_summary),
                        endActions = {
                            Text(
                                text = stringResource(R.string.setup_provider_added),
                                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                fontSize = 14.sp,
                            )
                        },
                        enabled = false,
                    )
                }
            }

            when {
                providerState.value.isLoading -> item(key = "setup_provider_loading") {
                    SetupProviderStateCard(
                        title = stringResource(R.string.provider_catalog_loading),
                    )
                }

                providerState.value.error != null -> item(key = "setup_provider_error") {
                    SetupProviderStateCard(
                        title = stringResource(R.string.provider_catalog_load_failed),
                        summary = localizeProviderError(
                            providerState.value.error,
                            providerErrorTexts,
                            unknownErrorText,
                        ),
                        actionText = stringResource(R.string.setup_provider_retry),
                        onClick = {
                            coroutineScope.launch {
                                refreshSetupProviders(context, providerStateFlow)
                            }
                        },
                    )
                }

                else -> providerState.value.items
                    .filter { provider -> provider.catalog.available }
                    .forEach { provider ->
                    item(key = "setup_provider_${provider.catalog.id}") {
                        val busy = provider.catalog.id in providerState.value.busyPluginIds
                        val added = provider.installed
                        val available = provider.catalog.available
                        val versionText = provider.catalog.versionName
                            ?: stringResource(R.string.unknown)
                        val statusText = if (available) {
                            stringResource(R.string.provider_status_available, versionText)
                        } else {
                            stringResource(R.string.provider_status_unavailable)
                        }
                        Card(modifier = Modifier
                            .fillMaxWidth()
                            .setupStagger(5)) {
                            ProComponent(
                                title = provider.catalog.displayName,
                                summary = "$statusText\n${provider.catalog.targetPackages.joinToString()}",
                                onClick = if (!added && !busy && available) {
                                    { installProvider(provider) }
                                } else {
                                    null
                                },
                                endActions = {
                                    Text(
                                        text = when {
                                            added -> stringResource(R.string.setup_provider_added)
                                            busy -> stringResource(R.string.provider_status_downloading)
                                            available -> stringResource(R.string.provider_action_download)
                                            else -> stringResource(R.string.provider_status_unavailable)
                                        },
                                        color = if (!added && (available || busy)) {
                                            MiuixTheme.colorScheme.primary
                                        } else {
                                            MiuixTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                        },
                                        fontSize = 14.sp,
                                    )
                                },
                                enabled = !added && !busy && available,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompletionPage(appleMusicOnly: Boolean = false) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(LocalSetupBarPadding.current)
            .padding(horizontal = 28.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        SetupCompletionMark(modifier = Modifier.setupStagger(3))
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.title_setup_complete),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.setupStagger(4)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(
                if (appleMusicOnly) R.string.setup_completion_apple_music_only
                else R.string.setup_completion_restart_hint
            ),
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.setupStagger(5)
        )
        // 视觉重心略高于正中。
        Spacer(modifier = Modifier.height(48.dp))
    }
}

@Composable
private fun SetupProviderStateCard(
    title: String,
    summary: String? = null,
    actionText: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Card(modifier = Modifier
        .fillMaxWidth()
        .setupStagger(5)) {
        ProComponent(
            title = title,
            summary = summary,
            onClick = onClick,
            endActions = if (actionText != null) {
                {
                    Text(
                        text = actionText,
                        color = MiuixTheme.colorScheme.primary,
                        fontSize = 14.sp,
                    )
                }
            } else {
                null
            },
            showIndication = onClick != null,
        )
    }
}

private suspend fun refreshSetupProviders(
    context: Context,
    stateFlow: MutableStateFlow<OfficialProviderUiState>,
) {
    stateFlow.update { state -> state.copy(isLoading = true, error = null) }
    runCatching { OfficialProviderRepository.loadItems(context) }
        .onSuccess { items ->
            stateFlow.value = OfficialProviderUiState(items = items)
        }
        .onFailure { error ->
            stateFlow.value = OfficialProviderUiState(error = error.message)
        }
}

@Composable
private fun DisclaimerPage() {
    SetupPageColumn(title = stringResource(R.string.title_disclaimer)) {
        item(key = "setup_enhanced_notice") {
            EnhancedVersionNotice(modifier = Modifier
                .fillMaxWidth()
                .setupStagger(1))
        }
        item(key = "setup_disclaimer") {
            Card(modifier = Modifier
                .fillMaxWidth()
                .setupStagger(2)) {
                BasicComponent(
                    title = stringResource(R.string.title_disclaimer_notice),
                    summary = stringResource(R.string.summary_disclaimer)
                )
            }
        }
    }
}
