/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import java.util.concurrent.ConcurrentHashMap

internal object IslandRuntimePreferenceOverrides {
    private val values = ConcurrentHashMap<String, Any>()

    fun put(key: String, value: Any?) {
        if (value == null) {
            values.remove(key)
        } else {
            values[key] = value
        }
    }

    fun getInt(key: String, fallback: Int): Int = values[key] as? Int ?: fallback

    fun getBoolean(key: String, fallback: Boolean): Boolean =
        values[key] as? Boolean ?: fallback

    fun getStringSet(key: String, fallback: Set<String>?): Set<String>? =
        (values[key] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: fallback

    fun clear() {
        values.clear()
    }
}
