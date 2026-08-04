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
import java.util.LinkedHashMap

class AppleMusicHookProfilesTest {

    @Test
    fun `Apple Music 650 selects its exact obfuscated hook targets`() {
        val version = AppleMusicVersion("6.5.0", 1580L)

        assertEquals("am-6.5.0-1580", AppleMusicHookProfiles.profileFor(version)?.id)
        assertEquals(
            listOf("s8.E"),
            classNames(version, AppleMusicHookPoint.MEDIA_API_LOCALIZATION),
        )
        assertEquals(
            listOf("com.airbnb.epoxy.K"),
            classNames(version, AppleMusicHookPoint.EPOXY_FINAL_BIND),
        )
        assertEquals(
            listOf("com.apple.android.music.player.fragment.e0"),
            classNames(version, AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER),
        )
        assertEquals(
            listOf("com.apple.android.music.player.z"),
            classNames(version, AppleMusicHookPoint.LYRICS_WORD_RENDER_ADAPTER),
        )
        assertEquals(
            listOf(
                "com.apple.android.music.player.R0",
                "com.apple.android.music.player.z",
            ),
            classNames(version, AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER),
        )
        assertEquals(
            listOf("z1.l", "z1.t"),
            classNames(version, AppleMusicHookPoint.COMPOSE_TEXT_LAYOUT),
        )
        assertEquals(
            listOf("com.apple.android.music.utils.l1\$a"),
            classNames(version, AppleMusicHookPoint.APPLE_TEXT_STYLE_UTILS),
        )
        assertEquals(
            listOf("l7.e8"),
            classNames(version, AppleMusicHookPoint.IN_APP_ACTION_SHEET_BINDING),
        )
        assertEquals(
            listOf("z0.v0"),
            classNames(version, AppleMusicHookPoint.COMPOSE_NEVER_EQUAL_POLICY),
        )
        assertEquals(
            listOf("B0"),
            methodNames(version, AppleMusicHookPoint.LIBRARY_COMPOSE_VIEW_MODEL_GETTER),
        )
    }

    @Test
    fun `Apple Music 651 selects its exact obfuscated hook targets`() {
        val version = AppleMusicVersion("6.5.1", 1583L)

        assertEquals("am-6.5.1-1583", AppleMusicHookProfiles.profileFor(version)?.id)
        assertEquals(
            listOf("s8.F"),
            classNames(version, AppleMusicHookPoint.MEDIA_API_LOCALIZATION),
        )
        assertEquals(
            listOf("com.airbnb.epoxy.J"),
            classNames(version, AppleMusicHookPoint.EPOXY_FINAL_BIND),
        )
        assertEquals(
            listOf("com.apple.android.music.player.fragment.a0"),
            classNames(version, AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER),
        )
        assertEquals(
            listOf("com.apple.android.music.player.A"),
            classNames(version, AppleMusicHookPoint.LYRICS_WORD_RENDER_ADAPTER),
        )
        assertEquals(
            listOf(
                "com.apple.android.music.player.A",
                "com.apple.android.music.player.U0",
            ),
            classNames(version, AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER),
        )
        assertEquals(
            listOf("z1.k", "z1.s"),
            classNames(version, AppleMusicHookPoint.COMPOSE_TEXT_LAYOUT),
        )
        assertEquals(
            listOf("com.apple.android.music.utils.i1\$a"),
            classNames(version, AppleMusicHookPoint.APPLE_TEXT_STYLE_UTILS),
        )
        assertEquals(
            listOf("l7.f8"),
            classNames(version, AppleMusicHookPoint.IN_APP_ACTION_SHEET_BINDING),
        )
        assertEquals(
            listOf("z0.t0"),
            classNames(version, AppleMusicHookPoint.COMPOSE_NEVER_EQUAL_POLICY),
        )
        assertEquals(
            listOf("A0"),
            methodNames(version, AppleMusicHookPoint.LIBRARY_COMPOSE_VIEW_MODEL_GETTER),
        )
        assertFalse(
            classNames(
                version,
                AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER,
            ).any { className -> className.contains("ViewOnClickListenerC3365a0") }
        )
    }

