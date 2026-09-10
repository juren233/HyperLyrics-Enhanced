/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.hooksettings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.online.OnlineTranslationSourcePreferences
import com.juren233.hyperlyricsenhanced.online.model.Source
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun OnlineSourceOrderList(
    order: List<Source>,
    enabledSources: Set<Source>,
    sortingVisible: Boolean,
    listState: LazyListState,
    onOrderChange: (List<Source>) -> Unit,
    onCheckedChange: (Source, Boolean) -> Unit,
) {
    val rowHeightPx = with(LocalDensity.current) { SOURCE_ROW_HEIGHT.toPx() }
    val haptic = LocalHapticFeedback.current
    val currentOrder by rememberUpdatedState(order)
    val currentSortingVisible by rememberUpdatedState(sortingVisible)
    val saveOrder by rememberUpdatedState(onOrderChange)
    var session by remember { mutableStateOf<SourceDragSession?>(null) }
    val dropProgress = remember { Animatable(0f) }
    val preview = session?.previewOrder ?: order

    fun finishDrag(cancelled: Boolean) {
        val dragging = session?.takeUnless { it.released } ?: return
        val finished = dragging.finish(cancelled)
        session = finished
        // Persist once on release. Leaving the page during the landing cannot lose the move.
        if (!cancelled && finished.previewOrder != currentOrder) saveOrder(finished.previewOrder)
    }

    LaunchedEffect(sortingVisible) {
        if (!sortingVisible) finishDrag(cancelled = true)
    }
    LaunchedEffect(session?.released) {
        if (session?.released == true) {
            dropProgress.animateTo(1f, tween(SOURCE_MOVE_DURATION_MS, easing = FastOutSlowInEasing))
            session = null
        }
        dropProgress.snapTo(0f)
    }

    // Scroll only enough to reveal this card when it is partially behind the app bar or
    // viewport edge. Compensate the drag by the consumed distance to keep it under the finger.
    LaunchedEffect(session?.source, session?.released, listState, rowHeightPx) {
        var lastFrame = withFrameNanos { it }
        while (session?.released == false) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - lastFrame) / 1_000_000_000f).coerceAtMost(0.032f)
            lastFrame = frame
            val dragging = session?.takeUnless { it.released } ?: break
            val layout = listState.layoutInfo
            val item = layout.visibleItemsInfo.firstOrNull { it.key == "platform_sources" }
                ?: break
            val top = (layout.viewportStartOffset + layout.beforeContentPadding).toFloat()
            val bottom = (layout.viewportEndOffset - layout.afterContentPadding).toFloat()
            val center = item.offset + (dragging.position + 0.5f) * rowHeightPx
            val edge = rowHeightPx * 0.75f
            val desired = when {
                center < top + edge -> -((top + edge - center) / edge).coerceIn(0f, 1f)
                center > bottom - edge -> ((center - bottom + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            } * rowHeightPx * 5f * seconds
            val distance = desired.coerceIn(
                -(top - item.offset).coerceAtLeast(0f),
                (item.offset + order.size * rowHeightPx - bottom).coerceAtLeast(0f),
            )
            if (distance != 0f) {
                val consumed = listState.scrollBy(distance)
                session?.takeUnless { it.released }?.let {
                    session = it.moveBy(consumed / rowHeightPx)
                }
            }
        }
    }

    Box(
        Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth(),
    ) {
        // Keep the stock card as a background only. It must not clip the floating row/shadow.
        Card(Modifier.fillMaxWidth().height(SOURCE_ROW_HEIGHT * order.size)) {}
        Box(Modifier.fillMaxWidth().height(SOURCE_ROW_HEIGHT * order.size)
            .semantics { isTraversalGroup = true }) {
            // A fixed composition order preserves the active pointer-input node throughout
            // reordering; only visual positions and accessibility traversal order change.
            order.sortedBy { it.ordinal }.forEach { source ->
                key(source) {
                    val index = preview.indexOf(source)
                    val active = session?.source == source
                    val animatedPosition by animateFloatAsState(
                        targetValue = index * rowHeightPx,
                        // The dragged row renders from the gesture/drop coordinates below.
                        // Keep its resting position current so landing never reveals a lagging spring.
                        animationSpec = if (active) snap() else spring(
                            dampingRatio = 0.9f,
                            stiffness = 600f,
                        ),
                        label = "sourcePosition",
                    )
                    val lift by animateFloatAsState(
                        targetValue = if (active && session?.released == false) 1f else 0f,
                        animationSpec = tween(SOURCE_MOVE_DURATION_MS, easing = FastOutSlowInEasing),
                        label = "sourceLift",
                    )
                    val moveUp = stringResource(R.string.action_move_source_up)
                    val moveDown = stringResource(R.string.action_move_source_down)
                    val dragLabel = stringResource(R.string.action_drag_source)
                    val priorityLabel = stringResource(R.string.source_priority_description, index + 1)
                    val sourceName = source.displayName()
                    val handleModifier = if (sortingVisible) Modifier
                        .semantics {
                            contentDescription = "$sourceName, $dragLabel"
                            stateDescription = priorityLabel
                            customActions = buildList {
                                fun move(direction: Int): Boolean {
                                    if (session != null || !currentSortingVisible) return false
                                    val from = currentOrder.indexOf(source)
                                    val target = from + direction
                                    if (from < 0 || target !in currentOrder.indices) return false
                                    saveOrder(currentOrder.toMutableList().apply {
                                        add(target, removeAt(from))
                                    })
                                    return true
                                }
                                if (index > 0) add(CustomAccessibilityAction(moveUp) { move(-1) })
                                if (index < order.lastIndex) add(CustomAccessibilityAction(moveDown) { move(1) })
                            }
                        }
                        .pointerInput(source, rowHeightPx) {
                            // Do not key this coroutine on order, index, or session: a swap
                            // would cancel the very gesture that caused it.
                            var ownsGesture = false
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    ownsGesture = session == null && currentSortingVisible
                                    if (ownsGesture) {
                                        session = SourceDragSession(currentOrder.toList(), source)
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                },
                                onDrag = { change, amount ->
                                    if (ownsGesture) {
                                        change.consume()
                                        session = session?.moveBy(amount.y / rowHeightPx)
                                    }
                                },
                                onDragEnd = {
                                    if (ownsGesture) finishDrag(cancelled = false)
                                    ownsGesture = false
                                },
                                onDragCancel = {
                                    if (ownsGesture) finishDrag(cancelled = true)
                                    ownsGesture = false
                                },
                            )
                        } else Modifier
                    val floatingColor = MiuixTheme.colorScheme.surfaceContainer
                    Box(
                        Modifier.fillMaxWidth()
                            .zIndex(if (active) 1f else 0f)
                            .semantics { traversalIndex = index.toFloat() }
                            .graphicsLayer {
                                val drag = session?.takeIf { it.source == source }
                                translationY = if (drag == null) animatedPosition else {
                                    val start = drag.position * rowHeightPx
                                    if (drag.released) start +
                                        (drag.targetIndex * rowHeightPx - start) * dropProgress.value
                                    else start
                                }
                                // Elevation alone conveys lift while preserving the row width.
                                shadowElevation = 10.dp.toPx() * lift
                                shape = RoundedCornerShape(16.dp)
                                clip = false
                            }
                            .background(floatingColor.copy(alpha = if (active) 1f else 0f), RoundedCornerShape(16.dp)),
                    ) {
                        SourceOrderPreference(
                            source = source,
                            priority = index + 1,
                            checked = source in enabledSources,
                            enabled = OnlineTranslationSourcePreferences.canToggleSource(source, enabledSources),
                            sortingVisible = sortingVisible,
                            handleModifier = handleModifier,
                            onCheckedChange = { if (session == null) onCheckedChange(source, it) },
                        )
                    }
                }
            }
        }
    }
}

