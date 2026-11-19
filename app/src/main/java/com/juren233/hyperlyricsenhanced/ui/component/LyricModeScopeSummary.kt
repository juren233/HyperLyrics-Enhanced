/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.LyricModeUsage

/**
 * 只对竖屏或横屏其中一个方向生效的设置，副标题开头加「竖屏模式 · 」/「横屏模式 · 」；
 * 原本没有副标题时只显示标识。两个方向都生效时原样返回 [summary]。
 */
@Composable
fun lyricModeScopedSummary(scope: LyricModeUsage.Scope?, summary: String? = null): String? {
    val label = when (scope) {
        LyricModeUsage.Scope.PORTRAIT -> stringResource(R.string.label_lyric_mode_scope_portrait)
        LyricModeUsage.Scope.LANDSCAPE -> stringResource(R.string.label_lyric_mode_scope_landscape)
        LyricModeUsage.Scope.BOTH, null -> return summary
    }
    return if (summary.isNullOrEmpty()) label
    else stringResource(R.string.format_lyric_mode_scope_summary, label, summary)
}
