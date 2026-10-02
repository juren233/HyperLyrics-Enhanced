/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class IslandStatusBarColorProfileTest {
    @Test
    fun `runtime classes use original DEX names without decompiler prefixes`() {
        val names = listOf(
            IslandStatusBarColorProfile.DISPATCHER,
            IslandStatusBarColorProfile.DISPATCHER_INTERFACE,
            IslandStatusBarColorProfile.DEPENDENCY,
        )
        assertEquals(listOf(
            "com.android.systemui.statusbar.phone.DarkIconDispatcherImpl",
            "com.android.systemui.plugins.DarkIconDispatcher",
            "com.android.systemui.Dependency",
        ), names)
        names.forEach {
            assertFalse(it.startsWith("p000"))
            assertFalse(it.contains(".p007"))
            assertFalse(it.contains("MiuiDarkIconDispatcher"))
        }
    }

    @Test
    fun `native tint and cold-start lookup retain verified member names`() {
        assertEquals("applyIconTint", IslandStatusBarColorProfile.APPLY_TINT)
        assertEquals("mIconTint", IslandStatusBarColorProfile.TINT)
        assertEquals("mTintAreas", IslandStatusBarColorProfile.AREAS)
        assertEquals("mDumpableName", IslandStatusBarColorProfile.DUMP_NAME)
        assertEquals("DarkIconDispatcherImpl", IslandStatusBarColorProfile.DEFAULT_DISPLAY_DUMP_NAME)
        assertEquals("getTint", IslandStatusBarColorProfile.GET_TINT)
        assertEquals("sDependency", IslandStatusBarColorProfile.DEPENDENCY_INSTANCE)
        assertEquals("getDependencyInner", IslandStatusBarColorProfile.GET_DEPENDENCY)
    }
}
