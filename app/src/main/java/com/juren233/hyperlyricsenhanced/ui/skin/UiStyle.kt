/*
 * Copyright 2026 juren233
 */
package com.juren233.hyperlyricsenhanced.ui.skin

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.juren233.hyperlyricsenhanced.common.UIConstants

/** 界面风格。与颜色主题（系统/浅/深/Monet）相互独立。 */
enum class UiStyle(val prefValue: Int) {
    Miuix(0),
    Glasmio(1);

    companion object {
        fun fromPref(value: Int): UiStyle = entries.firstOrNull { it.prefValue == value } ?: Miuix
    }
}

/** 当前生效的界面风格；所有 [com.juren233.hyperlyricsenhanced.ui.skin] 组件按它分发。 */
val LocalUiStyle = compositionLocalOf { UiStyle.Miuix }

val UiStyle.isGlasmio: Boolean get() = this == UiStyle.Glasmio

/**
 * 读取界面风格。两种风格的组件树结构不同，运行中不原地切换：风格只在组合树创建时读取一次，
 * 设置页改动后通过重建 Activity 生效（原地热切换会让尚在旧树中的作用域读到新风格而崩溃，见 DEBUGGING_MISTAKES.md）。
 */
@Composable
fun rememberUiStyle(): UiStyle {
    val context = LocalContext.current
    return remember(context) {
        val prefs = context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
        UiStyle.fromPref(prefs.getInt(UIConstants.KEY_UI_STYLE, UIConstants.DEFAULT_UI_STYLE))
    }
}
