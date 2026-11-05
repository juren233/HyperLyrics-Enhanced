/*
 * Copyright 2026 juren233
 */
package com.juren233.hyperlyricsenhanced.ui.skin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.glass.LocalGlassBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberLayerBackdrop
import io.github.neoruaa.glasmio.scaffold.GlasmioPageBody
import io.github.neoruaa.glasmio.scaffold.rememberGlasmioHeaderCollapse
import io.github.neoruaa.glasmio.scaffold.rememberGlasmioHeaderScrollEdge
import io.github.neoruaa.glasmio.ui.GlasmioTopBar

/**
 * Glasmio 列表页：与 `GlasmioPinnedListPage` 的结构和动效一致（大标题折叠、顶栏渐进模糊、页面采样图层），
 * 区别是顶栏左侧 [navigation] 可放任意内容——官方页面容器只开放返回箭头，而 `GlasmioTopBar` 本身有左侧插槽。
 * 升级 Glasmio 版本时需对照 `IosPinnedListPage.kt` 中的 `GlasmioPinnedListPage` / `GlasmioPinnedPage` 同步。
 */
@Composable
fun SkinGlasmioListPage(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    bottomPadding: Dp,
    listState: LazyListState,
    largeTitleHorizontalPadding: Dp = 16.dp,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val collapseProgress = rememberGlasmioHeaderCollapse(state = listState)
    val scrollEdgeProgress = rememberGlasmioHeaderScrollEdge(listState)
    val pageBackdrop = rememberLayerBackdrop()
    val colors = GlasmioTheme.colors
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val toolbarHeight = statusBarHeight + 62.dp
    val contentPadding = PaddingValues(top = toolbarHeight + 10.dp, bottom = bottomPadding + 24.dp)

    Box(modifier.fillMaxSize()) {
        GlasmioPageBody(
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
            backgroundColor = colors.groupedBackground,
            topBarBlurProgress = scrollEdgeProgress,
            backdrop = pageBackdrop,
        ) { padding ->
            key(listState) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = padding,
                    verticalArrangement = Arrangement.Top,
                ) {
                    item(key = "skin-large-title:$title") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    alpha = 1f - collapseProgress
                                    val scale = 1f - 0.04f * collapseProgress
                                    scaleX = scale
                                    scaleY = scale
                                    transformOrigin = TransformOrigin(0f, 0.5f)
                                }
                                .blur(6.dp * collapseProgress)
                                .offset(y = (-10).dp)
                                .padding(horizontal = largeTitleHorizontalPadding, vertical = 6.dp),
                        ) {
                            Text(
                                text = title,
                                style = IosTypography.largeTitle,
                                fontWeight = FontWeight.Bold,
                                color = colors.content,
                            )
                            subtitle?.let {
                                Text(
                                    text = it,
                                    style = IosTypography.subheadline,
                                    color = colors.secondaryContent,
                                    modifier = Modifier.padding(top = 3.dp),
                                )
                            }
                        }
                    }
                    content()
                }
            }
        }
        CompositionLocalProvider(LocalGlassBackdrop provides pageBackdrop) {
            GlasmioTopBar(
                title = title,
                subtitle = subtitle,
                modifier = Modifier.fillMaxWidth().statusBarsPadding().align(Alignment.TopCenter),
                collapseProgress = collapseProgress,
                navigation = navigation,
                actions = actions,
            )
        }
    }
}
