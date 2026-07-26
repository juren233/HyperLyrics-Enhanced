package com.juren233.hyperlyricsenhanced.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDataTest {
    @Test
    fun `release apk asset provides the displayed version code`() {
        assertEquals(
            110013L,
            UpdateData.extractReleaseVersionCode(
                assetNames = listOf(
                    "HyperLyrics.Enhanced-debug-v7.2.0-110013.apk",
                    "HyperLyrics.Enhanced-release-v7.2.0-110013.apk",
                ),
                versionName = "7.2.0",
            ),
        )
    }

    @Test
    fun `unrelated assets do not provide a version code`() {
        assertEquals(
            null,
            UpdateData.extractReleaseVersionCode(
                assetNames = listOf("source.zip", "HyperLyrics-v7.1.0.apk"),
                versionName = "7.2.0",
            ),
        )
    }

    @Test
    fun `higher release version code is an update`() {
        assertTrue(
            UpdateData.isUpdateAvailable(
                latestVersionName = "7.2.0",
                latestVersionCode = 110013,
                currentVersionName = "7.1.0",
                currentVersionCode = 110012,
            ),
        )
    }

    @Test
    fun `same or older release version code is not an update`() {
        assertFalse(
            UpdateData.isUpdateAvailable(
                latestVersionName = "7.1.0",
                latestVersionCode = 110012,
                currentVersionName = "7.1.0",
                currentVersionCode = 110012,
            ),
        )
        assertFalse(
            UpdateData.isUpdateAvailable(
                latestVersionName = "7.0.1",
                latestVersionCode = 110011,
                currentVersionName = "7.1.0",
                currentVersionCode = 110012,
            ),
        )
    }
}