    @Test
    fun `Listen Now artwork hooks are owned by the 651 original DEX profile`() {
        val version651 = AppleMusicVersion("6.5.1", 1583L)
        val version650 = AppleMusicVersion("6.5.0", 1580L)
        val listenNowHookPoints = listOf(
            AppleMusicHookPoint.LISTEN_NOW_MODEL_BUILDER,
            AppleMusicHookPoint.LISTEN_NOW_BOUND_LISTENER,
            AppleMusicHookPoint.LISTEN_NOW_MODEL,
            AppleMusicHookPoint.LISTEN_NOW_ARTWORK_RESOLVER,
            AppleMusicHookPoint.LISTEN_NOW_DELEGATING_ITEM,
            AppleMusicHookPoint.LISTEN_NOW_CUSTOM_IMAGE_VIEW,
            AppleMusicHookPoint.LISTEN_NOW_MEDIA_ENTITY,
            AppleMusicHookPoint.LISTEN_NOW_COLLECTION_ITEM_VIEW,
        )

        val builder = target(version651, AppleMusicHookPoint.LISTEN_NOW_MODEL_BUILDER)
        assertEquals(
            "com.apple.android.music.listennow.ListenNowEpoxyController",
            builder.className,
        )
        assertEquals("buildStandardSwoosh\$lambda\$35", builder.methodName)
        assertEquals(5, builder.parameterCount)
        assertEquals(
            listOf(
                "com.apple.android.music.listennow.ListenNowEpoxyController",
                "com.apple.android.music.mediaapi.models.Recommendation",
                "com.apple.android.music.common.D0",
                "com.apple.android.music.mediaapi.models.MediaEntity",
                "java.util.List",
            ),
            builder.parameterTypeNames,
        )
        assertEquals("com.airbnb.epoxy.l", builder.returnTypeName)
        assertEquals(true, builder.isStatic)
        assertFalse(builder.includeSynthetic)

        val boundListener = target(
            version651,
            AppleMusicHookPoint.LISTEN_NOW_BOUND_LISTENER,
        )
        assertEquals(
            "com.apple.android.music.listennow.ListenNowEpoxyController\$Q",
            boundListener.className,
        )
        assertEquals("onModelBound", boundListener.methodName)
        assertEquals(3, boundListener.parameterCount)
        assertEquals("void", boundListener.returnTypeName)
        assertTrue(boundListener.includeSynthetic)

        val artworkResolver = target(
            version651,
            AppleMusicHookPoint.LISTEN_NOW_ARTWORK_RESOLVER,
        )
        assertEquals("com.apple.android.music.common.J", artworkResolver.className)
        assertEquals("t", artworkResolver.methodName)
        assertEquals(
            listOf("com.apple.android.music.model.CollectionItemView"),
            artworkResolver.parameterTypeNames,
        )
        assertEquals("void", artworkResolver.returnTypeName)
        assertTrue(artworkResolver.includeSynthetic)

        assertEquals(
            "com.apple.android.music.l1",
            target(version651, AppleMusicHookPoint.LISTEN_NOW_MODEL).className,
        )
        assertEquals(
            "com.apple.android.music.model.extensions.DelegatingCollectionItemView",
            target(version651, AppleMusicHookPoint.LISTEN_NOW_DELEGATING_ITEM).className,
        )
        assertEquals(
            "com.apple.android.music.common.CustomImageView",
            target(version651, AppleMusicHookPoint.LISTEN_NOW_CUSTOM_IMAGE_VIEW).className,
        )
        assertEquals(
            "com.apple.android.music.mediaapi.models.MediaEntity",
            target(version651, AppleMusicHookPoint.LISTEN_NOW_MEDIA_ENTITY).className,
        )
        assertEquals(
            "com.apple.android.music.model.CollectionItemView",
            target(version651, AppleMusicHookPoint.LISTEN_NOW_COLLECTION_ITEM_VIEW).className,
        )

        val exactClassNames = listenNowHookPoints.flatMap { hookPoint ->
            classNames(version651, hookPoint)
        }
        assertFalse(
            exactClassNames.contains(
                "com.apple.android.music.ListenNowEpoxyController\$Q"
            )
        )
        assertFalse(exactClassNames.contains("p213l7.Uf"))
        assertTrue(listenNowHookPoints.all { hookPoint ->
            AppleMusicHookProfiles.exactTargets(version650, hookPoint).isEmpty()
        })
    }

    @Test
    fun `single class resolver uses the profiled Listen Now target`() {
        val expectedClassName = "com.apple.android.music.l1"
        val resolver = AppleMusicHookResolver(
            version = AppleMusicVersion("6.5.1", 1583L),
            classLookup = { name ->
                if (name == expectedClassName) String::class.java
                else throw ClassNotFoundException(name)
            },
        )

        val resolved = resolver.resolveClass(AppleMusicHookPoint.LISTEN_NOW_MODEL)

        assertEquals(expectedClassName, resolved.target.className)
        assertEquals(String::class.java, resolved.clazz)
        assertFalse(resolved.compatibilityFallback)
    }

    @Test
    fun `compose targets keep each version original dex names`() {
        val targets650 = classNames(
            AppleMusicVersion("6.5.0", 1580L),
            AppleMusicHookPoint.COMPOSE_TEXT_LAYOUT,
        )
        val targets651 = classNames(
            AppleMusicVersion("6.5.1", 1583L),
            AppleMusicHookPoint.COMPOSE_TEXT_LAYOUT,
        )

        assertEquals(listOf("z1.l", "z1.t"), targets650)
        assertEquals(listOf("z1.k", "z1.s"), targets651)
        assertTrue((targets650 + targets651).none { className ->
            className.matches(Regex("p\\d+.*"))
        })
    }

