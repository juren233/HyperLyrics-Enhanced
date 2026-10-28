package com.juren233.hyperlyricsenhanced.ui.navigation

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavEntryBuilder
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import kotlin.reflect.KClass
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.ui.page.MAIN_PAGE_OUTRO_STAGES
import com.juren233.hyperlyricsenhanced.ui.page.MAIN_STAGE_SHELL
import com.juren233.hyperlyricsenhanced.ui.page.MainPage
import com.juren233.hyperlyricsenhanced.ui.page.SetupPage
import com.juren233.hyperlyricsenhanced.ui.page.setup.LocalSetupOutro
import com.juren233.hyperlyricsenhanced.ui.page.setup.SetupOutroAwareTransition
import com.juren233.hyperlyricsenhanced.ui.page.setup.SetupOutroHost
import com.juren233.hyperlyricsenhanced.ui.page.setup.rememberSetupOutroController
import com.juren233.hyperlyricsenhanced.ui.page.setup.rememberSetupOutroStage
import com.juren233.hyperlyricsenhanced.ui.page.setup.setupOutroDestination
import com.juren233.hyperlyricsenhanced.ui.page.setup.setupOutroTraceLayout
import com.juren233.hyperlyricsenhanced.ui.page.LicensesPage
import com.juren233.hyperlyricsenhanced.ui.page.LogPage
import com.juren233.hyperlyricsenhanced.ui.page.SettingsPage
import com.juren233.hyperlyricsenhanced.ui.page.PoetryPage
import com.juren233.hyperlyricsenhanced.ui.page.HookSettingsPage
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.LyricProviderPage
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.OfficialProviderDownloadPage
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.LyricAnimationPage
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.LyricSettingsPage
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.OnlineTranslationSourcesPage
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.AppleMusicOptimizationPage
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.HyperIslandSettingsPage
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.HyperIslandTouchSettingsPage
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.HyperIslandAlbumCoverWhitelistPage
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.MediaSessionOnlineFallbackPlayersPage
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.media.MediaCardSettingsPage
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.aod.ClassicAodSettingsPage
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.aod.LockScreenAodSettingsPage
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.display.LyricDisplayPage
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.scroll.LyricScrollPage
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.translation.LyricTranslationPage
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.verbatim.VerbatimLyricPage
import com.juren233.hyperlyricsenhanced.ui.page.DynamicIslandNotificationPage
import com.juren233.hyperlyricsenhanced.ui.page.HelpPage
import com.juren233.hyperlyricsenhanced.ui.page.ChangelogPage
import com.juren233.hyperlyricsenhanced.ui.page.ContributorsPage
import com.juren233.hyperlyricsenhanced.ui.utils.rememberIsWideScreen

/**
 * 路由内容注册表：宽屏平行窗口渲染器不经 miuix NavDisplay（其 NavEntry 为 internal），
 * 以路由 KClass 查表取得页面 composable；窄屏 NavDisplay 经 [appEntry] 消费同一份注册。
 */
private object RouteContentRegistry {
    val contents = mutableMapOf<KClass<out NavKey>, @Composable (NavKey) -> Unit>()
}

private fun contentFor(key: NavKey): @Composable (NavKey) -> Unit {
    return RouteContentRegistry.contents[key::class]
        ?: error("No content registered for route: ${key::class.simpleName}")
}

private inline fun <reified T : Route> NavEntryBuilder.appEntry(
    swipeDismiss: NavSwipeDirection = NavSwipeDirection.LeftToRight,
    transition: NavTransition? = null,
    noinline content: @Composable (T) -> Unit,
) {
    @Suppress("UNCHECKED_CAST")
    RouteContentRegistry.contents[T::class] = { key -> content(key as T) }
    // contentKey 同时是 saveable 状态身份且必须能进 Bundle（NavSaveableStateHolder 的
    // SaveableStateProvider 限制），data object 路由实例本身不可存，用其值派生字符串
    entry(contentKey = { it.toString() }, transition = transition, swipeDismiss = swipeDismiss, content = content)
}

