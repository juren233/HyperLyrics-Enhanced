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
    fun `accepts caller method name as semantic anchor`() {
        OfficialProviderDexMethodQueryValidator.validate(
            OfficialProviderDexMethodQuery(
                cacheKey = "kugou-lite-next-media-v2",
                requiredCallerMethodNames = listOf("getNextMedia"),
                parameterTypeNames = emptyList(),
                returnTypeName = "com.kugou.common.player.manager.IMedia",
                isStatic = false,
            ),
        )
    }

    @Test
    fun `rejects blank caller method name`() {
        assertThrows(IllegalArgumentException::class.java) {
            OfficialProviderDexMethodQueryValidator.validate(
                OfficialProviderDexMethodQuery(
                    cacheKey = "blank-caller",
                    requiredCallerMethodNames = listOf(" "),
                ),
            )
        }
    }

    @Test
    fun `rejects preferred target with caller semantic constraint`() {
        assertThrows(IllegalArgumentException::class.java) {
            OfficialProviderDexMethodQueryValidator.validate(
                OfficialProviderDexMethodQuery(
                    cacheKey = "unsafe-preferred-caller",
                    preferredTarget = OfficialProviderMethodTarget(
                        className = "example.QueuePlayerManager",
                        methodName = "P0",
                        returnTypeName = "example.IMedia",
                        isStatic = false,
                    ),
                    requiredCallerMethodNames = listOf("getNextMedia"),
                ),
            )
        }
    }

    @Test
    fun `accepts forbidden invoke descriptor as semantic anchor`() {
        OfficialProviderDexMethodQueryValidator.validate(
            OfficialProviderDexMethodQuery(
                cacheKey = "qqmusic-hd-current-song-v4",
                forbiddenInvokedMethodDescriptors = listOf(
                    "Lcom/tencent/qqmusic/openapisdk/model/SongInfo;->getSongId()J",
                ),
                parameterTypeNames = emptyList(),
                returnTypeName = "com.tencent.qqmusic.openapisdk.model.SongInfo",
                isStatic = false,
            ),
        )
    }

    @Test
    fun `rejects blank forbidden invoke descriptor`() {
        assertThrows(IllegalArgumentException::class.java) {
            OfficialProviderDexMethodQueryValidator.validate(
                OfficialProviderDexMethodQuery(
                    cacheKey = "blank-forbidden-invoke",
                    forbiddenInvokedMethodDescriptors = listOf(" "),
                ),
            )
        }
    }

    @Test
    fun `rejects preferred target with forbidden invoke constraint`() {
        assertThrows(IllegalArgumentException::class.java) {
            OfficialProviderDexMethodQueryValidator.validate(
                OfficialProviderDexMethodQuery(
                    cacheKey = "unsafe-preferred-forbidden-invoke",
                    preferredTarget = OfficialProviderMethodTarget(
                        className = "example.MusicPlayerHelper",
                        methodName = "l0",
                        returnTypeName = "example.SongInfo",
                        isStatic = false,
                    ),
                    forbiddenInvokedMethodDescriptors = listOf(
                        "Lexample/SongInfo;->getSongId()J",
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

    @Test
    fun `keeps the plugin api v3 provider pack constructors`() {
        val v3Parameters = arrayOf(
            String::class.java,
            OfficialProviderMethodTarget::class.java,
            String::class.java,
            String::class.java,
            OfficialProviderDexTypeReference::class.java,
            List::class.java,
            List::class.java,
            List::class.java,
            List::class.java,
            Map::class.java,
            String::class.java,
            String::class.java,
            OfficialProviderDexTypeReference::class.java,
            Boolean::class.javaPrimitiveType!!,
            Boolean::class.javaObjectType,
        )
        assertNotNull(
            OfficialProviderDexMethodQuery::class.java.getDeclaredConstructor(*v3Parameters),
        )
        assertNotNull(
            OfficialProviderDexMethodQuery::class.java.getDeclaredConstructor(
                *v3Parameters,
                Int::class.javaPrimitiveType!!,
                DefaultConstructorMarker::class.java,
            ),
        )
    }

    @Test
    fun `keeps the caller constraint provider pack constructors`() {
        val callerConstraintParameters = arrayOf(
            String::class.java,
            OfficialProviderMethodTarget::class.java,
            String::class.java,
            String::class.java,
            OfficialProviderDexTypeReference::class.java,
            List::class.java,
            List::class.java,
            List::class.java,
            List::class.java,
            Map::class.java,
            String::class.java,
            String::class.java,
            OfficialProviderDexTypeReference::class.java,
            Boolean::class.javaPrimitiveType!!,
            Boolean::class.javaObjectType,
            List::class.java,
        )
        assertNotNull(
            OfficialProviderDexMethodQuery::class.java.getDeclaredConstructor(
                *callerConstraintParameters,
            ),
        )
        assertNotNull(
            OfficialProviderDexMethodQuery::class.java.getDeclaredConstructor(
                *callerConstraintParameters,
                Int::class.javaPrimitiveType!!,
                DefaultConstructorMarker::class.java,
            ),
        )
    }
}
