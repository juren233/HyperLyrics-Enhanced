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
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.VerticalDivider
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * 平板「平行窗口」双栏渲染器（miuix-nav 版）。可见双栏组恒等于 (当前页面的层级父页面 | 当前页面)：
 * - 在左栏页面（含主页全宽时）点入口：新子页面在右栏打开、左栏不动；同级的其余子页面
 *   同样只在右栏打开（右栏内容以推入滑动切换，左栏保持点击的那个页面）。
 * - 在右栏页面往深处点：整组左移一栏，右栏页面连续移入左栏，新页面自右缘滑入。
 * - 返回（含预测性返回跟手）整体反向。
 *
 * miuix-nav 的 NavEntry 为 internal，无法经库侧解析页面内容，页面 composable 由
 * AppNavigation 的注册表经 [contentFor] 提供。双栏不经 NavDisplay：直接读返回栈渲染
 * 栈顶三级页面（祖父级 + 双栏组），组内位移由单个 Animatable 随栈深驱动；页面
 * Saveable 状态按路由键分桶保存，等价于单栏 NavDisplay 的状态装配。仅宽屏调用，
 * 窄屏由 miuix NavDisplay 单栏渲染。
 */
@Composable
internal fun ParallelWorldNavDisplay(
    backStack: List<NavKey>,
    contentFor: (NavKey) -> @Composable (NavKey) -> Unit,
) {
    if (backStack.isEmpty()) return
    // SnapshotStateList 读操作会被快照订阅：内容变化触发重组，这里取即时快照即可
    val keys = backStack.toList()
    ParallelWorldLayout(
        depth = keys.size,
        entries = keys.takeLast(COMPOSED_ENTRY_COUNT),
        contentFor = contentFor,
    )
}

private const val COMPOSED_ENTRY_COUNT = 3

private class ParallelWorldSlot(
    val index: Int,
    val key: NavKey,
)

private const val SLIDE_DURATION = 400
private const val PREDICTIVE_CANCEL_DURATION = 250
/** 右栏同级切换/推入时旧页面视差退让比例的分母（退让 1/N 栏宽）。 */
private const val PUSH_PARALLAX_DIVISOR = 3
/** 平行窗口相关分割线（栏间 + 侧栏右缘）在主题色基础上的透明度，比普通列表分割线更淡。 */
internal const val PARALLEL_WINDOW_DIVIDER_ALPHA = 0.5f

@Composable
private fun ParallelWorldLayout(
    depth: Int,
    entries: List<NavKey>,
    contentFor: (NavKey) -> @Composable (NavKey) -> Unit,
) {
    if (depth <= 0 || entries.isEmpty()) return
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val saveableStateHolder = rememberSaveableStateHolder()

    // 归一化组位置：0 = 单栏全宽；1 = (父页面 | 第 1 层)；2 = (第 1 层 | 第 2 层)；依此类推
    val worldPosition = remember { Animatable((depth - 1).coerceAtLeast(0).toFloat()) }
    // 待渲染槽位 = 栈顶三级页面 + 出栏动画尚未结束的幽灵页面，保证页面实例连续
    val slots = remember { mutableStateListOf<ParallelWorldSlot>() }
    // 各路由键所在栈深（栈底为 1），用于区分「同级切换」和「层级推进」
    val depthByKey = remember { mutableMapOf<Any, Int>() }

    remember(entries) {
        val firstIndex = depth - entries.size
        entries.forEachIndexed { k, routeKey ->
            val index = firstIndex + k
            depthByKey[routeKey] = index + 1
            val slot = ParallelWorldSlot(index, routeKey)
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
                        PaneContent(
                            slotKey = slot.key,
                            depthByKey = depthByKey,
                            contentFor = contentFor,
                            saveableStateHolder = saveableStateHolder,
                        )
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
private fun PaneContent(
    slotKey: NavKey,
    depthByKey: Map<Any, Int>,
    contentFor: (NavKey) -> @Composable (NavKey) -> Unit,
    saveableStateHolder: SaveableStateHolder,
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
    ) { routeKey ->
        Box(modifier = Modifier.fillMaxSize()) {
            // saveable 状态按与 miuix NavDisplay 相同的约定以 contentKey.toString() 分桶；
            // SaveableStateProvider 的键必须能进 Bundle，不能直接用路由实例
            saveableStateHolder.SaveableStateProvider(routeKey.toString()) {
                contentFor(routeKey)(routeKey)
            }
        }
    }
}
