/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Original 1607 DEX descriptors, not JADX display names; APK checks run in AppleMusicProfileBinaryTest. */
class AppleMusic7001607ProfileRegressionTest {
    private val version = AppleMusicVersion("7.0.0-beta", 1607L)
    private val previous = AppleMusicVersion("7.0.0-beta", 1606L)
    private fun targets(point: AppleMusicHookPoint) = AppleMusicHookProfiles.exactTargets(version, point)
    private fun target(point: AppleMusicHookPoint) = targets(point).single()

    @Test
    fun `1607 preserves every supported group and remains isolated from other beta builds`() {
        assertEquals("am-7.0.0-beta-1607", AppleMusicHookProfiles.profileFor(version)!!.id)
        assertEquals("am-7.0.0-beta-1606", AppleMusicHookProfiles.profileFor(previous)!!.id)
        assertNull(AppleMusicHookProfiles.profileFor(AppleMusicVersion("7.0.0-beta", 1608L)))
        assertNull(AppleMusicHookProfiles.profileFor(AppleMusicVersion("7.0.0-beta", null)))
        AppleMusicHookPoint.entries.forEach { point ->
            val old = AppleMusicHookProfiles.exactTargets(previous, point)
            val current = targets(point)
            assertEquals("Lost targets: $point", old.size, current.size)
            if (point !in setOf(AppleMusicHookPoint.SETTINGS_DATA_CATEGORY_BUILD,
                    AppleMusicHookPoint.SETTINGS_CELLULAR_SIM_CHECK)) {
                assertTrue("Missing 1607 group: $point", current.isNotEmpty())
            }
            old.zip(current).forEach { (before, after) ->
                assertEquals("Changed member role: $point", before.runtimeMemberNames, after.runtimeMemberNames)
                assertEquals(before.isStatic, after.isStatic)
                assertEquals(before.includeSynthetic, after.includeSynthetic)
                assertEquals(before.allowFirstMatch, after.allowFirstMatch)
            }
        }
        assertTrue(AppleMusicHookProfiles.profileFor(version)!!.settingsDataCategoryHasNoSimGate)
    }

    @Test
    fun `lyrics font theme and playback use exact binary owners instead of reused 1606 names`() {
        val expected = mapOf(
            AppleMusicHookPoint.APP_COMPAT_THEME_STATE to "k.g",
            AppleMusicHookPoint.LYRICS_NETWORK_REQUEST to "x9.P0",
            AppleMusicHookPoint.LYRICS_COOKIE_JAR to "rb.k",
            AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER to "com.apple.android.music.player.fragment.e0",
            AppleMusicHookPoint.LYRICS_WORD_RENDER_ADAPTER to "com.apple.android.music.player.C",
            AppleMusicHookPoint.PLAYER_LYRICS_AVAILABILITY_CALCULATOR to "com.apple.android.music.player.f1",
            AppleMusicHookPoint.APPLE_TEXT_STYLE_UTILS to "com.apple.android.music.utils.i1",
            AppleMusicHookPoint.APPLE_PLAYER_UTIL_CLASS to "com.apple.android.music.player.Q",
            AppleMusicHookPoint.COMPOSE_NEVER_EQUAL_POLICY to "z0.r0",
        )
        expected.forEach { (point, name) ->
            assertEquals(point.name, name, target(point).className)
            assertFalse(AppleMusicHookProfiles.exactTargets(previous, point).any { it.className == name })
        }
        assertEquals(listOf("com.apple.android.music.player.C", "com.apple.android.music.player.V0"),
            targets(AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER).map { it.className })
        listOf(AppleMusicHookPoint.IN_APP_GLOBAL_METADATA_DISPATCHER,
            AppleMusicHookPoint.IN_APP_NOW_PLAYING_METADATA_LISTENER).forEach { point ->
            assertEquals(listOf("z3.x"), target(point).parameterTypeNames)
            assertEquals("onMediaMetadataChanged", target(point).methodName)
        }
    }

