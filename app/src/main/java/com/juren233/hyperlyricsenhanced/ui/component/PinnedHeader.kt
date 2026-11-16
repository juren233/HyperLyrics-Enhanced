/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import kotlin.math.max

/**
 * 一个在列表滚动时固定在顶栏下方的分组头（小标题、筛选、搜索框、标签切换器等）。
 *
 * 页面顶栏的模糊取样列表所在的那一层（`layerBackdrop`）；而顶栏画在列表之上，它的模糊区域
 * 向下延伸盖住头时会把头一起盖掉。所以头分两处：
 * - 列表里是真正的头（[pinnedHeader]）：滚动、拖动、点按与输入都和普通列表项一样。
 *   它自己不画出来（因此也不进模糊取样），只把画面录进 [PinnedHeaderState.layer]。
 * - 顶栏那一层（[PinnedHeaderBarLayout]）把录下的画面画在顶栏之上，位置取真头此刻的坐标。
 *   列表有两层回弹平移：页面的 `overScrollVertical`，以及 Miuix 主题经 `LocalOverscrollFactory`
 *   给 `LazyColumn` 装的 `MiuixOverscrollEffect`（在列表内部）。只有真头的坐标同时包含两层。
 *   真头一移动（滚动、任一层回弹）就直接让这一层重画。
 *
 * 头停住时顶栏模糊按停住进度向下延伸盖住头（`BlurredBar` 的 `pinnedHeight`、`pinnedProgress`），
 * 渐进模糊的规律与平时的顶栏相同，只是区域更高。
 */
@Stable
class PinnedHeaderState internal constructor(
    internal val key: Any,
    internal val layer: GraphicsLayer,
) {
    /** 头的实际高度（px）。 */
    internal var heightPx by mutableIntStateOf(0)

    /** 真头的坐标（下移之前），绘制时读取，含此刻两层回弹的平移。 */
    internal var headerCoordinates: LayoutCoordinates? = null

    internal var canvas: PinnedHeaderCanvasNode? = null

    /**
     * 头之后各列表项最近一次测量的高度（px），按项的 key 记录。只在测量阶段读写，不是快照状态，
     * 读它不会让列表反复重新测量。见 [pinnedHeaderFiller]。
     */
    internal val contentHeights = HashMap<Any, Int>()

    internal fun invalidateCanvas() {
        canvas?.invalidateDraw()
    }
}

/** [key] 须与列表中其它项不同。 */
@Composable
fun rememberPinnedHeaderState(key: Any): PinnedHeaderState {
    val layer = rememberGraphicsLayer()
    return remember(key, layer) { PinnedHeaderState(key, layer) }
}

/**
 * 列表里的头。Compose 的 `stickyHeader` 固定在视口顶端（`-beforeContentPadding`），会落到顶栏底下
 * （`LazyLayoutStickyItems.StickToTopPlacement`），这里按它在列表里的偏移下移，停在内边距之下，
 * 使点按落在画出来的位置上。[zIndex] 让它始终位于其它项之上接收点按。
 */
fun LazyListScope.pinnedHeader(
    state: PinnedHeaderState,
    listState: LazyListState,
    content: @Composable ColumnScope.() -> Unit,
) {
    stickyHeader(key = state.key) {
        Column(
            modifier = Modifier
                .zIndex(1f)
                .fillMaxWidth()
                .onSizeChanged { state.heightPx = it.height }
                // 头随滚动或任一层回弹移动时，顶栏那一层随之重画。图层平移变化时 Compose 也会派发
                // 定位回调（`NodeCoordinator.updateLayerParameters` → `requestOnPositionedCallback`，
                // 派发到子树里所有带回调的节点），在本帧绘制之前。放在下移之前，坐标不含下移。
                .onGloballyPositioned {
                    state.headerCoordinates = it
                    state.invalidateCanvas()
                }
                .graphicsLayer { translationY = listState.pinnedShift(state).toFloat() }
                // 只录不画：画面由顶栏那一层画出。内容变化时重录，顶栏那一层随之重画。
                .drawWithContent {
                    state.layer.record { this@drawWithContent.drawContent() }
                    state.invalidateCanvas()
                },
            content = content,
        )
    }
}

/**
 * 头之后的内容（如分类分页）的最小高度：头停住时这块内容正好填满头以下的可视区域。
 * 内容变矮（切到较短的分类）时列表的可滚动距离仍够头停住，列表不会把滚动位置拉回、把头顶开。
 */
@Composable
fun rememberPinnedHeaderContentMinHeight(state: PinnedHeaderState, listState: LazyListState): Dp {
    val px by remember(state, listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            (info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding - state.heightPx)
                .coerceAtLeast(0)
        }
    }
    return with(LocalDensity.current) { px.toDp() }
}

/**
 * 头之后由多个列表项组成的内容（如歌曲行）：每项挂上它，测量时记下高度，供 [pinnedHeaderFiller] 补足。
 * [key] 须与该项在列表里的 key 相同。
 */
fun Modifier.pinnedHeaderContent(state: PinnedHeaderState, key: Any): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        state.contentHeights[key] = placeable.height
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

/**
 * 头之后多个列表项的末尾补白：平时高 [baseHeight]；头之后的内容（[contentKeys] 各项，见
 * [pinnedHeaderContent]）加上补白不足 [minContentHeight]（[rememberPinnedHeaderContentMinHeight]）时
 * 补到这个高度。搜索、筛选把列表变短时，可滚动距离仍够头停住，列表不会把滚动位置拉回、把头顶开。
 *
 * 补白在同一次测量里排在这些项之后，所以读到的是它们本次（或滚出视野前最近一次）的高度。
 * 有项从未测量过时（还在视野下方很远处），内容一定足够长，按 [baseHeight] 处理。
 */
