/* Copyright 2026 juren233. Licensed under the Apache License, Version 2.0. */
package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.*
import org.junit.Test

class AppleMediaLaunchTest {
    private val version = AppleMusicVersion("7.0.0-beta", 1606L)
    private fun target(point: AppleMusicHookPoint) = AppleMusicHookProfiles.exactTargets(version, point).single()

    @Test fun `1606 launch targets the manifest activity while legacy service input is separate`() {
        assertEquals("com.apple.android.music.common.MainActivity", target(AppleMusicHookPoint.APPLE_MAIN_CONTENT_ACTIVITY).className)
        assertEquals("com.apple.android.music.common.MainContentActivity", target(AppleMusicHookPoint.APPLE_MEDIA_LEGACY_ACTIVITY).className)
        assertEquals("com.apple.android.music.player.MediaPlaybackService", target(AppleMusicHookPoint.APPLE_MEDIA_SESSION_SERVICE).className)
        assertEquals("onCreate", target(AppleMusicHookPoint.APPLE_MEDIA_SESSION_SERVICE).methodName)
        assertEquals(emptyList<String>(), target(AppleMusicHookPoint.APPLE_MEDIA_SESSION_SERVICE).parameterTypeNames)
        assertEquals("i1", target(AppleMusicHookPoint.APPLE_MEDIA_MAIN_VIEW_MODEL).methodName)
        assertEquals("q1", target(AppleMusicHookPoint.APPLE_MEDIA_PLAYER_VIEW_MODEL).methodName)
        assertEquals("requestPlayerSheetExpand", target(AppleMusicHookPoint.APPLE_MEDIA_PLAYER_EXPAND).methodName)
        assertEquals(listOf("android.view.View", "android.os.Bundle"), target(AppleMusicHookPoint.APPLE_MEDIA_PLAYER_VIEW_CREATED).parameterTypeNames)
        assertEquals(listOf("android.content.Intent"), target(AppleMusicHookPoint.APPLE_MEDIA_MAIN_NEW_INTENT).parameterTypeNames)
        assertEquals("onPostResume", target(AppleMusicHookPoint.APPLE_MEDIA_MAIN_POST_RESUME).methodName)
    }

    @Test fun `six series retain the original launch component and no new session hooks`() {
        for (old in listOf(AppleMusicVersion("6.5.0", 1580L), AppleMusicVersion("6.5.1", 1583L),
            AppleMusicVersion("6.5.2", 1586L), AppleMusicVersion("6.5.3", 1599L))) {
            assertEquals("com.apple.android.music.common.MainContentActivity",
                AppleMusicHookResolver(old, classLookup = { error("Configured names must not load classes") })
                    .configuredClassNames(AppleMusicHookPoint.APPLE_MAIN_CONTENT_ACTIVITY).single())
            assertTrue(AppleMusicHookProfiles.exactTargets(old, AppleMusicHookPoint.APPLE_MEDIA_SESSION_SERVICE).isEmpty())
        }
        assertNull(AppleMusicHookProfiles.profileFor(AppleMusicVersion("7.0.0-beta", 1607L)))
    }

    @Test fun `native repair is restricted to the original media service pending intent`() {
        val legacy = target(AppleMusicHookPoint.APPLE_MEDIA_LEGACY_ACTIVITY).className
        val pkg = Constants.APPLE_MUSIC_PACKAGE_NAME
        assertTrue(shouldRepairAppleMediaSessionLaunch(true, 0, pkg, legacy, legacy))
        assertFalse(shouldRepairAppleMediaSessionLaunch(false, 0, pkg, legacy, legacy))
        assertFalse(shouldRepairAppleMediaSessionLaunch(true, 18508, pkg, legacy, legacy))
        assertFalse(shouldRepairAppleMediaSessionLaunch(true, 0, "other", legacy, legacy))
        assertFalse(shouldRepairAppleMediaSessionLaunch(true, 0, pkg,
            target(AppleMusicHookPoint.APPLE_MAIN_CONTENT_ACTIVITY).className, legacy))
        assertFalse(shouldRepairAppleMediaSessionLaunch(true, 0, null, null, legacy))
    }

    @Test fun `cold notification waits for native subscriber and expands exactly once`() {
        val state = ApplePlayerLaunchState<Any>()
        val request = Any()
        var calls = 0
        state.request(request)
        assertFalse(state.dispatch(true) { calls++; true })
        state.viewReady = true
        assertTrue(state.dispatch(true) { assertSame(request, it); calls++; true })
        assertFalse(state.dispatch(true) { calls++; true })
        assertEquals(1, calls)
    }

    @Test fun `warm launch and view recreation retain only the latest unconsumed request`() {
        val state = ApplePlayerLaunchState<Any>()
        state.viewReady = true
        state.request(Any())
        assertTrue(state.dispatch(true) { true })
        state.viewReady = false
        state.request(Any())
        val latest = Any()
        state.request(latest)
        assertFalse(state.dispatch(true) { fail("view destroyed"); true })
        state.viewReady = true
        assertTrue(state.dispatch(true) { assertSame(latest, it); true })
    }

    @Test fun `disabled preference and ordinary launches discard pending expansion`() {
        val state = ApplePlayerLaunchState<Any>()
        state.request(Any())
        assertFalse(state.dispatch(false) { fail("disabled"); true })
        state.viewReady = true
        assertFalse(state.dispatch(true) { fail("old request"); true })
        state.request(Any())
        state.request(null)
        assertFalse(state.dispatch(true) { fail("ordinary launcher open"); true })
    }

    @Test fun `failed native dispatch stays pending until the next lifecycle opportunity`() {
        val state = ApplePlayerLaunchState<Any>()
        state.viewReady = true
        val request = Any()
        state.request(request)
        assertFalse(state.dispatch(true) { false })
        assertTrue(state.dispatch(true) { assertSame(request, it); true })
    }

    @Test fun `cancellation returns the intent whose replay marker must be removed`() {
        val state = ApplePlayerLaunchState<Any>()
        val request = Any()
        state.request(request)
        assertSame(request, state.cancel())
        state.viewReady = true
        assertFalse(state.dispatch(true) { fail("cancelled launch"); true })
        assertNull(state.cancel())
    }

    @Test fun `request delivered during native expansion is not cleared with the older request`() {
        val state = ApplePlayerLaunchState<Any>()
        state.viewReady = true
        state.request(Any())
        val newer = Any()
        assertTrue(state.dispatch(true) { state.request(newer); true })
        assertTrue(state.dispatch(true) { assertSame(newer, it); true })
    }
}
