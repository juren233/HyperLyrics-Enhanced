/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 一份数据的修订号：写入方在数据变化后 [bump]，显示这份数据的页面以 [value] 为键重新读取。
 *
 * 给没有现成变化通知的数据用（文件、数据库、跨进程查询；SharedPreferences 用它自己的监听）。
 * 父页面与子页面、平行窗口的两栏各自读一份时，只靠它保持一致：返回父页面或另一栏改动后，
 * 显示的内容都会跟着刷新。
 */
class DataRevision {
    private val state = MutableStateFlow(0L)
    val value: StateFlow<Long> = state.asStateFlow()

    fun bump() {
        state.update { it + 1 }
    }
}
