/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island.touch

import com.juren233.hyperlyricsenhanced.common.IslandTouchAction
import org.junit.Assert.*
import org.junit.Test

class IslandTouchExpansionProfileTest {
    @Test fun `new action preserves the saved IDs of every existing action`() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
            IslandTouchAction.entries.filter { it.id <= 10 }.map { it.id })
        assertEquals(IslandTouchAction.OPEN_APP, IslandTouchAction.fromId(9))
        assertEquals(IslandTouchAction.EXPAND_ISLAND, IslandTouchAction.fromId(10))
    }
    @Test fun `only collapsed real island states can request expansion`() {
        val p = IslandTouchExpansionProfile
        assertTrue(p.canExpand(p.BIG, true, true))
        assertTrue(p.canExpand(p.SHOW_ONCE_BIG, true, true))
        assertFalse(p.canExpand("${p.STATE}\$Expanded", true, true))
        assertFalse(p.canExpand("${p.STATE}\$SmallIsland", true, true))
        assertFalse(p.canExpand("${p.STATE}\$Deleted", true, true))
        assertFalse(p.canExpand(null, true, true))
    }
    @Test fun `native transition gate and missing expanded card prevent expansion`() {
        val p = IslandTouchExpansionProfile
        assertFalse(p.canExpand(p.BIG, false, true))
        assertFalse(p.canExpand(p.BIG, true, false))
    }
    @Test fun `binary class names retain original DEX descriptors and nested separators`() {
        val p = IslandTouchExpansionProfile
        assertEquals("miui.systemui.dynamicisland.window.content.DynamicIslandContentView", p.CONTENT)
        assertEquals("miui.systemui.dynamicisland.event.DynamicIslandState", p.STATE)
        assertEquals("miui.systemui.dynamicisland.event.DynamicIslandState\$BigIsland", p.BIG)
        assertEquals("miui.systemui.dynamicisland.event.DynamicIslandState\$ShowOnceBigIsland", p.SHOW_ONCE_BIG)
        assertEquals("com.android.systemui.plugins.miui.dynamicisland.DynamicIslandData", p.DATA)
        assertEquals("miui.systemui.dynamicisland.event.DynamicIslandEventCoordinator", p.COORDINATOR)
        assertEquals("miui.systemui.dynamicisland.event.DynamicIslandEvent", p.EVENT)
        assertEquals("miui.systemui.dynamicisland.event.DynamicIslandEvent\$ClickDynamicIsland", p.CLICK_EVENT)
        assertFalse(p.isCollapsedState("defpackage.DynamicIslandState\$BigIsland"))
        assertFalse(p.isCollapsedState("miui.systemui.dynamicisland.event.DynamicIslandState.BigIsland"))
    }
    @Test fun `binary method and field names remain exact`() {
        val p = IslandTouchExpansionProfile
        assertEquals(listOf("INSTANCE", "getState", "getCurrentIslandData", "getView",
            "getDynamicIslandEventCoordinator", "canClick", "resetOpenAppFromIsland",
            "dispatchEvent", "setUserExpanded"), listOf(p.INSTANCE, p.GET_STATE, p.GET_DATA,
            p.GET_VIEW, p.GET_COORDINATOR, p.CAN_CLICK, p.RESET_OPEN_APP, p.DISPATCH, p.SET_USER_EXPANDED))
    }
}
