/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import org.junit.Assert.*
import org.junit.Test

class MediaCardClickTargetTest {
    @Test fun `selects the matching notification card among multiple players`() {
        val target = Any()
        val cards = listOf(MediaCardClickTarget("player.other", "token-2", Any()),
            MediaCardClickTarget("player.current", "token-1", target))
        assertSame(target, selectMediaCardClickTarget(cards, "player.current", "token-1"))
    }
    @Test fun `never opens another session from the same package`() {
        val cards = listOf(MediaCardClickTarget("player.current", "old-session", Any()))
        assertNull(selectMediaCardClickTarget(cards, "player.current", "new-session"))
    }
    @Test fun `missing card does not produce a substitute launch target`() {
        assertNull(selectMediaCardClickTarget(emptyList<MediaCardClickTarget<Any>>(), "player.current", "token"))
    }
    @Test fun `ambiguous bindings are rejected`() {
        val cards = listOf(MediaCardClickTarget("player.current", "token", Any()),
            MediaCardClickTarget("player.current", "token", Any()))
        assertNull(selectMediaCardClickTarget(cards, "player.current", "token"))
    }
    @Test fun `sessionless notification matches only a sessionless request`() {
        val target = Any()
        val cards = listOf(MediaCardClickTarget("player.current", null, target))
        assertSame(target, selectMediaCardClickTarget(cards, "player.current", null))
        assertNull(selectMediaCardClickTarget(cards, "player.current", "live-session"))
    }
    @Test fun `the returned action is the native card action itself`() {
        var nativeClicks = 0
        val native: () -> Unit = { nativeClicks++ }
        val cards = listOf(MediaCardClickTarget("player.current", "token", native))
        val selected = selectMediaCardClickTarget(cards, "player.current", "token")
        assertSame(native, selected)
        selected?.invoke()
        assertEquals(1, nativeClicks)
    }
}
