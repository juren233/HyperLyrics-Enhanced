/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view.line

import java.util.WeakHashMap

/** The assembled presentation may have word timing even when the source main line does not. */
interface PositionUpdateConsumer {
    val needsFrequentPositionUpdates: Boolean
}

/** Main-thread only, like view attachment and the local timeline's position loop. */
internal class PositionUpdateDemand {
    private val consumers = WeakHashMap<PositionUpdateConsumer, Unit>()

    fun attach(consumer: PositionUpdateConsumer) {
        consumers[consumer] = Unit
    }

    fun detach(consumer: PositionUpdateConsumer) {
        consumers.remove(consumer)
    }

    fun requiresFrequentUpdates(): Boolean = consumers.keys.any { it.needsFrequentPositionUpdates }

    companion object {
        val active = PositionUpdateDemand()
    }
}
