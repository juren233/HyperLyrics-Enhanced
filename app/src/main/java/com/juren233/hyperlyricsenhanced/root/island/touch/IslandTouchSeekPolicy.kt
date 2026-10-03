/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island.touch

import com.juren233.hyperlyricsenhanced.common.IslandTouchAction
import com.juren233.hyperlyricsenhanced.common.IslandTouchBinding
import com.juren233.hyperlyricsenhanced.common.IslandTouchConfig

internal object IslandTouchSeekPolicy {
    fun target(position: Long, duration: Long, binding: IslandTouchBinding): Long? {
        if (binding.action == IslandTouchAction.RESTART) return 0L
        if (position < 0L) return null
        val delta = binding.seconds.coerceIn(IslandTouchConfig.MIN_SECONDS, IslandTouchConfig.MAX_SECONDS) * 1000L
        val target = when (binding.action) {
            IslandTouchAction.FORWARD -> position.coerceAtMost(Long.MAX_VALUE - delta) + delta
            IslandTouchAction.BACKWARD -> (position - delta).coerceAtLeast(0L)
            else -> return null
        }
        return if (duration > 0L) target.coerceAtMost(duration) else target
    }
}
