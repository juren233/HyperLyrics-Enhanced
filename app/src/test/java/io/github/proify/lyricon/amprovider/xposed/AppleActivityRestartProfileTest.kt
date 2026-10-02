/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleActivityRestartProfileTest {
    private val version = AppleMusicVersion("7.0.0-beta", 1606L)
    private val points = listOf(
        AppleMusicHookPoint.ACTIVITY_THEME_CREATE, AppleMusicHookPoint.ACTIVITY_THEME_RESTART,
        AppleMusicHookPoint.THEME_MODE_EMIT, AppleMusicHookPoint.APP_COMPAT_THEME_STATE,
    )

    @Test
    fun `restart diagnostics retain exact 1606 binary names and descriptors`() {
        fun target(point: AppleMusicHookPoint) = AppleMusicHookProfiles.exactTargets(version, point).single()
        val create = target(AppleMusicHookPoint.ACTIVITY_THEME_CREATE)
        val restart = target(AppleMusicHookPoint.ACTIVITY_THEME_RESTART)
        val emit = target(AppleMusicHookPoint.THEME_MODE_EMIT)
        val state = target(AppleMusicHookPoint.APP_COMPAT_THEME_STATE)
        assertEquals("com.apple.android.music.common.activity.BaseActivity", create.className)
        assertEquals(create.className, restart.className)
        assertEquals("onCreate", create.methodName)
        assertEquals(listOf("android.os.Bundle"), create.parameterTypeNames)
        assertEquals(1, create.parameterCount)
        assertEquals("J0", create.runtimeMemberName(AppleMusicRuntimeMember.ACTIVITY_THEME_MODE_FIELD))
        assertEquals("onRestart", restart.methodName)
        assertEquals(emptyList<String>(), restart.parameterTypeNames)
        assertEquals(0, restart.parameterCount)
        assertEquals("com.apple.android.music.D\$a", emit.className)
        assertEquals("emit", emit.methodName)
        assertEquals(listOf("java.lang.Object", "kotlin.coroutines.Continuation"), emit.parameterTypeNames)
        assertEquals(2, emit.parameterCount)
        assertEquals("java.lang.Object", emit.returnTypeName)
        // Do not replace these raw identifiers with JADX-readable AppCompatDelegate aliases.
        assertEquals("k.f", state.className)
        assertEquals("b", state.runtimeMemberName(AppleMusicRuntimeMember.APP_COMPAT_THEME_MODE_FIELD))
        listOf(create, restart).forEach { assertEquals("void", it.returnTypeName) }
        listOf(create, restart, emit).forEach {
            assertEquals(false, it.isStatic)
            assertFalse(it.includeSynthetic)
            assertFalse(it.allowFirstMatch)
        }
    }

    @Test
    fun `unverified releases have no exact restart diagnostics`() {
        listOf(AppleMusicVersion("6.5.3", 1599L), AppleMusicVersion("7.0.0-beta", 1607L)).forEach { other ->
            points.forEach { assertTrue(AppleMusicHookProfiles.exactTargets(other, it).isEmpty()) }
        }
    }
}
