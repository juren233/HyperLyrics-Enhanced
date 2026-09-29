/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

/** 仅在同一次过渡且注入槽位仍有实际内容时省略重复装配。 */
internal object IslandTransitionAssemblyPolicy {
    fun shouldSkipFullAssembly(
        sameGeneration: Boolean,
        slotsPresent: Boolean,
        slotsIntact: Boolean,
    ): Boolean = sameGeneration && slotsPresent && slotsIntact
}
