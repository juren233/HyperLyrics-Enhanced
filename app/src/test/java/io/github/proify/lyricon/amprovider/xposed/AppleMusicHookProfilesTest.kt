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
        assertContentHttpLocalizationTarget(version)
        assertPlaybackTargets(version)
        assertDebugNetworkTargets(version)
        assertLyricsFeatureTargets(version)
        assertQueueMetadataTargets(version)
        assertLibrarySurfaceTargets(version)
        assertEquals(
            listOf("com.airbnb.epoxy.K"),
            classNames(version, AppleMusicHookPoint.EPOXY_FINAL_BIND),
        )
        assertEquals(
            listOf("com.apple.android.music.player.fragment.e0"),
            classNames(version, AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER),
        )
        assertEquals(
            "a",
            target(version, AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER)
                .runtimeMemberName(
                    AppleMusicRuntimeMember.LYRICS_SOURCE_MENU_FRAGMENT_FIELD
                ),
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
        assertContentHttpLocalizationTarget(version)
        assertPlaybackTargets(version)
        assertDebugNetworkTargets(version)
        assertLyricsFeatureTargets(version)
        assertQueueMetadataTargets(version)
        assertLibrarySurfaceTargets(version)
        assertEquals(
            listOf("com.airbnb.epoxy.J"),
            classNames(version, AppleMusicHookPoint.EPOXY_FINAL_BIND),
        )
        assertEquals(
            listOf("com.apple.android.music.player.fragment.a0"),
            classNames(version, AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER),
        )
        assertEquals(
            null,
            target(version, AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER)
                .runtimeMemberNameOrNull(
                    AppleMusicRuntimeMember.LYRICS_SOURCE_MENU_FRAGMENT_FIELD
                ),
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
        val delegatingItem = target(
            version651,
            AppleMusicHookPoint.LISTEN_NOW_DELEGATING_ITEM,
        )
        assertEquals(
            "com.apple.android.music.model.extensions.DelegatingCollectionItemView",
            delegatingItem.className,
        )
        assertEquals(
            mapOf(
                AppleMusicRuntimeMember.COLLECTION_ITEM_GET_ID_METHOD to "getId",
                AppleMusicRuntimeMember.COLLECTION_ITEM_GET_PERSISTENT_ID_METHOD to
                    "getPersistentId",
                AppleMusicRuntimeMember.COLLECTION_ITEM_GET_CONTENT_TYPE_METHOD to
                    "getContentType",
                AppleMusicRuntimeMember.ARTWORK_GET_ARTWORK_TOKEN_METHOD to "getArtworkToken",
                AppleMusicRuntimeMember.ARTWORK_GET_ALL_ARTWORK_TOKENS_METHOD to
                    "getAllArtworkTokens",
                AppleMusicRuntimeMember.ARTWORK_GET_FETCHABLE_ARTWORK_TOKEN_METHOD to
                    "getFetchableArtworkToken",
                AppleMusicRuntimeMember.ARTWORK_GET_IMAGE_URL_METHOD to "getImageUrl",
                AppleMusicRuntimeMember.ARTWORK_GET_IMAGE_URLS_METHOD to "getImageUrls",
                AppleMusicRuntimeMember.ARTWORK_SET_IMAGE_URL_METHOD to "setImageUrl",
                AppleMusicRuntimeMember.ARTWORK_SET_IMAGE_URLS_METHOD to "setImageUrls",
                AppleMusicRuntimeMember.ARTWORK_NOTIFY_INITIAL_IMAGE_URL_METHOD to
                    "notifyInitialImageUrl",
            ),
            delegatingItem.runtimeMemberNames,
        )
        val customImageView = target(
            version651,
            AppleMusicHookPoint.LISTEN_NOW_CUSTOM_IMAGE_VIEW,
        )
        assertEquals(
            "com.apple.android.music.common.CustomImageView",
            customImageView.className,
        )
        assertEquals(
            "setBitmap",
            customImageView.runtimeMemberName(
                AppleMusicRuntimeMember.CUSTOM_IMAGE_SET_BITMAP_METHOD
            ),
        )
        val mediaEntity = target(
            version651,
            AppleMusicHookPoint.LISTEN_NOW_MEDIA_ENTITY,
        )
        assertEquals(
            "com.apple.android.music.mediaapi.models.MediaEntity",
            mediaEntity.className,
        )
        assertEquals(
            mapOf(
                AppleMusicRuntimeMember.COLLECTION_ITEM_GET_ID_METHOD to "getId",
                AppleMusicRuntimeMember.COLLECTION_ITEM_GET_PERSISTENT_ID_METHOD to
                    "getPersistentId",
                AppleMusicRuntimeMember.COLLECTION_ITEM_GET_CONTENT_TYPE_METHOD to
                    "getContentType",
                AppleMusicRuntimeMember.COLLECTION_ITEM_GET_TITLE_METHOD to "getTitle",
            ),
            mediaEntity.runtimeMemberNames,
        )
        val collectionItem = target(
            version651,
            AppleMusicHookPoint.LISTEN_NOW_COLLECTION_ITEM_VIEW,
        )
        assertEquals(
            "com.apple.android.music.model.CollectionItemView",
            collectionItem.className,
        )
        assertEquals(
            mapOf(
                AppleMusicRuntimeMember.COLLECTION_ITEM_GET_ID_METHOD to "getId",
                AppleMusicRuntimeMember.COLLECTION_ITEM_GET_PERSISTENT_ID_METHOD to
                    "getPersistentId",
                AppleMusicRuntimeMember.COLLECTION_ITEM_GET_CONTENT_TYPE_METHOD to
                    "getContentType",
                AppleMusicRuntimeMember.COLLECTION_ITEM_GET_TITLE_METHOD to "getTitle",
                AppleMusicRuntimeMember.COLLECTION_ITEM_SET_TITLE_METHOD to "setTitle",
                AppleMusicRuntimeMember.COLLECTION_ITEM_NOTIFY_CHANGE_METHOD to "notifyChange",
                AppleMusicRuntimeMember.ARTWORK_GET_ARTWORK_TOKEN_METHOD to "getArtworkToken",
                AppleMusicRuntimeMember.ARTWORK_GET_ALL_ARTWORK_TOKENS_METHOD to
                    "getAllArtworkTokens",
                AppleMusicRuntimeMember.ARTWORK_GET_FETCHABLE_ARTWORK_TOKEN_METHOD to
                    "getFetchableArtworkToken",
            ),
            collectionItem.runtimeMemberNames,
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
    fun `Apple Music 652 selects its exact obfuscated hook targets`() {
        val version = AppleMusicVersion("6.5.2", 1586L)

        assertEquals("am-6.5.2-1586", AppleMusicHookProfiles.profileFor(version)?.id)
        assertEquals(
            listOf("com.apple.android.music.player.fragment.d0"),
            classNames(version, AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER),
        )
        assertEquals(
            "a",
            target(version, AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER)
                .runtimeMemberName(
                    AppleMusicRuntimeMember.LYRICS_SOURCE_MENU_FRAGMENT_FIELD
                ),
        )
        assertEquals(
            listOf("z0.s0"),
            classNames(version, AppleMusicHookPoint.COMPOSE_NEVER_EQUAL_POLICY),
        )
        assertEquals(
            listOf("C1.w"),
            classNames(version, AppleMusicHookPoint.COMPOSE_OBSERVE_AS_STATE),
        )
        assertEquals(
            listOf("com.apple.android.music.common.L"),
            classNames(version, AppleMusicHookPoint.LISTEN_NOW_ARTWORK_RESOLVER),
        )
        assertEquals(
            listOf("com.apple.android.music.player.e"),
            classNames(version, AppleMusicHookPoint.IN_APP_GLOBAL_METADATA_DISPATCHER),
        )
        assertEquals(
            listOf("com.apple.android.music.library2.LibraryMainContentEpoxyController"),
            classNames(version, AppleMusicHookPoint.LIBRARY_EPOXY_BUILD),
        )
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
            listOf("z0.s0", "z0.t0", "z0.v0"),
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
        assertEquals(
            listOf("com.apple.android.music.common.L", "com.apple.android.music.common.J"),
            AppleMusicHookProfiles.candidates(
                version,
                AppleMusicHookPoint.LISTEN_NOW_ARTWORK_RESOLVER,
            ).map(AppleMusicHookTarget::className),
        )
        assertEquals(
            listOf("com.apple.android.music.player.e", "com.apple.android.music.player.f"),
            AppleMusicHookProfiles.candidates(
                version,
                AppleMusicHookPoint.IN_APP_GLOBAL_METADATA_DISPATCHER,
            ).map(AppleMusicHookTarget::className),
        )
        assertEquals(
            listOf(
                "com.apple.android.music.player.fragment.d0",
                "com.apple.android.music.player.fragment.a0",
                "com.apple.android.music.player.fragment.e0",
            ),
            AppleMusicHookProfiles.candidates(
                version,
                AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER,
            ).map(AppleMusicHookTarget::className),
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

    private fun assertContentHttpLocalizationTarget(version: AppleMusicVersion) {
        val target = target(version, AppleMusicHookPoint.CONTENT_HTTP_LOCALIZATION)
        assertEquals("u8.a", target.className)
        assertEquals("a", target.methodName)
        assertEquals(1, target.parameterCount)
        assertEquals(
            mapOf(
                AppleMusicRuntimeMember.CONTENT_HTTP_CHAIN_REQUEST_FIELD to "e",
                AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_URL_FIELD to "a",
                AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_HEADERS_FIELD to "c",
                AppleMusicRuntimeMember.CONTENT_HTTP_RESPONSE_STATUS_FIELD to "d",
                AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_NEW_BUILDER_METHOD to "b",
                AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_BUILDER_URL_METHOD to "h",
                AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_BUILDER_HEADER_METHOD to "d",
                AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_BUILDER_BUILD_METHOD to "b",
                AppleMusicRuntimeMember.CONTENT_HTTP_HEADERS_GET_METHOD to "e",
                AppleMusicRuntimeMember.CONTENT_HTTP_RESPONSE_REQUEST_FIELD to "a",
                AppleMusicRuntimeMember.CONTENT_HTTP_RESPONSE_HEADERS_FIELD to "f",
                AppleMusicRuntimeMember.CONTENT_HTTP_HEADERS_VALUES_FIELD to "a",
            ),
            target.runtimeMemberNames,
        )
        assertFalse(target.className.matches(Regex("p\\d+.*")))
    }

    private fun assertPlaybackTargets(version: AppleMusicVersion) {
        val exo = target(version, AppleMusicHookPoint.EXO_MEDIA_PLAYER)
        assertEquals(
            "com.apple.android.music.playback.player.ExoMediaPlayer",
            exo.className,
        )
        assertEquals("seekToPosition", exo.runtimeMemberName(AppleMusicRuntimeMember.EXO_SEEK_METHOD))
        assertEquals("play", exo.runtimeMemberName(AppleMusicRuntimeMember.EXO_PLAY_METHOD))
        assertEquals("pause", exo.runtimeMemberName(AppleMusicRuntimeMember.EXO_PAUSE_METHOD))
        assertEquals("stop", exo.runtimeMemberName(AppleMusicRuntimeMember.EXO_STOP_METHOD))
        assertEquals("release", exo.runtimeMemberName(AppleMusicRuntimeMember.EXO_RELEASE_METHOD))
        assertEquals(
            "getCurrentPosition",
            exo.runtimeMemberName(AppleMusicRuntimeMember.EXO_CURRENT_POSITION_METHOD),
        )

        val audioSession = target(version, AppleMusicHookPoint.EXO_AUDIO_SESSION_ID)
        assertEquals("com.apple.android.music.playback.player.ExoMediaPlayer", audioSession.className)
        assertEquals("onAudioSessionId", audioSession.methodName)
        assertEquals(listOf("int"), audioSession.parameterTypeNames)
        assertEquals("void", audioSession.returnTypeName)

        val controller = target(
            version,
            AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_CONTROLLER_STATE,
        )
        assertEquals(
            "com.apple.android.music.playback.controller.LocalMediaPlayerController",
            controller.className,
        )
        assertEquals("onPlaybackStateChanged", controller.methodName)
        assertEquals(3, controller.parameterCount)

        val audioVariant = target(
            version,
            AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_AUDIO_VARIANT_CHANGED,
        )
        assertEquals(
            "com.apple.android.music.playback.controller.LocalMediaPlayerController",
            audioVariant.className,
        )
        assertEquals("onPlaybackAudioVariantChanged", audioVariant.methodName)
        assertEquals(
            listOf(
                "com.apple.android.music.playback.player.MediaPlayer",
                "int",
                "long",
                "com.google.android.exoplayer2.Format",
                "com.google.android.exoplayer2.Format",
            ),
            audioVariant.parameterTypeNames,
        )
        assertEquals("void", audioVariant.returnTypeName)
        assertAtmosDiagnosticTargets(version)
        assertEquals(
            mapOf(
                AppleMusicRuntimeMember.PLAYBACK_PLAYER_CURRENT_ITEM_METHOD to "getCurrentItem",
                AppleMusicRuntimeMember.PLAYBACK_QUEUE_ITEM_ITEM_METHOD to "getItem",
                AppleMusicRuntimeMember.PLAYBACK_QUEUE_ITEM_ID_METHOD to "getPlaybackQueueId",
                AppleMusicRuntimeMember.PLAYBACK_MEDIA_ITEM_TITLE_METHOD to "getTitle",
                AppleMusicRuntimeMember.PLAYBACK_MEDIA_ITEM_ARTIST_NAME_METHOD to "getArtistName",
                AppleMusicRuntimeMember.PLAYBACK_MEDIA_ITEM_GENRE_NAME_METHOD to "getGenreName",
                AppleMusicRuntimeMember.PLAYBACK_MEDIA_ITEM_DURATION_METHOD to "getDuration",
                AppleMusicRuntimeMember.PLAYBACK_MEDIA_ITEM_SUBSCRIPTION_STORE_ID_METHOD to
                    "getSubscriptionStoreId",
                AppleMusicRuntimeMember.PLAYBACK_MEDIA_ITEM_PERSISTENT_ID_METHOD to
                    "getPersistentId",
            ),
            controller.runtimeMemberNames,
        )

        val metadataUpdated = target(
            version,
            AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_METADATA_UPDATED,
        )
        assertEquals(controller.className, metadataUpdated.className)
        assertEquals("onMetadataUpdated", metadataUpdated.methodName)
        assertEquals(2, metadataUpdated.parameterCount)
        assertTrue(metadataUpdated.includeSynthetic)
        assertTrue(metadataUpdated.allowFirstMatch)

        val indexChanged = target(
            version,
            AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_INDEX_CHANGED,
        )
        assertEquals(controller.className, indexChanged.className)
        assertEquals("onPlaybackIndexChanged", indexChanged.methodName)
        assertEquals(3, indexChanged.parameterCount)
        assertTrue(indexChanged.includeSynthetic)
        assertTrue(indexChanged.allowFirstMatch)

        val historyUpdate = target(version, AppleMusicHookPoint.IN_APP_HISTORY_UPDATE)
        assertEquals(
            "Z8.d",
            historyUpdate.runtimeMemberName(
                AppleMusicRuntimeMember.QUEUE_HISTORY_ENTRY_CLASS_NAME,
            ),
        )

        val contentItemTargets = AppleMusicHookProfiles.exactTargets(
            version,
            AppleMusicHookPoint.CONTENT_ITEM_METADATA_CLASSES,
        )
        assertEquals(6, contentItemTargets.size)
        val baseContentItem = contentItemTargets.single { target ->
            target.runtimeMemberNameOrNull(AppleMusicRuntimeMember.CONTENT_ITEM_ROLE) == "base"
        }
        assertEquals("com.apple.android.music.model.BaseContentItem", baseContentItem.className)
        assertEquals(
            mapOf(
                AppleMusicRuntimeMember.CONTENT_ITEM_ROLE to "base",
                AppleMusicRuntimeMember.CONTENT_ITEM_TITLE_GETTER to "getTitle",
                AppleMusicRuntimeMember.CONTENT_ITEM_NOW_PLAYING_TITLE_GETTER to
                    "getNowPlayingTitle",
                AppleMusicRuntimeMember.CONTENT_ITEM_ARTIST_GETTER to "getArtistName",
                AppleMusicRuntimeMember.CONTENT_ITEM_NOW_PLAYING_SUBTITLE_GETTER to
                    "getNowPlayingSubtitle",
                AppleMusicRuntimeMember.CONTENT_ITEM_SUBTITLE_GETTER to "getSubTitle",
                AppleMusicRuntimeMember.CONTENT_ITEM_COLLECTION_GETTER to "getCollectionName",
                AppleMusicRuntimeMember.CONTENT_ITEM_SUBSCRIPTION_STORE_ID_GETTER to
                    "getSubscriptionStoreId",
                AppleMusicRuntimeMember.CONTENT_ITEM_ID_GETTER to "getId",
                AppleMusicRuntimeMember.CONTENT_ITEM_PERSISTENT_ID_GETTER to "getPersistentId",
                AppleMusicRuntimeMember.CONTENT_ITEM_ASSET_ADAM_ID_GETTER to "getAssetAdamId",
                AppleMusicRuntimeMember.CONTENT_ITEM_REPORTING_ADAM_ID_GETTER to
                    "getReportingAdamId",
                AppleMusicRuntimeMember.CONTENT_ITEM_FORMER_IDS_GETTER to "getFormerIds",
                AppleMusicRuntimeMember.CONTENT_ITEM_ARTIST_ID_GETTER to "getArtistId",
                AppleMusicRuntimeMember.CONTENT_ITEM_ARTIST_ADAM_ID_GETTER to
                    "getArtistAdamId",
                AppleMusicRuntimeMember.CONTENT_ITEM_ARTIST_STORE_ID_GETTER to
                    "getArtistStoreId",
                AppleMusicRuntimeMember.CONTENT_ITEM_ARTIST_SUBSCRIPTION_STORE_ID_GETTER to
                    "getArtistSubscriptionStoreId",
                AppleMusicRuntimeMember.CONTENT_ITEM_TITLE_FIELD to "name",
                AppleMusicRuntimeMember.CONTENT_ITEM_ARTIST_FIELD to "artistName",
                AppleMusicRuntimeMember.CONTENT_ITEM_COLLECTION_FIELD to "collectionName",
                AppleMusicRuntimeMember.CONTENT_ITEM_SET_TITLE_METHOD to "setTitle",
                AppleMusicRuntimeMember.CONTENT_ITEM_SET_ARTIST_METHOD to "setArtistName",
                AppleMusicRuntimeMember.CONTENT_ITEM_SET_COLLECTION_METHOD to
                    "setCollectionName",
                AppleMusicRuntimeMember.CONTENT_ITEM_SET_SUBTITLE_METHOD to "setSubTitle",
                AppleMusicRuntimeMember.CONTENT_ITEM_NOTIFY_CHANGE_METHOD to "notifyChange",
            ),
            baseContentItem.runtimeMemberNames,
        )

        val recentlySearched = target(
            version,
            AppleMusicHookPoint.RECENTLY_SEARCHED_CONTROLLER,
        )
        assertEquals(
            "com.apple.android.music.search2.RecentlySearchedEpoxyController",
            recentlySearched.className,
        )
        assertEquals("setData", recentlySearched.methodName)
        assertEquals(listOf("java.util.List"), recentlySearched.parameterTypeNames)
        val recentlySearchedBound = target(
            version,
            AppleMusicHookPoint.RECENTLY_SEARCHED_MODEL_BOUND,
        )
        assertEquals(recentlySearched.className, recentlySearchedBound.className)
        assertEquals("onModelBound", recentlySearchedBound.methodName)
        assertEquals(4, recentlySearchedBound.parameterCount)
        assertTrue(recentlySearchedBound.includeSynthetic)
        assertEquals(
            "com.apple.android.music.mediaapi.models.MediaEntity",
            target(version, AppleMusicHookPoint.RECENTLY_SEARCHED_MEDIA_ENTITY).className,
        )
        assertEquals(
            "com.apple.android.music.common.MainContentActivity",
            target(version, AppleMusicHookPoint.APPLE_MAIN_CONTENT_ACTIVITY).className,
        )
        assertEquals(
            "com.apple.android.music.utils.AppSharedPreferences",
            target(version, AppleMusicHookPoint.APPLE_SHARED_PREFERENCES_CLASS).className,
        )
        val songModel = target(version, AppleMusicHookPoint.APPLE_SONG_MODEL_CLASS)
        assertEquals("com.apple.android.music.model.Song", songModel.className)
        assertEquals(
            mapOf(
                AppleMusicRuntimeMember.APPLE_SONG_SET_ID_METHOD to "setId",
                AppleMusicRuntimeMember.APPLE_SONG_SET_QUEUE_ID_METHOD to "setQueueId",
                AppleMusicRuntimeMember.APPLE_SONG_SET_HAS_LYRICS_METHOD to "setHasLyrics",
                AppleMusicRuntimeMember.LYRICS_SONG_ID_METHOD to "getId",
                AppleMusicRuntimeMember.LYRICS_SONG_QUEUE_ID_METHOD to "getQueueId",
            ),
            songModel.runtimeMemberNames,
        )
        assertEquals(
            "com.apple.android.music.player.O",
            target(version, AppleMusicHookPoint.APPLE_PLAYER_UTIL_CLASS).className,
        )
        assertEquals(
            mapOf(
                AppleMusicRuntimeMember.APPLE_PLAYER_UTIL_CONTAINER_METHOD to "a",
                AppleMusicRuntimeMember.APPLE_PLAYER_UTIL_PLAYBACK_ITEM_METHOD to "b",
            ),
            target(version, AppleMusicHookPoint.APPLE_PLAYER_UTIL_CLASS).runtimeMemberNames,
        )
        assertEquals(
            "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel",
            target(version, AppleMusicHookPoint.PLAYER_LYRICS_VIEW_MODEL_CLASS).className,
        )
        val artistContainer = target(version, AppleMusicHookPoint.IN_APP_CONTAINER_ARTIST_CLASS)
        assertEquals("com.apple.android.music.model.Artist", artistContainer.className)
        assertEquals(
            mapOf(
                AppleMusicRuntimeMember.IN_APP_CONTAINER_SET_TITLE_METHOD to "setTitle",
                AppleMusicRuntimeMember.IN_APP_CONTAINER_NOTIFY_CHANGE_METHOD to "notifyChange",
            ),
            artistContainer.runtimeMemberNames,
        )
        val albumContainer = target(version, AppleMusicHookPoint.IN_APP_CONTAINER_ALBUM_CLASS)
        assertEquals("com.apple.android.music.model.Album", albumContainer.className)
        assertEquals(artistContainer.runtimeMemberNames, albumContainer.runtimeMemberNames)
        val mediaApiHolder = target(
            version,
            AppleMusicHookPoint.MEDIA_API_REPOSITORY_HOLDER_CLASS,
        )
        assertEquals(
            "com.apple.android.music.mediaapi.repository.MediaApiRepositoryHolder",
            mediaApiHolder.className,
        )
        assertEquals(
            mapOf(
                AppleMusicRuntimeMember.MEDIA_API_HOLDER_GET_MEDIA_API_METHOD to "getMediaApi",
                AppleMusicRuntimeMember.MEDIA_API_STOREFRONT_FIELD to "s",
                AppleMusicRuntimeMember.MEDIA_API_DIRECT_QUERY_METHOD to "B",
                AppleMusicRuntimeMember.CATALOG_RESPONSE_DATA_METHOD to "getData",
                AppleMusicRuntimeMember.CATALOG_ENTITY_ID_METHOD to "getId",
                AppleMusicRuntimeMember.CATALOG_ENTITY_SUBSCRIPTION_STORE_ID_METHOD to
                    "getSubscriptionStoreId",
                AppleMusicRuntimeMember.CATALOG_ENTITY_ASSET_ADAM_ID_METHOD to "getAssetAdamId",
                AppleMusicRuntimeMember.CATALOG_ENTITY_REPORTING_ADAM_ID_METHOD to
                    "getReportingAdamId",
                AppleMusicRuntimeMember.CATALOG_ENTITY_FORMER_IDS_METHOD to "getFormerIds",
                AppleMusicRuntimeMember.CATALOG_ENTITY_ATTRIBUTES_METHOD to "getAttributes",
                AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_PLAY_PARAMS_METHOD to "getPlayParams",
                AppleMusicRuntimeMember.CATALOG_PLAY_PARAMS_CATALOG_ID_METHOD to "getCatalogId",
                AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_NAME_METHOD to "getName",
                AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ARTIST_NAME_METHOD to "getArtistName",
                AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ALBUM_NAME_METHOD to "getAlbumName",
                AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ARTIST_ID_METHOD to "getArtistId",
                AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ARTIST_ADAM_ID_METHOD to
                    "getArtistAdamId",
                AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ARTIST_STORE_ID_METHOD to
                    "getArtistStoreId",
                AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ARTIST_SUBSCRIPTION_STORE_ID_METHOD to
                    "getArtistSubscriptionStoreId",
                AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_SET_NAME_METHOD to "setName",
                AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_SET_ARTIST_NAME_METHOD to
                    "setArtistName",
                AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_SET_ALBUM_NAME_METHOD to
                    "setAlbumName",
                AppleMusicRuntimeMember.CATALOG_ENTITY_RELATIONSHIPS_METHOD to "getRelationships",
                AppleMusicRuntimeMember.CATALOG_RELATIONSHIP_ENTITIES_METHOD to "getEntities",
                AppleMusicRuntimeMember.CATALOG_RELATIONSHIP_DATA_METHOD to "getData",
                AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ISRC_METHOD to "getIsrc",
                AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_GENRE_NAMES_METHOD to "getGenreNames",
                AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_GENRE_NAME_METHOD to "getGenreName",
            ),
            mediaApiHolder.runtimeMemberNames,
        )
    }

    private fun assertAtmosDiagnosticTargets(version: AppleMusicVersion) {
        val mediaCodecPeriod = target(
            version,
            AppleMusicHookPoint.DEBUG_ATMOS_MEDIA_CODEC_PERIOD_ID,
        )
        assertEquals(
            "com.apple.android.music.playback.renderer.SVMediaCodecAudioRenderer",
            mediaCodecPeriod.className,
        )
        assertEquals("invalidatePeriodId", mediaCodecPeriod.methodName)
        assertEquals(
            listOf("com.google.android.exoplayer2.source.SampleStream", "long"),
            mediaCodecPeriod.parameterTypeNames,
        )

        val mediaCodecFormat = target(
            version,
            AppleMusicHookPoint.DEBUG_ATMOS_MEDIA_CODEC_INPUT_FORMAT,
        )
        assertEquals("onInputFormatChanged", mediaCodecFormat.methodName)
        assertEquals(
            listOf("com.google.android.exoplayer2.FormatHolder"),
            mediaCodecFormat.parameterTypeNames,
        )
        assertEquals(
            "format",
            mediaCodecFormat.runtimeMemberName(
                AppleMusicRuntimeMember.DEBUG_FORMAT_HOLDER_FORMAT_FIELD
            ),
        )
        assertEquals(
            "loudness",
            mediaCodecFormat.runtimeMemberName(
                AppleMusicRuntimeMember.DEBUG_FORMAT_LOUDNESS_FIELD
            ),
        )

        val mediaCodecSession = target(
            version,
            AppleMusicHookPoint.DEBUG_ATMOS_MEDIA_CODEC_AUDIO_SESSION,
        )
        assertEquals(
            "com.google.android.exoplayer2.audio.MediaCodecAudioRenderer",
            mediaCodecSession.className,
        )
        assertEquals("onAudioSessionId", mediaCodecSession.methodName)

        val mediaCodecOutput = target(
            version,
            AppleMusicHookPoint.DEBUG_ATMOS_MEDIA_CODEC_OUTPUT_BUFFER,
        )
        assertEquals("processOutputBuffer", mediaCodecOutput.methodName)
        assertEquals(
            listOf(
                "long",
                "long",
                "android.media.MediaCodec",
                "java.nio.ByteBuffer",
                "int",
                "int",
                "long",
                "boolean",
                "boolean",
                "com.google.android.exoplayer2.Format",
            ),
            mediaCodecOutput.parameterTypeNames,
        )
        assertEquals("boolean", mediaCodecOutput.returnTypeName)

        val svPeriod = target(version, AppleMusicHookPoint.DEBUG_ATMOS_SV_AUDIO_PERIOD_ID)
        assertEquals(
            "com.apple.android.music.playback.renderer.SVAudioRendererV2",
            svPeriod.className,
        )
        assertEquals("invalidatePeriodId", svPeriod.methodName)

        val svStream = target(
            version,
            AppleMusicHookPoint.DEBUG_ATMOS_SV_AUDIO_STREAM_CHANGED,
        )
        assertEquals("onStreamChanged", svStream.methodName)
        assertEquals(
            listOf("[Lcom.google.android.exoplayer2.Format;", "long"),
            svStream.parameterTypeNames,
        )
        assertEquals(
            "codecs",
            svStream.runtimeMemberName(AppleMusicRuntimeMember.DEBUG_FORMAT_CODECS_FIELD),
        )

        val svSession = target(version, AppleMusicHookPoint.DEBUG_ATMOS_SV_AUDIO_SESSION)
        assertEquals("onAudioSessionId", svSession.methodName)
        assertEquals(listOf("int"), svSession.parameterTypeNames)

        val svFirstBuffer = target(
            version,
            AppleMusicHookPoint.DEBUG_ATMOS_SV_AUDIO_FIRST_BUFFER,
        )
        assertEquals("maybeNotifyFirstDecodedBuffer", svFirstBuffer.methodName)
        assertEquals(emptyList<String>(), svFirstBuffer.parameterTypeNames)
    }

    private fun assertDebugNetworkTargets(version: AppleMusicVersion) {
        val request = target(version, AppleMusicHookPoint.LYRICS_NETWORK_REQUEST)
        assertEquals("t8.N0", request.className)
        assertEquals("z", request.methodName)
        assertTrue(request.allowFirstMatch)

        val cookies = target(version, AppleMusicHookPoint.LYRICS_COOKIE_JAR)
        assertEquals("s8.b", cookies.className)
        assertEquals("d", cookies.methodName)
        assertEquals(1, cookies.parameterCount)
        assertEquals(
            "a",
            cookies.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_COOKIE_NAME_FIELD),
        )
        assertEquals(
            "b",
            cookies.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_COOKIE_VALUE_FIELD),
        )
    }

    private fun assertQueueMetadataTargets(version: AppleMusicVersion) {
        val globalDispatcher = target(
            version,
            AppleMusicHookPoint.IN_APP_GLOBAL_METADATA_DISPATCHER,
        )
        assertEquals("com.apple.android.music.player.f", globalDispatcher.className)
        assertEquals("onMediaMetadataChanged", globalDispatcher.methodName)
        assertEquals(1, globalDispatcher.parameterCount)

        val nowPlaying = target(
            version,
            AppleMusicHookPoint.IN_APP_NOW_PLAYING_METADATA_LISTENER,
        )
        assertEquals(
            "com.apple.android.music.player.fragment." +
                "PlayerSongViewFragment\$PlayerListener",
            nowPlaying.className,
        )
        assertEquals("onMediaMetadataChanged", nowPlaying.methodName)
        assertEquals(1, nowPlaying.parameterCount)

        val queue = target(version, AppleMusicHookPoint.IN_APP_QUEUE_UPDATE)
        assertEquals(
            "com.apple.android.music.player.queuefa.NewPlayerQueueViewModel",
            queue.className,
        )
        assertEquals("updateQueue", queue.methodName)
        assertEquals(5, queue.parameterCount)

        val history = target(version, AppleMusicHookPoint.IN_APP_HISTORY_UPDATE)
        assertEquals(
            "com.apple.android.music.player.queuefa.NewPlayerQueueViewModel",
            history.className,
        )
        assertEquals("updateHistory", history.methodName)
        assertEquals(1, history.parameterCount)

        val adapterSubmit = target(
            version,
            AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_SUBMIT,
        )
        assertEquals("Y8.a", adapterSubmit.className)
        assertEquals("B", adapterSubmit.methodName)
        assertEquals(1, adapterSubmit.parameterCount)
        assertEquals(
            mapOf(
                AppleMusicRuntimeMember.QUEUE_ADAPTER_DISPLAYED_ENTRY_METHOD to "A",
                AppleMusicRuntimeMember.QUEUE_ADAPTER_SUBMITTED_ENTRIES_FIELD to "l",
                AppleMusicRuntimeMember.QUEUE_ENTRY_ITEM_FIELD to "b",
                AppleMusicRuntimeMember.QUEUE_ITEM_METADATA_FIELD to "d",
                AppleMusicRuntimeMember.QUEUE_ITEM_ID_FIELD to "a",
                AppleMusicRuntimeMember.MEDIA3_METADATA_BUNDLE_FIELD to "I",
                AppleMusicRuntimeMember.MEDIA3_METADATA_TITLE_FIELD to "a",
                AppleMusicRuntimeMember.MEDIA3_METADATA_ARTIST_FIELD to "b",
            ),
            adapterSubmit.runtimeMemberNames,
        )

        val adapterBind = target(
            version,
            AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_BIND,
        )
        assertEquals("Y8.a", adapterBind.className)
        assertEquals("p", adapterBind.methodName)
        assertEquals(2, adapterBind.parameterCount)
    }

    private fun assertLibrarySurfaceTargets(version: AppleMusicVersion) {
        val entityTargets = AppleMusicHookProfiles.exactTargets(
            version,
            AppleMusicHookPoint.LIBRARY_ENTITY_CLASSES,
        )
        assertEquals(
            listOf(
                "com.apple.android.music.model.AlbumCollectionItem",
                "com.apple.android.music.model.Song",
                "com.apple.android.music.mediaapi.models.Song",
                "com.apple.android.music.mediaapi.models.LibrarySong",
                "com.apple.android.music.mediaapi.models.Album",
                "com.apple.android.music.mediaapi.models.LibraryAlbum",
                "com.apple.android.music.mediaapi.models.Artist",
                "com.apple.android.music.mediaapi.models.LibraryArtist",
            ),
            entityTargets.map(AppleMusicHookTarget::className),
        )
        assertEquals(
            listOf(
                "model_album" to null,
                "model_song" to null,
                "media_api_song" to "song",
                "library_song" to "song",
                "media_api_album" to "album",
                "library_album" to "album",
                "media_api_artist" to "artist",
                "library_artist" to "artist",
            ),
            entityTargets.map { target ->
                target.runtimeMemberName(AppleMusicRuntimeMember.LIBRARY_ENTITY_ROLE) to
                    target.runtimeMemberNameOrNull(AppleMusicRuntimeMember.LIBRARY_ENTITY_KIND)
            },
        )

        val epoxy = target(version, AppleMusicHookPoint.LIBRARY_EPOXY_BUILD)
        assertEquals(
            "com.apple.android.music.library2.LibraryMainContentEpoxyController",
            epoxy.className,
        )
        assertEquals("buildModels", epoxy.methodName)
        assertEquals(5, epoxy.parameterCount)

        val compose = target(version, AppleMusicHookPoint.LIBRARY_COMPOSE_CONTENT)
        assertEquals(
            "com.apple.android.music.library3.LibraryComposeContentFragment",
            compose.className,
        )
        assertEquals("J1", compose.methodName)
        assertEquals(2, compose.parameterCount)
        assertEquals(
            "getRecentItemsLiveResult",
            compose.runtimeMemberName(
                AppleMusicRuntimeMember.LIBRARY_RECENT_ITEMS_LIVE_RESULT_METHOD
            ),
        )

        val observe = target(version, AppleMusicHookPoint.COMPOSE_OBSERVE_AS_STATE)
        assertEquals("C1.c", observe.className)
        assertEquals("g", observe.methodName)
        assertEquals(2, observe.parameterCount)
        assertEquals(
            mapOf(
                AppleMusicRuntimeMember.LIBRARY_COMPOSE_STATE_POLICY_FIELD to "b",
                AppleMusicRuntimeMember.LIBRARY_COMPOSE_STATE_GET_VALUE_METHOD to "getValue",
                AppleMusicRuntimeMember.LIBRARY_COMPOSE_STATE_SET_VALUE_METHOD to "setValue",
            ),
            observe.runtimeMemberNames,
        )

        val dataBindingTargets = AppleMusicHookProfiles.exactTargets(
            version,
            AppleMusicHookPoint.DATA_BINDING_RUNTIME_CLASSES,
        )
        assertEquals(
            listOf(
                "androidx.databinding.ViewDataBinding",
                "androidx.databinding.i",
                "com.apple.android.music.playback.BR",
                "androidx.recyclerview.widget.RecyclerView",
                "com.apple.android.music.model.BaseContentItem",
            ),
            dataBindingTargets.map(AppleMusicHookTarget::className),
        )
        val bindingRuntime = dataBindingTargets.first()
        assertEquals(
            mapOf(
                AppleMusicRuntimeMember.DATA_BINDING_RUNTIME_ROLE to "binding",
                AppleMusicRuntimeMember.DATA_BINDING_REGISTRATION_METHOD to "k0",
                AppleMusicRuntimeMember.DATA_BINDING_INVALIDATE_METHOD to "y",
                AppleMusicRuntimeMember.DATA_BINDING_EXECUTE_METHOD to "n",
                AppleMusicRuntimeMember.DATA_BINDING_SET_VARIABLE_METHOD to "h0",
            ),
            bindingRuntime.runtimeMemberNames,
        )

        val collectionTargets = AppleMusicHookProfiles.exactTargets(
            version,
            AppleMusicHookPoint.COLLECTION_SURFACE_CLASSES,
        )
        assertEquals(
            listOf(
                "recycler",
                "media_entity",
                "album_entity",
                "album_controller",
                "album_header_model",
                "playlist_controller",
                "album_row_model",
                "playlist_row_model",
            ),
            collectionTargets.map { target ->
                target.runtimeMemberName(AppleMusicRuntimeMember.COLLECTION_RUNTIME_ROLE)
            },
        )
        val albumController = collectionTargets.first { target ->
            target.runtimeMemberName(AppleMusicRuntimeMember.COLLECTION_RUNTIME_ROLE) ==
                "album_controller"
        }
        assertEquals(
            mapOf(
                AppleMusicRuntimeMember.COLLECTION_RUNTIME_ROLE to "album_controller",
                AppleMusicRuntimeMember.COLLECTION_ALBUM_HEADER_BUILD_METHOD to
                    "buildHeaderModelInternal",
                AppleMusicRuntimeMember.COLLECTION_CONTROLLER_ATTACH_METHOD to
                    "onAttachedToRecyclerView",
                AppleMusicRuntimeMember.COLLECTION_CONTROLLER_DETACH_METHOD to
                    "onDetachedFromRecyclerView",
                AppleMusicRuntimeMember.COLLECTION_CONTROLLER_SET_DATA_METHOD to "setData",
            ),
            albumController.runtimeMemberNames,
        )
        val playlistRow = collectionTargets.first { target ->
            target.runtimeMemberName(AppleMusicRuntimeMember.COLLECTION_RUNTIME_ROLE) ==
                "playlist_row_model"
        }
        assertEquals("M", playlistRow.runtimeMemberName(
            AppleMusicRuntimeMember.COLLECTION_PLAYLIST_TITLE_FIELD
        ))
        assertEquals("P", playlistRow.runtimeMemberName(
            AppleMusicRuntimeMember.COLLECTION_PLAYLIST_SUBTITLE_FIELD
        ))
        assertEquals(
            "u",
            target(version, AppleMusicHookPoint.EPOXY_FINAL_BIND).runtimeMemberName(
                AppleMusicRuntimeMember.EPOXY_FINAL_HOLDER_MODEL_HOLDER_METHOD
            ),
        )
        assertEquals(
            "c",
            target(version, AppleMusicHookPoint.APPLE_TEXT_STYLE_UTILS).runtimeMemberName(
                AppleMusicRuntimeMember.APPLE_TEXT_STYLE_EXPLICIT_TITLE_METHOD
            ),
        )

        val artistTargets = AppleMusicHookProfiles.exactTargets(
            version,
            AppleMusicHookPoint.ARTIST_SURFACE_CLASSES,
        )
        assertEquals(
            listOf(
                "recycler",
                "media_entity",
                "base_controller",
                "artist_controller",
                "top_song_model",
                "header_model",
            ),
            artistTargets.map { target ->
                target.runtimeMemberName(AppleMusicRuntimeMember.ARTIST_RUNTIME_ROLE)
            },
        )
        val artistController = artistTargets.first { target ->
            target.runtimeMemberName(AppleMusicRuntimeMember.ARTIST_RUNTIME_ROLE) ==
                "artist_controller"
        }
        assertEquals("buildModels", artistController.runtimeMemberName(
            AppleMusicRuntimeMember.ARTIST_PROFILE_BUILD_METHOD
        ))
        assertEquals("setData", artistController.runtimeMemberName(
            AppleMusicRuntimeMember.ARTIST_CONTROLLER_SET_DATA_METHOD
        ))
        val topSong = artistTargets.first { target ->
            target.runtimeMemberName(AppleMusicRuntimeMember.ARTIST_RUNTIME_ROLE) ==
                "top_song_model"
        }
        assertEquals("a", topSong.runtimeMemberName(
            AppleMusicRuntimeMember.ARTIST_MODEL_BIND_METHOD
        ))
        assertEquals("L", topSong.runtimeMemberName(
            AppleMusicRuntimeMember.ARTIST_TOP_SONG_TITLE_FIELD
        ))
        assertEquals("P", topSong.runtimeMemberName(
            AppleMusicRuntimeMember.ARTIST_TOP_SONG_SUBTITLE_FIELD
        ))
    }

    private fun assertLyricsFeatureTargets(version: AppleMusicVersion) {
        assertEquals(
            "com.apple.android.music.utils.AppSharedPreferences",
            target(version, AppleMusicHookPoint.LYRICS_TRANSLATION_PREFERENCE).className,
        )
        assertEquals(
            "setLyricsTranslationSelected",
            target(version, AppleMusicHookPoint.LYRICS_TRANSLATION_PREFERENCE).methodName,
        )
        assertEquals(
            "setLyricsPronunciationSelected",
            target(version, AppleMusicHookPoint.LYRICS_PRONUNCIATION_PREFERENCE).methodName,
        )
        assertEquals(
            "matchToSystemLyricsScript",
            target(version, AppleMusicHookPoint.LYRICS_OFFICIAL_PRONUNCIATION_MATCH).methodName,
        )
        assertEquals(
            "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel\$f",
            target(version, AppleMusicHookPoint.LYRICS_PREFERRED_LANGUAGES_REQUEST).className,
        )

        val load = target(version, AppleMusicHookPoint.LYRICS_VIEW_MODEL_LOAD)
        assertEquals(
            "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel",
            load.className,
        )
        assertEquals("loadLyrics", load.methodName)
        assertEquals(
            "getHtmlPronunciationLineText",
            load.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_TEXT_METHOD
            ),
        )
        assertEquals(
            "getPronunciationBackgroundWords",
            load.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_BACKGROUND_WORDS_METHOD
            ),
        )
        assertEquals(
            "com.apple.android.music.ttml.javanative.model.LyricsWordVector",
            load.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_WORD_VECTOR_CLASS_NAME),
        )
        assertEquals(
            mapOf(
                AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD to "get",
                AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD to "get",
                AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_SIZE_METHOD to "size",
                AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_ADDRESS_METHOD to "address",
                AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_SECTIONS_METHOD to "getSections",
                AppleMusicRuntimeMember.LYRICS_NATIVE_SECTION_LINES_METHOD to "getLines",
                AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD to "getBegin",
                AppleMusicRuntimeMember.LYRICS_NATIVE_END_METHOD to "getEnd",
                AppleMusicRuntimeMember.LYRICS_NATIVE_DURATION_METHOD to "getDuration",
                AppleMusicRuntimeMember.LYRICS_NATIVE_WORD_ID_METHOD to "getWordId",
                AppleMusicRuntimeMember.LYRICS_NATIVE_WHITESPACE_METHOD to "isWhitespace",
                AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_PRONUNCIATION_LANGUAGES_METHOD to
                    "getPronunciationLanguages",
                AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_TRANSLATION_LANGUAGES_METHOD to
                    "getTranslationLanguages",
                AppleMusicRuntimeMember.LYRICS_NATIVE_SET_ADAM_ID_METHOD to "setAdamId",
                AppleMusicRuntimeMember.LYRICS_NATIVE_SET_QUEUE_ID_METHOD to "setQueueId",
                AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_QUEUE_ID_METHOD to "getQueueId",
                AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_AGENTS_METHOD to "getAgents",
                AppleMusicRuntimeMember.LYRICS_NATIVE_AGENT_METHOD to "getAgent",
                AppleMusicRuntimeMember.LYRICS_NATIVE_AGENT_NAME_TYPES_METHOD to "getNameTypes_",
                AppleMusicRuntimeMember.LYRICS_NATIVE_AGENT_TYPE_METHOD to "getType_",
                AppleMusicRuntimeMember.LYRICS_NATIVE_AGENT_ID_METHOD to "getId",
                AppleMusicRuntimeMember.LYRICS_SONG_ADAM_ID_METHOD to "getAdamId",
                AppleMusicRuntimeMember.LYRICS_VIEW_MODEL_CURRENT_LANGUAGE_METHOD to
                    "getCurrentSystemLyricsLanguage",
                AppleMusicRuntimeMember.LYRICS_VIEW_MODEL_RESULT_GETTER to "getLyricsResult",
                AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD to "getHtmlLineText",
                AppleMusicRuntimeMember.LYRICS_NATIVE_TRANSLATION_TEXT_METHOD to
                    "getHtmlTranslationLineText",
                AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_TEXT_METHOD to
                    "getHtmlPronunciationLineText",
                AppleMusicRuntimeMember.LYRICS_NATIVE_BACKGROUND_TEXT_METHOD to
                    "getHtmlBackgroundVocalsLineText",
                AppleMusicRuntimeMember.LYRICS_NATIVE_TRANSLATED_BACKGROUND_TEXT_METHOD to
                    "getHtmlTranslatedBackgroundVocalsLineText",
                AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_BACKGROUND_TEXT_METHOD to
                    "getHtmlPronunciationBackgroundVocalsLineText",
                AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_WORDS_METHOD to
                    "getPronunciationWords",
                AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_BACKGROUND_WORDS_METHOD to
                    "getPronunciationBackgroundWords",
                AppleMusicRuntimeMember.LYRICS_NATIVE_WORDS_METHOD to "getWords",
                AppleMusicRuntimeMember.LYRICS_NATIVE_BACKGROUND_WORDS_METHOD to
                    "getBackgroundWords",
                AppleMusicRuntimeMember.LYRICS_NATIVE_SET_TRANSLATION_METHOD to "setTranslation",
                AppleMusicRuntimeMember.LYRICS_NATIVE_HAS_TRANSLATION_METHOD to "hasTranslation",
                AppleMusicRuntimeMember.LYRICS_NATIVE_SET_PRONUNCIATION_METHOD to
                    "setPronunciation",
                AppleMusicRuntimeMember.LYRICS_NATIVE_HAS_PRONUNCIATION_METHOD to
                    "hasPronunciation",
                AppleMusicRuntimeMember.LYRICS_WORD_VECTOR_CLASS_NAME to
                    "com.apple.android.music.ttml.javanative.model.LyricsWordVector",
            ),
            load.runtimeMemberNames,
        )
        assertEquals(
            "buildTimeRangeToLyricsMap",
            target(version, AppleMusicHookPoint.LYRICS_VIEW_MODEL_BUILD).methodName,
        )
        val resultPresentation = target(
            version,
            AppleMusicHookPoint.LYRICS_RESULT_PRESENTATION,
        )
        assertEquals("I2", resultPresentation.methodName)
        assertEquals(
            listOf("com.apple.android.music.ttml.javanative.model.SongInfo\$SongInfoPtr"),
            resultPresentation.parameterTypeNames,
        )
        assertEquals("void", resultPresentation.returnTypeName)
        assertEquals(
            "R2",
            target(version, AppleMusicHookPoint.LYRICS_NATIVE_PRESENTATION).methodName,
        )
        val ui = target(version, AppleMusicHookPoint.LYRICS_UI_ON_CREATE_VIEW)
        assertEquals("onCreateView", ui.methodName)
        assertEquals(
            mapOf(
                AppleMusicRuntimeMember.LYRICS_UI_RECYCLER_VIEW_METHOD to "getRecyclerView",
                AppleMusicRuntimeMember.LYRICS_UI_ROOT_VIEW_GETTER to "getView",
                AppleMusicRuntimeMember.LYRICS_UI_BINDING_FIELD to "i0",
                AppleMusicRuntimeMember.LYRICS_UI_BINDING_RECYCLER_FIELD to "a0",
                AppleMusicRuntimeMember.LYRICS_UI_ADAPTER_FIELD to "k0",
                AppleMusicRuntimeMember.LYRICS_UI_VIEW_MODEL_FIELD to "j1",
                AppleMusicRuntimeMember.LYRICS_UI_LOADING_PROGRESS_RESOURCE_NAME to
                    "loading_progress",
                AppleMusicRuntimeMember.LYRICS_VIEW_MODEL_PRONUNCIATION_SELECTED_GETTER to
                    "getPronunciationSelectedLiveResult",
                AppleMusicRuntimeMember.LYRICS_VIEW_MODEL_PRONUNCIATION_AVAILABLE_GETTER to
                    "getPronunciationAvailableLiveResult",
                AppleMusicRuntimeMember.LYRICS_VIEW_MODEL_TRANSLATION_SELECTED_GETTER to
                    "getTranslationSelectedLiveResult",
                AppleMusicRuntimeMember.LYRICS_VIEW_MODEL_TRANSLATION_AVAILABLE_GETTER to
                    "getTranslationAvailableLiveResult",
            ),
            ui.runtimeMemberNames,
        )
        AppleMusicHookProfiles.exactTargets(
            version,
            AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER,
        ).forEach { adapter ->
            assertEquals(
                mapOf(
                    AppleMusicRuntimeMember.LYRICS_ADAPTER_ACTIVE_POSITIONS_METHOD to "B",
                    AppleMusicRuntimeMember.LYRICS_ADAPTER_LYRICS_METHOD to "C",
                    AppleMusicRuntimeMember.LYRICS_ADAPTER_LINE_COUNT_METHOD to "b",
                    AppleMusicRuntimeMember.LYRICS_ADAPTER_LINE_AT_METHOD to "a",
                    AppleMusicRuntimeMember.LYRICS_ADAPTER_ITEM_VIEW_TYPE_METHOD to "k",
                    AppleMusicRuntimeMember.LYRICS_ADAPTER_ITEM_COUNT_METHOD to "i",
                    AppleMusicRuntimeMember.LYRICS_ADAPTER_NOTIFY_DATA_CHANGED_METHOD to "l",
                    AppleMusicRuntimeMember.LYRICS_ADAPTER_ACTIVE_LINES_UPDATE_METHOD to "T",
                    AppleMusicRuntimeMember.LYRICS_ADAPTER_TRANSLATION_SELECTED_FIELD to "d",
                    AppleMusicRuntimeMember.LYRICS_ADAPTER_PRONUNCIATION_SELECTED_FIELD to "e",
                ),
                adapter.runtimeMemberNames,
            )
        }
        val availabilityCalculator = target(
            version,
            AppleMusicHookPoint.PLAYER_LYRICS_AVAILABILITY_CALCULATOR,
        )
        assertEquals(
            "com.apple.android.music.player.e1",
            availabilityCalculator.className,
        )
        assertEquals("i", availabilityCalculator.methodName)
        assertEquals(
            listOf("com.apple.android.music.model.PlaybackItem"),
            availabilityCalculator.parameterTypeNames,
        )
        assertEquals("boolean", availabilityCalculator.returnTypeName)
        assertEquals(true, availabilityCalculator.isStatic)
        assertEquals(
            mapOf(
                AppleMusicRuntimeMember.PLAYER_LYRICS_ITEM_HAS_LYRICS_METHOD to "hasLyrics",
                AppleMusicRuntimeMember.PLAYER_LYRICS_ITEM_HAS_CUSTOM_LYRICS_METHOD to
                    "hasCustomLyrics",
            ),
            availabilityCalculator.runtimeMemberNames,
        )
        val playerSongBinding = target(
            version,
            AppleMusicHookPoint.PLAYER_SONG_BINDING_EXECUTE,
        )
        assertEquals("l7.N2", playerSongBinding.className)
        assertEquals("l", playerSongBinding.methodName)
        assertEquals(emptyList<String?>(), playerSongBinding.parameterTypeNames)
        assertEquals("void", playerSongBinding.returnTypeName)
        assertEquals(false, playerSongBinding.isStatic)
        assertEquals(
            mapOf(
                AppleMusicRuntimeMember.PLAYER_SONG_BINDING_PLAYBACK_ITEM_FIELD to "i0",
                AppleMusicRuntimeMember.PLAYER_SONG_BINDING_LYRICS_BUTTON_FIELD to "a0",
            ),
            playerSongBinding.runtimeMemberNames,
        )
        val customTextView = target(version, AppleMusicHookPoint.APPLE_CUSTOM_TEXT_VIEW)
        assertEquals(
            "com.apple.android.music.common.views.CustomTextView",
            customTextView.className,
        )
        assertEquals(
            mapOf(
                AppleMusicRuntimeMember.CUSTOM_TEXT_VIEW_SET_TYPEFACE_METHOD to "setTypeface",
                AppleMusicRuntimeMember.CUSTOM_TEXT_VIEW_SET_TEXT_METHOD to "setText",
                AppleMusicRuntimeMember.CUSTOM_TEXT_VIEW_ON_DRAW_METHOD to "onDraw",
                AppleMusicRuntimeMember.CUSTOM_TEXT_VIEW_FUTURE_RESOLVE_METHOD to "f",
            ),
            customTextView.runtimeMemberNames,
        )

        val gradient = target(version, AppleMusicHookPoint.LYRICS_GRADIENT_MASK_UPDATE)
        assertEquals(
            "com.apple.android.music.common.views.FullWidthAlphaGradientFlexboxLayout\$a",
            gradient.className,
        )
        assertEquals("b", gradient.methodName)
        assertEquals(
            "com.apple.android.music.common.views.FullWidthAlphaGradientFlexboxLayout",
            gradient.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_GRADIENT_LAYOUT_CLASS_NAME
            ),
        )
        assertFalse(
            listOf(load.className, gradient.className).any { className ->
                className.matches(Regex("p\\d+.*"))
            }
        )
    }

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