    @Test
    fun `unknown versions try newer verified targets before older ones`() {
        val version = AppleMusicVersion("6.6.0", 1600L)

        assertEquals(
            listOf("com.airbnb.epoxy.J", "com.airbnb.epoxy.K"),
            AppleMusicHookProfiles.candidates(
                version,
                AppleMusicHookPoint.EPOXY_FINAL_BIND,
            ).map(AppleMusicHookTarget::className),
        )
        assertEquals(
            listOf("s8.F", "s8.E"),
            AppleMusicHookProfiles.candidates(
                version,
                AppleMusicHookPoint.MEDIA_API_LOCALIZATION,
            ).map(AppleMusicHookTarget::className),
        )
        assertEquals(
            listOf("com.apple.android.music.utils.i1\$a", "com.apple.android.music.utils.l1\$a"),
            AppleMusicHookProfiles.candidates(
                version,
                AppleMusicHookPoint.APPLE_TEXT_STYLE_UTILS,
            ).map(AppleMusicHookTarget::className),
        )
        assertEquals(
            listOf("l7.f8", "l7.e8"),
            AppleMusicHookProfiles.candidates(
                version,
                AppleMusicHookPoint.IN_APP_ACTION_SHEET_BINDING,
            ).map(AppleMusicHookTarget::className),
        )
        assertEquals(
            listOf("z0.t0", "z0.v0"),
            AppleMusicHookProfiles.candidates(
                version,
                AppleMusicHookPoint.COMPOSE_NEVER_EQUAL_POLICY,
            ).map(AppleMusicHookTarget::className),
        )
        assertEquals(
            listOf(
                "com.apple.android.music.player.A",
                "com.apple.android.music.player.U0",
                "com.apple.android.music.player.R0",
                "com.apple.android.music.player.z",
            ),
            AppleMusicHookProfiles.candidates(
                version,
                AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER,
            ).map(AppleMusicHookTarget::className),
        )
        assertEquals(
            listOf("z1.k", "z1.s", "z1.l", "z1.t"),
            AppleMusicHookProfiles.candidates(
                version,
                AppleMusicHookPoint.COMPOSE_TEXT_LAYOUT,
            ).map(AppleMusicHookTarget::className),
        )
        assertEquals(
            listOf("A0", "B0"),
            AppleMusicHookProfiles.candidates(
                version,
                AppleMusicHookPoint.LIBRARY_COMPOSE_VIEW_MODEL_GETTER,
            ).mapNotNull(AppleMusicHookTarget::methodName),
        )
    }

    @Test
    fun `resolver rejects a class with the wrong method shape before fallback`() {
        val classes = mapOf(
            "com.airbnb.epoxy.J" to InvalidEpoxyHolder::class.java,
            "com.airbnb.epoxy.K" to CompatibleEpoxyHolder::class.java,
        )
        val resolver = AppleMusicHookResolver(
            version = AppleMusicVersion("6.5.1", 1583L),
            classLookup = { name -> classes[name] ?: throw ClassNotFoundException(name) },
        )

        val resolved = resolver.resolveMethod(AppleMusicHookPoint.EPOXY_FINAL_BIND)

        assertEquals("com.airbnb.epoxy.K", resolved.target.className)
        assertEquals("t", resolved.method.name)
        assertTrue(resolved.compatibilityFallback)
    }

    @Test
    fun `resolver accepts the 651 MediaApi target only with a map return type`() {
        val classes = mapOf("s8.F" to CompatibleMediaApi::class.java)
        val resolver = AppleMusicHookResolver(
            version = AppleMusicVersion("6.5.1", 1583L),
            classLookup = { name -> classes[name] ?: throw ClassNotFoundException(name) },
        )

        val resolved = resolver.resolveMethod(AppleMusicHookPoint.MEDIA_API_LOCALIZATION)

        assertEquals("s8.F", resolved.target.className)
        assertEquals("c0", resolved.method.name)
        assertFalse(resolved.compatibilityFallback)
    }

    private fun classNames(
        version: AppleMusicVersion,
        hookPoint: AppleMusicHookPoint,
    ): List<String> = AppleMusicHookProfiles.exactTargets(version, hookPoint)
        .map(AppleMusicHookTarget::className)

    private fun methodNames(
        version: AppleMusicVersion,
        hookPoint: AppleMusicHookPoint,
    ): List<String> = AppleMusicHookProfiles.exactTargets(version, hookPoint)
        .mapNotNull(AppleMusicHookTarget::methodName)

    private fun target(
        version: AppleMusicVersion,
        hookPoint: AppleMusicHookPoint,
    ): AppleMusicHookTarget = AppleMusicHookProfiles.exactTargets(version, hookPoint).single()

    private class InvalidEpoxyHolder {
        @Suppress("UNUSED_PARAMETER")
        fun t(model: Any, previousModel: Any, payloads: List<*>, position: String) = Unit
    }

    private class CompatibleEpoxyHolder {
        @Suppress("UNUSED_PARAMETER")
        fun t(model: Any, previousModel: Any, payloads: List<*>, position: Int) = Unit
    }

    private class CompatibleMediaApi {
        companion object {
            @JvmStatic
            fun c0(params: Map<Any?, Any?>): LinkedHashMap<Any?, Any?> =
                LinkedHashMap(params)
        }
    }
}
