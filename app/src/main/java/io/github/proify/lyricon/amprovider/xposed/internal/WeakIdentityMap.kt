/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.internal

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

internal class WeakIdentityMap<K : Any, V> {
    private val queue = ReferenceQueue<K>()
    private val values = HashMap<IdentityWeakReference<K>, V>()

    operator fun get(key: K): V? = synchronized(values) {
        removeCollectedKeys()
        values[IdentityWeakReference(key)]
    }

    operator fun set(key: K, value: V) {
        synchronized(values) {
            removeCollectedKeys()
            values[IdentityWeakReference(key, queue)] = value
        }
    }

    fun remove(key: K) {
        synchronized(values) {
            removeCollectedKeys()
            values.remove(IdentityWeakReference(key))
        }
    }

    fun clear() {
        synchronized(values) {
            values.clear()
            while (queue.poll() != null) Unit
        }
    }

    private fun removeCollectedKeys() {
        while (true) {
            @Suppress("UNCHECKED_CAST")
            val reference = queue.poll() as? IdentityWeakReference<K> ?: return
            values.remove(reference)
        }
    }
}

private class IdentityWeakReference<T : Any>(
    referent: T,
    queue: ReferenceQueue<T>? = null,
) : WeakReference<T>(referent, queue) {
    private val identityHashCode = System.identityHashCode(referent)

    override fun hashCode(): Int = identityHashCode

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdentityWeakReference<*>) return false
        val referent = get() ?: return false
        return referent === other.get()
    }
}
