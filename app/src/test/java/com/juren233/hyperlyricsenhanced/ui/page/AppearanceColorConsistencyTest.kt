package com.juren233.hyperlyricsenhanced.ui.page

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.juren233.hyperlyricsenhanced.ui.component.animatedTileColors
import com.juren233.hyperlyricsenhanced.ui.utils.MonetAccentTuning
import com.juren233.hyperlyricsenhanced.ui.utils.MonetPalette
import com.juren233.hyperlyricsenhanced.ui.utils.ThemeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 真机上发现的两处外观配色不一致：
 * 1. 主题预览卡的标签文字色、卡片底色和下面磁贴不是同一套颜色（选中卡标签用 primary，开启的磁贴用加深后的主题色）。
 * 2. 迷你模拟界面的背景、卡片、占位条用写死的灰白/黑灰，开启莫奈后真实页面底色已不是纯白/纯黑，预览却没跟上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AppearanceColorConsistencyTest {

    private class NoNodeApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    /** 在 [themeMode]/[monetColorIndex] 的真实主题里组合一次 [read]，返回读到的值。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> readInTheme(themeMode: Int, monetColorIndex: Int, read: @Composable () -> T): T {
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(TestScope(UnconfinedTestDispatcher()).coroutineContext + clock)
        val recomposer = Recomposer(scope.coroutineContext)
        val composition = Composition(NoNodeApplier(), recomposer)
        var result: T? = null
        try {
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            val context = RuntimeEnvironment.getApplication()
            composition.setContent {
                CompositionLocalProvider(
                    LocalContext provides context,
                    LocalConfiguration provides context.resources.configuration,
                ) {
                    ThemeUtils.AppMiuixTheme(ThemeUtils.themeController(themeMode, monetColorIndex), monetColorIndex) {
                        result = read()
                    }
                }
            }
            Snapshot.sendApplyNotifications()
            clock.sendFrame(1_000_000_000L)
            @Suppress("UNCHECKED_CAST")
            return result as T
        } finally {
            composition.dispose()
            recomposer.cancel()
            scope.cancel()
        }
    }

    private val themeModes = 0..5

    @Test
    fun previewCardColors_matchTileColors_selectedAndUnselected() {
        for (mode in themeModes) {
            for (accent in listOf(0, 1, MonetPalette.keyColors.lastIndex)) {
                for (selected in listOf(true, false)) {
                    val (preview, tile) = readInTheme(mode, accent) {
                        animatedPreviewCardColors(selected) to animatedTileColors(selected)
                    }
                    val where = "themeMode=$mode accent=$accent selected=$selected"
                    assertEquals("$where 预览卡标签文字色应与磁贴标题文字色一致", tile.content, preview.label)
                    assertEquals("$where 预览卡底色应与磁贴底色一致", tile.container, preview.container)
                }
            }
        }
    }

    /** 预览里每种深浅模式的配色，应等于 App 真处在该模式时页面、卡片、开关实际用的颜色。 */
    @Test
    fun miniPreviewPalette_matchesRealThemeOfThatMode() {
        val brightnesses = listOf(ThemeModeSelection.BRIGHTNESS_LIGHT, ThemeModeSelection.BRIGHTNESS_DARK)
        for (monet in listOf(true, false)) {
            for (accent in listOf(0, 1, MonetAccentTuning.RED_INDEX, MonetPalette.keyColors.lastIndex)) {
                for (target in brightnesses) {
                    val targetMode = ThemeModeSelection.combine(target, monet)
                    val real = readInTheme(targetMode, accent) {
                        val c = MiuixTheme.colorScheme
                        PreviewPalette(c.surface, c.surfaceContainer, c.secondary, c.primary)
                    }
                    // 从另一种深浅模式的页面里画这张预览，结果也不能受当前页面主题影响。
                    for (current in brightnesses) {
                        val currentMode = ThemeModeSelection.combine(current, monet)
                        val preview = readInTheme(currentMode, accent) { previewPalette(target, monet, accent) }
                        val where = "当前=$currentMode 预览=$targetMode accent=$accent"
                        assertEquals("$where 预览背景应等于真实页面底色", real.background, preview.background)
                        assertEquals("$where 预览卡片应等于真实卡片底色", real.card, preview.card)
                        assertEquals("$where 预览占位条应等于真实 secondary", real.line, preview.line)
                        assertEquals("$where 预览开关应等于真实 primary", real.accent, preview.accent)
                    }
                }
            }
        }
    }

    /** 真机截图的场景：莫奈开启后浅色页面底色不是固定的 #F7F7F7，深色不是纯黑，预览要跟着变。 */
    @Test
    fun monetOn_previewBackgroundIsNotTheOldFixedGrey() {
        val light = readInTheme(4, 1) { previewPalette(ThemeModeSelection.BRIGHTNESS_LIGHT, monet = true, monetColorIndex = 1) }
        val dark = readInTheme(4, 1) { previewPalette(ThemeModeSelection.BRIGHTNESS_DARK, monet = true, monetColorIndex = 1) }
        assertNotEquals(Color(0xFFF7F7F7), light.background)
        assertNotEquals(Color.White, light.card)
        assertNotEquals(Color.Black, dark.background)
        assertNotEquals(Color(0xFF242424), dark.card)
    }
}