@Composable
fun AppNavigation(startRoute: Route) {
    val backStack = rememberNavBackStack<Route>(startRoute)
    val navigator = remember { Navigator(backStack) }
    val isWideScreen = rememberIsWideScreen()
    // 平行窗口 UI 开关（设置页可控）：与宽屏条件共同决定是否启用双栏场景
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
    }
    var parallelWindowUiEnabled by remember {
        mutableStateOf(prefs.getBoolean(UIConstants.KEY_PARALLEL_WINDOW_UI, UIConstants.DEFAULT_PARALLEL_WINDOW_UI))
    }
    var swipeBackGestureEnabled by remember {
        mutableStateOf(prefs.getBoolean(UIConstants.KEY_SWIPE_BACK_GESTURE, UIConstants.DEFAULT_SWIPE_BACK_GESTURE))
    }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            when (key) {
                UIConstants.KEY_PARALLEL_WINDOW_UI -> {
                    parallelWindowUiEnabled = p.getBoolean(UIConstants.KEY_PARALLEL_WINDOW_UI, UIConstants.DEFAULT_PARALLEL_WINDOW_UI)
                }
                UIConstants.KEY_SWIPE_BACK_GESTURE -> {
                    swipeBackGestureEnabled = p.getBoolean(UIConstants.KEY_SWIPE_BACK_GESTURE, UIConstants.DEFAULT_SWIPE_BACK_GESTURE)
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val parallelWindowUi = isWideScreen && parallelWindowUiEnabled
    // 「滑动手势返回上一页」开关：关闭后窄屏 NavDisplay 不挂载滑动返回手势
    val swipeDismiss = if (swipeBackGestureEnabled) NavSwipeDirection.LeftToRight else NavSwipeDirection.None

    // 基础设置完成转场的图标画在导航之上，跨越页面切换继续演出。
    val setupOutro = rememberSetupOutroController()
    val setupOutroTransition = remember(setupOutro) { SetupOutroAwareTransition(setupOutro) }

    CompositionLocalProvider(LocalNavigator provides navigator, LocalSetupOutro provides setupOutro) {
        // 路由注册的单一事实源：appEntry 同时填充宽屏注册表并完成窄屏 entry 注册。
        val entryBuilder: NavEntryBuilder.() -> Unit = {
            appEntry<Route.Setup>(swipeDismiss = swipeDismiss, transition = setupOutroTransition) {
                SetupPage(onNavigateToMain = {
                    navigator.popUpTo(Route.Setup, inclusive = true)
                    navigator.navigate(Route.Main)
                })
            }
            appEntry<Route.SetupReview>(swipeDismiss = swipeDismiss, transition = setupOutroTransition) {
                SetupPage(onNavigateToMain = { navigator.popUpTo(Route.SetupReview, inclusive = true) })
            }
            // 基础设置完成转场结束时，主页面与设置页以逐项浮现的样式显露；主页面在转场中分帧组合，
            // 切换页面的那一帧只建空白路由。
            appEntry<Route.Main>(swipeDismiss = swipeDismiss, transition = setupOutroTransition) {
                val stage = rememberSetupOutroStage(MAIN_PAGE_OUTRO_STAGES)
                Box(Modifier.setupOutroDestination(setupOutro).setupOutroTraceLayout("MainRoute")) {
                    if (stage >= MAIN_STAGE_SHELL) MainPage(outroStage = stage)
                }
            }

            appEntry<Route.Settings>(swipeDismiss = swipeDismiss, transition = setupOutroTransition) { settings ->
                Box(Modifier.setupOutroDestination(setupOutro)) {
                    SettingsPage(scrollToFeatureSwitches = settings.scrollToFeatureSwitches)
                }
            }
            appEntry<Route.HookSettings>(swipeDismiss = swipeDismiss) { HookSettingsPage() }
            appEntry<Route.AppleMusicOptimization>(swipeDismiss = swipeDismiss) { AppleMusicOptimizationPage() }
            appEntry<Route.LyricProvider>(swipeDismiss = swipeDismiss) { LyricProviderPage() }
            appEntry<Route.LyricProviderDownloads>(swipeDismiss = swipeDismiss) { OfficialProviderDownloadPage() }
            appEntry<Route.LyricAnimation>(swipeDismiss = swipeDismiss) { LyricAnimationPage() }
            appEntry<Route.LyricSettings>(swipeDismiss = swipeDismiss) { LyricSettingsPage() }
            appEntry<Route.OnlineTranslationSources>(swipeDismiss = swipeDismiss) { OnlineTranslationSourcesPage() }
            appEntry<Route.LyricDisplay>(swipeDismiss = swipeDismiss) { LyricDisplayPage() }
            appEntry<Route.LyricScroll>(swipeDismiss = swipeDismiss) { LyricScrollPage() }
            appEntry<Route.VerbatimLyric>(swipeDismiss = swipeDismiss) { VerbatimLyricPage() }
            appEntry<Route.LyricTranslation>(swipeDismiss = swipeDismiss) { LyricTranslationPage() }
            appEntry<Route.HyperIslandTouchSettings>(swipeDismiss = swipeDismiss) { HyperIslandTouchSettingsPage() }
            appEntry<Route.HyperIslandSettings>(swipeDismiss = swipeDismiss) { HyperIslandSettingsPage() }
            appEntry<Route.HyperIslandAlbumCoverWhitelist>(swipeDismiss = swipeDismiss) { HyperIslandAlbumCoverWhitelistPage() }
            appEntry<Route.MediaSessionOnlineFallbackPlayers>(swipeDismiss = swipeDismiss) { MediaSessionOnlineFallbackPlayersPage() }
            appEntry<Route.MediaCardSettings>(swipeDismiss = swipeDismiss) { MediaCardSettingsPage() }
            appEntry<Route.LockScreenAodSettings>(swipeDismiss = swipeDismiss) { LockScreenAodSettingsPage() }
            appEntry<Route.ClassicAodSettings>(swipeDismiss = swipeDismiss) { ClassicAodSettingsPage() }
            appEntry<Route.DynamicIslandNotification>(swipeDismiss = swipeDismiss) { DynamicIslandNotificationPage() }
            appEntry<Route.Log>(swipeDismiss = swipeDismiss) { LogPage() }
            appEntry<Route.Licenses>(swipeDismiss = swipeDismiss) { LicensesPage() }
            appEntry<Route.Poetry>(swipeDismiss = swipeDismiss) { PoetryPage() }
            appEntry<Route.Help>(swipeDismiss = swipeDismiss) { HelpPage() }
            appEntry<Route.Changelog>(swipeDismiss = swipeDismiss) { ChangelogPage() }
            appEntry<Route.Contributors>(swipeDismiss = swipeDismiss) { ContributorsPage() }
        }
        // 宽屏时 NavDisplay 不组合、其 builder 不会执行，组合期间无条件预热一次注册表
        remember { NavEntryBuilder().apply(entryBuilder) }

        Box(Modifier.fillMaxSize()) {
            if (parallelWindowUi) {
                ParallelWorldNavDisplay(backStack, ::contentFor)
            } else {
                NavDisplay(
                    backStack = backStack,
                    onBack = { navigator.pop() },
                    // 默认半径为 0；转场页面的可见边缘应跟随设备屏幕圆角。
                    effects = NavDisplayEffects(
                        cornerClipRadius = rememberNavSystemCornerRadius(),
                    ),
                    content = entryBuilder,
                )
            }
            SetupOutroHost(setupOutro)
        }
    }
}
