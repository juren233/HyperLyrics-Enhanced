/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island.touch

/** One synchronous native consent check; never shares a context with another call or thread. */
internal class MediaOutputCtaCallScope<C : Any> {
    private class Call<C>(val receiver: Any, val context: C, var consumed: Boolean = false)
    private val active = ThreadLocal<Call<C>?>()

    fun <T> invoke(receiver: Any, context: C, action: () -> T): T {
        val previous = active.get()
        active.set(Call(receiver, context))
        return try { action() } finally {
            if (previous == null) active.remove() else active.set(previous)
        }
    }

    fun argument(receiver: Any?, original: C?): C? {
        val call = active.get() ?: return original
        if (receiver !== call.receiver || call.consumed) return original
        call.consumed = true
        return original ?: call.context
    }

    fun clear() = active.remove()
}
