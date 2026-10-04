package com.juren233.hyperlyricsenhanced.root.source

import io.github.proify.lyricon.central.Constants
import org.junit.Assert.assertEquals
import org.junit.Test

class LyriconBridgeTrafficObserverTest {
    @Test fun `bridge traffic actions match vendored central protocol constants`() {
        assertEquals(Constants.ACTION_REGISTER_PROVIDER, LyriconBridgeTrafficObserver.ACTION_REGISTER_PROVIDER)
        assertEquals(Constants.ACTION_REGISTER_SUBSCRIBER, LyriconBridgeTrafficObserver.ACTION_REGISTER_SUBSCRIBER)
        assertEquals(
            Constants.ACTION_CENTRAL_BOOT_COMPLETED,
            LyriconBridgeTrafficObserver.ACTION_CENTRAL_BOOT_COMPLETED,
        )
    }
}
