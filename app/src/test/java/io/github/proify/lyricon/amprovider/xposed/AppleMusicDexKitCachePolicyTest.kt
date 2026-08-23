/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMusicDexKitCachePolicyTest {
    @Test
    fun `runtime cache scope changes with APK identity`() {
        val baseline = AppleMusicDexKitCachePolicy.runtimeScope(
            versionCode = 1586L,
            lastUpdateTime = 100L,
            sourceLength = 200L,
            sourceLastModified = 300L,
        )

        assertNotEquals(
            baseline,
            AppleMusicDexKitCachePolicy.runtimeScope(1587L, 100L, 200L, 300L),
        )
        assertNotEquals(
            baseline,
            AppleMusicDexKitCachePolicy.runtimeScope(1586L, 101L, 200L, 300L),
        )
        assertNotEquals(
            baseline,
            AppleMusicDexKitCachePolicy.runtimeScope(1586L, 100L, 201L, 300L),
        )
        assertTrue(baseline.contains("resolver-2:contract-2"))
    }

    @Test
    fun `runtime class cache is role scoped while trusted baseline stays cross version`() {
        val scopeA = AppleMusicDexKitCachePolicy.runtimeScope(1586L, 100L, 200L, 300L)
        val scopeB = AppleMusicDexKitCachePolicy.runtimeScope(1587L, 200L, 300L, 400L)

        val cacheA = AppleMusicDexKitCachePolicy.classCacheKey(
            scopeA,
            AppleMusicHookPoint.COMPOSE_TEXT_LAYOUT,
            "z1.q",
        )
        val cacheB = AppleMusicDexKitCachePolicy.classCacheKey(
            scopeB,
            AppleMusicHookPoint.COMPOSE_TEXT_LAYOUT,
            "z1.q",
        )
        val otherRole = AppleMusicDexKitCachePolicy.classCacheKey(
            scopeA,
            AppleMusicHookPoint.COMPOSE_TEXT_LAYOUT,
            "z1.i",
        )

        assertNotEquals(cacheA, cacheB)
        assertNotEquals(cacheA, otherRole)
        assertEquals(
            AppleMusicDexKitCachePolicy.trustedClassBaselineKey(
                AppleMusicHookPoint.COMPOSE_TEXT_LAYOUT,
                "z1.q",
            ),
            AppleMusicDexKitCachePolicy.trustedClassBaselineKey(
                AppleMusicHookPoint.COMPOSE_TEXT_LAYOUT,
                "z1.q",
            ),
        )
    }

    @Test
    fun `rejection registry quarantines only the rejected HookPoint role`() {
        val registry = AppleMusicDexKitRejectionRegistry()

        assertTrue(
            registry.reject(
                AppleMusicHookPoint.COMPOSE_TEXT_LAYOUT,
                "z1.q",
                "future.a",
            ),
        )
        assertTrue(
            registry.contains(
                AppleMusicHookPoint.COMPOSE_TEXT_LAYOUT,
                "z1.q",
                "future.a",
            ),
        )
        assertFalse(
            registry.contains(
                AppleMusicHookPoint.COMPOSE_TEXT_LAYOUT,
                "z1.i",
                "future.a",
            ),
        )
        assertFalse(
            registry.reject(
                AppleMusicHookPoint.COMPOSE_TEXT_LAYOUT,
                "z1.q",
                "future.a",
            ),
        )
    }


    @Test
    fun `rejection registry quarantines only the rejected method signature`() {
        val registry = AppleMusicDexKitRejectionRegistry()
        val target = MethodFixture::class.java.getDeclaredMethod("target", String::class.java)
        val other = MethodFixture::class.java.getDeclaredMethod("other", String::class.java)

        assertTrue(
            registry.rejectMethod(
                AppleMusicHookPoint.EPOXY_FINAL_BIND,
                "com.airbnb.epoxy.K",
                target,
            ),
        )
        assertTrue(
            registry.containsMethod(
                AppleMusicHookPoint.EPOXY_FINAL_BIND,
                "com.airbnb.epoxy.K",
                target,
            ),
        )
        assertFalse(
            registry.containsMethod(
                AppleMusicHookPoint.EPOXY_FINAL_BIND,
                "com.airbnb.epoxy.K",
                other,
            ),
        )
        assertFalse(
            registry.containsMethod(
                AppleMusicHookPoint.EPOXY_FINAL_BIND,
                "other.role",
                target,
            ),
        )
        assertFalse(
            registry.rejectMethod(
                AppleMusicHookPoint.EPOXY_FINAL_BIND,
                "com.airbnb.epoxy.K",
                target,
            ),
        )
    }

    private class MethodFixture {
        fun target(value: String) = value
        fun other(value: String) = value
    }

    @Test
    fun `candidate selector rejects ties and accepts a unique semantic winner`() {
        assertNull(
            AppleMusicDexKitCachePolicy.selectUniqueBest(
                listOf("wrong.a" to 200, "wrong.b" to 200),
            ),
        )
        assertEquals(
            "future.correct",
            AppleMusicDexKitCachePolicy.selectUniqueBest(
                listOf("future.correct" to 260, "future.lookalike" to 220),
            ),
        )
    }
}
