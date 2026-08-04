package com.juren233.hyperlyricsenhanced.ui.page.main

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.ui.utils.pageScrollModifiers
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar

data class AboutHeroVisualState(
    val backgroundAlpha: Float = 1f,
    val logoAlpha: Float = 1f,
    val logoScale: Float = 1f,
    val scrollOffsetPx: Float = 0f,
)

/** 构建具有全屏动态背景的关于页，并保留原有导航入口。 */
@Composable
fun AboutPage(
    outerPadding: PaddingValues,
    aboutAppVersion: String?,
    availableUpdateVersion: String?,
    aboutDeviceName: String,
    aboutDeviceModel: String,
    aboutOsVersion: String,
    aboutAndroidVersion: String,
    onHelpClick: () -> Unit,
    onLicensesClick: () -> Unit,
    onChangelogClick: () -> Unit,
    onContributorsClick: () -> Unit,
    onHeroStateChanged: (AboutHeroVisualState) -> Unit,
) {
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val lazyListState = rememberLazyListState()
    val density = LocalDensity.current
    val backgroundFadeDistance = with(density) { 352.dp.toPx() }
    val logoFadeStart = with(density) { 70.dp.toPx() }
    val logoFadeDistance = with(density) { 40.dp.toPx() }
    val currentScrollOffset by remember(lazyListState, backgroundFadeDistance) {
        derivedStateOf {
            if (lazyListState.firstVisibleItemIndex > 0) {
                backgroundFadeDistance
            } else {
                lazyListState.firstVisibleItemScrollOffset.toFloat()
            }
        }
    }
    val backgroundAlpha by remember(currentScrollOffset, backgroundFadeDistance) {
        derivedStateOf {
            (1f - currentScrollOffset / backgroundFadeDistance).coerceIn(0f, 1f)
        }
    }
    val logoFadeProgress by remember(currentScrollOffset, logoFadeStart, logoFadeDistance) {
        derivedStateOf {
            ((currentScrollOffset - logoFadeStart) / logoFadeDistance).coerceIn(0f, 1f)
        }
    }
    val versionFadeProgress by remember(currentScrollOffset, logoFadeStart) {
        derivedStateOf { (currentScrollOffset / logoFadeStart).coerceIn(0f, 1f) }
    }
    val showTopBarTitle = logoFadeProgress >= 1f
    val pageTitle = stringResource(R.string.about)

    SideEffect {
        onHeroStateChanged(
            AboutHeroVisualState(
                backgroundAlpha = backgroundAlpha,
                logoAlpha = 1f - logoFadeProgress,
                logoScale = 1f - logoFadeProgress * 0.1f,
                scrollOffsetPx = currentScrollOffset,
            ),
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                color = Color.Transparent,
                title = if (showTopBarTitle) pageTitle else "",
                largeTitle = "",
                scrollBehavior = topAppBarScrollBehavior,
            )
        },
    ) {
        // HyperCeiler 的品牌区从页面顶部开始，顶栏以覆盖方式叠在内容之上。
        val contentPadding = PaddingValues(
            bottom = outerPadding.calculateBottomPadding(),
        )

        LazyColumn(
            state = lazyListState,
            modifier = Modifier.pageScrollModifiers(
                enableScrollEndHaptic = true,
                showTopAppBar = true,
                topAppBarScrollBehavior = topAppBarScrollBehavior,
            ),
            contentPadding = contentPadding,
        ) {
            aboutPageSections(
                aboutAppVersion = aboutAppVersion,
                availableUpdateVersion = availableUpdateVersion,
                aboutDeviceName = aboutDeviceName,
                aboutDeviceModel = aboutDeviceModel,
                aboutOsVersion = aboutOsVersion,
                aboutAndroidVersion = aboutAndroidVersion,
                headerVersionAlpha = 1f - versionFadeProgress,
                headerVersionScale = 1f -
                    (currentScrollOffset / backgroundFadeDistance).coerceIn(0f, 1f) * 0.1f,
                cardSurfaceRestoreProgress = 1f - backgroundAlpha,
                onHelpClick = onHelpClick,
                onLicensesClick = onLicensesClick,
                onChangelogClick = onChangelogClick,
                onContributorsClick = onContributorsClick,
            )
        }
    }
}
