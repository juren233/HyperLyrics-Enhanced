/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import kotlin.jvm.internal.DefaultConstructorMarker
import org.junit.Assert.assertNotNull
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

    @Test
    fun `accepts an ordered query type reference as the anchor`() {
        OfficialProviderDexMethodQueryValidator.validate(
            OfficialProviderDexMethodQuery(
                cacheKey = "next-node",
                declaringClassReference = OfficialProviderDexTypeReference(
                    queryCacheKey = "previous-node",
                    source = OfficialProviderDexTypeSource.RETURN_TYPE,
                ),
                parameterTypeNames = emptyList(),
            ),
        )
    }

    @Test
    fun `rejects invalid parameter type reference`() {
        assertThrows(IllegalArgumentException::class.java) {
            OfficialProviderDexMethodQueryValidator.validate(
                OfficialProviderDexMethodQuery(
                    cacheKey = "invalid-parameter-reference",
                    declaringClassName = "example.Owner",
                    parameterTypeNames = emptyList(),
                    parameterTypeReferences = mapOf(
                        0 to OfficialProviderDexTypeReference(
                            queryCacheKey = "previous-node",
                            source = OfficialProviderDexTypeSource.RETURN_TYPE,
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `keeps the pre-reference provider pack constructors`() {
        val oldParameters = arrayOf(
            String::class.java,
            OfficialProviderMethodTarget::class.java,
            String::class.java,
            String::class.java,
            List::class.java,
            List::class.java,
            List::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType!!,
            Boolean::class.javaObjectType,
        )
        assertNotNull(
            OfficialProviderDexMethodQuery::class.java.getDeclaredConstructor(*oldParameters),
        )
        assertNotNull(
            OfficialProviderDexMethodQuery::class.java.getDeclaredConstructor(
                *oldParameters,
                Int::class.javaPrimitiveType!!,
                DefaultConstructorMarker::class.java,
            ),
        )
    }
}
