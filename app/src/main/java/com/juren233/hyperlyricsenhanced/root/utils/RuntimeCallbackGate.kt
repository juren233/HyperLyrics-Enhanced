package com.juren233.hyperlyricsenhanced.root.utils

/** Invalidates already-queued work without relying on successful platform unregistration. */
internal class RuntimeCallbackGate {
    @Volatile
    private var current: Any? = null

    val active: Boolean get() = current != null

    fun begin(): Any = Any().also { current = it }

    fun invalidate() {
        current = null
    }

    fun isCurrent(generation: Any?): Boolean = generation != null && current === generation

    fun guard(generation: Any?, action: () -> Unit): Runnable = Runnable {
        if (isCurrent(generation)) action()
    }
}
