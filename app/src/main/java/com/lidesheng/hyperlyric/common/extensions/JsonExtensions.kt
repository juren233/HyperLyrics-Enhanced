/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.lidesheng.hyperlyric.common.extensions

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val json: Json = Json {
    coerceInputValues = true
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = false
}

inline fun <reified T> T.toJson(): String {
    return json.encodeToString(this)
}
