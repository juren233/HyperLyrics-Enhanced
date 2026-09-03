/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import org.junit.Assert.assertFalse
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
}