    @Test
    fun `merged request executors retain all six routes and the storefront argument`() {
        val executors = targets(AppleMusicHookPoint.MEDIA_API_CATALOG_REQUEST_EXECUTOR)
        assertEquals(listOf("x9.D#b", "x9.D#e", "Eg.c#d", "Eg.c#c", "Ef.d#e", "Ef.d#g"),
            executors.map { "${it.className}#${it.methodName}" })
        executors.forEach { executor ->
            assertEquals("java.lang.String", executor.parameterTypeNames!![3])
            assertEquals("hi.c", executor.parameterTypeNames.last())
            assertEquals("java.lang.Object", executor.returnTypeName)
            assertEquals(false, executor.isStatic)
            assertFalse(executor.includeSynthetic)
            assertFalse(executor.allowFirstMatch)
        }
        assertEquals(listOf(7, 7, 7, 6, 7, 7), executors.map { it.parameterTypeNames!!.size })
        // In 1607 Eg.c.e has d's signature but requests stations; it must not replace /search.
        assertFalse(executors.any { it.className == "Eg.c" && it.methodName == "e" })
    }

    @Test
    fun `library browse and album Compose use the 1607 state and renderer descriptors`() {
        val observe = target(AppleMusicHookPoint.COMPOSE_OBSERVE_AS_STATE)
        assertEquals("A0.h", observe.className)
        assertEquals("i", observe.methodName)
        assertEquals(listOf("androidx.lifecycle.G", "z0.m"), observe.parameterTypeNames)
        assertEquals("z0.p0", observe.returnTypeName)
        assertEquals(listOf("getValue"), observe.requiredInvokedMethodNames)
        assertEquals("com.apple.android.music.library2.F",
            target(AppleMusicHookPoint.LIBRARY_EPOXY_BUILD).parameterTypeNames!!.first())
        assertEquals(listOf("Yb.f", "O9.j", "oa.i", "oa.r", "Yb.n"),
            targets(AppleMusicHookPoint.BROWSE_COMPOSE_ITEM).map { it.className })
        assertEquals("z0.O0", target(AppleMusicHookPoint.BROWSE_COMPOSER_SCOPE).returnTypeName)
        assertEquals(listOf("z0.M0"), target(AppleMusicHookPoint.BROWSE_COMPOSER_USE_SCOPE).parameterTypeNames)
        assertEquals("z0.O0", target(AppleMusicHookPoint.BROWSE_SCOPE_INVALIDATE).className)
        val row = target(AppleMusicHookPoint.ALBUM_COMPOSE_ROW)
        assertEquals("y7.m", row.className)
        assertEquals("a", row.methodName)
        assertEquals(listOf("O0.j", "D7.i", "D7.t", "Rb.w5", "Vb.u", "Rb.v5", "Rb.x5", "int",
            "boolean", "D7.o", "D7.p", "pi.l", "pi.p", "pi.p", "z0.m", "int"), row.parameterTypeNames)
        assertEquals(true, row.isStatic)
    }

    @Test
    fun `older APKs never borrow renamed 1607 classes as compatibility candidates`() {
        listOf(previous, AppleMusicVersion("6.5.3", 1599L), AppleMusicVersion("6.5.2", 1586L),
            AppleMusicVersion("6.5.1", 1583L), AppleMusicVersion("6.5.0", 1580L)).forEach { old ->
            assertFalse(AppleMusicHookProfiles.candidates(old, AppleMusicHookPoint.APPLE_TEXT_STYLE_UTILS)
                .any { it.className == "com.apple.android.music.utils.i1" })
            assertFalse(AppleMusicHookProfiles.candidates(old, AppleMusicHookPoint.MEDIA_API_CATALOG_REQUEST_EXECUTOR)
                .any { it.className == "Eg.c" || it.className == "Ef.d" })
        }
        assertEquals(listOf("com.apple.android.music.player.A", "com.apple.android.music.player.Y0"),
            AppleMusicHookProfiles.exactTargets(previous, AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER).map { it.className })
    }
}
