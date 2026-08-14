/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

/** Handles suspend markers returned across the target process and module class loaders. */
internal object SuspendingHookResultPolicy {
    fun isCoroutineSuspended(result: Any?): Boolean {
        // Identity comparison with kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED is unsafe:
        // SystemUI and the module can load separate Kotlin runtimes in different class loaders.
        return result is Enum<*> && result.name == "COROUTINE_SUSPENDED"
    }
}
