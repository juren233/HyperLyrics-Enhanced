/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.island

import org.junit.Assert.assertEquals
import org.junit.Test

class IslandExpandedMediaLightBackgroundPolicyTest {
    @Test
    fun `OS4 bionics uses the notification media glass path when blur is open`() {
        assertEquals(
            IslandExpandedMediaLightBackgroundMode.NOTIFICATION_GLASS,
            IslandExpandedMediaLightBackgroundPolicy.select(
                hasBionicsMaterialApi = true,
                hasNotificationGlassApi = true,
                blurOpened = true,
                attached = true,
            ),
        )
    }

    @Test
    fun `notification glass supports a not-yet-attached view like MiGlassCompat`() {
        assertEquals(
            IslandExpandedMediaLightBackgroundMode.NOTIFICATION_GLASS,
            IslandExpandedMediaLightBackgroundPolicy.select(
                hasBionicsMaterialApi = true,
                hasNotificationGlassApi = true,
                blurOpened = true,
                attached = false,
            ),
        )
    }

    @Test
    fun `older systems retain the legacy blur path when it is usable`() {
        assertEquals(
            IslandExpandedMediaLightBackgroundMode.LEGACY_BLUR,
            IslandExpandedMediaLightBackgroundPolicy.select(
                hasBionicsMaterialApi = false,
                hasNotificationGlassApi = false,
                blurOpened = true,
                attached = true,
            ),
        )
    }

    @Test
    fun `missing notification glass API never retries legacy blur on bionics`() {
        assertEquals(
            IslandExpandedMediaLightBackgroundMode.DRAWABLE_FALLBACK,
            IslandExpandedMediaLightBackgroundPolicy.select(
                hasBionicsMaterialApi = true,
                hasNotificationGlassApi = false,
                blurOpened = true,
                attached = true,
            ),
        )
    }

    @Test
    fun `unavailable blur or a detached legacy view uses the drawable fallback`() {
        assertEquals(
            IslandExpandedMediaLightBackgroundMode.DRAWABLE_FALLBACK,
            IslandExpandedMediaLightBackgroundPolicy.select(
                hasBionicsMaterialApi = false,
                hasNotificationGlassApi = false,
                blurOpened = false,
                attached = true,
            ),
        )
        assertEquals(
            IslandExpandedMediaLightBackgroundMode.DRAWABLE_FALLBACK,
            IslandExpandedMediaLightBackgroundPolicy.select(
                hasBionicsMaterialApi = false,
                hasNotificationGlassApi = false,
                blurOpened = true,
                attached = false,
            ),
        )
    }
}
