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
        assertEquals(
            setOf("com.apple.android.music"),
            OfficialProviderPreferencePolicy.affectedPlayerPackages(
                OfficialProviderCatalog.removedKey("applemusic"),
            ),
        )
        assertTrue(
            OfficialProviderPreferencePolicy.affectedPlayerPackages(
                OfficialProviderCatalog.installedVersionNameKey("salt-player"),
            ).isEmpty(),
        )
    }

    @Test
    fun `debug baseline counts only when newer than the installed Pack and targets the player`() {
        val targets = listOf("com.tencent.qqmusic", "com.tencent.freemusic")
        assertTrue(
            OfficialProviderPreferencePolicy.isDebugBaselineActive(
                baselineVersionCode = 40,
                baselineTargets = targets,
                installedVersion = 39,
                playerPackageName = "com.tencent.freemusic",
            ),
        )
        assertFalse(
            OfficialProviderPreferencePolicy.isDebugBaselineActive(
                baselineVersionCode = 39,
                baselineTargets = targets,
                installedVersion = 39,
                playerPackageName = "com.tencent.freemusic",
            ),
        )
        assertFalse(
            OfficialProviderPreferencePolicy.isDebugBaselineActive(
                baselineVersionCode = 40,
                baselineTargets = targets,
                installedVersion = 0,
                playerPackageName = "com.tencent.freemusic",
            ),
        )
        assertFalse(
            OfficialProviderPreferencePolicy.isDebugBaselineActive(
                baselineVersionCode = 40,
                baselineTargets = targets,
                installedVersion = 39,
                playerPackageName = "com.miui.player",
            ),
        )
    }
}
