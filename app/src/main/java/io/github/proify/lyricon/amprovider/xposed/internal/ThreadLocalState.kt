/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.internal

internal class ThreadLocalReentryGuard {
    private val depth = ThreadLocal<Int>()

    val isActive: Boolean
        get() = (depth.get() ?: 0) > 0

    fun <T> run(block: () -> T): T {
        val previousDepth = depth.get() ?: 0
        depth.set(previousDepth + 1)
        return try {
            block()
        } finally {
            if (previousDepth == 0) {
                depth.remove()
            } else {
                depth.set(previousDepth)
            }
        }
    }
}

internal class ThreadLocalStack<T : Any> {
    private val stacks = ThreadLocal<ArrayDeque<T>>()

    val current: T?
        get() = stacks.get()?.lastOrNull()

    fun push(value: T) {
        val stack = stacks.get() ?: ArrayDeque<T>().also(stacks::set)
        stack.addLast(value)
    }

    fun pop(): T? {
        val stack = stacks.get() ?: return null
        if (stack.isEmpty()) {
            stacks.remove()
            return null
        }
        val value = stack.removeLast()
        if (stack.isEmpty()) stacks.remove()
        return value
    }
}