fun LazyListScope.pinnedHeaderFiller(
    state: PinnedHeaderState,
    key: Any,
    contentKeys: List<Any>,
    minContentHeight: Dp,
    baseHeight: Dp,
) {
    item(key = key) {
        Spacer(
            modifier = Modifier.layout { measurable, constraints ->
                // 只保留当前内容的记录，筛掉的项不再累计。
                state.contentHeights.keys.retainAll(contentKeys.toSet())
                val base = baseHeight.roundToPx()
                var content = 0
                var complete = true
                for (contentKey in contentKeys) {
                    val height = state.contentHeights[contentKey]
                    if (height == null) {
                        complete = false
                        break
                    }
                    content += height
                }
                val height = if (complete) max(base, minContentHeight.roundToPx() - content) else base
                val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, height))
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            },
        )
    }
}

private fun LazyListState.headerOffset(state: PinnedHeaderState): Int? =
    layoutInfo.visibleItemsInfo.firstOrNull { it.key == state.key }?.offset

/** 头需要下移的距离：它在列表中的偏移为负（已滚进顶部内边距）时取其绝对值，否则为 0。 */
private fun LazyListState.pinnedShift(state: PinnedHeaderState): Int =
    (-(headerOffset(state) ?: 0)).coerceAtLeast(0)

/**
 * 头停住的进度：头与顶栏底边的距离从一个头高缩到 0 时由 0 升到 1；头不在可见项里（还在视野下方）时为 0。
 * 顶栏模糊按它逐步向下延伸，不会在头停住的一瞬间突然盖下来。
 */
internal fun LazyListState.pinnedHeaderProgress(state: PinnedHeaderState): Float {
    val height = state.heightPx
    val offset = headerOffset(state) ?: return 0f
    if (height <= 0) return 0f
    return ((height - offset.coerceAtLeast(0)).toFloat() / height).coerceIn(0f, 1f)
}

/**
 * 顶栏那一层：[bar] 照常放在顶端，只上报它自己的高度（页面内容的顶部内边距不变）；
 * 其上叠一层不接收点按的画布画出头。[background] 不为 null 时（设备不支持模糊、顶栏是实色）
 * 先在头的位置铺这层底色，滚过的内容不会透出来。
 */
@Composable
internal fun PinnedHeaderBarLayout(
    state: PinnedHeaderState,
    listState: LazyListState,
    background: Color?,
    bar: @Composable () -> Unit,
) {
    val header: @Composable () -> Unit = {
        Spacer(modifier = Modifier.fillMaxSize().then(PinnedHeaderCanvasElement(state, listState, background)))
    }
    Layout(contents = listOf(bar, header)) { (barMeasurables, headerMeasurables), constraints ->
        val barPlaceables = barMeasurables.map { it.measure(constraints) }
        val headerPlaceables = headerMeasurables.map { it.measure(constraints) }
        val width = barPlaceables.maxOfOrNull { it.width } ?: constraints.minWidth
        val height = barPlaceables.maxOfOrNull { it.height } ?: constraints.minHeight
        layout(width, height) {
            barPlaceables.forEach { it.place(0, 0) }
            headerPlaceables.forEach { it.place(0, 0) }
        }
    }
}

private data class PinnedHeaderCanvasElement(
    val state: PinnedHeaderState,
    val listState: LazyListState,
    val background: Color?,
) : ModifierNodeElement<PinnedHeaderCanvasNode>() {
    override fun create() = PinnedHeaderCanvasNode(state, listState, background)

    override fun update(node: PinnedHeaderCanvasNode) {
        if (node.state !== state) {
            node.state.canvas = null
            node.state = state
            state.canvas = node
        }
        node.listState = listState
        node.background = background
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "pinnedHeaderCanvas"
    }
}

internal class PinnedHeaderCanvasNode(
    var state: PinnedHeaderState,
    var listState: LazyListState,
    var background: Color?,
) : Modifier.Node(), DrawModifierNode {
    override fun onAttach() {
        state.canvas = this
    }

    override fun onDetach() {
        if (state.canvas === this) state.canvas = null
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val info = listState.layoutInfo
        if (info.visibleItemsInfo.none { it.key == state.key }) return
        val header = state.headerCoordinates?.takeIf { it.isAttached } ?: return
        val own = requireLayoutCoordinates().takeIf { it.isAttached } ?: return
        // 真头在本层的位置：未停住时是它在列表里的位置，停住时 stickyHeader 把它放在列表顶端，
        // 都含此刻两层回弹的平移。顶栏底边不随回弹移动。
        val headerOrigin = own.localPositionOf(header, Offset.Zero)
        val barBottom = info.beforeContentPadding.toFloat()
        // 头随列表移动；停住后贴着顶栏底边，底部回弹时不随列表钻进顶栏底下。
        val position = Offset(headerOrigin.x, max(barBottom, headerOrigin.y))
        clipRect(top = barBottom) {
            background?.let { drawRect(it, topLeft = position, size = Size(header.size.width.toFloat(), state.heightPx.toFloat())) }
            translate(position.x, position.y) { drawLayer(state.layer) }
        }
    }
}