/** The handle owns the long press; the switch keeps its independent hit target. */
@Composable
private fun SourceOrderPreference(
    source: Source,
    priority: Int,
    checked: Boolean,
    enabled: Boolean,
    sortingVisible: Boolean,
    handleModifier: Modifier,
    onCheckedChange: (Boolean) -> Unit,

) {
    val sourceControlsSlideDistancePx = with(LocalDensity.current) {
        SOURCE_CONTROLS_SLIDE_DISTANCE.toPx()
    }
    val sortingProgress by animateFloatAsState(
        targetValue = if (sortingVisible) 1f else 0f,
        animationSpec = tween(
            durationMillis = SOURCE_CONTROLS_ANIMATION_DURATION_MS,
            easing = FastOutSlowInEasing,
        ),
        label = "sourceSortingControls",
    )
    val prioritySlotWidth = (SOURCE_PRIORITY_BADGE_SIZE + SOURCE_PRIORITY_TO_NAME_GAP) * sortingProgress
    val handleSlotWidth = SOURCE_HANDLE_SLOT_WIDTH * sortingProgress
    val rowEndPadding = SOURCE_ROW_HORIZONTAL_PADDING -
        (SOURCE_ROW_HORIZONTAL_PADDING - SOURCE_ROW_END_PADDING) * sortingProgress
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SOURCE_ROW_HEIGHT)
            .padding(
                start = SOURCE_ROW_HORIZONTAL_PADDING,
                end = rowEndPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Keep the slot and badge measured throughout the animation. Only the
        // slot width and the badge's visual offset change, so the title never
        // jumps when the controls reach their final state.
        Box(
            modifier = Modifier
                .width(prioritySlotWidth)
                .requiredHeight(SOURCE_PRIORITY_BADGE_SIZE)
                .graphicsLayer { clip = true },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .requiredWidth(SOURCE_PRIORITY_BADGE_SIZE)
                    .requiredHeight(SOURCE_PRIORITY_BADGE_SIZE)
                    .graphicsLayer {
                        alpha = sortingProgress
                        translationX = -sourceControlsSlideDistancePx *
                            (1f - sortingProgress)
                    },
            ) {
                SourcePriorityBadge(priority)
            }
        }
        Text(
            text = source.displayName(),
            modifier = Modifier.weight(1f),
            fontSize = MiuixTheme.textStyles.headline1.fontSize,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // The switch always has the same measured height and remains on the
        // row's center line even while the drag-handle slot is entering/leaving.
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
        Box(
            modifier = Modifier
                .width(handleSlotWidth)
                .requiredHeight(SOURCE_HANDLE_SIZE)
                .graphicsLayer { clip = true },
        ) {
            Row(
                modifier = Modifier
                    .requiredWidth(SOURCE_HANDLE_SLOT_WIDTH)
                    .requiredHeight(SOURCE_HANDLE_SIZE)
                    .graphicsLayer {
                        alpha = sortingProgress
                        translationX = sourceControlsSlideDistancePx *
                            (1f - sortingProgress)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.width(SOURCE_SWITCH_TO_HANDLE_GAP))
                Box(
                    modifier = Modifier.size(SOURCE_HANDLE_SIZE).then(handleModifier),
                    contentAlignment = Alignment.Center,
                ) {
                    val handleColor = MiuixTheme.colorScheme.onSurfaceVariantActions
                    Canvas(Modifier.size(22.dp)) {
                        val stroke = 2.dp.toPx()
                        for (fraction in listOf(0.25f, 0.5f, 0.75f)) {
                            drawLine(
                                color = handleColor,
                                start = Offset(2.dp.toPx(), size.height * fraction),
                                end = Offset(size.width - 2.dp.toPx(), size.height * fraction),
                                strokeWidth = stroke,
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 左侧圆形序号使用成对的主题表面色，深色模式会自动反转明暗关系。 */
@Composable
private fun SourcePriorityBadge(priority: Int) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(SOURCE_PRIORITY_BADGE_SIZE)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh),
    ) {
        Text(
            text = priority.toString(),
            color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            fontWeight = FontWeight.SemiBold,
        )
    }
}


@Composable
private fun Source.displayName(): String = when (this) {
    Source.NE -> stringResource(R.string.source_netease_music)
    Source.QM -> stringResource(R.string.source_qq_music)
    Source.KUWO -> stringResource(R.string.source_kuwo_music)
    Source.KUGOU -> stringResource(R.string.source_kugou_music)
    Source.LB -> "LunaBeat TTML"
}

private val SOURCE_ROW_HEIGHT = 64.dp
private val SOURCE_ROW_HORIZONTAL_PADDING = 16.dp
private val SOURCE_ROW_END_PADDING = 12.dp
private val SOURCE_PRIORITY_BADGE_SIZE = 30.dp
private val SOURCE_PRIORITY_TO_NAME_GAP = 16.dp
private val SOURCE_HANDLE_SIZE = 48.dp
private val SOURCE_SWITCH_TO_HANDLE_GAP = 12.dp
private val SOURCE_HANDLE_SLOT_WIDTH = SOURCE_SWITCH_TO_HANDLE_GAP + SOURCE_HANDLE_SIZE
private const val SOURCE_MOVE_DURATION_MS = 220
private const val SOURCE_CONTROLS_ANIMATION_DURATION_MS = 320
private val SOURCE_CONTROLS_SLIDE_DISTANCE = 8.dp
