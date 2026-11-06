package com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.PrefsBridge
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.ui.component.CompactBarTitle
import com.juren233.hyperlyricsenhanced.ui.navigation.LocalNavigator
import com.juren233.hyperlyricsenhanced.ui.utils.BlurredBar
import com.juren233.hyperlyricsenhanced.ui.utils.pageScrollModifiers
import com.juren233.hyperlyricsenhanced.ui.utils.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 大标题开始收起为顶栏小标题的收起比例，与 miuix 顶栏内部阈值一致。 */
private const val COLLAPSED_TITLE_FRACTION = 1f / 3f

@Composable
internal fun rememberHookPrefs(): SharedPreferences {
    val context = LocalContext.current
    return remember(context) {
        context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
    }
}

@Composable
internal fun rememberHookConfigSaver(prefs: SharedPreferences): (String, Any) -> Unit {
    return remember(prefs) {
        { key: String, value: Any ->
            prefs.edit {
                when (value) {
                    is Int -> putInt(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Float -> putFloat(key, value)
                    is String -> putString(key, value)
                }
            }
            when (value) {
                is Int -> PrefsBridge.putInt(key, value)
                is Boolean -> PrefsBridge.putBoolean(key, value)
                is Float -> PrefsBridge.putFloat(key, value)
                is String -> PrefsBridge.putString(key, value)
            }
        }
    }
}

@Composable
internal fun XposedLyricSettingPage(
    title: String,
    subtitle: String = "",
    outerPadding: PaddingValues = PaddingValues(),
    showNavigationIcon: Boolean = true,
    collapsedTitle: String? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    floatingActionButton: (@Composable () -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit
) {
    val navigator = LocalNavigator.current
    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    // 指定折叠标题时（如主页面内嵌页面），标题收回顶栏后只显示该标题，不再带小副标题。
    val collapsed by remember(topAppBarScrollBehavior) {
        derivedStateOf {
            topAppBarScrollBehavior.state.collapsedFraction >= COLLAPSED_TITLE_FRACTION
        }
    }
    val barSubtitle = if (collapsedTitle != null && collapsed) "" else subtitle
    val navigationIconContent: @Composable () -> Unit = {
        when {
            leadingContent != null -> leadingContent()
            showNavigationIcon -> {
                IconButton(onClick = { navigator.pop() }) {
                    Icon(
                        imageVector = MiuixIcons.Back,
                        contentDescription = stringResource(id = R.string.back)
                    )
                }
            }
        }
    }

    Scaffold(
        floatingActionButton = { floatingActionButton?.invoke() },
        topBar = {
            BlurredBar(backdrop, blurActive) {
                // 顶栏左右都有按钮时，收起标题用略小字号。
                val barContent: @Composable () -> Unit = {
                    if (collapsedTitle != null) {
                        // 收起后只显示 collapsedTitle，展开时仍是原来的大标题。
                        TopAppBar(
                            color = barColor,
                            title = collapsedTitle,
                            largeTitle = title,
                            subtitle = barSubtitle,
                            scrollBehavior = topAppBarScrollBehavior,
                            navigationIcon = navigationIconContent,
                            actions = { trailingContent?.invoke() },
                        )
                    } else {
                        // 未指定折叠标题：完全沿用原有标题与副标题行为。
                        TopAppBar(
                            color = barColor,
                            title = title,
                            subtitle = subtitle,
                            scrollBehavior = topAppBarScrollBehavior,
                            navigationIcon = navigationIconContent,
                            actions = { trailingContent?.invoke() },
                        )
                    }
                }
                val hasLeadingSlot = leadingContent != null || showNavigationIcon
                if (hasLeadingSlot && trailingContent != null) {
                    CompactBarTitle { barContent() }
                } else {
                    barContent()
                }
            }
        }
    ) { padding ->
        val topPadding = padding.calculateTopPadding()
        val bottomPadding = padding.calculateBottomPadding()
        val outerTopPadding = outerPadding.calculateTopPadding()
        val outerBottomPadding = outerPadding.calculateBottomPadding()
        val contentPadding = remember(
            topPadding,
            bottomPadding,
            outerTopPadding,
            outerBottomPadding,
        ) {
            PaddingValues(
                top = topPadding + outerTopPadding,
                start = 0.dp,
                end = 0.dp,
                bottom = bottomPadding + outerBottomPadding + 16.dp,
            )
        }
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                state = listState,
                modifier = Modifier.pageScrollModifiers(
                    enableScrollEndHaptic = true,
                    showTopAppBar = true,
                    topAppBarScrollBehavior = topAppBarScrollBehavior
                ),
                contentPadding = contentPadding,
                content = content
            )
        }
    }
}
