package com.juren233.hyperlyricsenhanced.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogDataTest {
    @Test
    fun `new version includes its own release and earlier releases`() {
        assertTrue(ChangelogData.isReleaseVisible("v7.1.0", "7.1.0"))
        assertTrue(ChangelogData.isReleaseVisible("v7.0.0", "7.1.0"))
    }

    @Test
    fun `future releases are excluded`() {
        assertFalse(ChangelogData.isReleaseVisible("v7.2.0", "7.1.0"))
        assertFalse(ChangelogData.isReleaseVisible("v7.1.1", "7.1.0"))
    }

    @Test
    fun `release tags must use vX dot X dot X format`() {
        assertFalse(ChangelogData.isReleaseVisible("7.0.0", "7.0.0"))
        assertFalse(ChangelogData.isReleaseVisible("v7.0", "7.0.0"))
        assertFalse(ChangelogData.isReleaseVisible("v7.0.0-100001", "7.0.0"))
    }

    @Test
    fun `unknown tags are excluded`() {
        assertFalse(ChangelogData.isReleaseVisible("latest", "7.0.0"))
        assertFalse(ChangelogData.isReleaseVisible("release-v7.0.0", "7.0.0"))
    }
}
