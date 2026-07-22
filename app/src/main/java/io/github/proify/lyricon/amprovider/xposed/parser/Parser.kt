/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.parser

import io.github.proify.lyricon.amprovider.xposed.AppleReflection

/**
 * 尝试调用对象方法
 */
fun callMethod(any: Any, name: String, vararg args: Any?): Any? =
    runCatching {
        AppleReflection.call(any, name, *args)
    }.getOrNull()
