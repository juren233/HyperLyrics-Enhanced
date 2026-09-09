/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AodOverlayLifetimeTest {
    @Test fun `production callbacks and removal use per overlay lifetime`() {
        fun source(name: String): String {
            val path = "src/main/java/com/juren233/hyperlyricsenhanced/root/mediacard/notification/$name.kt"
            return listOf(File(path), File("app/$path")).first { it.isFile }.readText()
        }
        val remove = source("NotificationMediaAodLyricHooker")
            .substringAfter("private fun removeOverlay(state: ControllerState) {")
            .substringBefore("private fun removeAodPluginOverlay")
        val dispose = remove.indexOf("overlay.lifetime.dispose()")
        assertTrue(dispose >= 0 && dispose < remove.indexOf("restorePlayerHeight("))
        assertTrue(source("NotificationMediaAodControllerApply").contains(
            "if (!overlay.lifetime.allowsUpdates || state.overlay !== overlay) return@post"
        ))
        assertTrue(source("NotificationMediaAodHookerLockScreenLayout").contains(
            "if (!overlay.lifetime.allowsUpdates || !overlay.root.isShown) return"
        ))
        assertTrue(source("NotificationMediaAodHookerInternals").contains(
            "val lifetime = AodOverlayLifetime()"
        ))
    }

    @Test fun `live overlay allows updates`() {
        assertTrue(AodOverlayLifetime().allowsUpdates)
    }

    @Test fun `dequeued show callback does not update disposed overlay`() {
        val lifetime = AodOverlayLifetime()
        var shows = 0
        val callback = Runnable { if (lifetime.allowsUpdates) shows++ }
        lifetime.dispose()
        callback.run()
        assertEquals(0, shows)
    }

    @Test fun `deferred remeasure cannot overwrite restored height`() {
        val lifetime = AodOverlayLifetime()
        var height = 150
        val callback = Runnable { if (lifetime.allowsUpdates) height = 200 }
        lifetime.dispose()
        height = 100
        callback.run()
        assertEquals(100, height)
    }

    @Test fun `replacement has independent lifetime and old callback stays invalid`() {
        val old = AodOverlayLifetime()
        old.dispose()
        val replacement = AodOverlayLifetime()
        assertFalse(old.allowsUpdates)
        assertTrue(replacement.allowsUpdates)
    }

    @Test fun `layout callback during removal is rejected before resource restoration`() {
        val lifetime = AodOverlayLifetime()
        var layoutUpdates = 0
        lifetime.dispose()
        // Restoration/removal can synchronously provoke layout callbacks.
        if (lifetime.allowsUpdates) layoutUpdates++
        assertEquals(0, layoutUpdates)
    }

    @Test fun `repeated dispose never reactivates overlay`() {
        val lifetime = AodOverlayLifetime()
        lifetime.dispose()
        lifetime.dispose()
        assertFalse(lifetime.allowsUpdates)
    }
}
