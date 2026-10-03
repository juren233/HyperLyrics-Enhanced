/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island.touch

import com.juren233.hyperlyricsenhanced.common.IslandTouchAction
import com.juren233.hyperlyricsenhanced.common.IslandTouchConfig
import com.juren233.hyperlyricsenhanced.common.IslandTouchGesture
import com.juren233.hyperlyricsenhanced.common.IslandTouchSide
import org.junit.Assert.*
import org.junit.Test

class IslandTouchFreeformProfileTest {
    @Test fun `freeform follows open app without reinterpreting saved expansion actions`() {
        val actions = IslandTouchAction.entries
        assertEquals(IslandTouchAction.OPEN_APP_FREEFORM, actions[actions.indexOf(IslandTouchAction.OPEN_APP) + 1])
        assertEquals(11, IslandTouchAction.OPEN_APP_FREEFORM.id)
        assertEquals(actions.size, actions.map { it.id }.toSet().size)
        val key = IslandTouchConfig.actionKey(IslandTouchSide.RIGHT, IslandTouchGesture.TAP)
        for ((saved, expected) in listOf(9 to IslandTouchAction.OPEN_APP,
                10 to IslandTouchAction.EXPAND_ISLAND, 11 to IslandTouchAction.OPEN_APP_FREEFORM)) {
            val config = IslandTouchConfig.read({ _, fallback -> fallback }, { name, fallback ->
                if (name == key) saved else fallback
            })
            assertEquals(expected, config.binding(IslandTouchSide.RIGHT, IslandTouchGesture.TAP).action)
            assertEquals(IslandTouchAction.NONE, config.binding(IslandTouchSide.LEFT, IslandTouchGesture.TAP).action)
        }
    }

    @Test fun `freeform requires the current app and an unlocked activity target`() {
        val p = IslandTouchFreeformProfile
        assertTrue(p.canOpen("player.a", "player.a", true, false))
        assertFalse(p.canOpen("player.a", "player.b", true, false))
        assertFalse(p.canOpen("player.a", null, true, false))
        assertFalse(p.canOpen("", "", true, false))
        assertFalse(p.canOpen("player.a", "player.a", false, false))
        assertFalse(p.canOpen("player.a", "player.a", true, true))
    }

    @Test fun `runtime names retain original DEX spelling and host protocol keys`() {
        val p = IslandTouchFreeformProfile
        assertEquals("miui.systemui.dynamicisland.window.content.DynamicIslandBaseContentView", p.BASE_CONTENT)
        assertEquals("com.android.systemui.plugins.miui.dynamicisland.DynamicIslandData", p.DATA)
        assertEquals("miui.systemui.dynamicisland.event.DynamicIslandEventCoordinator", p.COORDINATOR)
        assertEquals(listOf("getCurrentIslandData", "getExtras", "getDynamicIslandEventCoordinator",
            "getKeyguardShowing", "openFreeForm"),
            listOf(p.GET_DATA, p.GET_EXTRAS, p.GET_COORDINATOR, p.GET_KEYGUARD_SHOWING, p.OPEN_FREEFORM))
        assertEquals(listOf("miui.pkg.name", "miui.sbn", "miui.pending.intent",
            "extra_open_freeform_in_workbench_mode"),
            listOf(p.PACKAGE_NAME, p.NOTIFICATION, p.PENDING_INTENT, p.WORKBENCH_MODE))
        assertFalse(listOf(p.BASE_CONTENT, p.DATA, p.COORDINATOR).any { it.startsWith("defpackage.") })
        assertNotEquals("openFreeform", p.OPEN_FREEFORM)
    }
}
