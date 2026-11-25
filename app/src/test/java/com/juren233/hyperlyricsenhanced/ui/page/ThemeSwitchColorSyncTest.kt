package com.juren233.hyperlyricsenhanced.ui.page

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.juren233.hyperlyricsenhanced.ui.component.HeroTone
import com.juren233.hyperlyricsenhanced.ui.component.animatedHeroPalette
import com.juren233.hyperlyricsenhanced.ui.component.animatedTileColors
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
 * 复现真机现象：在应用设置页切换深浅模式时，页面背景立刻变了，预览卡、磁贴等卡片底色却慢半拍才跟上。
 * 原因是这些卡片用 animateColorAsState 取色，主题变化导致的目标色变化也被当成选中态动画补间 300ms。
 *
 * 这里在一个真实组合里切换主题（ThemeUtils.themeController，与 App 主题同一条路径），用手动推进的帧时钟
 * 精确控制帧：主题切换后的第一帧，卡片颜色就应等于新主题下的最终颜色；另外确认点选（选中态切换）本身仍有过渡动画。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ThemeSwitchColorSyncTest {

    /** 一帧里取到的所有卡片颜色。 */
    private data class Snapshot2(val colors: List<Color>)

    /** 组合里不产出任何界面节点，只读颜色，用空 Applier 即可。 */
    private class NoNodeApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private class Harness(initialThemeMode: Int, initialSelected: Boolean, monetColorIndex: Int) : AutoCloseable {
        var themeMode by mutableIntStateOf(initialThemeMode)
        var selected by mutableStateOf(initialSelected)
        var tone by mutableStateOf(HeroTone.Accent)
        private var latest: Snapshot2? = null
        private val clock = BroadcastFrameClock()
        private val scope = CoroutineScope(TestScope(UnconfinedTestDispatcher()).coroutineContext + clock)
        private val recomposer = Recomposer(scope.coroutineContext)
        private val composition = Composition(NoNodeApplier(), recomposer)
        private var nowNanos = 1_000_000_000L

        init {
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            val context = RuntimeEnvironment.getApplication()
            composition.setContent {
                CompositionLocalProvider(
                    LocalContext provides context,
                    LocalConfiguration provides context.resources.configuration,
                ) {
                    val controller = remember(themeMode) { ThemeUtils.themeController(themeMode, monetColorIndex) }
                    MiuixTheme(controller = controller) {
                        val preview = animatedPreviewCardColors(selected)
                        val tile = animatedTileColors(selected)
                        val hero = animatedHeroPalette(tone)
                        val ring = animatedSwatchRing(MiuixTheme.colorScheme.primary, selected)
                        latest = Snapshot2(
                            listOf(
                                preview.container, preview.label,
                                tile.container, tile.content, tile.status,
                                hero.container, hero.content, hero.secondary, hero.decoration,
                                ring,
                            ),
                        )
                    }
                }
            }
            settle()
        }

        val current: Snapshot2 get() = latest!!

        /** 推进一帧（[millis] 毫秒后），返回这一帧组合出的颜色。 */
        fun frame(millis: Long = 16): Snapshot2 {
            Snapshot.sendApplyNotifications()
            nowNanos += millis * 1_000_000
            clock.sendFrame(nowNanos)
            return current
        }

        fun settle(): Snapshot2 {
            repeat(80) { frame() }
            return current
        }

        override fun close() {
            composition.dispose()
            recomposer.cancel()
            scope.cancel()
        }
    }

    private fun assertThemeSwitchIsImmediate(from: Int, to: Int, selected: Boolean, monetColorIndex: Int = 0) {
        Harness(from, selected, monetColorIndex).use { harness ->
        val before = harness.current
        harness.themeMode = to
        val firstFrame = harness.frame()
        val settled = harness.settle()
        assertNotEquals("主题 $from → $to 应改变卡片颜色", before, settled)
        settled.colors.forEachIndexed { index, color ->
            assertEquals("主题 $from → $to，accent=$monetColorIndex，selected=$selected，第 $index 个颜色在切换后第一帧应已是新主题的颜色", color, firstFrame.colors[index])
        }
        }
    }

    @Test
    fun lightToDark_monetOn_cardColorsFollowThemeInFirstFrame() {
        // 真机截图的场景：莫奈开启，浅色 ⇄ 深色。
        assertThemeSwitchIsImmediate(from = 4, to = 5, selected = true)
        assertThemeSwitchIsImmediate(from = 4, to = 5, selected = false)
    }

    @Test
    fun darkToLight_monetOn_cardColorsFollowThemeInFirstFrame() {
        assertThemeSwitchIsImmediate(from = 5, to = 4, selected = true)
        assertThemeSwitchIsImmediate(from = 5, to = 4, selected = false)
    }

    @Test
    fun lightToDark_monetOnFixedAccent_cardColorsFollowThemeInFirstFrame() {
        assertThemeSwitchIsImmediate(from = 4, to = 5, selected = true, monetColorIndex = 1)
        assertThemeSwitchIsImmediate(from = 5, to = 4, selected = false, monetColorIndex = 1)
    }

    @Test
    fun lightToDark_monetOff_cardColorsFollowThemeInFirstFrame() {
        assertThemeSwitchIsImmediate(from = 1, to = 2, selected = true)
        assertThemeSwitchIsImmediate(from = 1, to = 2, selected = false)
    }

    @Test
    fun selectionChange_stillAnimates() {
        Harness(initialThemeMode = 4, initialSelected = false, monetColorIndex = 0).use { harness ->
        val off = harness.current
        harness.selected = true
        // 第一帧重组并启动动画，第二帧记下动画起点，再过 100ms 应处在半途。
        repeat(2) { harness.frame() }
        val midway = harness.frame(100)
        val on = harness.settle()
        // 底色、标签色、磁贴三色、外圈：中途既不是起点也不是终点，说明点选仍有过渡。
        listOf(0, 1, 2, 3, 4, 9).forEach { index ->
            assertNotEquals("第 $index 个颜色中途不应还是起点", off.colors[index], midway.colors[index])
            assertNotEquals("第 $index 个颜色中途不应已到终点", on.colors[index], midway.colors[index])
        }
        }
    }

    @Test
    fun heroToneChange_stillAnimates() {
        Harness(initialThemeMode = 4, initialSelected = false, monetColorIndex = 0).use { harness ->
        val accent = harness.current
        harness.tone = HeroTone.Error
        // 第一帧重组并启动动画，第二帧记下动画起点，再过 100ms 应处在半途。
        repeat(2) { harness.frame() }
        val midway = harness.frame(100)
        val error = harness.settle()
        (5..8).forEach { index ->
            assertNotEquals("头卡第 $index 个颜色中途不应还是起点", accent.colors[index], midway.colors[index])
            assertNotEquals("头卡第 $index 个颜色中途不应已到终点", error.colors[index], midway.colors[index])
        }
        }
    }
}
