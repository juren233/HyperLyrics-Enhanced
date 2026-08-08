/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import org.junit.Assert.assertThrows
import org.junit.Test

class OfficialProviderDexMethodQueryValidatorTest {
    @Test
    fun `accepts exact declaring class without required strings`() {
        OfficialProviderDexMethodQueryValidator.validate(
            OfficialProviderDexMethodQuery(
                cacheKey = "kugou-lyric-manager-path-loader-v1",
                declaringClassName = "com.kugou.framework.lyric.LyricManager",
                requiredStrings = emptyList(),
                parameterTypeNames = listOf("java.lang.String"),
                returnTypeName = "com.kugou.framework.lyric.k",
                isStatic = false,
            )
        )
    }

    @Test
    fun `rejects query without class or required strings`() {
        assertThrows(IllegalArgumentException::class.java) {
            OfficialProviderDexMethodQueryValidator.validate(
                OfficialProviderDexMethodQuery(
                    cacheKey = "unanchored-query",
                    requiredStrings = emptyList(),
                )
            )
        }
    }

    @Test
    fun `rejects blank required string`() {
        assertThrows(IllegalArgumentException::class.java) {
            OfficialProviderDexMethodQueryValidator.validate(
                OfficialProviderDexMethodQuery(
                    cacheKey = "blank-string-query",
                    requiredStrings = listOf(" "),
                )
            )
        }
    }
}
