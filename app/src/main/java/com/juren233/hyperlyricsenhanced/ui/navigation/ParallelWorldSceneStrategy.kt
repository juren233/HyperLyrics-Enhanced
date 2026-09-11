package com.juren233.hyperlyricsenhanced.ui.navigation

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.VerticalDivider
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * 平板「平行窗口」场景。可见双栏组恒等于 (当前页面的层级父页面 | 当前页面)：
 * - 在左栏页面（含主页全宽时）点入口：新子页面在右栏打开、左栏不动；同级的其余子页面
 *   同样只在右栏打开（右栏内容以推入滑动切换，左栏保持点击的那个页面）。
 * - 在右栏页面往深处点：整组左移一栏，右栏页面连续移入左栏，新页面自右缘滑入。
 * - 返回（含预测性返回跟手）整体反向。
 *
 * 场景 key 恒定，NavDisplay 不做整屏切换；组内位移由单个 Animatable 随栈深驱动；
 * 常驻渲染栈顶三级页面（祖父级 + 双栏组），保证预测性返回时祖父级页面可滑入。
 * 仅宽屏启用，窄屏返回 null 由 NavDisplay 回落到单栏场景。
 */
internal class ParallelWorldSceneStrategy<T : Any>(
    private val enabled: Boolean,
) : SceneStrategy<T> {

    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        if (!enabled || entries.isEmpty()) return null
        return ParallelWorldScene(
            key = SCENE_KEY,
            entries = entries.takeLast(VISIBLE_ENTRY_COUNT),
            composed = entries.takeLast(COMPOSED_ENTRY_COUNT),
            backStackDepth = entries.size,
        )
    }
}

private const val SCENE_KEY = "parallel-world"
private const val VISIBLE_ENTRY_COUNT = 2
private const val COMPOSED_ENTRY_COUNT = 3

private class ParallelWorldScene<T : Any>(
    override val key: Any,
    override val entries: List<NavEntry<T>>,
    val composed: List<NavEntry<T>>,
    val backStackDepth: Int,
) : Scene<T> {

    override val previousEntries: List<NavEntry<T>> = emptyList()

    override val content: @Composable () -> Unit = {
        ParallelWorldLayout(depth = backStackDepth, visible = entries, composed = composed)
    }
}

private data class ParallelWorldSlot<T : Any>(
    val index: Int,
    val entry: NavEntry<T>,
)

private const val SLIDE_DURATION = 400
private const val PREDICTIVE_CANCEL_DURATION = 250
/** 右栏同级切换/推入时旧页面视差退让比例的分母（退让 1/N 栏宽）。 */
private const val PUSH_PARALLAX_DIVISOR = 3
/** 平行窗口相关分割线（栏间 + 侧栏右缘）在主题色基础上的透明度，比普通列表分割线更淡。 */
internal const val PARALLEL_WINDOW_DIVIDER_ALPHA = 0.5f

