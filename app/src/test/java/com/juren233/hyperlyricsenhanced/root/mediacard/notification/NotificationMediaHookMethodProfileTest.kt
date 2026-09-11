/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationMediaHookMethodProfileTest {
    @Test
    fun `recognizes HyperOS 4 layout refresh methods`() {
        assertTrue(isLayout(NotificationMediaHookMethodProfile.OS4_LOAD_LAYOUT))
        assertTrue(isLayout(NotificationMediaHookMethodProfile.OS4_UPDATE_LAYOUT))
    }

    @Test
    fun `keeps legacy layout names as fallback candidates`() {
        assertTrue(isLayout(NotificationMediaHookMethodProfile.LEGACY_LOAD_LAYOUT))
        assertTrue(isLayout(NotificationMediaHookMethodProfile.LEGACY_UPDATE_LAYOUT))
    }


    @Test
    fun `separates layout loading from layout application`() {
        assertEquals(
            listOf(
                NotificationMediaHookMethodProfile.OS4_LOAD_LAYOUT,
                NotificationMediaHookMethodProfile.LEGACY_LOAD_LAYOUT,
            ),
            NotificationMediaHookMethodProfile.layoutLoadMethodNames,
        )
        assertEquals(
            listOf(
                NotificationMediaHookMethodProfile.OS4_UPDATE_LAYOUT,
                NotificationMediaHookMethodProfile.LEGACY_UPDATE_LAYOUT,
            ),
            NotificationMediaHookMethodProfile.layoutApplyMethodNames,
        )
        assertTrue(
            NotificationMediaHookMethodProfile.layoutLoadMethodNames.none {
                it in NotificationMediaHookMethodProfile.layoutApplyMethodNames
            },
        )
    }

    @Test
    fun `rejects wrong layout descriptors and unrelated classes`() {
        assertFalse(
            NotificationMediaHookMethodProfile.isLayoutRefresh(
                declaringClassName = NotificationMediaHookMethodProfile.LAYOUT_CONTROLLER_CLASS,
                name = NotificationMediaHookMethodProfile.OS4_LOAD_LAYOUT,
                returnTypeName = Void.TYPE.name,
                parameterCount = 1,
            )
        )
        assertFalse(
            NotificationMediaHookMethodProfile.isLayoutRefresh(
                declaringClassName = "other.Controller",
                name = NotificationMediaHookMethodProfile.OS4_LOAD_LAYOUT,
                returnTypeName = Void.TYPE.name,
                parameterCount = 0,
            )
        )
        assertFalse(
            NotificationMediaHookMethodProfile.isLayoutRefresh(
                declaringClassName = NotificationMediaHookMethodProfile.LAYOUT_CONTROLLER_CLASS,
                name = NotificationMediaHookMethodProfile.OS4_LOAD_LAYOUT,
                returnTypeName = "java.lang.Object",
                parameterCount = 0,
            )
        )
    }

    private fun isLayout(name: String): Boolean =
        NotificationMediaHookMethodProfile.isLayoutRefresh(
            declaringClassName = NotificationMediaHookMethodProfile.LAYOUT_CONTROLLER_CLASS,
            name = name,
            returnTypeName = Void.TYPE.name,
            parameterCount = 0,
        )

    @Test
    fun `rejects the tinypanel updateMediaBackground alias as a media target`() {
        // "updateMediaBackground" exists only on
        // com.android.notification.tinypanel.FlipRowContainerController and never on any
        // HyperOS 4 media controller dex (verified OS4.0.0.6/.0.0.8/.0.0.34, 2026-09-12).
        assertFalse(
            NotificationMediaHookMethodProfile.UPDATE_FOREGROUND_COLORS ==
                NotificationMediaHookMethodProfile.REJECTED_GHOST_UPDATE_MEDIA_BACKGROUND
        )
        TARGET_METHOD_NAMES.forEach { name ->
            assertNotEquals(
                NotificationMediaHookMethodProfile.REJECTED_GHOST_UPDATE_MEDIA_BACKGROUND,
                name,
            )
        }
        NATIVE_BACKGROUND_UPDATE_METHODS.forEach { name ->
            assertNotEquals(
                NotificationMediaHookMethodProfile.REJECTED_GHOST_UPDATE_MEDIA_BACKGROUND,
                name,
            )
        }
    }

    @Test
    fun `lists the seven verified media material effects`() {
        val effects = NotificationMediaHookMethodProfile.mediaViewEffectClassNames
        assertEquals(7, effects.size)
        assertTrue(
            effects.all {
                it.startsWith(NotificationMediaHookMethodProfile.MEDIA_VIEW_EFFECT_PACKAGE)
            },
        )
        assertEquals(effects.size, effects.toSet().size)
        assertTrue(effects.any { it.endsWith(".MediaViewNormalEffect") })
        assertTrue(effects.any { it.endsWith(".MediaViewGlassEffect") })
    }

    @Test
    fun `overrides only the night mode bits`() {
        val uiMode = 0x01 or android.content.res.Configuration.UI_MODE_NIGHT_NO
        val dark = NotificationMediaHookMethodProfile.overrideNightMode(uiMode, dark = true)
        val light = NotificationMediaHookMethodProfile.overrideNightMode(uiMode, dark = false)
        val mask = android.content.res.Configuration.UI_MODE_NIGHT_MASK
        assertEquals(android.content.res.Configuration.UI_MODE_NIGHT_YES, dark and mask)
        assertEquals(android.content.res.Configuration.UI_MODE_NIGHT_NO, light and mask)
        assertEquals(
            uiMode and mask.inv(),
            dark and mask.inv(),
        )
    }
}
