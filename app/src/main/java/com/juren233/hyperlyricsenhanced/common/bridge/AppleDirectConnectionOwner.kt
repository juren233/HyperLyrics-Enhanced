/* Copyright 2026 juren233. Licensed under the Apache License, Version 2.0. */
package com.juren233.hyperlyricsenhanced.common.bridge

/** Entries are ownership tokens: equality (or the same Binder in a newer entry) is insufficient. */
internal class AppleDirectConnectionOwner<T : Any> {
    @Volatile
    var current: T? = null
        private set

    @Synchronized
    fun replace(next: T?): T? = current.also { current = next }

    @Synchronized
    fun clearIfCurrent(expected: T): Boolean {
        if (current !== expected) return false
        current = null
        return true
    }
}
