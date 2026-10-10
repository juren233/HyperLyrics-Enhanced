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

class AppleMusic7001609ProfileRegressionTest {
    private val version = AppleMusicVersion("7.0.0-beta", 1609L)

    @Test
    fun `new action sheet candidates never leak into known older builds`() {
        listOf(
            AppleMusicVersion("7.0.0-beta", 1607L),
            AppleMusicVersion("7.0.0-beta", 1606L),
            AppleMusicVersion("6.5.3", 1599L),
            AppleMusicVersion("6.5.2", 1586L),
        ).forEach { older ->
            assertFalse(older.displayName, AppleMusicHookProfiles.candidates(
                older, AppleMusicHookPoint.IN_APP_ACTION_SHEET_BINDING,
            ).any { it.className == "q8.r7" })
        }
    }

    @Test
    fun `1609 has an isolated profile based on the verified 1607 map`() {
        assertEquals("am-7.0.0-beta-1609", AppleMusicHookProfiles.profileFor(version)?.id)
        assertEquals("am-7.0.0-beta-1607", AppleMusicHookProfiles.profileFor(
            AppleMusicVersion("7.0.0-beta", 1607L),
        )?.id)
        assertNull(AppleMusicHookProfiles.profileFor(AppleMusicVersion("7.0.0-beta", 1608L)))
    }

    @Test
    fun `lyrics toggles use the 1609 LyricsPreferencesManager owners`() {
        val preferences = AppleMusicHookProfiles.exactTargets(
            version,
            AppleMusicHookPoint.APPLE_SHARED_PREFERENCES_CLASS,
        ).single()
        assertEquals("ja.h0", preferences.className)
        assertEquals("g", preferences.runtimeMemberName(
            AppleMusicRuntimeMember.LYRICS_PREFERENCES_TRANSLATION_GETTER,
        ))
        listOf(
            AppleMusicHookPoint.LYRICS_TRANSLATION_PREFERENCE to "n",
            AppleMusicHookPoint.LYRICS_PRONUNCIATION_PREFERENCE to "m",
        ).forEach { (point, method) ->
            val target = AppleMusicHookProfiles.exactTargets(version, point).single()
            assertEquals("ja.h0", target.className)
            assertEquals(method, target.methodName)
            assertEquals(listOf("boolean"), target.parameterTypeNames)
            assertEquals("void", target.returnTypeName)
            assertEquals(true, target.isStatic)
        }
        assertTrue(AppleMusicHookProfiles.exactTargets(
            AppleMusicVersion("7.0.0-beta", 1607L),
            AppleMusicHookPoint.LYRICS_TRANSLATION_PREFERENCE,
        ).single().className == "ja.i0")
    }
}
