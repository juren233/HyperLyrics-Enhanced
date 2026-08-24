package com.juren233.hyperlyricsenhanced.common

import org.junit.Assert.assertEquals
import org.junit.Test

class LogLevelPolicyTest {
    @Test
    fun `fresh debug defaults to debug and fresh release defaults to normal`() {
        assertEquals(
            LogLevelPolicy.LEVEL_DEBUG,
            LogLevelPolicy.effectiveLevel(null, null, debugBuild = true),
        )
        assertEquals(
            LogLevelPolicy.LEVEL_NORMAL,
            LogLevelPolicy.effectiveLevel(null, null, debugBuild = false),
        )
    }

    @Test
    fun `switching build types replaces the previous build default immediately`() {
        assertEquals(
            LogLevelPolicy.LEVEL_DEBUG,
            LogLevelPolicy.effectiveLevel(
                storedLevel = LogLevelPolicy.LEVEL_NORMAL,
                storedBuildKind = LogLevelPolicy.BUILD_KIND_RELEASE,
                debugBuild = true,
            ),
        )
        assertEquals(
            LogLevelPolicy.LEVEL_NORMAL,
            LogLevelPolicy.effectiveLevel(
                storedLevel = LogLevelPolicy.LEVEL_DEBUG,
                storedBuildKind = LogLevelPolicy.BUILD_KIND_DEBUG,
                debugBuild = false,
            ),
        )
    }

    @Test
    fun `same build type preserves an explicit user selection`() {
        assertEquals(
            LogLevelPolicy.LEVEL_NORMAL,
            LogLevelPolicy.effectiveLevel(
                storedLevel = LogLevelPolicy.LEVEL_NORMAL,
                storedBuildKind = LogLevelPolicy.BUILD_KIND_DEBUG,
                debugBuild = true,
            ),
        )
        assertEquals(
            LogLevelPolicy.LEVEL_DEBUG,
            LogLevelPolicy.effectiveLevel(
                storedLevel = LogLevelPolicy.LEVEL_DEBUG,
                storedBuildKind = LogLevelPolicy.BUILD_KIND_RELEASE,
                debugBuild = false,
            ),
        )
    }
}
