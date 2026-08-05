/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.app.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMetadataResolutionPolicyTest {

    @Test
    fun `configured region lookup remains active while original metadata is requested`() {
        val plan = AppleMetadataResolutionEngine.catalogMetadataResolutionPlan(
            overrideAccountLanguage = true,
            restoreCjkOriginalMetadata = true,
        )

        assertTrue(plan.resolveConfiguredRegion)
        assertTrue(plan.resolveOriginalRegion)
    }

    @Test
    fun `disabled metadata replacement schedules no catalog lookup`() {
        val plan = AppleMetadataResolutionEngine.catalogMetadataResolutionPlan(
            overrideAccountLanguage = false,
            restoreCjkOriginalMetadata = false,
        )

        assertFalse(plan.resolveConfiguredRegion)
        assertFalse(plan.resolveOriginalRegion)
    }

    @Test
    fun `visible artist profile songs resolve original metadata before localized fallback`() {
        val plan = AppleMetadataResolutionEngine.inAppOriginalResolutionPlan(
            mediaIds = listOf("1819419303", "1819419303", "1819419299"),
            awaitingLocalizedIds = setOf("1819419303", "1819419299"),
            mode = InAppOriginalResolutionMode.ORIGINAL_FIRST,
        )

        assertEquals(
            listOf("1819419303", "1819419299"),
            plan.beforeLocalized,
        )
        assertTrue(plan.afterLocalized.isEmpty())
        assertFalse(plan.resolveLocalizedImmediately)
    }

    @Test
    fun `playlist rows keep one original-first lookup path`() {
        assertEquals(
            InAppOriginalResolutionMode.ORIGINAL_FIRST,
            collectionPageOriginalResolutionMode("playlist"),
        )
        assertEquals(
            InAppOriginalResolutionMode.ORIGINAL_FIRST,
            collectionPageOriginalResolutionMode("album"),
        )

        val plan = AppleMetadataResolutionEngine.inAppOriginalResolutionPlan(
            mediaIds = listOf("1819419303", "1819419303", "1819419299"),
            awaitingLocalizedIds = emptySet(),
            mode = InAppOriginalResolutionMode.ORIGINAL_FIRST,
        )

        assertEquals(
            listOf("1819419303", "1819419299"),
            plan.beforeLocalized,
        )
        assertTrue(plan.afterLocalized.isEmpty())
        assertFalse(plan.resolveLocalizedImmediately)
    }

    @Test
    fun `other app surfaces keep waiting for localized lookup before original metadata`() {
        val plan = AppleMetadataResolutionEngine.inAppOriginalResolutionPlan(
            mediaIds = listOf("ready", "waiting"),
            awaitingLocalizedIds = setOf("waiting"),
            mode = InAppOriginalResolutionMode.AFTER_LOCALIZED,
        )

        assertTrue(plan.beforeLocalized.isEmpty())
        assertEquals(listOf("ready"), plan.afterLocalized)
        assertTrue(plan.resolveLocalizedImmediately)
    }

    @Test
    fun `temporary original cache misses become retryable without hot loop`() {
        assertTrue(
            AppleMetadataResolutionEngine.shouldRetryOriginalMetadataCacheProbe(
                originalResolved = false,
                lastMissUptimeMillis = null,
                nowUptimeMillis = 1_000L,
            )
        )
        assertFalse(
            AppleMetadataResolutionEngine.shouldRetryOriginalMetadataCacheProbe(
                originalResolved = true,
                lastMissUptimeMillis = null,
                nowUptimeMillis = 1_000L,
            )
        )
        assertFalse(
            AppleMetadataResolutionEngine.shouldRetryOriginalMetadataCacheProbe(
                originalResolved = true,
                lastMissUptimeMillis = 1_000L,
                nowUptimeMillis = 1_749L,
                retryAfterMillis = 750L,
            )
        )
        assertTrue(
            AppleMetadataResolutionEngine.shouldRetryOriginalMetadataCacheProbe(
                originalResolved = true,
                lastMissUptimeMillis = 1_000L,
                nowUptimeMillis = 1_750L,
                retryAfterMillis = 750L,
            )
        )
    }

    @Test
    fun `metadata without a trustworthy id cannot borrow another songs id`() {
        assertEquals(null, selectTrustworthyMediaId(null, emptyList()))
        assertEquals(null, selectTrustworthyMediaId(null, listOf("1", "2")))
        assertEquals("1", selectTrustworthyMediaId(null, listOf("1", "1")))
        assertEquals("3", selectTrustworthyMediaId("3", listOf("1", "2")))
    }

    @Test
    fun `artist caches prefer catalog ids over names`() {
        assertEquals(
            setOf("id:100", "id:200"),
            AppleMetadataResolutionEngine.stableArtistCacheKeys(
                setOf("name:shared", "id:100", "id:200")
            ),
        )
        assertEquals(
            setOf("name:fallback"),
            AppleMetadataResolutionEngine.stableArtistCacheKeys(setOf("name:fallback")),
        )
    }

    @Test
    fun `localized artist aliases require catalog ids`() {
        assertEquals(
            emptySet<String>(),
            AppleMetadataResolutionEngine.localizedArtistCacheKeys(setOf("name:the weeknd")),
        )
        assertEquals(
            setOf("id:479756766"),
            AppleMetadataResolutionEngine.localizedArtistCacheKeys(
                setOf("name:the weeknd", "id:479756766", "id:not-a-number")
            ),
        )
    }

    @Test
    fun `artist entities reuse their title as the shared artist credit`() {
        val artistCredit = AppleMetadataResolutionEngine.associatedArtistCredit(
            entityType = AppleInternalCatalogResolver.LocalizedEntityType.ARTIST,
            accountTitle = "陶喆",
            accountArtist = null,
        )

        assertEquals(
            "陶喆",
            artistCredit,
        )
        assertTrue(
            AppleMetadataResolutionEngine.shouldUseAssociatedArtistEntities(
                artistIds = listOf("16789930"),
                artistCredit = artistCredit,
            )
        )
        assertEquals(
            "David Tao",
            AppleMetadataResolutionEngine.associatedArtistCredit(
                entityType = AppleInternalCatalogResolver.LocalizedEntityType.SONG,
                accountTitle = "普通朋友",
                accountArtist = "David Tao",
            ),
        )
    }

    @Test
    fun `collaboration songs never borrow a single artist entity credit`() {
        val credit = AppleMetadataResolutionEngine.associatedArtistCredit(
            entityType = AppleInternalCatalogResolver.LocalizedEntityType.SONG,
            accountTitle = "Home",
            accountArtist = "Charlie Puth、Utada",
        )

        assertEquals("Charlie Puth、Utada", credit)
        assertFalse(
            AppleMetadataResolutionEngine.shouldUseAssociatedArtistEntities(
                artistIds = listOf("479756766"),
                artistCredit = credit,
            )
        )
    }

    @Test
    fun `solo artist aliases share only through the same exact artist id`() {
        assertEquals(
            "1486113150",
            AppleMetadataResolutionEngine.sharedAssociatedArtistId(
                artistIds = listOf("1486113150"),
                artistCredit = "藤井风",
            ),
        )
        assertTrue(
            AppleMetadataResolutionEngine.shouldShareAssociatedArtistAlias(
                artistId = "1486113150",
                targetArtistIds = listOf("1486113150"),
                targetArtistCredit = "藤井风",
            )
        )
        assertFalse(
            AppleMetadataResolutionEngine.shouldShareAssociatedArtistAlias(
                artistId = "1486113150",
                targetArtistIds = listOf("1087553199", "1486113150"),
                targetArtistCredit = "Elmiene、藤井风",
            )
        )
        assertFalse(
            AppleMetadataResolutionEngine.shouldShareAssociatedArtistAlias(
                artistId = "1486113150",
                targetArtistIds = listOf("1486113150"),
                targetArtistCredit = "Elmiene、藤井风",
            )
        )
        assertFalse(
            AppleMetadataResolutionEngine.shouldShareAssociatedArtistAlias(
                artistId = "1486113150",
                targetArtistIds = listOf("18756224"),
                targetArtistCredit = "Utada",
            )
        )
    }

    @Test
    fun `reads direct artist ids from library attributes before relationship fallback`() {
        val catalogTarget = AppleMusicHookProfiles.exactTargets(
            AppleMusicVersion("6.5.1", 1583L),
            AppleMusicHookPoint.MEDIA_API_REPOSITORY_HOLDER_CLASS,
        ).single()
        val artistIdGetters = listOf(
            AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ARTIST_ID_METHOD,
            AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ARTIST_ADAM_ID_METHOD,
            AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ARTIST_STORE_ID_METHOD,
            AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ARTIST_SUBSCRIPTION_STORE_ID_METHOD,
        ).map(catalogTarget::runtimeMemberName)
        assertEquals(
            listOf("16789930"),
            mediaApiAttributeArtistIds(
                FakeLibraryAttributes(
                    artistId = "16789930",
                    artistStoreId = "0",
                ),
                artistIdGetters,
            ),
        )
        assertEquals(
            emptyList<String>(),
            mediaApiAttributeArtistIds(
                FakeLibraryAttributes(artistId = "", artistStoreId = "0"),
                artistIdGetters,
            ),
        )
    }

    @Test
    fun `maps the Tao library album directly to its catalog artist`() {
        assertEquals(
            listOf("16789930"),
            libraryAssociatedArtistIds(
                kind = InAppLibraryEntityKind.ALBUM,
                mediaId = "914664926",
                attributeArtistIds = listOf("16789930"),
                associationKeys = emptySet(),
            ),
        )
    }

    @Test
    fun `reads associated artist ids collected from playback item cache keys`() {
        assertEquals(
            listOf("369211611"),
            artistIdsFromAssociationKeys(
                listOf("name:mayday", "id:369211611", "id:369211611")
            ),
        )
    }

    @Test
    fun `does not promote an unresolved account snapshot to confirmed original metadata`() {
        val unresolved = AppleInternalCatalogResolver.OriginalResolution(
            alias = null,
            language = "zh-Hans-CN",
            originKnown = true,
            artistIds = listOf("369211611"),
        )
        assertNull(
            confirmedOriginalSongAlias(unresolved)
        )
        assertEquals(
            "zh-Hans-CN",
            originalSongRetryLanguage(unresolved),
        )
    }

    @Test
    fun `current playback only exposes confirmed original metadata`() {
        assertFalse(
            AppleMetadataResolutionEngine.shouldExposeOriginalMetadataOverride(
                mediaId = "123",
                currentPlaybackMediaId = "123",
                confirmed = false,
            )
        )
        assertTrue(
            AppleMetadataResolutionEngine.shouldExposeOriginalMetadataOverride(
                mediaId = "123",
                currentPlaybackMediaId = "123",
                confirmed = true,
            )
        )
        assertTrue(
            AppleMetadataResolutionEngine.shouldExposeOriginalMetadataOverride(
                mediaId = "123",
                currentPlaybackMediaId = "456",
                confirmed = false,
            )
        )
    }

    @Test
    fun `maps visible media api attributes for automatic artist page refresh`() {
        assertEquals(
            VisibleTextField.TITLE,
            visibleTextFieldForMediaApiAttribute(
                InAppLibraryEntityKind.SONG,
                AppleMediaApiTextAttribute.NAME,
            ),
        )
        assertEquals(
            VisibleTextField.ALBUM,
            visibleTextFieldForMediaApiAttribute(
                InAppLibraryEntityKind.ALBUM,
                AppleMediaApiTextAttribute.NAME,
            ),
        )
        assertEquals(
            VisibleTextField.ARTIST,
            visibleTextFieldForMediaApiAttribute(
                InAppLibraryEntityKind.ARTIST,
                AppleMediaApiTextAttribute.NAME,
            ),
        )
        assertEquals(
            VisibleTextField.ARTIST,
            visibleTextFieldForMediaApiAttribute(
                InAppLibraryEntityKind.SONG,
                AppleMediaApiTextAttribute.ARTIST_NAME,
            ),
        )
    }

    @Test
    fun `only visible getter access can start metadata resolution`() {
        assertFalse(
            shouldResolveMetadataFromGetter(
                AppleInternalCatalogResolver.RequestPriority.BACKGROUND,
            )
        )
        assertFalse(
            shouldResolveMetadataFromGetter(
                AppleInternalCatalogResolver.RequestPriority.ACTIVE_PAGE,
            )
        )
        assertTrue(
            shouldResolveMetadataFromGetter(
                AppleInternalCatalogResolver.RequestPriority.VISIBLE,
            )
        )
    }

    @Test
    fun `uses album metadata when refreshing visible album text`() {
        val alias = AppleInternalCatalogResolver.Alias(
            title = "歌曲",
            artist = "歌手",
            album = "专辑",
            language = "zh-Hans-CN",
        )

        assertEquals("歌曲", localizedVisibleText(VisibleTextField.TITLE, alias))
        assertEquals("歌手", localizedVisibleText(VisibleTextField.ARTIST, alias))
        assertEquals("专辑", localizedVisibleText(VisibleTextField.ALBUM, alias))
    }

    @Test
    fun `maps every playback and list getter to one song alias`() {
        val alias = AppleInternalCatalogResolver.Alias(
            title = "喜欢寂寞",
            artist = "苏打绿",
            album = "你在烦恼什么",
            language = "zh-Hans-CN",
        )
        val song = AppleInternalCatalogResolver.LocalizedEntityType.SONG

        assertEquals(
            "喜欢寂寞",
            contentItemMetadataOverride(song, AppleContentItemGetter.TITLE, alias, "raw"),
        )
        assertEquals(
            "喜欢寂寞",
            contentItemMetadataOverride(
                song,
                AppleContentItemGetter.NOW_PLAYING_TITLE,
                alias,
                "raw",
            ),
        )
        listOf(
            AppleContentItemGetter.ARTIST,
            AppleContentItemGetter.NOW_PLAYING_SUBTITLE,
            AppleContentItemGetter.SUBTITLE,
        ).forEach { getter ->
            assertEquals(
                "苏打绿",
                contentItemMetadataOverride(song, getter, alias, "raw"),
            )
        }
        assertEquals(
            "你在烦恼什么",
            contentItemMetadataOverride(
                song,
                AppleContentItemGetter.COLLECTION,
                alias,
                "raw",
            ),
        )
        assertEquals(
            VisibleTextField.ARTIST,
            visibleTextFieldForContentItemGetter(
                song,
                AppleContentItemGetter.NOW_PLAYING_SUBTITLE,
            ),
        )
        assertEquals(
            VisibleTextField.ALBUM,
            visibleTextFieldForContentItemGetter(
                song,
                AppleContentItemGetter.COLLECTION,
            ),
        )
    }

    @Test
    fun `maps album and artist titles by entity instead of song fields`() {
        val alias = AppleInternalCatalogResolver.Alias(
            title = "song title",
            artist = "陶喆",
            album = "陶喆同名专辑",
            language = "zh-Hans-CN",
        )

        assertEquals(
            "陶喆同名专辑",
            contentItemMetadataOverride(
                AppleInternalCatalogResolver.LocalizedEntityType.ALBUM,
                AppleContentItemGetter.TITLE,
                alias,
                "raw",
            ),
        )
        assertEquals(
            "陶喆",
            contentItemMetadataOverride(
                AppleInternalCatalogResolver.LocalizedEntityType.ALBUM,
                AppleContentItemGetter.SUBTITLE,
                alias,
                "raw",
            ),
        )
        assertEquals(
            "陶喆",
            contentItemMetadataOverride(
                AppleInternalCatalogResolver.LocalizedEntityType.ARTIST,
                AppleContentItemGetter.TITLE,
                alias,
                "raw",
            ),
        )
    }

    @Test
    fun `original artist entity overrides the stale artist embedded in an album`() {
        val album = AppleInternalCatalogResolver.Alias(
            title = "陶喆同名专辑",
            artist = "David Tao",
            album = "陶喆同名专辑",
            language = "zh-Hans-CN",
        )
        val artist = AppleInternalCatalogResolver.Alias(
            title = "",
            artist = "陶喆",
            language = "zh-Hans-CN",
        )

        val effective = AppleMetadataResolutionEngine.selectEffectiveMetadataAlias(
            restoreOriginalEnabled = true,
            originalMetadataResolved = true,
            originalMetadata = album,
            originalArtistResolved = true,
            originalArtist = artist,
            localizedMetadata = album.copy(artist = "David Tao", language = "zh-CN"),
            localizedArtist = album.copy(title = "", artist = "David Tao", album = ""),
        )

        assertEquals("陶喆同名专辑", effective?.title)
        assertEquals("陶喆", effective?.artist)
    }

    @Test
    fun `configured metadata is shown atomically while original metadata is pending`() {
        val localizedAlbum = AppleInternalCatalogResolver.Alias(
            title = "Configured Album",
            artist = "David Tao",
            album = "Configured Album",
            language = "zh-CN",
        )
        val localizedArtist = localizedAlbum.copy(
            title = "",
            artist = "陶喆",
            album = "",
        )

        val effective = AppleMetadataResolutionEngine.selectEffectiveMetadataAlias(
            restoreOriginalEnabled = true,
            originalMetadataResolved = false,
            originalMetadata = null,
            originalArtistResolved = true,
            originalArtist = null,
            localizedMetadata = localizedAlbum,
            localizedArtist = localizedArtist,
        )

        assertEquals("Configured Album", effective?.title)
        assertEquals("陶喆", effective?.artist)
        assertEquals("Configured Album", effective?.album)
    }

    @Test
    fun `cached original artist is visible while original song metadata is pending`() {
        val configuredSong = AppleInternalCatalogResolver.Alias(
            title = "Come Back to Me",
            artist = "Utada",
            album = "This Is the One",
            language = "zh-CN",
        )
        val originalArtist = AppleInternalCatalogResolver.Alias(
            title = "",
            artist = "宇多田ヒカル",
            album = "",
            language = "ja-JP",
        )

        val effective = AppleMetadataResolutionEngine.selectEffectiveMetadataAlias(
            restoreOriginalEnabled = true,
            originalMetadataResolved = false,
            originalMetadata = null,
            originalArtistResolved = true,
            originalArtist = originalArtist,
            localizedMetadata = configuredSong,
            localizedArtist = configuredSong.copy(title = "", album = ""),
        )

        assertEquals("Come Back to Me", effective?.title)
        assertEquals("宇多田ヒカル", effective?.artist)
        assertEquals("This Is the One", effective?.album)
    }

    @Test
    fun `confirmed original metadata remains stable while the original artist entity is pending`() {
        val originalAlbum = AppleInternalCatalogResolver.Alias(
            title = "陶喆同名专辑",
            artist = "David Tao",
            album = "陶喆同名专辑",
            language = "zh-Hans-CN",
        )
        val configuredAlbum = AppleInternalCatalogResolver.Alias(
            title = "Tao",
            artist = "David Tao",
            album = "Tao",
            language = "zh-CN",
        )

        val effective = AppleMetadataResolutionEngine.selectEffectiveMetadataAlias(
            restoreOriginalEnabled = true,
            originalMetadataResolved = true,
            originalMetadata = originalAlbum,
            originalArtistResolved = false,
            originalArtist = null,
            localizedMetadata = configuredAlbum,
            localizedArtist = null,
        )

        assertEquals("陶喆同名专辑", effective?.title)
        assertEquals("David Tao", effective?.artist)
    }

    @Test
    fun `cached single artist can be applied before song metadata resolves`() {
        val configuredArtist = AppleInternalCatalogResolver.Alias(
            title = "Dove Cameron",
            artist = "德芙·卡梅隆",
            album = "Should not replace the current album",
            language = "zh-CN",
        )

        val effective = AppleMetadataResolutionEngine.selectIndependentArtistAlias(
            restoreOriginalEnabled = true,
            canUseAssociatedArtist = true,
            originalArtist = null,
            localizedArtist = configuredArtist,
        )

        assertEquals("", effective?.title)
        assertEquals("德芙·卡梅隆", effective?.artist)
        assertEquals("", effective?.album)
        assertEquals("zh-CN", effective?.language)
    }

    @Test
    fun `resolved original artist replaces the temporary configured artist`() {
        val configuredArtist = AppleInternalCatalogResolver.Alias(
            title = "",
            artist = "Utada",
            album = "",
            language = "en-US",
        )
        val originalArtist = configuredArtist.copy(
            artist = "宇多田ヒカル",
            language = "ja-JP",
        )

        val effective = AppleMetadataResolutionEngine.selectIndependentArtistAlias(
            restoreOriginalEnabled = true,
            canUseAssociatedArtist = true,
            originalArtist = originalArtist,
            localizedArtist = configuredArtist,
        )

        assertEquals("宇多田ヒカル", effective?.artist)
        assertEquals("ja-JP", effective?.language)
    }

    @Test
    fun `collaboration credit cannot use an independent cached artist`() {
        val configuredArtist = AppleInternalCatalogResolver.Alias(
            title = "",
            artist = "宇多田ヒカル",
            album = "",
            language = "ja-JP",
        )

        assertNull(
            AppleMetadataResolutionEngine.selectIndependentArtistAlias(
                restoreOriginalEnabled = true,
                canUseAssociatedArtist = false,
                originalArtist = configuredArtist,
                localizedArtist = configuredArtist,
            )
        )
    }

    @Test
    fun `original artist wins when the original song title matches the configured title`() {
        val configuredSong = AppleInternalCatalogResolver.Alias(
            title = "One Last Kiss",
            artist = "Utada",
            album = "One Last Kiss",
            language = "ja-JP",
        )
        val originalArtist = AppleInternalCatalogResolver.Alias(
            title = "",
            artist = "宇多田ヒカル",
            album = "",
            language = "ja-JP",
        )

        val effective = AppleMetadataResolutionEngine.selectEffectiveMetadataAlias(
            restoreOriginalEnabled = true,
            originalMetadataResolved = true,
            originalMetadata = null,
            originalArtistResolved = true,
            originalArtist = originalArtist,
            localizedMetadata = configuredSong,
            localizedArtist = configuredSong.copy(title = "", album = ""),
        )

        assertEquals("One Last Kiss", effective?.title)
        assertEquals("宇多田ヒカル", effective?.artist)
        assertEquals("One Last Kiss", effective?.album)
    }

    @Test
    fun `keeps requesting after original metadata resolves while its artist is pending`() {
        assertTrue(
            AppleMetadataResolutionEngine.shouldRequestEffectiveMetadataResolution(
                restoreOriginalEnabled = true,
                originalMetadataResolved = true,
                hasOriginalMetadata = true,
                hasAssociatedArtists = true,
                originalArtistResolved = false,
                hasLocalizedMetadata = true,
            )
        )
        assertFalse(
            AppleMetadataResolutionEngine.shouldRequestEffectiveMetadataResolution(
                restoreOriginalEnabled = true,
                originalMetadataResolved = true,
                hasOriginalMetadata = true,
                hasAssociatedArtists = true,
                originalArtistResolved = true,
                hasLocalizedMetadata = true,
            )
        )
    }

    @Test
    fun `keeps the confirmed song credit when original artist lookup completes without a hit`() {
        val originalSong = AppleInternalCatalogResolver.Alias(
            title = "喜歡寂寞",
            artist = "sodagreen",
            album = "你在煩惱什麼",
            language = "zh-Hant-TW",
        )
        val configuredArtist = AppleInternalCatalogResolver.Alias(
            title = "",
            artist = "苏打绿",
            album = "",
            language = "zh-Hans-CN",
        )

        val effective = AppleMetadataResolutionEngine.selectEffectiveMetadataAlias(
            restoreOriginalEnabled = true,
            originalMetadataResolved = true,
            originalMetadata = originalSong,
            originalArtistResolved = true,
            originalArtist = null,
            localizedMetadata = null,
            localizedArtist = configuredArtist,
        )

        assertEquals("喜歡寂寞", effective?.title)
        assertEquals("sodagreen", effective?.artist)
        assertEquals("你在煩惱什麼", effective?.album)
    }

    @Test
    fun `confirmed collaboration metadata never merges a localized single artist`() {
        val originalSong = AppleInternalCatalogResolver.Alias(
            title = "日本語の原題",
            artist = "Charlie Puth、Utada",
            album = "Original Album",
            language = "ja-JP",
        )
        val localizedArtist = AppleInternalCatalogResolver.Alias(
            title = "",
            artist = "宇多田ヒカル",
            album = "",
            language = "ja-JP",
        )

        val effective = AppleMetadataResolutionEngine.selectEffectiveMetadataAlias(
            restoreOriginalEnabled = true,
            originalMetadataResolved = true,
            originalMetadata = originalSong,
            originalArtistResolved = true,
            originalArtist = null,
            localizedMetadata = originalSong.copy(artist = "Charlie Puth, Utada"),
            localizedArtist = localizedArtist,
        )

        assertEquals("日本語の原題", effective?.title)
        assertEquals("Charlie Puth、Utada", effective?.artist)
        assertEquals("Original Album", effective?.album)
    }

    @Test
    fun `associated artist replacement requires every related artist entity`() {
        val aliases = mapOf(
            "16789930" to AppleInternalCatalogResolver.Alias(
                title = "陶喆",
                artist = "陶喆",
                language = "zh-Hans-CN",
            )
        )

        assertNull(
            AppleMetadataResolutionEngine.associatedArtistAlias(
                artistIds = listOf("16789930", "missing"),
                aliases = aliases,
                language = "zh-Hans-CN",
            )
        )
        assertEquals(
            "陶喆",
            AppleMetadataResolutionEngine.associatedArtistAlias(
                artistIds = listOf("16789930"),
                aliases = aliases,
                language = "zh-Hans-CN",
            )?.artist,
        )
    }

    @Test
    fun `collaboration credits do not use independent artist entity overrides`() {
        assertTrue(
            AppleMetadataResolutionEngine.shouldUseAssociatedArtistEntities(
                artistIds = listOf("18756224"),
                artistCredit = "Utada",
            )
        )
        assertFalse(
            AppleMetadataResolutionEngine.shouldUseAssociatedArtistEntities(
                artistIds = listOf("18756224"),
                artistCredit = null,
            )
        )
        assertFalse(
            AppleMetadataResolutionEngine.shouldUseAssociatedArtistEntities(
                artistIds = listOf("479756766", "1322012752"),
                artistCredit = "Charlie Puth、Utada",
            )
        )
        assertFalse(
            AppleMetadataResolutionEngine.shouldUseAssociatedArtistEntities(
                artistIds = listOf("479756766"),
                artistCredit = "Charlie Puth、Utada",
            )
        )
        assertFalse(
            AppleMetadataResolutionEngine.shouldUseAssociatedArtistEntities(
                artistIds = listOf("1443363988"),
                artistCredit = "Dove Cameron (feat. Khalid)",
            )
        )
        assertFalse(
            AppleMetadataResolutionEngine.shouldUseAssociatedArtistEntities(
                artistIds = listOf("1087553199", "1039574375"),
                artistCredit = "Elmiene、藤井风",
            )
        )
    }

    @Test
    fun `late artist callbacks are accepted only for the same isolated solo artist`() {
        assertTrue(
            AppleMetadataResolutionEngine.shouldAcceptAssociatedArtistResolution(
                requestedArtistIds = listOf("18756224"),
                currentArtistIds = listOf("18756224"),
                artistCredit = "Utada",
            )
        )
        assertFalse(
            AppleMetadataResolutionEngine.shouldAcceptAssociatedArtistResolution(
                requestedArtistIds = listOf("18756224"),
                currentArtistIds = listOf("479756766", "18756224"),
                artistCredit = "Charlie Puth、Utada",
            )
        )
        assertFalse(
            AppleMetadataResolutionEngine.shouldAcceptAssociatedArtistResolution(
                requestedArtistIds = listOf("18756224"),
                currentArtistIds = listOf("18756224"),
                artistCredit = "Charlie Puth、Utada",
            )
        )
        assertFalse(
            AppleMetadataResolutionEngine.shouldAcceptAssociatedArtistResolution(
                requestedArtistIds = listOf("18756224"),
                currentArtistIds = listOf("1443363988"),
                artistCredit = "Dove Cameron",
            )
        )
    }

    @Test
    fun `song resolution shares an artist region only for one exact non collaboration artist`() {
        val oneLastKiss = AppleInternalCatalogResolver.OriginalResolution(
            alias = null,
            language = "ja-JP",
            originKnown = true,
            artistIds = listOf("18756224"),
        )
        val home = oneLastKiss.copy(
            artistIds = listOf("479756766", "18756224"),
        )

        assertEquals(
            "ja-JP",
            originalArtistLanguageFromSongResolution(
                resolution = oneLastKiss,
                localizedArtist = "Utada",
            ),
        )
        assertNull(
            originalArtistLanguageFromSongResolution(
                resolution = home,
                localizedArtist = "Charlie Puth、Utada",
            )
        )
        assertNull(
            originalArtistLanguageFromSongResolution(
                resolution = oneLastKiss,
                localizedArtist = "Charlie Puth、Utada",
            )
        )
    }

    @Test
    fun `collaboration alias cannot localize only the Home artist credit`() {
        assertNull(
            validatedOriginalSongAlias(
                alias = AppleInternalCatalogResolver.Alias(
                    title = "Home",
                    artist = "チャーリー・プース、宇多田ヒカル",
                    album = "Whatever's Clever! (Expanded)",
                    language = "ja-JP",
                ),
                localizedTitle = "Home",
                localizedArtist = "Charlie Puth、Utada",
            )
        )
        assertEquals(
            "日本語の原題",
            validatedOriginalSongAlias(
                alias = AppleInternalCatalogResolver.Alias(
                    title = "日本語の原題",
                    artist = "チャーリー・プース、宇多田ヒカル",
                    language = "ja-JP",
                ),
                localizedTitle = "Romanized Title",
                localizedArtist = "Charlie Puth、Utada",
            )?.title,
        )
    }

    @Test
    fun `artist profile coverage cannot partially rewrite Comets and Gold collaborators`() {
        assertNull(
            validatedOriginalSongAlias(
                alias = AppleInternalCatalogResolver.Alias(
                    title = "Comets + Gold",
                    artist = "Elmiene、藤井 風",
                    album = "Comets + Gold",
                    language = "ja-JP",
                ),
                localizedTitle = "Comets + Gold",
                localizedArtist = "Elmiene、藤井风",
            )
        )
    }

    @Test
    fun `infers a single artist original region from a localized album genre`() {
        assertEquals(
            "zh-Hans-CN",
            inferredOriginalArtistLanguage(
                kind = InAppLibraryEntityKind.ALBUM,
                artist = "David Tao",
                associatedArtistIds = listOf("16789930"),
                genres = listOf("国语流行"),
            ),
        )
    }

    @Test
    fun `does not assign one album region to collaborating artists`() {
        assertNull(
            inferredOriginalArtistLanguage(
                kind = InAppLibraryEntityKind.ALBUM,
                artist = "American Artist & Japanese Artist",
                associatedArtistIds = listOf("100", "200"),
                genres = listOf("J-Pop"),
            )
        )
        assertNull(
            inferredOriginalArtistLanguage(
                kind = InAppLibraryEntityKind.ALBUM,
                artist = "American Artist feat. Japanese Artist",
                associatedArtistIds = listOf("100"),
                genres = listOf("J-Pop"),
            )
        )
    }

    @Test
    fun `late associated artist hydration publishes the changed effective alias`() {
        val previous = AppleInternalCatalogResolver.Alias(
            title = "Feelin’ Go(o)d",
            artist = "藤井风",
            language = "zh-Hans",
            album = "Pre: Prema",
        )
        val updated = previous.copy(
            artist = "藤井 風",
            language = "ja-JP",
        )

        assertEquals(
            updated,
            changedAssociatedArtistAlias(
                previousAlias = previous,
                updatedAlias = updated,
            ),
        )
    }

    @Test
    fun `stable or missing associated artist hydration does not republish`() {
        val alias = AppleInternalCatalogResolver.Alias(
            title = "Home",
            artist = "Charlie Puth、Utada",
            language = "en-US",
            album = "CHARLIE",
        )

        assertNull(
            changedAssociatedArtistAlias(
                previousAlias = alias,
                updatedAlias = alias,
            )
        )
        assertNull(
            changedAssociatedArtistAlias(
                previousAlias = alias,
                updatedAlias = null,
            )
        )
    }

    @Test
    fun `visible original first request survives deferred request merging`() {
        assertEquals(
            DeferredMetadataResolution(
                priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
                originalResolutionMode = InAppOriginalResolutionMode.ORIGINAL_FIRST,
            ),
            mergeDeferredMetadataResolution(
                previous = DeferredMetadataResolution(
                    priority = AppleInternalCatalogResolver.RequestPriority.ACTIVE_PAGE,
                    originalResolutionMode = InAppOriginalResolutionMode.AFTER_LOCALIZED,
                ),
                incoming = DeferredMetadataResolution(
                    priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
                    originalResolutionMode = InAppOriginalResolutionMode.ORIGINAL_FIRST,
                ),
            ),
        )
    }

    private class FakeLibraryAttributes(
        private val artistId: String,
        private val artistStoreId: String,
    ) {
        fun getArtistId(): String = artistId

        fun getArtistStoreId(): String = artistStoreId
    }
}
