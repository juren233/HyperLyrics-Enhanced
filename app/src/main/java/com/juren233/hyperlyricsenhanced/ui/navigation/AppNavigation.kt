package com.juren233.hyperlyricsenhanced.ui.navigation

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.ui.page.MainPage
import com.juren233.hyperlyricsenhanced.ui.page.SetupPage
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
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.SuperIslandSettingsPage
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.SuperIslandAlbumCoverWhitelistPage
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

@Composable
fun AppNavigation(startRoute: Route) {
    val backStack = rememberNavBackStack(startRoute)
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
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == UIConstants.KEY_PARALLEL_WINDOW_UI) {
                parallelWindowUiEnabled = p.getBoolean(UIConstants.KEY_PARALLEL_WINDOW_UI, UIConstants.DEFAULT_PARALLEL_WINDOW_UI)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val parallelWindowUi = isWideScreen && parallelWindowUiEnabled
    val sceneStrategies = remember(parallelWindowUi) {
        listOf(ParallelWorldSceneStrategy<NavKey>(parallelWindowUi))
    }

    CompositionLocalProvider(LocalNavigator provides navigator) {
        val entryProvider = remember(backStack) {
            entryProvider<NavKey> {
                entry<Route.Setup> {
                    SetupPage(onNavigateToMain = {
                        navigator.popUpTo(Route.Setup, inclusive = true)
                        navigator.navigate(Route.Main)
                    })
                }
                entry<Route.Main> { MainPage() }
                
                entry<Route.Settings> { SettingsPage() }
                entry<Route.HookSettings> { HookSettingsPage() }
                entry<Route.AppleMusicOptimization> { AppleMusicOptimizationPage() }
                entry<Route.LyricProvider> { LyricProviderPage() }
                entry<Route.LyricProviderDownloads> { OfficialProviderDownloadPage() }
                entry<Route.LyricAnimation> { LyricAnimationPage() }
                entry<Route.LyricSettings> { LyricSettingsPage() }
                entry<Route.OnlineTranslationSources> { OnlineTranslationSourcesPage() }
                entry<Route.LyricDisplay> { LyricDisplayPage() }
                entry<Route.LyricScroll> { LyricScrollPage() }
                entry<Route.VerbatimLyric> { VerbatimLyricPage() }
                entry<Route.LyricTranslation> { LyricTranslationPage() }
                entry<Route.SuperIslandSettings> { SuperIslandSettingsPage() }
                entry<Route.SuperIslandAlbumCoverWhitelist> { SuperIslandAlbumCoverWhitelistPage() }
                entry<Route.MediaCardSettings> { MediaCardSettingsPage() }
                entry<Route.LockScreenAodSettings> { LockScreenAodSettingsPage() }
                entry<Route.ClassicAodSettings> { ClassicAodSettingsPage() }
                entry<Route.DynamicIslandNotification> { DynamicIslandNotificationPage() }
                entry<Route.Log> { LogPage() }
                entry<Route.Licenses> { LicensesPage() }
                entry<Route.Poetry> { PoetryPage() }
                entry<Route.Help> { HelpPage() }
                entry<Route.Changelog> { ChangelogPage() }
                entry<Route.Contributors> { ContributorsPage() }
            }
        }
        val entries = rememberDecoratedNavEntries(
            backStack = backStack, 
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
            entryProvider = entryProvider
        )
        
        NavDisplay(
            entries = entries,
            onBack = { navigator.pop() },
            sceneStrategies = sceneStrategies,
        )
    }
}
