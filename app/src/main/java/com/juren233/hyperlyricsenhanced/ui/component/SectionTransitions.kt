/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.ui.component

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.Alignment

/**
 * 设置子项展开/收起的统一过渡：淡入并从顶部展开，淡出并向顶部收起。
 * 全 App 的 `AnimatedVisibility` 显示/隐藏都引用这里，不在各处另写数值。
 */
val SectionEnter: EnterTransition = fadeIn() + expandVertically(expandFrom = Alignment.Top)

val SectionExit: ExitTransition = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top)
