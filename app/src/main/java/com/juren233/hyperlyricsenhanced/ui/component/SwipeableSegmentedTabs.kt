/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.ui.component

import com.juren233.hyperlyricsenhanced.ui.skin.SkinTheme
import com.juren233.hyperlyricsenhanced.ui.skin.Text
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import com.juren233.hyperlyricsenhanced.ui.skin.LocalUiStyle
import com.juren233.hyperlyricsenhanced.ui.skin.isGlasmio
import io.github.neoruaa.glasmio.ui.GlasmioSegmentedControl
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.squircle.squircleBackground

/**
 * Segmented switcher whose thumb tracks [pagerState] live. Dragging the thumb scrolls the pager
 * with it and settles on the nearest page; tapping a segment animates to that page. Sizes and
 * colors follow miuix's TabRowWithContour so it sits beside the other tab rows unchanged.
 */
@Composable
fun SwipeableSegmentedTabs(
    labels: List<String>,
    pagerState: PagerState,
    modifier: Modifier = Modifier,
    trackColor: Color = SkinTheme.colors.surfaceContainer,
    thumbColor: Color = SkinTheme.colors.primary,
    selectedContentColor: Color = SkinTheme.colors.onPrimary,
    contentColor: Color = SkinTheme.colors.onSurfaceVariantSummary,
) {
    val count = labels.size
    val scope = rememberCoroutineScope()
    if (LocalUiStyle.current.isGlasmio) {
        // Glasmio：分段控件自身带滑块动画；页面横滑时跟随 currentPage，点选则动画翻页。
        GlasmioSegmentedControl(
            items = labels.mapIndexed { index, label -> index to label },
            selected = pagerState.currentPage,
            onSelected = { index -> scope.launch { pagerState.animateScrollToPage(index.coerceIn(0, count - 1)) } },
            modifier = modifier.fillMaxWidth(),
        )
        return
    }
    val position = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
        .coerceIn(0f, (count - 1).coerceAtLeast(0).toFloat())
    fun goTo(index: Int) {
        scope.launch { pagerState.animateScrollToPage(index.coerceIn(0, count - 1)) }
    }
    fun settle() = goTo((pagerState.currentPage + pagerState.currentPageOffsetFraction).roundToInt())

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(45.dp)
            .squircleBackground(color = trackColor, cornerRadius = 13.dp)
            .padding(5.dp)
            .pointerInput(count) {
                detectTapGestures { offset -> goTo((offset.x / (size.width / count)).toInt()) }
            }
            .pointerInput(count, pagerState) {
                detectHorizontalDragGestures(
                    onDragEnd = ::settle,
                    onDragCancel = ::settle,
                ) { change, deltaX ->
                    change.consume()
                    val pageSize = pagerState.layoutInfo.pageSize + pagerState.layoutInfo.pageSpacing
                    // One segment of thumb travel equals one pager page.
                    if (pageSize > 0) pagerState.dispatchRawDelta(deltaX / (size.width / count) * pageSize)
                }
            },
    ) {
        val segmentWidth = maxWidth / count
        val segmentPx = with(LocalDensity.current) { segmentWidth.toPx() }
        Box(
            modifier = Modifier
                .width(segmentWidth)
                .fillMaxHeight()
                .offset { IntOffset((position * segmentPx).roundToInt(), 0) }
                .squircleBackground(color = thumbColor, cornerRadius = 8.dp),
        )
        Row(modifier = Modifier.fillMaxSize()) {
            labels.forEachIndexed { index, label ->
                val nearness = (1f - abs(position - index)).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics {
                            role = Role.Tab
                            selected = pagerState.currentPage == index
                            onClick { goTo(index); true }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        fontSize = SkinTheme.textStyles.body2.fontSize,
                        fontWeight = if (nearness >= 0.5f) FontWeight.Bold else FontWeight.Normal,
                        color = lerp(contentColor, selectedContentColor, nearness),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}
