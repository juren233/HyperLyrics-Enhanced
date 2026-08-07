/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class OfficialProviderScopeManagerTest {
    @Test
    fun `requests only target packages missing from current module scope`() {
        assertEquals(
            setOf("com.salt.music", "cn.kuwo.player"),
            OfficialProviderScopeManager.missingScopes(
                desiredScopes = setOf(
                    "com.salt.music",
                    "cn.kuwo.player",
                    "com.tencent.qqmusic",
                ),
                currentScopes = setOf("com.tencent.qqmusic", "com.android.systemui"),
            ),
        )
    }

    @Test
    fun `requests only target packages installed on the device`() {
        assertEquals(
            setOf("com.netease.cloudmusic"),
            OfficialProviderScopeManager.filterInstalledScopes(
                desiredScopes = setOf(
                    "com.netease.cloudmusic",
                    "com.hihonor.cloudmusic",
                ),
                installedPackages = setOf(
                    "com.netease.cloudmusic",
                    "com.android.systemui",
                ),
            ),
        )
    }

    @Test
    fun `does not request a scope when none of its target packages is installed`() {
        assertEquals(
            emptySet<String>(),
            OfficialProviderScopeManager.filterInstalledScopes(
                desiredScopes = setOf("com.hihonor.cloudmusic"),
                installedPackages = setOf("com.android.systemui"),
            ),
        )
    }
}
