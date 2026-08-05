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

        val contentItemTargets = AppleMusicHookProfiles.exactTargets(
            version,
            AppleMusicHookPoint.CONTENT_ITEM_METADATA_CLASSES,
        )
        assertEquals(6, contentItemTargets.size)
        assertEquals(
            "com.apple.android.music.model.BaseContentItem",
            contentItemTargets.single { target ->
                target.runtimeMemberNameOrNull(AppleMusicRuntimeMember.CONTENT_ITEM_ROLE) == "base"
            }.className,
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
        assertEquals(
            "com.apple.android.music.mediaapi.models.MediaEntity",
            target(version, AppleMusicHookPoint.RECENTLY_SEARCHED_MEDIA_ENTITY).className,
        )
        assertEquals(
            "com.apple.android.music.utils.AppSharedPreferences",
            target(version, AppleMusicHookPoint.APPLE_SHARED_PREFERENCES_CLASS).className,
        )
        assertEquals(
            "com.apple.android.music.model.Song",
            target(version, AppleMusicHookPoint.APPLE_SONG_MODEL_CLASS).className,
        )
        assertEquals(
            "com.apple.android.music.player.O",
            target(version, AppleMusicHookPoint.APPLE_PLAYER_UTIL_CLASS).className,
        )
        assertEquals(
            "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel",
            target(version, AppleMusicHookPoint.PLAYER_LYRICS_VIEW_MODEL_CLASS).className,
        )
        assertEquals(
            "com.apple.android.music.model.Artist",
            target(version, AppleMusicHookPoint.IN_APP_CONTAINER_ARTIST_CLASS).className,
        )
        assertEquals(
            "com.apple.android.music.model.Album",
            target(version, AppleMusicHookPoint.IN_APP_CONTAINER_ALBUM_CLASS).className,
        )
        assertEquals(
            "com.apple.android.music.mediaapi.repository.MediaApiRepositoryHolder",
            target(version, AppleMusicHookPoint.MEDIA_API_REPOSITORY_HOLDER_CLASS).className,
        )
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
            "buildTimeRangeToLyricsMap",
            target(version, AppleMusicHookPoint.LYRICS_VIEW_MODEL_BUILD).methodName,
        )
        assertEquals(
            "R2",
            target(version, AppleMusicHookPoint.LYRICS_NATIVE_PRESENTATION).methodName,
        )
        assertEquals(
            "onCreateView",
            target(version, AppleMusicHookPoint.LYRICS_UI_ON_CREATE_VIEW).methodName,
        )
        assertEquals(
            "com.apple.android.music.common.views.CustomTextView",
            target(version, AppleMusicHookPoint.APPLE_CUSTOM_TEXT_VIEW).className,
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
