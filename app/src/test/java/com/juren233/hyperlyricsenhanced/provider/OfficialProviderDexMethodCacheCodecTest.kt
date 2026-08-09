/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialProviderDexMethodCacheCodecTest {
    private val target = OfficialProviderMethodTarget(
        className = "yv2.b",
        methodName = "a",
        parameterTypeNames = listOf("java.lang.String"),
        returnTypeName = "com.kugou.framework.lyric.k",
        isStatic = true,
    )

    @Test
    fun `round trips exact binary method target`() {
        assertEquals(target, OfficialProviderDexMethodCacheCodec.decode(
            OfficialProviderDexMethodCacheCodec.encode(target)
        ))
    }

    @Test
    fun `matches only declared query constraints`() {
        val query = OfficialProviderDexMethodQuery(
            cacheKey = "kugou-full-lyric-loader-v1",
            requiredStrings = listOf("file is not krc or lyc or txt file"),
            parameterTypeNames = listOf("java.lang.String"),
            isStatic = true,
        )
        assertTrue(OfficialProviderDexMethodCacheCodec.matches(target, query))
        assertFalse(
            OfficialProviderDexMethodCacheCodec.matches(
                target,
                query.copy(isStatic = false),
            )
        )
    }

    @Test
    fun `cache key changes with app or query version`() {
        val query = OfficialProviderDexMethodQuery(
            cacheKey = "kugou-full-lyric-loader-v1",
            requiredStrings = listOf("file is not krc or lyc or txt file"),
        )
        val first = OfficialProviderDexMethodCacheCodec.cacheKey(
            packageName = "com.kugou.android",
            processName = "com.kugou.android.support",
            versionCode = 20759,
            lastUpdateTime = 1,
            query = query,
        )
        assertNotEquals(first, OfficialProviderDexMethodCacheCodec.cacheKey(
            packageName = "com.kugou.android",
            processName = "com.kugou.android.support",
            versionCode = 20760,
            lastUpdateTime = 2,
            query = query,
        ))
        assertNotEquals(first, OfficialProviderDexMethodCacheCodec.cacheKey(
            packageName = "com.kugou.android",
            processName = "com.kugou.android.support",
            versionCode = 20759,
            lastUpdateTime = 1,
            query = query.copy(cacheKey = "kugou-full-lyric-loader-v2"),
        ))
    }

    @Test
    fun `cache key includes query graph references`() {
        val base = OfficialProviderDexMethodQuery(
            cacheKey = "graph-node",
            declaringClassReference = OfficialProviderDexTypeReference(
                queryCacheKey = "manager",
                source = OfficialProviderDexTypeSource.RETURN_TYPE,
            ),
        )
        val first = OfficialProviderDexMethodCacheCodec.cacheKey(
            packageName = "example.player",
            processName = "example.player",
            versionCode = 1,
            lastUpdateTime = 1,
            query = base,
        )
        val changed = OfficialProviderDexMethodCacheCodec.cacheKey(
            packageName = "example.player",
            processName = "example.player",
            versionCode = 1,
            lastUpdateTime = 1,
            query = base.copy(
                declaringClassReference = base.declaringClassReference?.copy(
                    queryCacheKey = "different-manager",
                ),
            ),
        )
        assertNotEquals(first, changed)
    }

    @Test
    fun `cache key includes caller method semantics`() {
        val base = OfficialProviderDexMethodQuery(
            cacheKey = "kugou-lite-next-media-v2",
            declaringClassName = "com.kugou.common.player.manager.QueuePlayerManager",
            requiredCallerMethodNames = listOf("getNextMedia"),
        )
        val first = OfficialProviderDexMethodCacheCodec.cacheKey(
            packageName = "com.kugou.android.lite",
            processName = "com.kugou.android.lite.support",
            versionCode = 11540,
            lastUpdateTime = 1,
            query = base,
        )
        assertNotEquals(
            first,
            OfficialProviderDexMethodCacheCodec.cacheKey(
                packageName = "com.kugou.android.lite",
                processName = "com.kugou.android.lite.support",
                versionCode = 11540,
                lastUpdateTime = 1,
                query = base.copy(requiredCallerMethodNames = listOf("getPreMedia")),
            ),
        )
    }

    @Test
    fun `cross version method baseline round trips structural identity`() {
        val baseline = OfficialProviderDexMethodBaseline(
            fieldCount = 18,
            methodCount = 42,
            interfaceCount = 2,
            stableFieldTypeCounts = mapOf(
                "java.lang.String" to 3,
                "long" to 1,
            ),
            parameterCount = 0,
            stableParameterTypeNames = emptyList(),
            stableReturnTypeName = "java.lang.String",
            isStatic = false,
            ordinal = 4,
        )

        assertEquals(
            baseline,
            OfficialProviderDexMethodBaselineCodec.decode(
                OfficialProviderDexMethodBaselineCodec.encode(baseline),
            ),
        )
    }

    @Test
    fun `cross version method baseline preserves obfuscated parameter slots`() {
        val baseline = OfficialProviderDexMethodBaseline(
            fieldCount = 3,
            methodCount = 9,
            interfaceCount = 1,
            stableFieldTypeCounts = emptyMap(),
            parameterCount = 3,
            stableParameterTypeNames = listOf("java.lang.String", null, "int"),
            stableReturnTypeName = null,
            isStatic = true,
            ordinal = 1,
        )

        assertEquals(
            baseline,
            OfficialProviderDexMethodBaselineCodec.decode(
                OfficialProviderDexMethodBaselineCodec.encode(baseline),
            ),
        )
    }
}
