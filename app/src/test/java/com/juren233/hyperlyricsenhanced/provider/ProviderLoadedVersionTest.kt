/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderLoadedVersionTest {

    private fun name(
        loaded: Int,
        installed: Int = 19,
        installedName: String? = "1.0.18",
        baselineCode: Int = 20,
        baselineName: String? = "1.0.19",
        releases: Map<Int, String> = mapOf(19 to "1.0.18"),
    ) = ProviderLoadedVersion.displayName(
        loaded, installed, installedName, baselineCode, baselineName, releases,
    )

    @Test
    fun baselineNewerThanInstalledShowsBaselineName() {
        assertEquals("1.0.19", name(loaded = 20))
    }

    @Test
    fun unknownLoadedVersionFallsBackToInstalledName() {
        assertEquals("1.0.18", name(loaded = 0))
    }

    @Test
    fun loadedEqualToInstalledKeepsInstalledName() {
        assertEquals("1.0.18", name(loaded = 19))
    }

    @Test
    fun rolledBackVersionUsesCatalogReleaseName() {
        assertEquals("1.0.17", name(loaded = 18, releases = mapOf(18 to "1.0.17")))
    }

    @Test
    fun unknownReleaseShowsVersionCode() {
        assertEquals("17", name(loaded = 17))
    }

    @Test
    fun newerBaselineOverridesOlderInstalledForUpdateComparison() {
        // 已装远程包 7（1.0.6）、基线 20（1.0.19）：运行的是基线，不应提示更新到 19。
        assertEquals(20, ProviderLoadedVersion.effectiveVersionCode(7, 20))
        assertEquals("1.0.19", ProviderLoadedVersion.effectiveVersionName(7, "1.0.6", 20, "1.0.19"))
    }

    @Test
    fun installedNewerThanBaselineKeepsInstalled() {
        assertEquals(30, ProviderLoadedVersion.effectiveVersionCode(30, 20))
        assertEquals("1.0.29", ProviderLoadedVersion.effectiveVersionName(30, "1.0.29", 20, "1.0.19"))
    }

    @Test
    fun noInstalledPackFallsBackToBaseline() {
        assertEquals(20, ProviderLoadedVersion.effectiveVersionCode(0, 20))
        assertEquals("1.0.19", ProviderLoadedVersion.effectiveVersionName(0, null, 20, "1.0.19"))
    }
}
