package io.github.proify.lyricon.amprovider.xposed

import android.app.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMusicProviderTest {

    @Test
    fun `system font variation cache reuses only identical requests and stays bounded`() {
        val cache = AppleSystemFontVariationCache<Any, Any>(maxEntries = 2)
        val original: Any = String(charArrayOf('s', 'f'))
        val equalButDistinctOriginal: Any = String(charArrayOf('s', 'f'))
        val first = Any()
        var creationCount = 0
        assertEquals(original, equalButDistinctOriginal)
        assertTrue(original !== equalButDistinctOriginal)

        val firstResult = cache.getOrCreate(original, 500, italic = false) {
            creationCount += 1
            first
        }
        val reusedResult = cache.getOrCreate(original, 500, italic = false) {
            creationCount += 1
            Any()
        }
        val distinctResult = cache.getOrCreate(
            equalButDistinctOriginal,
            500,
            italic = false,
        ) {
            creationCount += 1
            Any()
        }

        assertTrue(firstResult === first)
        assertTrue(reusedResult === first)
        assertTrue(distinctResult !== first)
        assertEquals(2, creationCount)

        cache.getOrCreate(original, 600, italic = false) { Any() }
        val recreated = cache.getOrCreate(original, 500, italic = false) { Any() }
        assertTrue(recreated !== first)
    }

    @Test
    fun `Apple translation and pronunciation tracks are selected independently`() {
        assertEquals(
            listOf(
                AppleNativeSupplementTrack.TRANSLATION,
                AppleNativeSupplementTrack.PRONUNCIATION,
            ),
            appleNativeSupplementTracks(
                pronunciationSelected = true,
                translationSelected = true,
            ),
        )
    }

    @Test
    fun `Apple translation remains selected when pronunciation is disabled`() {
        assertEquals(
            listOf(AppleNativeSupplementTrack.TRANSLATION),
            appleNativeSupplementTracks(
                pronunciationSelected = false,
                translationSelected = true,
            ),
        )
    }

    @Test
    fun `lyrics blur resumes only after Apples requested target is laid out active`() {
        assertTrue(
            shouldCompleteAppleLyricsProgrammaticRecenter(
                suspendedForScroll = true,
                scrollState = 0,
                pendingTargetPosition = 12,
                focusPositions = setOf(12),
            )
        )
        assertFalse(
            shouldCompleteAppleLyricsProgrammaticRecenter(
                suspendedForScroll = true,
                scrollState = 0,
                pendingTargetPosition = 12,
                focusPositions = setOf(11),
            )
        )
        assertFalse(
            shouldCompleteAppleLyricsProgrammaticRecenter(
                suspendedForScroll = true,
                scrollState = 1,
                pendingTargetPosition = 12,
                focusPositions = setOf(12),
            )
        )
        assertFalse(
            shouldCompleteAppleLyricsProgrammaticRecenter(
                suspendedForScroll = false,
                scrollState = 0,
                pendingTargetPosition = 12,
                focusPositions = setOf(12),
            )
        )
    }

    @Test
    fun `instrumental indicator becomes the clear blur focus while visible`() {
        assertEquals(
            setOf(8),
            appleLyricsBlurFocusPositions(
                activePositions = setOf(7),
                instrumentalPositions = setOf(8),
            ),
        )
    }

    @Test
    fun `active lyric remains the blur focus without an instrumental indicator`() {
        assertEquals(
            setOf(7),
            appleLyricsBlurFocusPositions(
                activePositions = setOf(7),
                instrumentalPositions = emptySet(),
            ),
        )
    }

    @Test
    fun `writers credits stay clear while the final lyric is active`() {
        assertEquals(
            setOf(7, 8),
            appleLyricsBlurFocusPositions(
                activePositions = setOf(7),
                instrumentalPositions = emptySet(),
                writersCreditsPositions = setOf(8),
            ),
        )
    }

    @Test
    fun `writers credits are not focused before the final lyric`() {
        assertEquals(
            setOf(6),
            appleLyricsBlurFocusPositions(
                activePositions = setOf(6),
                instrumentalPositions = emptySet(),
                writersCreditsPositions = setOf(8),
            ),
        )
    }

    @Test
    fun `outgoing lyric stays clear until its bottom leaves the current line zone`() {
        assertTrue(
            shouldDeferAppleLyricsOutgoingBlur(
                isPendingOutgoing = true,
                rowBottomY = 321f,
                currentZoneTopY = 220f,
            )
        )
        assertTrue(
            shouldDeferAppleLyricsOutgoingBlur(
                isPendingOutgoing = true,
                rowBottomY = 220.1f,
                currentZoneTopY = 220f,
            )
        )
        assertFalse(
            shouldDeferAppleLyricsOutgoingBlur(
                isPendingOutgoing = true,
                rowBottomY = 220f,
                currentZoneTopY = 220f,
            )
        )
        assertFalse(
            shouldDeferAppleLyricsOutgoingBlur(
                isPendingOutgoing = true,
                rowBottomY = 180f,
                currentZoneTopY = 220f,
            )
        )
        assertFalse(
            shouldDeferAppleLyricsOutgoingBlur(
                isPendingOutgoing = false,
                rowBottomY = 321f,
                currentZoneTopY = 220f,
            )
        )
    }

    @Test
    fun `configured region lookup remains active while original metadata is requested`() {
        val plan = AppleMusicProvider.catalogMetadataResolutionPlan(
            overrideAccountLanguage = true,
            restoreCjkOriginalMetadata = true,
        )

        assertTrue(plan.resolveConfiguredRegion)
        assertTrue(plan.resolveOriginalRegion)
    }

    @Test
    fun `disabled metadata replacement schedules no catalog lookup`() {
        val plan = AppleMusicProvider.catalogMetadataResolutionPlan(
            overrideAccountLanguage = false,
            restoreCjkOriginalMetadata = false,
        )

        assertFalse(plan.resolveConfiguredRegion)
        assertFalse(plan.resolveOriginalRegion)
    }

    @Test
    fun `visible artist profile songs resolve original metadata before localized fallback`() {
        val plan = AppleMusicProvider.inAppOriginalResolutionPlan(
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
            AppleMusicProvider.collectionPageOriginalResolutionMode("playlist"),
        )
        assertEquals(
            InAppOriginalResolutionMode.ORIGINAL_FIRST,
            AppleMusicProvider.collectionPageOriginalResolutionMode("album"),
        )

        val plan = AppleMusicProvider.inAppOriginalResolutionPlan(
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
        val plan = AppleMusicProvider.inAppOriginalResolutionPlan(
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
            AppleMusicProvider.shouldRetryOriginalMetadataCacheProbe(
                originalResolved = false,
                lastMissUptimeMillis = null,
                nowUptimeMillis = 1_000L,
            )
        )
        assertFalse(
            AppleMusicProvider.shouldRetryOriginalMetadataCacheProbe(
                originalResolved = true,
                lastMissUptimeMillis = null,
                nowUptimeMillis = 1_000L,
            )
        )
        assertFalse(
            AppleMusicProvider.shouldRetryOriginalMetadataCacheProbe(
                originalResolved = true,
                lastMissUptimeMillis = 1_000L,
                nowUptimeMillis = 1_749L,
                retryAfterMillis = 750L,
            )
        )
        assertTrue(
            AppleMusicProvider.shouldRetryOriginalMetadataCacheProbe(
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
    fun `renders current third party source labels for the Apple menu`() {
        assertEquals("QQ发音", AppleMusicProvider.onlineSourceMenuLabel("QM", "pronunciation"))
        assertEquals("网易发音", AppleMusicProvider.onlineSourceMenuLabel("NE", "pronunciation"))
        assertEquals("QQ翻译", AppleMusicProvider.onlineSourceMenuLabel("QM", "translation"))
        assertEquals("网易翻译", AppleMusicProvider.onlineSourceMenuLabel("NE", "translation"))
        assertEquals(
            "切换中",
            AppleMusicProvider.onlineSourceMenuLabel(
                "NE",
                "translation",
                OnlineSourceMenuStatus.SWITCHING,
            ),
        )
        assertEquals(
            "切换失败",
            AppleMusicProvider.onlineSourceMenuLabel(
                "QM",
                "pronunciation",
                OnlineSourceMenuStatus.FAILED,
            ),
        )
    }

    @Test
    fun `source menu immediately presents the requested source while switching`() {
        assertEquals(
            OnlineSourceMenuPresentation(
                source = "NE",
                status = OnlineSourceMenuStatus.SWITCHING,
            ),
            AppleMusicProvider.onlineSourceMenuPresentation(
                actualSource = "QM",
                pendingTargetSource = "NE",
                failedSource = null,
            ),
        )
    }

    @Test
    fun `source menu keeps the actual source visible when switching fails`() {
        assertEquals(
            OnlineSourceMenuPresentation(
                source = "QM",
                status = OnlineSourceMenuStatus.FAILED,
            ),
            AppleMusicProvider.onlineSourceMenuPresentation(
                actualSource = "QM",
                pendingTargetSource = null,
                failedSource = "QM",
            ),
        )
        assertNull(
            AppleMusicProvider.onlineSourceMenuPresentation(
                actualSource = null,
                pendingTargetSource = null,
                failedSource = null,
            )
        )
    }

    @Test
    fun `source menu exposes only third party content that is actually consumed`() {
        assertNull(
            AppleMusicProvider.effectiveOnlineSource(
                storedSource = "NE",
                confirmedSource = null,
                onlineContentConsumed = false,
            )
        )
        assertEquals(
            "NE",
            AppleMusicProvider.effectiveOnlineSource(
                storedSource = "NE",
                confirmedSource = null,
                onlineContentConsumed = true,
            )
        )
        assertEquals(
            "QM",
            AppleMusicProvider.effectiveOnlineSource(
                storedSource = "NE",
                confirmedSource = "QM",
                onlineContentConsumed = true,
            )
        )
    }

    @Test
    fun `online source menu never shrinks below the native popup width`() {
        assertEquals(
            320,
            AppleMusicProvider.onlineSourceMenuWidth(
                -2,
                320,
                80,
            ),
        )
        assertEquals(
            360,
            AppleMusicProvider.onlineSourceMenuWidth(
                320,
                360,
            ),
        )
        assertEquals(1, AppleMusicProvider.onlineSourceMenuWidth(-2, 0))
    }

    @Test
    fun `native translation presentation waits until the source menu closes`() {
        assertTrue(
            AppleMusicProvider.shouldDeferNativeTranslationPresentationRefresh(
                activeMenuSongId = "1775825199",
                popupShowing = true,
                expectedSongId = "1775825199",
            )
        )
        assertFalse(
            AppleMusicProvider.shouldDeferNativeTranslationPresentationRefresh(
                activeMenuSongId = "1775825199",
                popupShowing = false,
                expectedSongId = "1775825199",
            )
        )
        assertFalse(
            AppleMusicProvider.shouldDeferNativeTranslationPresentationRefresh(
                activeMenuSongId = "1775825199",
                popupShowing = true,
                expectedSongId = "different-song",
            )
        )
    }

    @Test
    fun `finds lyrics language arrays with or without a synthetic outer parameter`() {
        assertEquals(
            listOf(1, 3),
            appleLyricsStringArrayParameterIndexes(
                arrayOf(
                    Long::class.javaPrimitiveType!!,
                    Array<String>::class.java,
                    Long::class.javaPrimitiveType!!,
                    Array<String>::class.java,
                    Any::class.java,
                    Any::class.java,
                )
            ),
        )
        assertEquals(
            listOf(2, 4),
            appleLyricsStringArrayParameterIndexes(
                arrayOf(
                    Any::class.java,
                    Long::class.javaPrimitiveType!!,
                    Array<String>::class.java,
                    Long::class.javaPrimitiveType!!,
                    Array<String>::class.java,
                    Any::class.java,
                    Any::class.java,
                )
            ),
        )
    }

    @Test
    fun `preserves Apple pronunciation candidates and appends CJK Latin candidates`() {
        assertEquals(
            listOf(
                "zh-Hani",
                "ja-Hrkt",
                "ko-Latn",
                "ja-Latn",
                "zh-Latn",
            ),
            expandAppleLyricsPronunciationLanguages(
                listOf("zh-Hani", "ja-Hrkt", "ko-Latn"),
            ),
        )
    }

    @Test
    fun `expands simplified Chinese Apple translation request aliases`() {
        assertEquals(
            listOf("zh-Hans", "zh-Hans-CN", "zh-CN"),
            expandAppleLyricsTranslationLanguages(listOf("zh-Hans")),
        )
        assertEquals(
            listOf("zh-CN", "zh-Hans", "zh-Hans-CN"),
            expandAppleLyricsTranslationLanguages(listOf("zh-CN")),
        )
    }

    @Test
    fun `selects Apple's region-qualified translation for a script-only system locale`() {
        assertEquals(
            "zh-Hans-CN",
            selectAppleLyricsTranslationLanguage(
                systemLanguage = "zh-Hans",
                availableLanguages = listOf("en-US", "zh-Hans-CN"),
            ),
        )
        assertEquals(
            "zh-Hant-HK",
            selectAppleLyricsTranslationLanguage(
                systemLanguage = "zh-TW",
                availableLanguages = listOf("zh-Hans-CN", "zh-Hant-HK"),
            ),
        )
    }

    @Test
    fun `Apple visibility check reuses the region-qualified official translation tag`() {
        // AM 6.5.0 的 G2 会再次用系统标签 zh-Hans 查询可见性；兼容层必须仍指向
        // SongInfo 真正提供的 zh-Hans-CN，不能让已经解析的官方译文被适配器隐藏。
        assertEquals(
            "zh-Hans-CN",
            selectAppleLyricsTranslationLanguage(
                systemLanguage = "zh-Hans",
                availableLanguages = listOf("zh-Hans-CN"),
            ),
        )
    }

    @Test
    fun `does not treat a different Apple translation language as an official match`() {
        assertNull(
            selectAppleLyricsTranslationLanguage(
                systemLanguage = "zh-Hans",
                availableLanguages = listOf("ja-JP", "ko-KR"),
            )
        )
    }

    @Test
    fun `routes Apple song lyric endpoints through the account storefront`() {
        assertTrue(
            isAppleLyricsRequestPath(
                listOf("v1", "catalog", "us", "songs", "1708445038", "syllable-lyrics")
            )
        )
        assertTrue(
            isAppleLyricsRequestPath(
                listOf("v1", "catalog", "us", "songs", "1708445038", "lyrics")
            )
        )
        assertFalse(
            isAppleLyricsRequestPath(
                listOf("v1", "catalog", "us", "songs", "1708445038")
            )
        )
        assertFalse(
            isAppleLyricsRequestPath(
                listOf("v1", "catalog", "us", "albums", "1708445038", "syllable-lyrics")
            )
        )
    }

    @Test
    fun `position source reads the selected active player`() {
        val inactive = FakeMediaPlayer(position = 0L)
        val active = FakeMediaPlayer(position = 42_000L)

        val inactiveSource = resolvePlaybackPositionSource(inactive)
        val activeSource = resolvePlaybackPositionSource(active)

        assertEquals(0L, inactiveSource?.readPosition())
        assertEquals(42_000L, activeSource?.readPosition())
    }

    @Test
    fun `position source rejects objects without a position method`() {
        assertNull(resolvePlaybackPositionSource(Any()))
    }

    @Test
    fun `only callbacks from the active playback player are accepted`() {
        val active = FakeMediaPlayer(position = 42_000L)
        val queued = FakeMediaPlayer(position = 0L)

        assertTrue(isActivePlaybackCallback(active, active))
        assertFalse(isActivePlaybackCallback(queued, active))
        assertFalse(isActivePlaybackCallback(active, null))
    }

    @Test
    fun `active playback or an existing page binding may notify app models`() {
        assertTrue(shouldNotifyInAppModelChange("123", "123"))
        assertFalse(shouldNotifyInAppModelChange("123", "456"))
        assertFalse(shouldNotifyInAppModelChange("123", null))
        assertTrue(
            shouldNotifyInAppModelChange(
                mediaId = "123",
                activeMediaId = "456",
                hasBoundConsumer = true,
            )
        )
    }

    @Test
    fun `artist caches prefer catalog ids over names`() {
        assertEquals(
            setOf("id:100", "id:200"),
            AppleMusicProvider.stableArtistCacheKeys(
                setOf("name:shared", "id:100", "id:200")
            ),
        )
        assertEquals(
            setOf("name:fallback"),
            AppleMusicProvider.stableArtistCacheKeys(setOf("name:fallback")),
        )
    }

    @Test
    fun `localized artist aliases require catalog ids`() {
        assertEquals(
            emptySet<String>(),
            AppleMusicProvider.localizedArtistCacheKeys(setOf("name:the weeknd")),
        )
        assertEquals(
            setOf("id:479756766"),
            AppleMusicProvider.localizedArtistCacheKeys(
                setOf("name:the weeknd", "id:479756766", "id:not-a-number")
            ),
        )
    }

    @Test
    fun `artist entities reuse their title as the shared artist credit`() {
        val artistCredit = AppleMusicProvider.associatedArtistCredit(
            entityType = AppleInternalCatalogResolver.LocalizedEntityType.ARTIST,
            accountTitle = "陶喆",
            accountArtist = null,
        )

        assertEquals(
            "陶喆",
            artistCredit,
        )
        assertTrue(
            AppleMusicProvider.shouldUseAssociatedArtistEntities(
                artistIds = listOf("16789930"),
                artistCredit = artistCredit,
            )
        )
        assertEquals(
            "David Tao",
            AppleMusicProvider.associatedArtistCredit(
                entityType = AppleInternalCatalogResolver.LocalizedEntityType.SONG,
                accountTitle = "普通朋友",
                accountArtist = "David Tao",
            ),
        )
    }

    @Test
    fun `collaboration songs never borrow a single artist entity credit`() {
        val credit = AppleMusicProvider.associatedArtistCredit(
            entityType = AppleInternalCatalogResolver.LocalizedEntityType.SONG,
            accountTitle = "Home",
            accountArtist = "Charlie Puth、Utada",
        )

        assertEquals("Charlie Puth、Utada", credit)
        assertFalse(
            AppleMusicProvider.shouldUseAssociatedArtistEntities(
                artistIds = listOf("479756766"),
                artistCredit = credit,
            )
        )
    }

    @Test
    fun `solo artist aliases share only through the same exact artist id`() {
        assertEquals(
            "1486113150",
            AppleMusicProvider.sharedAssociatedArtistId(
                artistIds = listOf("1486113150"),
                artistCredit = "藤井风",
            ),
        )
        assertTrue(
            AppleMusicProvider.shouldShareAssociatedArtistAlias(
                artistId = "1486113150",
                targetArtistIds = listOf("1486113150"),
                targetArtistCredit = "藤井风",
            )
        )
        assertFalse(
            AppleMusicProvider.shouldShareAssociatedArtistAlias(
                artistId = "1486113150",
                targetArtistIds = listOf("1087553199", "1486113150"),
                targetArtistCredit = "Elmiene、藤井风",
            )
        )
        assertFalse(
            AppleMusicProvider.shouldShareAssociatedArtistAlias(
                artistId = "1486113150",
                targetArtistIds = listOf("1486113150"),
                targetArtistCredit = "Elmiene、藤井风",
            )
        )
        assertFalse(
            AppleMusicProvider.shouldShareAssociatedArtistAlias(
                artistId = "1486113150",
                targetArtistIds = listOf("18756224"),
                targetArtistCredit = "Utada",
            )
        )
    }

    @Test
    fun `reads direct artist ids from library attributes before relationship fallback`() {
        assertEquals(
            listOf("16789930"),
            AppleMusicProvider.mediaApiAttributeArtistIds(
                FakeLibraryAttributes(
                    artistId = "16789930",
                    artistStoreId = "0",
                )
            ),
        )
        assertEquals(
            emptyList<String>(),
            AppleMusicProvider.mediaApiAttributeArtistIds(
                FakeLibraryAttributes(artistId = "", artistStoreId = "0")
            ),
        )
    }

    @Test
    fun `maps the Tao library album directly to its catalog artist`() {
        assertEquals(
            listOf("16789930"),
            AppleMusicProvider.libraryAssociatedArtistIds(
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
            AppleMusicProvider.artistIdsFromAssociationKeys(
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
            AppleMusicProvider.confirmedOriginalSongAlias(unresolved)
        )
        assertEquals(
            "zh-Hans-CN",
            AppleMusicProvider.originalSongRetryLanguage(unresolved),
        )
    }

    @Test
    fun `current playback only exposes confirmed original metadata`() {
        assertFalse(
            shouldExposeOriginalMetadataOverride(
                mediaId = "123",
                currentPlaybackMediaId = "123",
                confirmed = false,
            )
        )
        assertTrue(
            shouldExposeOriginalMetadataOverride(
                mediaId = "123",
                currentPlaybackMediaId = "123",
                confirmed = true,
            )
        )
        assertTrue(
            shouldExposeOriginalMetadataOverride(
                mediaId = "123",
                currentPlaybackMediaId = "456",
                confirmed = false,
            )
        )
    }

    @Test
    fun `only media notifications open the full player`() {
        assertTrue(
            shouldOpenFullPlayerFromNotification(
                Notification.CATEGORY_TRANSPORT,
                hasMediaSession = false,
            )
        )
        assertTrue(
            shouldOpenFullPlayerFromNotification(
                category = null,
                hasMediaSession = true,
            )
        )
        assertFalse(
            shouldOpenFullPlayerFromNotification(
                Notification.CATEGORY_MESSAGE,
                hasMediaSession = false,
            )
        )
    }

    @Test
    fun `maps visible media api attributes for automatic artist page refresh`() {
        assertEquals(
            VisibleTextField.TITLE,
            AppleMusicProvider.visibleTextFieldForMediaApiAttribute(
                InAppLibraryEntityKind.SONG,
                "getName",
            ),
        )
        assertEquals(
            VisibleTextField.ALBUM,
            AppleMusicProvider.visibleTextFieldForMediaApiAttribute(
                InAppLibraryEntityKind.ALBUM,
                "getName",
            ),
        )
        assertEquals(
            VisibleTextField.ARTIST,
            AppleMusicProvider.visibleTextFieldForMediaApiAttribute(
                InAppLibraryEntityKind.ARTIST,
                "getName",
            ),
        )
        assertEquals(
            VisibleTextField.ARTIST,
            AppleMusicProvider.visibleTextFieldForMediaApiAttribute(
                InAppLibraryEntityKind.SONG,
                "getArtistName",
            ),
        )
    }

    @Test
    fun `only visible getter access can start metadata resolution`() {
        assertFalse(
            AppleMusicProvider.shouldResolveMetadataFromGetter(
                AppleInternalCatalogResolver.RequestPriority.BACKGROUND,
            )
        )
        assertFalse(
            AppleMusicProvider.shouldResolveMetadataFromGetter(
                AppleInternalCatalogResolver.RequestPriority.ACTIVE_PAGE,
            )
        )
        assertTrue(
            AppleMusicProvider.shouldResolveMetadataFromGetter(
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

        assertEquals("歌曲", AppleMusicProvider.localizedVisibleText(VisibleTextField.TITLE, alias))
        assertEquals("歌手", AppleMusicProvider.localizedVisibleText(VisibleTextField.ARTIST, alias))
        assertEquals("专辑", AppleMusicProvider.localizedVisibleText(VisibleTextField.ALBUM, alias))
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
            AppleMusicProvider.contentItemMetadataOverride(song, "getTitle", alias, "raw"),
        )
        assertEquals(
            "喜欢寂寞",
            AppleMusicProvider.contentItemMetadataOverride(
                song,
                "getNowPlayingTitle",
                alias,
                "raw",
            ),
        )
        listOf("getArtistName", "getNowPlayingSubtitle", "getSubTitle").forEach { getter ->
            assertEquals(
                "苏打绿",
                AppleMusicProvider.contentItemMetadataOverride(song, getter, alias, "raw"),
            )
        }
        assertEquals(
            "你在烦恼什么",
            AppleMusicProvider.contentItemMetadataOverride(
                song,
                "getCollectionName",
                alias,
                "raw",
            ),
        )
        assertEquals(
            VisibleTextField.ARTIST,
            AppleMusicProvider.visibleTextFieldForContentItemGetter(
                song,
                "getNowPlayingSubtitle",
            ),
        )
        assertEquals(
            VisibleTextField.ALBUM,
            AppleMusicProvider.visibleTextFieldForContentItemGetter(
                song,
                "getCollectionName",
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
            AppleMusicProvider.contentItemMetadataOverride(
                AppleInternalCatalogResolver.LocalizedEntityType.ALBUM,
                "getTitle",
                alias,
                "raw",
            ),
        )
        assertEquals(
            "陶喆",
            AppleMusicProvider.contentItemMetadataOverride(
                AppleInternalCatalogResolver.LocalizedEntityType.ALBUM,
                "getSubTitle",
                alias,
                "raw",
            ),
        )
        assertEquals(
            "陶喆",
            AppleMusicProvider.contentItemMetadataOverride(
                AppleInternalCatalogResolver.LocalizedEntityType.ARTIST,
                "getTitle",
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

        val effective = AppleMusicProvider.selectEffectiveMetadataAlias(
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

        val effective = AppleMusicProvider.selectEffectiveMetadataAlias(
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

        val effective = AppleMusicProvider.selectEffectiveMetadataAlias(
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

        val effective = AppleMusicProvider.selectEffectiveMetadataAlias(
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

        val effective = AppleMusicProvider.selectIndependentArtistAlias(
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

        val effective = AppleMusicProvider.selectIndependentArtistAlias(
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
            AppleMusicProvider.selectIndependentArtistAlias(
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

        val effective = AppleMusicProvider.selectEffectiveMetadataAlias(
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
            AppleMusicProvider.shouldRequestEffectiveMetadataResolution(
                restoreOriginalEnabled = true,
                originalMetadataResolved = true,
                hasOriginalMetadata = true,
                hasAssociatedArtists = true,
                originalArtistResolved = false,
                hasLocalizedMetadata = true,
            )
        )
        assertFalse(
            AppleMusicProvider.shouldRequestEffectiveMetadataResolution(
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

        val effective = AppleMusicProvider.selectEffectiveMetadataAlias(
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

        val effective = AppleMusicProvider.selectEffectiveMetadataAlias(
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
            AppleMusicProvider.associatedArtistAlias(
                artistIds = listOf("16789930", "missing"),
                aliases = aliases,
                language = "zh-Hans-CN",
            )
        )
        assertEquals(
            "陶喆",
            AppleMusicProvider.associatedArtistAlias(
                artistIds = listOf("16789930"),
                aliases = aliases,
                language = "zh-Hans-CN",
            )?.artist,
        )
    }

    @Test
    fun `collaboration credits do not use independent artist entity overrides`() {
        assertTrue(
            AppleMusicProvider.shouldUseAssociatedArtistEntities(
                artistIds = listOf("18756224"),
                artistCredit = "Utada",
            )
        )
        assertFalse(
            AppleMusicProvider.shouldUseAssociatedArtistEntities(
                artistIds = listOf("18756224"),
                artistCredit = null,
            )
        )
        assertFalse(
            AppleMusicProvider.shouldUseAssociatedArtistEntities(
                artistIds = listOf("479756766", "1322012752"),
                artistCredit = "Charlie Puth、Utada",
            )
        )
        assertFalse(
            AppleMusicProvider.shouldUseAssociatedArtistEntities(
                artistIds = listOf("479756766"),
                artistCredit = "Charlie Puth、Utada",
            )
        )
        assertFalse(
            AppleMusicProvider.shouldUseAssociatedArtistEntities(
                artistIds = listOf("1443363988"),
                artistCredit = "Dove Cameron (feat. Khalid)",
            )
        )
        assertFalse(
            AppleMusicProvider.shouldUseAssociatedArtistEntities(
                artistIds = listOf("1087553199", "1039574375"),
                artistCredit = "Elmiene、藤井风",
            )
        )
    }

    @Test
    fun `late artist callbacks are accepted only for the same isolated solo artist`() {
        assertTrue(
            AppleMusicProvider.shouldAcceptAssociatedArtistResolution(
                requestedArtistIds = listOf("18756224"),
                currentArtistIds = listOf("18756224"),
                artistCredit = "Utada",
            )
        )
        assertFalse(
            AppleMusicProvider.shouldAcceptAssociatedArtistResolution(
                requestedArtistIds = listOf("18756224"),
                currentArtistIds = listOf("479756766", "18756224"),
                artistCredit = "Charlie Puth、Utada",
            )
        )
        assertFalse(
            AppleMusicProvider.shouldAcceptAssociatedArtistResolution(
                requestedArtistIds = listOf("18756224"),
                currentArtistIds = listOf("18756224"),
                artistCredit = "Charlie Puth、Utada",
            )
        )
        assertFalse(
            AppleMusicProvider.shouldAcceptAssociatedArtistResolution(
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
            AppleMusicProvider.originalArtistLanguageFromSongResolution(
                resolution = oneLastKiss,
                localizedArtist = "Utada",
            ),
        )
        assertNull(
            AppleMusicProvider.originalArtistLanguageFromSongResolution(
                resolution = home,
                localizedArtist = "Charlie Puth、Utada",
            )
        )
        assertNull(
            AppleMusicProvider.originalArtistLanguageFromSongResolution(
                resolution = oneLastKiss,
                localizedArtist = "Charlie Puth、Utada",
            )
        )
    }

    @Test
    fun `collaboration alias cannot localize only the Home artist credit`() {
        assertNull(
            AppleMusicProvider.validatedOriginalSongAlias(
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
            AppleMusicProvider.validatedOriginalSongAlias(
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
            AppleMusicProvider.validatedOriginalSongAlias(
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
            AppleMusicProvider.inferredOriginalArtistLanguage(
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
            AppleMusicProvider.inferredOriginalArtistLanguage(
                kind = InAppLibraryEntityKind.ALBUM,
                artist = "American Artist & Japanese Artist",
                associatedArtistIds = listOf("100", "200"),
                genres = listOf("J-Pop"),
            )
        )
        assertNull(
            AppleMusicProvider.inferredOriginalArtistLanguage(
                kind = InAppLibraryEntityKind.ALBUM,
                artist = "American Artist feat. Japanese Artist",
                associatedArtistIds = listOf("100"),
                genres = listOf("J-Pop"),
            )
        )
    }

    @Test
    fun `prefers album entities for delayed artist page album bindings`() {
        assertEquals(
            AppleInternalCatalogResolver.LocalizedEntityType.SONG,
            AppleMusicProvider.preferredVisibleEntityType(VisibleTextField.TITLE),
        )
        assertEquals(
            AppleInternalCatalogResolver.LocalizedEntityType.ARTIST,
            AppleMusicProvider.preferredVisibleEntityType(VisibleTextField.ARTIST),
        )
        assertEquals(
            AppleInternalCatalogResolver.LocalizedEntityType.ALBUM,
            AppleMusicProvider.preferredVisibleEntityType(VisibleTextField.ALBUM),
        )
    }

    @Test
    fun `does not treat station models as songs`() {
        assertNull(
            AppleMusicProvider.localizedEntityTypeForContentItemClassNames(
                listOf("SongStationItem", "BaseContentItem")
            )
        )
        assertNull(
            AppleMusicProvider.localizedEntityTypeForContentItemClassNames(
                listOf("RadioStation", "BaseContentItem")
            )
        )
        assertEquals(
            AppleInternalCatalogResolver.LocalizedEntityType.SONG,
            AppleMusicProvider.localizedEntityTypeForContentItemClassNames(
                listOf("Song", "BaseContentItem")
            ),
        )
    }

    @Test
    fun `treats Apple queue history collection items as songs`() {
        assertTrue(isInAppHistoryQueueEntryClassName("Z8.d"))
        assertFalse(isInAppHistoryQueueEntryClassName("Z8.c"))
        assertEquals(
            AppleInternalCatalogResolver.LocalizedEntityType.SONG,
            AppleMusicProvider.localizedEntityTypeForQueueItem(
                historyEntry = true,
                classNames = listOf("CollectionItemView"),
            ),
        )
        assertNull(
            AppleMusicProvider.localizedEntityTypeForQueueItem(
                historyEntry = false,
                classNames = listOf("CollectionItemView"),
            )
        )
    }

    @Test
    fun `maps history title and artist to CollectionItemView accessors`() {
        assertEquals(
            InAppPlaybackItemAccess(
                readMember = "getTitle",
                readViaMethod = true,
                setter = "setTitle",
            ),
            inAppPlaybackItemAccess(
                InAppPlaybackItemContract.HISTORY,
                InAppPlaybackItemField.TITLE,
            ),
        )
        assertEquals(
            InAppPlaybackItemAccess(
                readMember = "getSubTitle",
                readViaMethod = true,
                setter = "setSubTitle",
            ),
            inAppPlaybackItemAccess(
                InAppPlaybackItemContract.HISTORY,
                InAppPlaybackItemField.ARTIST,
            ),
        )
        assertNull(
            inAppPlaybackItemAccess(
                InAppPlaybackItemContract.HISTORY,
                InAppPlaybackItemField.ALBUM,
            )
        )
    }

    @Test
    fun `rejects a delayed alias after a history item is rebound to another song`() {
        assertTrue(
            shouldApplyInAppPlaybackItemAlias(
                expectedMediaId = "1158763998",
                currentMediaId = "1158763998",
            )
        )
        assertFalse(
            shouldApplyInAppPlaybackItemAlias(
                expectedMediaId = "1158763998",
                currentMediaId = "1813917858",
            )
        )
        assertFalse(
            shouldApplyInAppPlaybackItemAlias(
                expectedMediaId = "1158763998",
                currentMediaId = null,
            )
        )
    }

    @Test
    fun `history getter reentry guard is nested and exception safe`() {
        val guard = ThreadLocalReentryGuard()

        assertFalse(guard.isActive)
        guard.run {
            assertTrue(guard.isActive)
            guard.run {
                assertTrue(guard.isActive)
            }
            assertTrue(guard.isActive)
        }
        assertFalse(guard.isActive)

        runCatching {
            guard.run {
                assertTrue(guard.isActive)
                error("expected")
            }
        }
        assertFalse(guard.isActive)
    }

    @Test
    fun `recycler bind capture stack restores the outer holder after nested binding`() {
        val stack = ThreadLocalStack<String>()

        stack.push("outer")
        assertEquals("outer", stack.current)
        stack.push("inner")
        assertEquals("inner", stack.current)
        assertEquals("inner", stack.pop())
        assertEquals("outer", stack.current)
        assertEquals("outer", stack.pop())
        assertNull(stack.current)
        assertNull(stack.pop())
    }

    @Test
    fun `compose render capture wins over the full recent items fallback`() {
        assertEquals(
            listOf("152197399", "1882935769"),
            composeVisibleMetadataResolutionIds(
                capturedMediaIds = listOf(
                    "152197399",
                    "invalid",
                    "1882935769",
                    "152197399",
                ),
                fallbackMediaIds = listOf(
                    "1529513416",
                    "6773456078",
                    "1747393653",
                ),
                limit = 12,
            ),
        )
    }

    @Test
    fun `compose fallback keeps only the first bounded catalog ids`() {
        assertEquals(
            listOf("152197399", "1529513416", "6773456078"),
            composeVisibleMetadataResolutionIds(
                capturedMediaIds = emptyList(),
                fallbackMediaIds = listOf(
                    "152197399",
                    "invalid",
                    "1529513416",
                    "6773456078",
                    "1747393653",
                ),
                limit = 3,
            ),
        )
        assertTrue(
            composeVisibleMetadataResolutionIds(
                capturedMediaIds = listOf("152197399"),
                fallbackMediaIds = emptyList(),
                limit = 0,
            ).isEmpty()
        )
    }

    @Test
    fun `carousel holder keeps every captured song id`() {
        assertEquals(
            linkedSetOf("1797266719", "1559632900", "1559632901", "1643823927"),
            normalizedRecyclerBindingMediaIds(
                listOf(
                    "1797266719",
                    "1559632900",
                    "1559632901",
                    "1643823927",
                    "1797266719",
                    "",
                    "not-a-catalog-id",
                )
            ),
        )
    }

    @Test
    fun `carousel holder never registers a structural recycler refresh`() {
        assertFalse(
            shouldRegisterGenericRecyclerRefresh(
                mediaIds = linkedSetOf(
                    "1797266719",
                    "1559632900",
                    "1559632901",
                    "1643823927",
                ),
                dataBindingMediaId = null,
                blockMultiItemStructuralRefresh = true,
            )
        )
    }

    @Test
    fun `artist profile controller is recognized behind the epoxy adapter`() {
        assertTrue(
            isArtistProfileControllerClassNames(
                listOf(
                    "com.apple.android.music.profiles.ArtistEpoxyController",
                    "com.apple.android.music.profiles.BaseProfileEpoxyController",
                )
            )
        )
        assertFalse(
            isArtistProfileControllerClassNames(
                listOf(
                    "com.airbnb.epoxy.s",
                    "com.airbnb.epoxy.f",
                )
            )
        )
    }

    @Test
    fun `other carousel holders retain their existing recycler refresh fallback`() {
        assertTrue(
            shouldRegisterGenericRecyclerRefresh(
                mediaIds = linkedSetOf("1797266719", "1559632900"),
                dataBindingMediaId = null,
                blockMultiItemStructuralRefresh = false,
            )
        )
    }

    @Test
    fun `single song binding uses direct binding refresh instead of recycler rebuild`() {
        assertFalse(
            shouldRegisterGenericRecyclerRefresh(
                mediaIds = setOf("1505498782"),
                dataBindingMediaId = "1505498782",
                blockMultiItemStructuralRefresh = false,
            )
        )
    }

    @Test
    fun `single unbound recycler row retains precise item refresh fallback`() {
        assertTrue(
            shouldRegisterGenericRecyclerRefresh(
                mediaIds = setOf("1505498782"),
                dataBindingMediaId = null,
                blockMultiItemStructuralRefresh = false,
            )
        )
    }

    @Test
    fun `visible recycler metadata skips empty hidden and repeated bindings`() {
        val mediaIds = setOf("1505498782")

        assertFalse(
            shouldScheduleVisibleRecyclerMetadata(
                previousMediaIds = null,
                currentMediaIds = emptySet(),
                visible = true,
            )
        )
        assertFalse(
            shouldScheduleVisibleRecyclerMetadata(
                previousMediaIds = null,
                currentMediaIds = mediaIds,
                visible = false,
            )
        )
        assertFalse(
            shouldScheduleVisibleRecyclerMetadata(
                previousMediaIds = mediaIds,
                currentMediaIds = mediaIds,
                visible = true,
            )
        )
        assertTrue(
            shouldScheduleVisibleRecyclerMetadata(
                previousMediaIds = mediaIds,
                currentMediaIds = setOf("1519740112"),
                visible = true,
            )
        )
    }

    @Test
    fun `pending data binding alias suppresses duplicate queued refreshes`() {
        val requested = AppliedMetadataAlias(
            mediaId = "1445886021",
            alias = AppleInternalCatalogResolver.Alias(
                title = "Come Back to Me",
                artist = "宇多田ヒカル",
                album = "This Is the One",
                language = "ja-JP",
            ),
        )

        assertFalse(
            shouldScheduleDataBindingAliasRefresh(
                appliedAlias = null,
                pendingAlias = requested,
                requestedAlias = requested,
            )
        )
        assertFalse(
            shouldScheduleDataBindingAliasRefresh(
                appliedAlias = requested,
                pendingAlias = null,
                requestedAlias = requested,
            )
        )
        assertTrue(
            shouldScheduleDataBindingAliasRefresh(
                appliedAlias = requested,
                pendingAlias = null,
                requestedAlias = requested.copy(artist = "Utada"),
            )
        )
    }

    @Test
    fun `already rendered album header skips another binding refresh`() {
        assertTrue(
            dataBindingAliasAlreadyRendered(
                expectedTitle = "Pre: Prema",
                expectedSubtitle = "藤井 風",
                renderedTexts = listOf("Pre: Prema", "藤井 風 · 2025年"),
            )
        )
    }

    @Test
    fun `album header still refreshes when only its artist is stale`() {
        assertFalse(
            dataBindingAliasAlreadyRendered(
                expectedTitle = "Pre: Prema",
                expectedSubtitle = "藤井 風",
                renderedTexts = listOf("Pre: Prema", "藤井风 · 2025年"),
            )
        )
    }

    @Test
    fun `listen now refreshes when Apple restores stale text for an applied alias`() {
        val alias = AppliedMetadataAlias(
            mediaId = "1519740112",
            alias = AppleInternalCatalogResolver.Alias(
                title = "盗作",
                artist = "ヨルシカ",
                album = "盗作",
                language = "ja-JP",
            ),
        )

        assertTrue(
            shouldRefreshListenNowDataBindingAlias(
                appliedAlias = alias,
                requestedAlias = alias,
                expectedTitle = "盗作",
                expectedSubtitle = "ヨルシカ",
                renderedTexts = listOf("Plagiarism", "Yorushika"),
            )
        )
    }

    @Test
    fun `listen now skips refresh while an applied alias is still rendered`() {
        val alias = AppliedMetadataAlias(
            mediaId = "1519740112",
            alias = AppleInternalCatalogResolver.Alias(
                title = "盗作",
                artist = "ヨルシカ",
                album = "盗作",
                language = "ja-JP",
            ),
        )

        assertFalse(
            shouldRefreshListenNowDataBindingAlias(
                appliedAlias = alias,
                requestedAlias = alias,
                expectedTitle = "盗作",
                expectedSubtitle = "ヨルシカ",
                renderedTexts = listOf("盗作", "ヨルシカ"),
            )
        )
    }

    @Test
    fun `data binding refresh is rejected after the card targets another item`() {
        assertFalse(
            isDataBindingRefreshCurrent(
                currentMediaId = "l.AgkTCQ8",
                requestedMediaId = "1648875799",
                currentBindGeneration = 12L,
                scheduledBindGeneration = 12L,
            )
        )
    }

    @Test
    fun `data binding refresh is rejected after another bind generation starts`() {
        assertFalse(
            isDataBindingRefreshCurrent(
                currentMediaId = "1648875799",
                requestedMediaId = "1648875799",
                currentBindGeneration = 13L,
                scheduledBindGeneration = 12L,
            )
        )
    }

    @Test
    fun `data binding refresh remains valid for the same item and generation`() {
        assertTrue(
            isDataBindingRefreshCurrent(
                currentMediaId = "1648875799",
                requestedMediaId = "1648875799",
                currentBindGeneration = 12L,
                scheduledBindGeneration = 12L,
            )
        )
    }

    @Test
    fun `successful data binding variables do not invalidate the full header`() {
        assertEquals(
            DataBindingRefreshStrategy.VARIABLES_ONLY,
            dataBindingRefreshStrategy(
                expectedTitle = "Pre: Prema",
                expectedSubtitle = "藤井 風",
                titleApplied = true,
                subtitleApplied = true,
            ),
        )
    }

    @Test
    fun `missing data binding variable keeps the structural refresh fallback`() {
        assertEquals(
            DataBindingRefreshStrategy.FULL_INVALIDATE,
            dataBindingRefreshStrategy(
                expectedTitle = "Pre: Prema",
                expectedSubtitle = "藤井 風",
                titleApplied = true,
                subtitleApplied = false,
            ),
        )
        assertEquals(
            DataBindingRefreshStrategy.FULL_INVALIDATE,
            dataBindingRefreshStrategy(
                expectedTitle = null,
                expectedSubtitle = null,
                titleApplied = false,
                subtitleApplied = false,
            ),
        )
    }

    @Test
    fun `artist header invalidates an applied alias after Apple renders its old title`() {
        val alias = AppliedMetadataAlias(
            mediaId = "18756224",
            alias = AppleInternalCatalogResolver.Alias(
                title = "宇多田ヒカル",
                artist = "宇多田ヒカル",
                album = "",
                language = "ja-JP",
            ),
        )

        assertTrue(
            shouldInvalidateArtistHeaderAppliedAlias(
                appliedAlias = alias,
                effectiveAlias = alias,
                pendingAlias = null,
                expectedTitle = "宇多田ヒカル",
                renderedTexts = listOf("Utada"),
            )
        )
    }

    @Test
    fun `artist header keeps alias state when rendered or refresh is already pending`() {
        val alias = AppliedMetadataAlias(
            mediaId = "18756224",
            alias = AppleInternalCatalogResolver.Alias(
                title = "宇多田ヒカル",
                artist = "宇多田ヒカル",
                album = "",
                language = "ja-JP",
            ),
        )

        assertFalse(
            shouldInvalidateArtistHeaderAppliedAlias(
                appliedAlias = alias,
                effectiveAlias = alias,
                pendingAlias = null,
                expectedTitle = "宇多田ヒカル",
                renderedTexts = listOf("宇多田ヒカル"),
            )
        )
        assertFalse(
            shouldInvalidateArtistHeaderAppliedAlias(
                appliedAlias = alias,
                effectiveAlias = alias,
                pendingAlias = alias,
                expectedTitle = "宇多田ヒカル",
                renderedTexts = listOf("Utada"),
            )
        )
        assertFalse(
            shouldInvalidateArtistHeaderAppliedAlias(
                appliedAlias = alias,
                effectiveAlias = alias.copy(artist = "Utada"),
                pendingAlias = null,
                expectedTitle = "Utada",
                renderedTexts = listOf("Utada"),
            )
        )
    }

    @Test
    fun `expired coordinator scope still refreshes an exact visible consumer`() {
        assertTrue(
            shouldRefreshInAppSurface(
                surfaceRelevant = false,
                hasVisibleExactConsumer = true,
            )
        )
        assertFalse(
            shouldRefreshInAppSurface(
                surfaceRelevant = false,
                hasVisibleExactConsumer = false,
            )
        )
    }

    @Test
    fun `visible request lease can refresh compose after an owner switch`() {
        assertTrue(
            shouldRefreshInAppSurface(
                surfaceRelevant = false,
                hasVisibleExactConsumer = false,
                hasActiveVisibleLease = true,
            )
        )
    }

    @Test
    fun `exact recycler refresh rejects recycled or hidden rows`() {
        assertTrue(
            shouldRefreshExactBoundTarget(
                surfaceRelevant = false,
                mediaIdMatches = true,
                rootVisible = true,
            )
        )
        assertFalse(
            shouldRefreshExactBoundTarget(
                surfaceRelevant = false,
                mediaIdMatches = false,
                rootVisible = true,
            )
        )
        assertFalse(
            shouldRefreshExactBoundTarget(
                surfaceRelevant = false,
                mediaIdMatches = true,
                rootVisible = false,
            )
        )
    }

    @Test
    fun `active surface can refresh its exact binding before visibility settles`() {
        assertTrue(
            shouldRefreshExactBoundTarget(
                surfaceRelevant = true,
                mediaIdMatches = true,
                rootVisible = false,
            )
        )
    }

    @Test
    fun `final Epoxy dispatcher routes collection and artist page models separately`() {
        assertEquals(
            MetadataPageFinalBindingKind.ALBUM_HEADER,
            metadataPageFinalBindingKind(
                albumHeader = true,
                albumRow = false,
                playlistRow = false,
                artistTopSong = false,
                artistHeader = false,
            ),
        )
        assertEquals(
            MetadataPageFinalBindingKind.ALBUM_ROW,
            metadataPageFinalBindingKind(
                albumHeader = false,
                albumRow = true,
                playlistRow = false,
                artistTopSong = false,
                artistHeader = false,
            ),
        )
        assertEquals(
            MetadataPageFinalBindingKind.PLAYLIST_ROW,
            metadataPageFinalBindingKind(
                albumHeader = false,
                albumRow = false,
                playlistRow = true,
                artistTopSong = false,
                artistHeader = false,
            ),
        )
        assertEquals(
            MetadataPageFinalBindingKind.ARTIST_TOP_SONG,
            metadataPageFinalBindingKind(
                albumHeader = false,
                albumRow = false,
                playlistRow = false,
                artistTopSong = true,
                artistHeader = false,
            ),
        )
        assertEquals(
            MetadataPageFinalBindingKind.ARTIST_HEADER,
            metadataPageFinalBindingKind(
                albumHeader = false,
                albumRow = false,
                playlistRow = false,
                artistTopSong = false,
                artistHeader = true,
            ),
        )
        assertNull(
            metadataPageFinalBindingKind(
                albumHeader = false,
                albumRow = false,
                playlistRow = false,
                artistTopSong = false,
                artistHeader = false,
            )
        )
    }

    @Test
    fun `album and artist controllers keep their typed setData rebuild paths`() {
        assertEquals(
            InAppLibraryControllerBuildStrategy.ALBUM_SET_DATA,
            inAppLibraryControllerBuildStrategy(
                hasAlbumBuildData = true,
                hasArtistBuildData = false,
                isPlaylistPageController = false,
            ),
        )
        assertEquals(
            InAppLibraryControllerBuildStrategy.ARTIST_SET_DATA,
            inAppLibraryControllerBuildStrategy(
                hasAlbumBuildData = false,
                hasArtistBuildData = true,
                isPlaylistPageController = false,
            ),
        )
        assertEquals(
            InAppLibraryControllerBuildStrategy.PLAYLIST_FORCE_MODEL_BUILD,
            inAppLibraryControllerBuildStrategy(
                hasAlbumBuildData = false,
                hasArtistBuildData = false,
                isPlaylistPageController = true,
            ),
        )
        assertEquals(
            InAppLibraryControllerBuildStrategy.GENERIC_REQUEST_MODEL_BUILD,
            inAppLibraryControllerBuildStrategy(
                hasAlbumBuildData = false,
                hasArtistBuildData = false,
                isPlaylistPageController = false,
            ),
        )
    }

    @Test
    fun `recent search entity classification keeps artists albums and songs isolated`() {
        assertEquals(
            InAppLibraryEntityKind.ARTIST,
            inAppLibraryEntityKindForClassNames(
                listOf(
                    "com.apple.android.music.mediaapi.models.Artist",
                    "com.apple.android.music.mediaapi.models.MediaEntity",
                )
            ),
        )
        assertEquals(
            InAppLibraryEntityKind.ALBUM,
            inAppLibraryEntityKindForClassNames(
                listOf(
                    "com.apple.android.music.mediaapi.models.LibraryAlbum",
                    "com.apple.android.music.mediaapi.models.MediaEntity",
                )
            ),
        )
        assertEquals(
            InAppLibraryEntityKind.SONG,
            inAppLibraryEntityKindForClassNames(
                listOf(
                    "com.apple.android.music.mediaapi.models.LibrarySong",
                    "com.apple.android.music.mediaapi.models.MediaEntity",
                )
            ),
        )
        assertNull(
            inAppLibraryEntityKindForClassNames(
                listOf(
                    "com.apple.android.music.mediaapi.models.MediaEntity",
                    "com.apple.android.music.search2.SearchResult",
                )
            )
        )
    }

    @Test
    fun `Listen Now entity kinds keep album cache keys separate from songs`() {
        assertEquals(
            AppleInternalCatalogResolver.LocalizedEntityType.ALBUM,
            localizedEntityTypeForInAppLibraryKind(InAppLibraryEntityKind.ALBUM),
        )
        assertEquals(
            AppleInternalCatalogResolver.LocalizedEntityType.SONG,
            localizedEntityTypeForInAppLibraryKind(InAppLibraryEntityKind.SONG),
        )
        assertEquals(
            AppleInternalCatalogResolver.LocalizedEntityType.ARTIST,
            localizedEntityTypeForInAppLibraryKind(InAppLibraryEntityKind.ARTIST),
        )
    }

    @Test
    fun `playlist controller refreshes its first alias immediately then enters cooldown`() {
        val state = InAppLibraryControllerRefreshState()

        assertEquals(
            InAppLibraryControllerRefreshDispatch(delayMillis = 0L),
            state.enqueue(
                mediaId = "first",
                strategy = InAppLibraryControllerBuildStrategy.PLAYLIST_FORCE_MODEL_BUILD,
                nowUptimeMillis = 1_000L,
                albumDebounceMillis = 180L,
                playlistIntervalMillis = 500L,
            ),
        )
        assertEquals(listOf("first"), state.takePendingMediaIds())
        state.recordBuildAttempt(nowUptimeMillis = 1_000L)
        assertNull(
            state.finishDrain(
                strategy = InAppLibraryControllerBuildStrategy.PLAYLIST_FORCE_MODEL_BUILD,
                nowUptimeMillis = 1_000L,
                albumDebounceMillis = 180L,
                playlistIntervalMillis = 500L,
            )
        )

        assertEquals(
            InAppLibraryControllerRefreshDispatch(delayMillis = 400L),
            state.enqueue(
                mediaId = "second",
                strategy = InAppLibraryControllerBuildStrategy.PLAYLIST_FORCE_MODEL_BUILD,
                nowUptimeMillis = 1_100L,
                albumDebounceMillis = 180L,
                playlistIntervalMillis = 500L,
            ),
        )
    }

    @Test
    fun `playlist controller coalesces window aliases and preserves its trailing batch`() {
        val state = InAppLibraryControllerRefreshState()
        state.enqueue(
            mediaId = "first",
            strategy = InAppLibraryControllerBuildStrategy.PLAYLIST_FORCE_MODEL_BUILD,
            nowUptimeMillis = 1_000L,
            albumDebounceMillis = 180L,
            playlistIntervalMillis = 500L,
        )
        assertEquals(listOf("first"), state.takePendingMediaIds())
        state.recordBuildAttempt(nowUptimeMillis = 1_000L)

        assertNull(
            state.enqueue(
                mediaId = "second",
                strategy = InAppLibraryControllerBuildStrategy.PLAYLIST_FORCE_MODEL_BUILD,
                nowUptimeMillis = 1_010L,
                albumDebounceMillis = 180L,
                playlistIntervalMillis = 500L,
            )
        )
        assertNull(
            state.enqueue(
                mediaId = "third",
                strategy = InAppLibraryControllerBuildStrategy.PLAYLIST_FORCE_MODEL_BUILD,
                nowUptimeMillis = 1_010L,
                albumDebounceMillis = 180L,
                playlistIntervalMillis = 500L,
            )
        )
        assertEquals(
            InAppLibraryControllerRefreshDispatch(delayMillis = 490L),
            state.finishDrain(
                strategy = InAppLibraryControllerBuildStrategy.PLAYLIST_FORCE_MODEL_BUILD,
                nowUptimeMillis = 1_010L,
                albumDebounceMillis = 180L,
                playlistIntervalMillis = 500L,
            ),
        )
        assertEquals(listOf("second", "third"), state.takePendingMediaIds())
    }

    @Test
    fun `album controller refreshes use a short debounce while generic remains immediate`() {
        assertEquals(
            180L,
            inAppLibraryControllerRefreshDelayMillis(
                strategy = InAppLibraryControllerBuildStrategy.ALBUM_SET_DATA,
                lastBuildUptimeMillis = 1_000L,
                nowUptimeMillis = 1_010L,
                albumDebounceMillis = 180L,
                playlistIntervalMillis = 500L,
            ),
        )
        assertEquals(
            0L,
            inAppLibraryControllerRefreshDelayMillis(
                strategy = InAppLibraryControllerBuildStrategy.GENERIC_REQUEST_MODEL_BUILD,
                lastBuildUptimeMillis = 1_000L,
                nowUptimeMillis = 1_010L,
                albumDebounceMillis = 180L,
                playlistIntervalMillis = 500L,
            ),
        )
    }

    @Test
    fun `album controller coalesces one batch and still accepts a later visible batch`() {
        val state = InAppLibraryControllerRefreshState()

        assertEquals(
            InAppLibraryControllerRefreshDispatch(delayMillis = 180L),
            state.enqueue(
                mediaId = "first",
                strategy = InAppLibraryControllerBuildStrategy.ALBUM_SET_DATA,
                nowUptimeMillis = 1_000L,
                albumDebounceMillis = 180L,
                playlistIntervalMillis = 500L,
            ),
        )
        assertNull(
            state.enqueue(
                mediaId = "second",
                strategy = InAppLibraryControllerBuildStrategy.ALBUM_SET_DATA,
                nowUptimeMillis = 1_020L,
                albumDebounceMillis = 180L,
                playlistIntervalMillis = 500L,
            )
        )
        assertEquals(listOf("first", "second"), state.takePendingMediaIds())
        state.recordBuildAttempt(nowUptimeMillis = 1_180L)
        assertNull(
            state.finishDrain(
                strategy = InAppLibraryControllerBuildStrategy.ALBUM_SET_DATA,
                nowUptimeMillis = 1_180L,
                albumDebounceMillis = 180L,
                playlistIntervalMillis = 500L,
            )
        )

        assertEquals(
            InAppLibraryControllerRefreshDispatch(delayMillis = 180L),
            state.enqueue(
                mediaId = "later-visible-row",
                strategy = InAppLibraryControllerBuildStrategy.ALBUM_SET_DATA,
                nowUptimeMillis = 1_300L,
                albumDebounceMillis = 180L,
                playlistIntervalMillis = 500L,
            ),
        )
    }

    @Test
    fun `album artist mismatch changes the controller applied alias for the same safe artist`() {
        val songAlias = AppliedMetadataAlias(
            mediaId = "song",
            title = "One Last Kiss",
            artist = "Utada",
            album = "One Last Kiss",
            language = "en-US",
        )

        assertEquals(
            songAlias.copy(artist = "宇多田ヒカル"),
            albumPageControllerAppliedAlias(
                appliedAlias = songAlias,
                songArtistId = "18756224",
                albumArtistId = "18756224",
                albumArtist = "宇多田ヒカル",
            ),
        )
    }

    @Test
    fun `album artist alignment ignores different artists and matching names`() {
        val songAlias = AppliedMetadataAlias(
            mediaId = "song",
            title = "Home",
            artist = "Charlie Puth、Utada",
            album = "CHARLIE",
            language = "en-US",
        )

        assertEquals(
            songAlias,
            albumPageControllerAppliedAlias(
                appliedAlias = songAlias,
                songArtistId = null,
                albumArtistId = "18756224",
                albumArtist = "宇多田ヒカル",
            ),
        )
        assertEquals(
            songAlias,
            albumPageControllerAppliedAlias(
                appliedAlias = songAlias,
                songArtistId = "18756224",
                albumArtistId = "1486113150",
                albumArtist = "藤井 風",
            ),
        )
        assertEquals(
            songAlias.copy(artist = "宇多田ヒカル"),
            albumPageControllerAppliedAlias(
                appliedAlias = songAlias.copy(artist = "宇多田ヒカル"),
                songArtistId = "18756224",
                albumArtistId = "18756224",
                albumArtist = "  宇多田ヒカル  ",
            ),
        )
    }

    @Test
    fun `Compose alias deduplication is isolated by media id within one state`() {
        val albumAlias = AppliedMetadataAlias(
            mediaId = "album",
            title = "Pre: Prema",
            artist = "藤井 風",
            album = "Pre: Prema",
            language = "ja-JP",
        )
        val songAlias = AppliedMetadataAlias(
            mediaId = "song",
            title = "Feelin’ Go(o)d",
            artist = "藤井 風",
            album = "Pre: Prema",
            language = "ja-JP",
        )
        val appliedAliases = mapOf(
            albumAlias.mediaId to albumAlias,
            songAlias.mediaId to songAlias,
        )

        assertFalse(
            shouldRefreshInAppLibraryComposeAlias(
                appliedAliases = appliedAliases,
                mediaId = albumAlias.mediaId,
                requestedAlias = albumAlias,
            )
        )
        assertFalse(
            shouldRefreshInAppLibraryComposeAlias(
                appliedAliases = appliedAliases,
                mediaId = songAlias.mediaId,
                requestedAlias = songAlias,
            )
        )
        assertTrue(
            shouldRefreshInAppLibraryComposeAlias(
                appliedAliases = appliedAliases,
                mediaId = songAlias.mediaId,
                requestedAlias = songAlias.copy(artist = "藤井风"),
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
    fun `artist profile capture accepts only catalog songs from top songs`() {
        assertEquals(
            "1505498782",
            artistProfileTopSongMediaId("top-songs", "1505498782"),
        )
        assertNull(artistProfileTopSongMediaId("albums", "1505498782"))
        assertNull(artistProfileTopSongMediaId("top-songs", "not-a-catalog-id"))
        assertNull(artistProfileTopSongMediaId("top-songs", null))
    }

    @Test
    fun `artist profile infers its artist id only for an exact known solo credit`() {
        assertEquals(
            "18756224",
            artistProfileFallbackArtistId(
                profileArtistId = "18756224",
                existingArtistIds = emptyList(),
                songArtistCredit = "Utada",
                profileArtistCredits = listOf("宇多田ヒカル", "Utada"),
            ),
        )
    }

    @Test
    fun `artist profile never infers its artist id for collaborations or existing ids`() {
        assertNull(
            artistProfileFallbackArtistId(
                profileArtistId = "18756224",
                existingArtistIds = emptyList(),
                songArtistCredit = "Charlie Puth、Utada",
                profileArtistCredits = listOf("Utada"),
            )
        )
        assertNull(
            artistProfileFallbackArtistId(
                profileArtistId = "18756224",
                existingArtistIds = listOf("111", "18756224"),
                songArtistCredit = "Utada",
                profileArtistCredits = listOf("Utada"),
            )
        )
        assertNull(
            artistProfileFallbackArtistId(
                profileArtistId = "18756224",
                existingArtistIds = emptyList(),
                songArtistCredit = "宇多田ヒカル feat. 椎名林檎",
                profileArtistCredits = listOf("宇多田ヒカル"),
            )
        )
    }

    @Test
    fun `artist profile subtitle replaces only the artist and preserves the year`() {
        assertEquals(
            "藤井 風 · 2023年",
            artistProfileSubtitleWithArtist(
                originalSubtitle = "藤井风 · 2023年",
                originalArtist = "藤井风",
                replacementArtist = "藤井 風",
            ),
        )
    }

    @Test
    fun `artist profile subtitle keeps unrelated Apple text unchanged`() {
        assertEquals(
            "2023年 · 流行乐",
            artistProfileSubtitleWithArtist(
                originalSubtitle = "2023年 · 流行乐",
                originalArtist = "藤井风",
                replacementArtist = "藤井 風",
            ),
        )
    }

    @Test
    fun `collaboration subtitle uses only its song specific full credit`() {
        assertEquals(
            "Charlie Puth、宇多田ヒカル · 2022年",
            artistProfileSubtitleWithArtist(
                originalSubtitle = "Charlie Puth、Utada · 2022年",
                originalArtist = "Charlie Puth、Utada",
                replacementArtist = "Charlie Puth、宇多田ヒカル",
            ),
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

    @Test
    fun `playlist direct row refresh bypasses only playlist full rebuilds`() {
        assertTrue(
            shouldUsePlaylistDirectRowRefresh(
                strategy = InAppLibraryControllerBuildStrategy.PLAYLIST_FORCE_MODEL_BUILD,
                hasDirectPlaylistRow = true,
            )
        )
        assertFalse(
            shouldUsePlaylistDirectRowRefresh(
                strategy = InAppLibraryControllerBuildStrategy.PLAYLIST_FORCE_MODEL_BUILD,
                hasDirectPlaylistRow = false,
            )
        )
        assertFalse(
            shouldUsePlaylistDirectRowRefresh(
                strategy = InAppLibraryControllerBuildStrategy.ALBUM_SET_DATA,
                hasDirectPlaylistRow = true,
            )
        )
    }

    @Test
    fun `album controller rebuild is never bypassed without a playlist direct row`() {
        assertFalse(
            shouldUsePlaylistDirectRowRefresh(
                strategy = InAppLibraryControllerBuildStrategy.ALBUM_SET_DATA,
                hasDirectPlaylistRow = false,
            )
        )
    }

    @Test
    fun `fresh matching artwork cache fills only an empty replacement delegate`() {
        assertEquals(
            listOf("https://example.test/cover.jpg"),
            selectInAppArtworkContinuityUrls(
                currentUrls = emptyList(),
                cachedUrls = listOf("https://example.test/cover.jpg"),
                cachedAtUptimeMillis = 1_000L,
                nowUptimeMillis = 1_100L,
                ttlMillis = 1_000L,
            ),
        )
        assertNull(
            selectInAppArtworkContinuityUrls(
                currentUrls = listOf("https://example.test/current.jpg"),
                cachedUrls = listOf("https://example.test/cover.jpg"),
                cachedAtUptimeMillis = 1_000L,
                nowUptimeMillis = 1_100L,
                ttlMillis = 1_000L,
            )
        )
    }

    @Test
    fun `stale or clock-invalid artwork cache never replaces an empty delegate`() {
        assertNull(
            selectInAppArtworkContinuityUrls(
                currentUrls = emptyList(),
                cachedUrls = listOf("https://example.test/old.jpg"),
                cachedAtUptimeMillis = 1_000L,
                nowUptimeMillis = 2_001L,
                ttlMillis = 1_000L,
            )
        )
        assertNull(
            selectInAppArtworkContinuityUrls(
                currentUrls = emptyList(),
                cachedUrls = listOf("https://example.test/future.jpg"),
                cachedAtUptimeMillis = 2_000L,
                nowUptimeMillis = 1_000L,
                ttlMillis = 1_000L,
            )
        )
    }

    @Test
    fun `Listen Now artwork values normalize empty strings arrays and duplicates`() {
        assertEquals(emptyList<String>(), normalizedInAppArtworkValueUrls(null))
        assertEquals(emptyList<String>(), normalizedInAppArtworkValueUrls(emptyArray<String>()))
        assertEquals(
            listOf("https://example.test/cover.jpg"),
            normalizedInAppArtworkValueUrls(
                arrayOf(
                    "  https://example.test/cover.jpg  ",
                    null,
                    "",
                    "https://example.test/cover.jpg",
                )
            ),
        )
        assertEquals(
            listOf("https://example.test/single.jpg"),
            normalizedInAppArtworkValueUrls("  https://example.test/single.jpg "),
        )
    }

    @Test
    fun `Listen Now skips a repeated lookup only for the matching non-empty seeded value`() {
        assertTrue(
            shouldSkipInAppListenNowArtworkLookup(
                keyMatches = true,
                currentUrls = listOf("https://example.test/cover.jpg"),
                seededUrls = listOf("https://example.test/cover.jpg"),
            )
        )
        assertFalse(
            shouldSkipInAppListenNowArtworkLookup(
                keyMatches = false,
                currentUrls = listOf("https://example.test/cover.jpg"),
                seededUrls = listOf("https://example.test/cover.jpg"),
            )
        )
        assertFalse(
            shouldSkipInAppListenNowArtworkLookup(
                keyMatches = true,
                currentUrls = emptyList(),
                seededUrls = listOf("https://example.test/cover.jpg"),
            )
        )
        assertFalse(
            shouldSkipInAppListenNowArtworkLookup(
                keyMatches = true,
                currentUrls = listOf("https://example.test/current.jpg"),
                seededUrls = listOf("https://example.test/seeded.jpg"),
            )
        )
        assertFalse(
            shouldSkipInAppListenNowArtworkLookup(
                keyMatches = true,
                currentUrls = listOf("https://example.test/cover.jpg"),
                seededUrls = emptyList(),
            )
        )
    }

    @Test
    fun `Listen Now keeps the builder identity across the delegate id namespace change`() {
        val builderKey = InAppListenNowArtworkContinuityKey(
            id = "l.AgkTCQ8",
            persistentId = 7_598_459_202_544_610_309L,
            contentType = 3,
            artworkIdentity = "shared-artwork-token",
        )
        val delegateKey = builderKey.copy(id = "1722205323")

        assertEquals(
            builderKey,
            preferredInAppListenNowArtworkKey(
                builderKey = builderKey,
                delegateKey = delegateKey,
            ),
        )
        assertEquals(
            delegateKey,
            preferredInAppListenNowArtworkKey(
                builderKey = null,
                delegateKey = delegateKey,
            ),
        )
    }

    @Test
    fun `Listen Now maps a local library id only through the exact card LiveData`() {
        val liveData = Any()
        val builderKey = InAppListenNowArtworkContinuityKey(
            id = "l.AgkTCQ8",
            persistentId = 7_598_459_202_544_610_309L,
            contentType = 3,
            artworkIdentity = "shared-artwork-token",
        )

        assertEquals(
            "1722205323",
            listenNowCatalogIdForExactCard(
                builderLiveData = liveData,
                delegateLiveData = liveData,
                builderKey = builderKey,
                delegateKey = builderKey.copy(id = "1722205323"),
            ),
        )
    }

    @Test
    fun `Listen Now rejects a catalog mapping from another card instance`() {
        val builderKey = InAppListenNowArtworkContinuityKey(
            id = "l.AgkTCQ8",
            persistentId = 7_598_459_202_544_610_309L,
            contentType = 3,
            artworkIdentity = "shared-artwork-token",
        )

        assertNull(
            listenNowCatalogIdForExactCard(
                builderLiveData = Any(),
                delegateLiveData = Any(),
                builderKey = builderKey,
                delegateKey = builderKey.copy(id = "1722205323"),
            )
        )
    }

    @Test
    fun `Listen Now rejects mismatched card identity and conflicting catalog ids`() {
        val liveData = Any()
        val builderKey = InAppListenNowArtworkContinuityKey(
            id = "l.AgkTCQ8",
            persistentId = 7_598_459_202_544_610_309L,
            contentType = 3,
            artworkIdentity = "shared-artwork-token",
        )
        val delegateKey = builderKey.copy(id = "1722205323")

        assertNull(
            listenNowCatalogIdForExactCard(
                builderLiveData = liveData,
                delegateLiveData = liveData,
                builderKey = null,
                delegateKey = delegateKey,
            )
        )
        assertNull(
            listenNowCatalogIdForExactCard(
                builderLiveData = liveData,
                delegateLiveData = liveData,
                builderKey = builderKey,
                delegateKey = null,
            )
        )
        listOf(
            delegateKey.copy(persistentId = delegateKey.persistentId + 1L),
            delegateKey.copy(contentType = 4),
            delegateKey.copy(artworkIdentity = "another-artwork-token"),
            delegateKey.copy(id = "l.not-a-catalog-id"),
        ).forEach { mismatchedDelegate ->
            assertNull(
                listenNowCatalogIdForExactCard(
                    builderLiveData = liveData,
                    delegateLiveData = liveData,
                    builderKey = builderKey,
                    delegateKey = mismatchedDelegate,
                )
            )
        }
        assertNull(
            listenNowCatalogIdForExactCard(
                builderLiveData = liveData,
                delegateLiveData = liveData,
                builderKey = builderKey.copy(id = "1519740112"),
                delegateKey = delegateKey,
            )
        )
    }

    private class FakeMediaPlayer(private val position: Long) {
        fun getCurrentPosition(): Long = position
    }

    private class FakeLibraryAttributes(
        private val artistId: String,
        private val artistStoreId: String,
    ) {
        fun getArtistId(): String = artistId

        fun getArtistStoreId(): String = artistStoreId
    }
}
