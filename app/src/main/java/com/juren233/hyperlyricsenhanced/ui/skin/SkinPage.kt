/*
 * Copyright 2026 juren233
 */
package com.juren233.hyperlyricsenhanced.ui.skin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.ui.component.CompactBarTitle
import com.juren233.hyperlyricsenhanced.ui.utils.BlurredBar
import com.juren233.hyperlyricsenhanced.ui.utils.pageScrollModifiers
import com.juren233.hyperlyricsenhanced.ui.utils.rememberBlurBackdrop
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 大标题开始收起为顶栏小标题的收起比例，与 Miuix 顶栏内部阈值一致。 */
private const val COLLAPSED_TITLE_FRACTION = 1f / 3f

/**
 * 列表页外壳：顶栏 + 滚动列表。
 * Miuix 风格 = Scaffold + 模糊顶栏 + LazyColumn；
 * Glasmio 风格 = [GlasmioPinnedListPage]（固定导航栏、渐进模糊、大标题折叠，均由 Glasmio 自身负责）。
 */
@Composable
fun SkinListPage(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    collapsedTitle: String? = null,
    outerPadding: PaddingValues = PaddingValues(),
    onBack: (() -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    snackbarHostState: SkinSnackbarHostState? = null,
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    if (LocalUiStyle.current.isGlasmio) {
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Box(modifier) {
            SkinGlasmioListPage(
                title = title,
                subtitle = subtitle.ifEmpty { null },
                bottomPadding = navBottom + outerPadding.calculateBottomPadding(),
                listState = listState,
                navigation = when {
                    leadingContent != null -> leadingContent
                    onBack != null -> {
                        {
                            GlasmioIconButton(onClick = onBack) {
                                SfIcon(SfSymbol.ChevronBack, stringResource(R.string.back), mirrored = true)
                            }
                        }
                    }
                    else -> null
                },
                actions = { trailingContent?.invoke(this) },
                content = content,
            )
            snackbarHostState?.let { SkinSnackbarHost(it) }
        }
        return
    }

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
    val scrollBehavior = MiuixScrollBehavior()
    val collapsed by remember(scrollBehavior) {
        derivedStateOf { scrollBehavior.state.collapsedFraction >= COLLAPSED_TITLE_FRACTION }
    }
    val barSubtitle = if (collapsedTitle != null && collapsed) "" else subtitle
    val navigationIcon: @Composable () -> Unit = {
        when {
            leadingContent != null -> leadingContent()
            onBack != null -> IconButton(onClick = onBack) {
                Icon(icon = SkinIcons.Back, contentDescription = stringResource(R.string.back))
            }
        }
    }
    Scaffold(
        modifier = modifier,
        snackbarHost = { snackbarHostState?.let { SkinSnackbarHost(it) } },
        topBar = {
            BlurredBar(backdrop, blurActive) {
                val bar: @Composable () -> Unit = {
                    if (collapsedTitle != null) {
                        TopAppBar(
                            color = barColor,
                            title = collapsedTitle,
                            largeTitle = title,
                            subtitle = barSubtitle,
                            scrollBehavior = scrollBehavior,
                            navigationIcon = navigationIcon,
                            actions = { trailingContent?.invoke(this) },
                        )
                    } else {
                        TopAppBar(
                            color = barColor,
                            title = title,
                            subtitle = subtitle,
                            scrollBehavior = scrollBehavior,
                            navigationIcon = navigationIcon,
                            actions = { trailingContent?.invoke(this) },
                        )
                    }
                }
                if ((leadingContent != null || onBack != null) && trailingContent != null) {
                    CompactBarTitle { bar() }
                } else {
                    bar()
                }
            }
        },
    ) { padding ->
        val contentPadding = remember(padding, outerPadding) {
            PaddingValues(
                top = padding.calculateTopPadding() + outerPadding.calculateTopPadding(),
                start = 0.dp,
                end = 0.dp,
                bottom = padding.calculateBottomPadding() + outerPadding.calculateBottomPadding() + 16.dp,
            )
        }
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                state = listState,
                modifier = Modifier.pageScrollModifiers(
                    enableScrollEndHaptic = true,
                    showTopAppBar = true,
                    topAppBarScrollBehavior = scrollBehavior,
                ),
                contentPadding = contentPadding,
                content = content,
            )
        }
    }
}
