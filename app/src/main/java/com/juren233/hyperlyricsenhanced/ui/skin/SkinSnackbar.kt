/*
 * Copyright 2026 juren233
 */
package com.juren233.hyperlyricsenhanced.ui.skin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.glass.GlassSurface
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState

/**
 * 两种风格共用的提示条状态。Glasmio 没有 Snackbar 组件，这里用 Glasmio 的玻璃表面承载同一条消息；
 * Miuix 风格仍走 Miuix 的 SnackbarHost。调用方不关心风格，只调用 [showSnackbar]。
 */
@Stable
class SkinSnackbarHostState(style: UiStyle = UiStyle.Miuix) {
    /** 由宿主在组合时更新，决定提示条走哪种风格。 */
    var style by mutableStateOf(style)
        internal set
    internal val miuix = SnackbarHostState()
    internal var glasmioMessage by mutableStateOf<String?>(null)

    suspend fun showSnackbar(message: String, durationMillis: Long = 2000L) {
        if (style.isGlasmio) {
            glasmioMessage = message
            try {
                delay(durationMillis)
            } finally {
                glasmioMessage = null
            }
        } else {
            miuix.showSnackbar(message = message, duration = SnackbarDuration.Custom(durationMillis))
        }
    }
}

@Composable
fun rememberSkinSnackbarHostState(): SkinSnackbarHostState {
    val style = LocalUiStyle.current
    return remember { SkinSnackbarHostState(style) }.also { it.style = style }
}

@Composable
fun SkinSnackbarHost(state: SkinSnackbarHostState, modifier: Modifier = Modifier) {
    val hostStyle = LocalUiStyle.current
    SideEffect { state.style = hostStyle }
    if (hostStyle.isGlasmio) {
        Box(
            modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val message = state.glasmioMessage
            AnimatedVisibility(visible = message != null, enter = fadeIn(), exit = fadeOut()) {
                GlassSurface {
                    Crossfade(targetState = message, label = "skin-snackbar") { text ->
                        MaterialText(text.orEmpty(), modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
                    }
                }
            }
        }
    } else {
        SnackbarHost(state = state.miuix, modifier = modifier)
    }
}
