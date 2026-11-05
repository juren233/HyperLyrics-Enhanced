/*
 * Copyright 2026 juren233
 */
package com.juren233.hyperlyricsenhanced.ui.skin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.ui.utils.BlurredBar
import com.juren233.hyperlyricsenhanced.ui.utils.pageScrollModifiers
import com.juren233.hyperlyricsenhanced.ui.utils.rememberBlurBackdrop
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioSegmentedControl
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop

/**
 * 带标签的列表页。
 * Miuix = 顶栏下方的轮廓标签 + 可横滑的 HorizontalPager；
 * Glasmio = 大标题下方的分段控件（GlasmioSegmentedControl，位于列表首项）+ 单个列表，
 * 切换标签即切换列表内容并保留各标签自己的滚动位置。
 * [pagerState] 两种风格共用：Glasmio 下它只记录当前标签，不横滑。
 * [miuixPage] 供 Miuix 风格下需要整页自定义（如下拉刷新、滚动条）的标签使用；缺省时用 [content] 构建列表。
 */
@Composable
fun SkinTabbedListPage(
    title: String,
    tabs: List<String>,
    pagerState: PagerState,
    listStates: List<LazyListState>,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    snackbarHostState: SkinSnackbarHostState? = null,
    miuixPage: (@Composable (page: Int, padding: PaddingValues, behavior: ScrollBehavior) -> Unit)? = null,
    content: LazyListScope.(page: Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    if (LocalUiStyle.current.isGlasmio) {
        val selected = pagerState.currentPage
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Box(modifier) {
            SkinGlasmioListPage(
                title = title,
                bottomPadding = navBottom,
                listState = listStates[selected],
                navigation = onBack?.let { back ->
                    {
                        GlasmioIconButton(onClick = back) {
                            SfIcon(SfSymbol.ChevronBack, stringResource(R.string.back), mirrored = true)
                        }
                    }
                },
                actions = { trailingContent?.invoke(this) },
            ) {
                item(key = "skin-tab-switcher") {
                    GlasmioSegmentedControl(
                        items = tabs.mapIndexed { index, name -> index to name },
                        selected = selected,
                        onSelected = { index -> scope.launch { pagerState.scrollToPage(index) } },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp),
                    )
                }
                content(selected)
            }
            snackbarHostState?.let { SkinSnackbarHost(it) }
        }
        return
    }

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else SkinTheme.colors.surface
    val scrollBehavior = MiuixScrollBehavior()
    Scaffold(
        modifier = modifier,
        snackbarHost = { snackbarHostState?.let { SkinSnackbarHost(it) } },
        topBar = {
            BlurredBar(backdrop, blurActive) {
                TopAppBar(
                    color = barColor,
                    title = title,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(icon = SkinIcons.Back, contentDescription = stringResource(R.string.back))
                            }
                        }
                    },
                    actions = { trailingContent?.invoke(this) },
                    bottomContent = {
                        TabRowWithContour(
                            tabs = tabs,
                            selectedTabIndex = pagerState.currentPage,
                            onTabSelected = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
                            colors = TabRowDefaults.tabRowColors(
                                backgroundColor = SkinTheme.colors.surfaceContainer,
                                selectedBackgroundColor = SkinTheme.colors.primary,
                                selectedContentColor = SkinTheme.colors.onPrimary,
                            ),
                            maxWidth = Dp.Infinity,
                        )
                    },
                )
            }
        },
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            HorizontalPager(state = pagerState, verticalAlignment = Alignment.Top, beyondViewportPageCount = 1) { page ->
                val top = innerPadding.calculateTopPadding()
                val bottom = innerPadding.calculateBottomPadding()
                val contentPadding = remember(top, bottom) { PaddingValues(top = top, bottom = bottom + 16.dp) }
                if (miuixPage != null) {
                    miuixPage(page, contentPadding, scrollBehavior)
                } else {
                    LazyColumn(
                        state = listStates[page],
                        modifier = Modifier.pageScrollModifiers(
                            enableScrollEndHaptic = true,
                            showTopAppBar = true,
                            topAppBarScrollBehavior = scrollBehavior,
                        ),
                        contentPadding = contentPadding,
                    ) { content(page) }
                }
            }
        }
    }
}
