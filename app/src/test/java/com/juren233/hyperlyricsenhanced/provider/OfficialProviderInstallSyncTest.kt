/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialProviderInstallSyncTest {

    private fun item(
        id: String,
        installed: Int,
        enabled: Boolean = true,
        versionName: String? = installed.takeIf { it > 0 }?.toString(),
        release: Int? = null,
    ) = OfficialProviderItem(
        catalog = ProviderCatalogEntry(id = id, displayName = id, targetPackages = listOf("pkg.$id"), available = release != null),
        installedVersionCode = installed,
        installedVersionName = versionName,
        enabled = enabled,
        selectedRelease = release?.let { ProviderCatalogRelease(it, it.toString(), "https://x/$id-$it.hlp", "00") },
    )

    @Test
    fun newlyAddedPluginAppearsInInstalledList() {
        val shown = listOf(item("a", 3))
        val local = listOf(item("a", 3), item("b", 1))
        assertEquals(listOf("a", "b"), OfficialProviderInstallSync.installedList(shown, local).map { it.catalog.id })
    }

    @Test
    fun removedPluginLeavesInstalledList() {
        val shown = listOf(item("a", 3), item("b", 1))
        val local = listOf(item("a", 3))
        assertEquals(listOf("a"), OfficialProviderInstallSync.installedList(shown, local).map { it.catalog.id })
    }

    @Test
    fun unchangedEntryKeepsCatalogInfo() {
        val shown = item("a", 3, release = 4)
        val merged = OfficialProviderInstallSync.installedList(listOf(shown), listOf(item("a", 3)))
        assertSame(shown, merged.single())
        assertTrue(merged.single().updateAvailable)
    }

    @Test
    fun externalUpdateTakesNewVersionAndClearsSatisfiedUpdate() {
        val shown = item("a", 3, release = 4)
        val merged = OfficialProviderInstallSync.installedList(listOf(shown), listOf(item("a", 4))).single()
        assertEquals(4, merged.installedVersionCode)
        assertEquals("4", merged.displayVersionName)
        assertEquals(false, merged.updateAvailable)
    }

    @Test
    fun enabledChangeIsMerged() {
        val merged = OfficialProviderInstallSync.installedList(listOf(item("a", 3)), listOf(item("a", 3, enabled = false)))
        assertEquals(false, merged.single().enabled)
    }

    @Test
    fun catalogListMarksRemovedPluginAddableAgain() {
        val shown = listOf(item("a", 3, release = 3), item("b", 0, release = 2))
        val merged = OfficialProviderInstallSync.catalogList(shown, local = emptyList())
        assertEquals(listOf(false, false), merged.map { it.installed })
        assertSame(shown[1], merged[1])
    }

    @Test
    fun catalogListMarksAddedPluginInstalled() {
        val shown = listOf(item("b", 0, release = 2))
        val merged = OfficialProviderInstallSync.catalogList(shown, local = listOf(item("b", 2)))
        assertTrue(merged.single().installed)
        assertEquals(2, merged.single().selectedRelease?.versionCode)
    }
}
