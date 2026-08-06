/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialProviderPreferencePolicyTest {
    @Test
    fun `Pack must be enabled installed and assigned to the player`() {
        assertTrue(
            OfficialProviderPreferencePolicy.isConfigured(
                enabled = true,
                installedVersion = 2,
                activeFile = "hle-provider-salt-player-2.hlp",
            ),
        )
        assertFalse(
            OfficialProviderPreferencePolicy.isConfigured(
                enabled = false,
                installedVersion = 2,
                activeFile = "hle-provider-salt-player-2.hlp",
            ),
        )
        assertFalse(
            OfficialProviderPreferencePolicy.isConfigured(
                enabled = true,
                installedVersion = 0,
                activeFile = "hle-provider-salt-player-2.hlp",
            ),
        )
        assertFalse(
            OfficialProviderPreferencePolicy.isConfigured(
                enabled = true,
                installedVersion = 2,
                activeFile = " ",
            ),
        )
    }

    @Test
    fun `only priority-affecting keys trigger player reevaluation`() {
        assertEquals(
            setOf("com.salt.music"),
            OfficialProviderPreferencePolicy.affectedPlayerPackages(
                OfficialProviderCatalog.enabledKey("salt-player"),
            ),
        )
        assertEquals(
            setOf("com.salt.music"),
            OfficialProviderPreferencePolicy.affectedPlayerPackages(
                OfficialProviderCatalog.installedVersionKey("salt-player"),
            ),
        )
        assertEquals(
            setOf("com.salt.music"),
            OfficialProviderPreferencePolicy.affectedPlayerPackages(
                OfficialProviderCatalog.activeFileKey("com.salt.music"),
            ),
        )
        assertTrue(
            OfficialProviderPreferencePolicy.affectedPlayerPackages(
                OfficialProviderCatalog.installedVersionNameKey("salt-player"),
            ).isEmpty(),
        )
    }
}