@Composable
private fun <T : Any> ParallelWorldLayout(
    depth: Int,
    visible: List<NavEntry<T>>,
    composed: List<NavEntry<T>>,
) {
    if (depth <= 0 || visible.isEmpty()) return
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()

    // 归一化组位置：0 = 单栏全宽；1 = (父页面 | 第 1 层)；2 = (第 1 层 | 第 2 层)；依此类推
    val worldPosition = remember { Animatable((depth - 1).coerceAtLeast(0).toFloat()) }
    // 待渲染槽位 = 栈顶三级页面 + 出栏动画尚未结束的幽灵页面，保证页面实例连续
    val slots = remember { mutableStateListOf<ParallelWorldSlot<T>>() }
    // 各页面所在栈深（栈底为 1），用于区分「同级切换」和「层级推进」
    val depthByKey = remember { mutableMapOf<Any, Int>() }
    // 栈深键 -> 页面。NavEntry 包装对象随栈重建且 equals 不稳定，
    // 栏内 AnimatedContent 必须以稳定的 contentKey 为动画键
    val entryByKey = remember { mutableMapOf<Any, NavEntry<T>>() }

    remember(composed) {
        val firstIndex = depth - composed.size
        composed.forEachIndexed { k, entry ->
            val index = firstIndex + k
            depthByKey[entry.contentKey] = index + 1
            entryByKey[entry.contentKey] = entry
            val slot = ParallelWorldSlot(index, entry)
            val existing = slots.indexOfFirst { it.index == index }
            if (existing >= 0) slots[existing] = slot else slots.add(slot)
        }
        slots.sortBy { it.index }
        true
    }

    LaunchedEffect(depth) {
        worldPosition.animateTo(
            targetValue = (depth - 1).coerceAtLeast(0).toFloat(),
            animationSpec = tween(durationMillis = SLIDE_DURATION, easing = FastOutSlowInEasing),
        )
        slots.removeAll { it.index < depth - COMPOSED_ENTRY_COUNT || it.index > depth - 1 }
    }

    // 预测性返回：页面组跟手右移（祖父级自左滑入），取消回弹，提交时真正出栈
    PredictiveBackHandler(enabled = depth >= 2) { progress ->
        val settled = (depth - 1).coerceAtLeast(0).toFloat()
        try {
            progress.collect { event ->
                worldPosition.snapTo(settled - event.progress.coerceIn(0f, 1f))
            }
            worldPosition.snapTo(settled - 1f)
            navigator.pop()
        } catch (e: CancellationException) {
            scope.launch {
                worldPosition.animateTo(
                    targetValue = settled,
                    animationSpec = tween(PREDICTIVE_CANCEL_DURATION, easing = FastOutSlowInEasing),
                )
            }
            throw e
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds(),
    ) {
        val containerWidth = constraints.maxWidth.toFloat()
        val density = LocalDensity.current
        val position = worldPosition.value
        val paneWidth = containerWidth / (1f + position.coerceIn(0f, 1f))
        val worldShift = (position - 1f).coerceAtLeast(0f) * paneWidth
        // 左栏（含幽灵退场页）的导航语义：新页面替换右栏，保持双栏组 = (点击页 | 新页)
        val leftNavigator = remember(navigator.backStack) {
            Navigator(navigator.backStack, replaceTop = true)
        }

        slots.forEach { slot ->
            key(slot.index) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset((paneWidth * slot.index - worldShift).roundToInt(), 0) }
                        .width(with(density) { paneWidth.toDp() })
                        .fillMaxHeight(),
                ) {
                    CompositionLocalProvider(
                        LocalNavigator provides if (slot.index >= depth - 1) navigator else leftNavigator,
                    ) {
                        PaneContent(slotKey = slot.entry.contentKey, entryByKey = entryByKey, depthByKey = depthByKey)
                    }
                    // 栏边界分割线跟随各页面左缘（即与左邻页面的真实分界）：
                    // 随分屏整体淡入，滑向屏幕左缘退出时淡出
                    if (slot.index >= 1) {
                        val edgeX = paneWidth * slot.index - worldShift
                        val dividerAlpha = position.coerceIn(0f, 1f) * (edgeX / paneWidth).coerceIn(0f, 1f)
                        if (dividerAlpha > 0f) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .fillMaxHeight()
                                    .graphicsLayer { alpha = dividerAlpha },
                            ) {
                                VerticalDivider(
                                    color = MiuixTheme.colorScheme.dividerLine.copy(alpha = PARALLEL_WINDOW_DIVIDER_ALPHA),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun <T : Any> PaneContent(
    slotKey: Any,
    entryByKey: Map<Any, NavEntry<T>>,
    depthByKey: Map<Any, Int>,
) {
    AnimatedContent(
        targetState = slotKey,
        transitionSpec = {
            // 仅同级替换（栈深不变，如从左栏另开一个入口）做栏内推入滑动；
            // 层级推进/返回由整组位移负责，栏内瞬时切换即可
            val initialDepth = depthByKey[initialState]
            val targetDepth = depthByKey[targetState]
            if (initialDepth != null && initialDepth == targetDepth) {
                ContentTransform(
                    targetContentEnter = slideInHorizontally(
                        animationSpec = tween(SLIDE_DURATION, easing = FastOutSlowInEasing),
                    ) { it },
                    initialContentExit = slideOutHorizontally(
                        animationSpec = tween(SLIDE_DURATION, easing = FastOutSlowInEasing),
                    ) { -it / PUSH_PARALLAX_DIVISOR },
                    targetContentZIndex = 1f,
                )
            } else {
                ContentTransform(EnterTransition.None, ExitTransition.None)
            }
        },
        label = "parallel-world-pane",
    ) { key ->
        val entry = entryByKey[key]
        if (entry != null) {
            Box(modifier = Modifier.fillMaxSize()) {
                entry.Content()
            }
        }
    }
}
