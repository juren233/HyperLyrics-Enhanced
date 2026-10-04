/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

internal data class IslandNaturalWidthSnapshot<V : Any>(
    val left: V,
    val right: V,
    val availableWidth: Int,
    val leftHeight: Int,
    val rightHeight: Int,
) {
    fun isStillValid(
        left: V?,
        right: V?,
        availableWidth: Int,
        leftHeight: Int,
        rightHeight: Int,
        contentChanged: Boolean,
        layoutPending: Boolean,
    ): Boolean = !contentChanged && !layoutPending &&
        this.left === left && this.right === right &&
        this.availableWidth == availableWidth &&
        this.leftHeight == leftHeight && this.rightHeight == rightHeight
}

/** A successful premeasure can be consumed once, by the same host in the same synchronous call. */
internal class IslandPremeasureScope<H : Any, V : Any> {
    private class Entry<H : Any, V : Any>(
        val host: H,
        val measurement: IslandNaturalWidthSnapshot<V>,
    )

    private val current = ThreadLocal<Entry<H, V>?>()

    fun <R> run(host: H, measurement: IslandNaturalWidthSnapshot<V>?, block: () -> R): R {
        val previous = current.get()
        current.set(measurement?.let { Entry(host, it) })
        return try {
            block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    fun take(host: H): IslandNaturalWidthSnapshot<V>? {
        val entry = current.get() ?: return null
        if (entry.host !== host) return null
        current.remove()
        return entry.measurement
    }
}
