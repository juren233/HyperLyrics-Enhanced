/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一组拆成独立 Lazy 项（便于长列表滚动）却要看起来像一张 [top.yukonga.miuix.kmp.basic.Card] 的行。
 *
 * 首尾行的圆角取 [CardDefaults.CornerRadius]，并且像 Card 一样裁剪内容（squircleSurface），
 * 行的按下/悬停高亮因此跟随圆角，不会在圆角外画出直角。中间行没有圆角，只铺底色。
 */
@Composable
fun Modifier.lazyCardRow(index: Int, count: Int): Modifier {
    val color = MiuixTheme.colorScheme.surfaceContainer
    val radius = CardDefaults.CornerRadius
    val top = if (index == 0) radius else 0.dp
    val bottom = if (index == count - 1) radius else 0.dp
    val base = this
        .padding(horizontal = 12.dp)
        .padding(bottom = if (index == count - 1) 12.dp else 0.dp)
        .fillMaxWidth()
    return if (top == 0.dp && bottom == 0.dp) {
        base.background(color)
    } else {
        base.squircleSurface(
            color = color,
            topStart = top,
            topEnd = top,
            bottomEnd = bottom,
            bottomStart = bottom,
        )
    }
}
