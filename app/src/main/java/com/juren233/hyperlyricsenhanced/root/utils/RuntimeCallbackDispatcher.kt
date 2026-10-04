package com.juren233.hyperlyricsenhanced.root.utils

/** Checks ownership on the same serial thread that executes the entire callback body. */
internal class RuntimeCallbackDispatcher(
    private val isOnOwnerThread: () -> Boolean,
    private val post: (Runnable) -> Unit,
) {
    fun dispatch(isCurrent: () -> Boolean, action: () -> Unit) {
        val task = Runnable { if (isCurrent()) action() }
        if (isOnOwnerThread()) task.run() else post(task)
    }
}
