/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import io.github.libxposed.api.XposedModule

/**
 * 官方 Provider 链路的模块日志统一出口。
 *
 * Vector 2.2 等框架只把框架自己的 tag 落盘到 modules*.log；带自定义 tag 的 [XposedModule.log]
 * 只进 logcat，「导出全部日志」里完全看不到（issue #66 第二份日志里插件加载链路一条都没有）。
 * 这里固定传 null tag 交给框架选择，把原 tag 并进消息前缀，格式与 HookLogger 一致，
 * 导出解析按 `[tag]` 前缀识别来源。
 */
internal fun XposedModule.persistentLog(
    priority: Int,
    tag: String,
    message: String,
    error: Throwable? = null,
) {
    log(priority, null, formatPersistentLog(tag, message), error)
}

internal fun formatPersistentLog(tag: String, message: String): String =
    "[${tag.trim()}] ${message.trim()}"
