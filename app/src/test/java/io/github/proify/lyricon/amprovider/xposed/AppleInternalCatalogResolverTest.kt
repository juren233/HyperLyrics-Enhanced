package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleInternalCatalogResolverTest {

    @Test
    fun `detects coroutine suspension across classloader boundaries by enum name`() {
        assertTrue(
            AppleInternalCatalogResolver.isCoroutineSuspended(
                TestCoroutineState.COROUTINE_SUSPENDED,
            )
        )
        assertFalse(
            AppleInternalCatalogResolver.isCoroutineSuspended(
                TestCoroutineState.COMPLETED,
            )
        )
        assertFalse(AppleInternalCatalogResolver.isCoroutineSuspended(null))
    }

    @Test
    fun `visible metadata requests leapfrog queued page and background work`() {
        val priorities = listOf(
            AppleInternalCatalogResolver.RequestPriority.BACKGROUND,
            AppleInternalCatalogResolver.RequestPriority.ACTIVE_PAGE,
            AppleInternalCatalogResolver.RequestPriority.VISIBLE,
            AppleInternalCatalogResolver.RequestPriority.VISIBLE,
        )

        assertEquals(
            2,
            AppleInternalCatalogResolver.selectNextRequestIndex(priorities),
        )
    }

    @Test
    fun `request promotion never lowers an existing priority`() {
        assertEquals(
            AppleInternalCatalogResolver.RequestPriority.VISIBLE,
            AppleInternalCatalogResolver.higherPriority(
                AppleInternalCatalogResolver.RequestPriority.VISIBLE,
                AppleInternalCatalogResolver.RequestPriority.BACKGROUND,
            ),
        )
        assertEquals(
            AppleInternalCatalogResolver.RequestPriority.ACTIVE_PAGE,
            AppleInternalCatalogResolver.higherPriority(
                AppleInternalCatalogResolver.RequestPriority.BACKGROUND,
                AppleInternalCatalogResolver.RequestPriority.ACTIVE_PAGE,
            ),
        )
    }

    @Test
    fun `new request scope demotes media outside the current page`() {
        val visible = setOf("2")
        val activePage = setOf("3")

        assertEquals(
            AppleInternalCatalogResolver.RequestPriority.BACKGROUND,
            AppleInternalCatalogResolver.priorityForRequestScope(
                mediaId = "1",
                visibleMediaIds = visible,
                activePageMediaIds = activePage,
            ),
        )
        assertEquals(
            AppleInternalCatalogResolver.RequestPriority.VISIBLE,
            AppleInternalCatalogResolver.priorityForRequestScope(
                mediaId = "2",
                visibleMediaIds = visible,
                activePageMediaIds = activePage,
            ),
        )
        assertEquals(
            AppleInternalCatalogResolver.RequestPriority.ACTIVE_PAGE,
            AppleInternalCatalogResolver.priorityForRequestScope(
                mediaId = "3",
                visibleMediaIds = visible,
                activePageMediaIds = activePage,
            ),
        )
    }

    private enum class TestCoroutineState {
        COROUTINE_SUSPENDED,
        COMPLETED,
    }

    @Test
    fun `background work leaves resolver capacity for visible requests`() {
        assertFalse(
            AppleInternalCatalogResolver.canStartRequest(
                priority = AppleInternalCatalogResolver.RequestPriority.BACKGROUND,
                totalRunning = 2,
                backgroundRunning = 2,
                maxRunning = 3,
                maxBackgroundRunning = 2,
            )
        )
        assertTrue(
            AppleInternalCatalogResolver.canStartRequest(
                priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
                totalRunning = 2,
                backgroundRunning = 2,
                maxRunning = 3,
                maxBackgroundRunning = 2,
            )
        )
    }

    @Test
    fun `does not cache an empty catalog identity before complete playback data arrives`() {
        assertFalse(
            AppleInternalCatalogResolver.shouldCacheCatalogIdentity(
                isrc = null,
                genres = emptyList(),
            )
        )
        assertTrue(
            AppleInternalCatalogResolver.shouldCacheCatalogIdentity(
                isrc = "TWA451600011",
                genres = emptyList(),
            )
        )
        assertTrue(
            AppleInternalCatalogResolver.shouldCacheCatalogIdentity(
                isrc = null,
                genres = listOf("Mandopop"),
            )
        )
        assertTrue(
            AppleInternalCatalogResolver.shouldRetryEmptyCatalogIdentity(
                mediaId = "1158763998",
                title = "派對動物",
                artist = "Mayday",
                genre = null,
                isrc = null,
                catalogGenres = emptyList(),
            )
        )
        assertFalse(
            AppleInternalCatalogResolver.shouldRetryEmptyCatalogIdentity(
                mediaId = "1158763998",
                title = "派對動物",
                artist = "五月天",
                genre = null,
                isrc = null,
                catalogGenres = emptyList(),
            )
        )
    }

    @Test
    fun `maps localized content types to catalog paths`() {
        assertEquals(
            "songs",
            AppleInternalCatalogResolver.LocalizedEntityType.SONG.path,
        )
        assertEquals(
            "albums",
            AppleInternalCatalogResolver.LocalizedEntityType.ALBUM.path,
        )
        assertEquals(
            "artists",
            AppleInternalCatalogResolver.LocalizedEntityType.ARTIST.path,
        )
    }

    @Test
    fun `metadata cache keys are isolated by entity type locale and storefront selection`() {
        val song = AppleInternalCatalogResolver.localizedMetadataCacheKey(
            selection = 1,
            entityType = AppleInternalCatalogResolver.LocalizedEntityType.SONG,
            mediaId = "123",
        )
        val album = AppleInternalCatalogResolver.localizedMetadataCacheKey(
            selection = 1,
            entityType = AppleInternalCatalogResolver.LocalizedEntityType.ALBUM,
            mediaId = "123",
        )
        val otherSelection = AppleInternalCatalogResolver.localizedMetadataCacheKey(
            selection = 2,
            entityType = AppleInternalCatalogResolver.LocalizedEntityType.SONG,
            mediaId = "123",
        )
        val chineseOriginal = AppleInternalCatalogResolver.originalEntityCacheKey(
            entityType = AppleInternalCatalogResolver.LocalizedEntityType.SONG,
            language = "zh-Hans-CN",
            mediaId = "123",
        )
        val japaneseOriginal = AppleInternalCatalogResolver.originalEntityCacheKey(
            entityType = AppleInternalCatalogResolver.LocalizedEntityType.SONG,
            language = "ja-JP",
            mediaId = "123",
        )

        assertTrue(song != album)
        assertTrue(song != otherSelection)
        assertTrue(chineseOriginal != japaneseOriginal)
        assertTrue(
            AppleInternalCatalogResolver.storefrontForContentUiLanguage(1) !=
                AppleInternalCatalogResolver.storefrontForContentUiLanguage(2)
        )
    }

    @Test
    fun `original entity cache probes direct and compatibility IDs in stable precedence order`() {
        val keys = AppleInternalCatalogResolver.originalEntityCacheLookupKeys(
            entityType = AppleInternalCatalogResolver.LocalizedEntityType.ALBUM,
            mediaId = "200",
            lookupIds = listOf("201", "200"),
            languages = listOf("zh-Hans-CN"),
        )

        assertEquals(
            listOf(
                "V2:ALBUM:200",
                "V2:ALBUM:201",
                "V2:ALBUM:zh-Hans-CN:200",
                "V2:ALBUM:zh-CN:200",
                "V2:ALBUM:zh-cn:200",
                "V2:ALBUM:zh-hans-cn:200",
                "V2:ALBUM:zh-Hans-CN:201",
                "V2:ALBUM:zh-CN:201",
                "V2:ALBUM:zh-cn:201",
                "V2:ALBUM:zh-hans-cn:201",
            ),
            keys,
        )
    }

    @Test
    fun `artist alias cache keys are routed separately from entity metadata`() {
        assertTrue(
            AppleInternalCatalogResolver.isLocalizedArtistAliasCacheKey(
                "2:ARTIST_ALIAS:V2:id:18756224",
            )
        )
        assertFalse(
            AppleInternalCatalogResolver.isLocalizedArtistAliasCacheKey(
                "2:ARTIST:18756224",
            )
        )
        assertFalse(
            AppleInternalCatalogResolver.isLocalizedArtistAliasCacheKey(
                "2:SONG:1542953977",
            )
        )
    }

    @Test
    fun `rejects the account current language as an original storefront`() {
        assertEquals(
            null,
            AppleInternalCatalogResolver.storefrontForOriginalLanguage("current"),
        )
        assertEquals(
            null,
            AppleInternalCatalogResolver.supportedOriginalLanguageOrNull("current"),
        )
        assertEquals(
            "cn",
            AppleInternalCatalogResolver.storefrontForOriginalLanguage("zh-TW"),
        )
    }

    @Test
    fun `does not persist current account aliases as original metadata`() {
        val currentAlias = AppleInternalCatalogResolver.Alias(
            title = "陶喆同名专辑",
            artist = "David Tao",
            language = "current",
        )
        val originalAlias = currentAlias.copy(
            artist = "陶喆",
            language = "zh-Hans-CN",
        )

        assertEquals(
            null,
            AppleInternalCatalogResolver.canonicalCachedOriginalAlias(currentAlias),
        )
        assertEquals(
            emptyList<AppleInternalCatalogResolver.Alias>(),
            AppleInternalCatalogResolver.regionalOriginalAliases(
                aliases = listOf(currentAlias),
                languages = emptyList(),
            ),
        )
        assertEquals(
            listOf(originalAlias),
            AppleInternalCatalogResolver.regionalOriginalAliases(
                aliases = listOf(currentAlias, originalAlias),
                languages = listOf("zh-Hans-CN"),
            ),
        )
    }

    @Test
    fun `keeps verified songs separate from in app entity candidates`() {
        val mediaId = "255921025"

        assertTrue(
            AppleInternalCatalogResolver.originalSongCacheKey(mediaId) !=
                AppleInternalCatalogResolver.originalDirectEntityCacheKey(
                    AppleInternalCatalogResolver.LocalizedEntityType.SONG,
                    mediaId,
                )
        )
    }

    @Test
    fun `original metadata cache keys use the post hook pollution schema`() {
        assertTrue(
            AppleInternalCatalogResolver.originalSongCacheKey("1542953977")
                .startsWith("V2:"),
        )
        assertTrue(
            AppleInternalCatalogResolver.originalDirectEntityCacheKey(
                AppleInternalCatalogResolver.LocalizedEntityType.ARTIST,
                "18756224",
            ).startsWith("V2:"),
        )
        assertTrue(
            AppleInternalCatalogResolver.originalEntityCacheKey(
                entityType = AppleInternalCatalogResolver.LocalizedEntityType.ARTIST,
                language = "ja-JP",
                mediaId = "18756224",
            ).startsWith("V2:"),
        )
    }

    @Test
    fun `exact catalog id wins over former id candidates`() {
        val exact = AppleInternalCatalogResolver.Alias(
            "我不难过",
            "孙燕姿",
            "zh-Hans-CN",
            "未完成",
        )
        val wrongFormerId = AppleInternalCatalogResolver.Alias(
            "I Am Fine",
            "孙燕姿",
            "zh-Hans-CN",
            "MADE FOR LOSER EMO限定",
        )

        assertEquals(
            exact,
            AppleInternalCatalogResolver.selectExactOriginalEntityAlias(
                mediaId = "255921025",
                lookupIds = listOf("other-id", "255921025"),
                resolved = mapOf(
                    "other-id" to wrongFormerId,
                    "255921025" to exact,
                ),
                sourceLanguage = "zh-Hans-CN",
            ),
        )
    }

    @Test
    fun `exact catalog id preserves a legitimate English original name`() {
        val exact = AppleInternalCatalogResolver.Alias(
            "English Title",
            "English Artist",
            "zh-Hans-CN",
            "English Album",
        )

        assertEquals(
            exact,
            AppleInternalCatalogResolver.selectExactOriginalEntityAlias(
                mediaId = "exact-id",
                lookupIds = listOf("exact-id"),
                resolved = mapOf("exact-id" to exact),
                sourceLanguage = "zh-Hans-CN",
            ),
        )
    }

    @Test
    fun `configured storefront exact identity can complete simplified Chinese original lookup`() {
        val exact = AppleInternalCatalogResolver.Alias(
            "海阔天空",
            "邓紫棋",
            "zh-CN",
            "T.I.M.E. - EP",
        )
        val unrelated = AppleInternalCatalogResolver.Alias(
            "Infinite",
            "G.E.M.",
            "en-US",
            "T.I.M.E. - EP",
        )

        assertEquals(
            exact,
            AppleInternalCatalogResolver.selectExactIdentityAlias(
                aliases = listOf(unrelated, exact),
                sourceLanguage = "zh-Hans-CN",
            ),
        )
    }

    @Test
    fun `configured storefront identity does not confirm a different language`() {
        assertEquals(
            null,
            AppleInternalCatalogResolver.selectExactIdentityAlias(
                aliases = listOf(
                    AppleInternalCatalogResolver.Alias(
                        "Infinite",
                        "G.E.M.",
                        "en-US",
                        "T.I.M.E. - EP",
                    )
                ),
                sourceLanguage = "zh-Hans-CN",
            ),
        )
    }

    @Test
    fun `maps Apple genres to original language tags`() {
        assertEquals(
            listOf("ja-JP"),
            AppleInternalCatalogResolver.languageTagsForGenre("J-Pop")
        )
        assertEquals(
            listOf("ko-KR"),
            AppleInternalCatalogResolver.languageTagsForGenre("K-Pop")
        )
        assertEquals(
            listOf("zh-Hans-CN"),
            AppleInternalCatalogResolver.languageTagsForGenre("Mandopop")
        )
        assertEquals(
            listOf("zh-Hans-CN"),
            AppleInternalCatalogResolver.languageTagsForGenre("Cantopop")
        )
        assertEquals(
            listOf("zh-Hans-CN"),
            AppleInternalCatalogResolver.languageTagsForGenre("国语流行")
        )
        assertEquals(
            listOf("ja-JP"),
            AppleInternalCatalogResolver.languageTagsForGenre("日本流行")
        )
    }

    @Test
    fun `uses CJK ISRC countries to avoid unnecessary regional queries`() {
        assertEquals(
            listOf("ja-JP"),
            AppleInternalCatalogResolver.languageTagsForOriginalMetadata(
                genre = null,
                isrc = "JPPO02400480",
            )
        )
        assertEquals(
            listOf("ko-KR"),
            AppleInternalCatalogResolver.languageTagsForOriginalMetadata(
                genre = null,
                isrc = "KRA252400001",
            )
        )
        assertEquals(
            listOf("zh-Hans-CN"),
            AppleInternalCatalogResolver.languageTagsForOriginalMetadata(
                genre = null,
                isrc = "CNZ632400001",
            )
        )
        assertEquals(
            listOf("zh-Hans-CN"),
            AppleInternalCatalogResolver.languageTagsForOriginalMetadata(
                genre = null,
                isrc = "HKA612400001",
            )
        )
        assertEquals(
            listOf("zh-Hans-CN"),
            AppleInternalCatalogResolver.languageTagsForOriginalMetadata(
                genre = null,
                isrc = "TWA452400001",
            )
        )
        assertEquals(
            listOf("zh-Hans-CN"),
            AppleInternalCatalogResolver.languageTagsForOriginalMetadata(
                genre = null,
                isrc = "MOA612400001",
            )
        )
        assertEquals(
            emptyList<String>(),
            AppleInternalCatalogResolver.languageTagsForOriginalMetadata(
                genre = null,
                isrc = "USUM72400001",
            )
        )
    }

    @Test
    fun `prefers catalog genre over distributor ISRC country`() {
        assertEquals(
            listOf("zh-Hans-CN"),
            AppleInternalCatalogResolver.languageTagsForOriginalMetadata(
                null,
                listOf("Mandopop"),
                "FR10S2241109",
            )
        )
        assertEquals(
            listOf("zh-Hans-CN"),
            AppleInternalCatalogResolver.languageTagsForOriginalMetadata(
                null,
                listOf("Mandopop"),
                null,
            )
        )
        assertEquals(
            listOf("zh-Hans-CN"),
            AppleInternalCatalogResolver.languageTagsForOriginalMetadata(
                "Mandopop",
                emptyList(),
                "JPPO02400480",
            )
        )
    }

    @Test
    fun `uses ISRC only when catalog genre has no regional signal`() {
        assertEquals(
            listOf("ko-KR"),
            AppleInternalCatalogResolver.languageTagsForOriginalMetadata(
                "Pop",
                listOf("Pop"),
                "KRA252400001",
            )
        )
    }

    @Test
    fun `selects original script metadata`() {
        val selected = AppleInternalCatalogResolver.selectOriginalAlias(
            variants = listOf(
                AppleInternalCatalogResolver.Alias("Kawakiwoameku", "Minami", "en-US"),
                AppleInternalCatalogResolver.Alias("カワキヲアメク", "美波", "ja-JP")
            ),
            localizedTitle = "Kawakiwoameku",
            localizedArtist = "Minami"
        )

        assertEquals("カワキヲアメク", selected?.title)
        assertEquals("美波", selected?.artist)
    }

    @Test
    fun `canonicalizes all Chinese original locale tags to simplified Chinese`() {
        listOf(
            "zh-Hans-CN",
            "zh-Hant-HK",
            "zh-Hant-TW",
            "zh-HK",
            "zh-MO",
            "zh-TW",
            "zh-CN",
        ).forEach { language ->
            assertEquals(
                "zh-Hans-CN",
                AppleInternalCatalogResolver.canonicalOriginalLanguage(language),
            )
        }
    }

    @Test
    fun `rejects all English aliases for a Chinese original source`() {
        assertFalse(
            AppleInternalCatalogResolver.isAcceptableOriginalAlias(
                AppleInternalCatalogResolver.Alias("HANA", "Masshiro", "zh-Hans-CN"),
                "zh-Hans-CN",
            )
        )
        assertTrue(
            AppleInternalCatalogResolver.isAcceptableOriginalAlias(
                AppleInternalCatalogResolver.Alias("I Am Fine", "孙燕姿", "zh-Hans-CN"),
                "zh-Hans-CN",
            )
        )
        assertTrue(
            AppleInternalCatalogResolver.isAcceptableOriginalAlias(
                AppleInternalCatalogResolver.Alias("白色", "HANA", "zh-Hans-CN"),
                "zh-Hans-CN",
            )
        )
    }

    @Test
    fun `invalidates traditional Chinese cached aliases`() {
        assertEquals(
            null,
            AppleInternalCatalogResolver.canonicalCachedOriginalAlias(
                AppleInternalCatalogResolver.Alias("純白", "HANA", "zh-Hant-TW"),
            ),
        )
        assertEquals(
            "zh-Hans-CN",
            AppleInternalCatalogResolver.canonicalCachedOriginalAlias(
                AppleInternalCatalogResolver.Alias("纯白", "HANA", "zh-CN"),
            )?.language,
        )
    }

    @Test
    fun `prefers original script title over localized artist only`() {
        val selected = AppleInternalCatalogResolver.selectOriginalAlias(
            variants = listOf(
                AppleInternalCatalogResolver.Alias(
                    "Michi Teyu Ku (Overflowing)",
                    "藤井风",
                    "zh-Hans-CN"
                ),
                AppleInternalCatalogResolver.Alias("満ちてゆく", "藤井 風", "ja-JP")
            ),
            localizedTitle = "Michi Teyu Ku (Overflowing)",
            localizedArtist = "Fujii Kaze"
        )

        assertEquals("満ちてゆく", selected?.title)
        assertEquals("藤井 風", selected?.artist)
    }

    @Test
    fun `does not treat localized title with original artist as strong alias`() {
        val alias = AppleInternalCatalogResolver.Alias(
            "Michi Teyu Ku (Overflowing)",
            "藤井风",
            "zh-Hans-CN"
        )

        assertEquals(
            false,
            AppleInternalCatalogResolver.isOriginalTitle(
                alias,
                "Michi Teyu Ku (Overflowing)"
            )
        )
    }

    @Test
    fun `rejects a romanized solo title when only the artist was localized`() {
        val alias = AppleInternalCatalogResolver.Alias(
            "Hana",
            "藤井 風",
            "ja-JP",
            "Pre: Prema",
        )

        assertEquals(
            null,
            AppleInternalCatalogResolver.selectOriginalAlias(
                variants = listOf(alias),
                localizedTitle = "Hana",
                localizedArtist = "Fujii Kaze",
            ),
        )
        assertFalse(
            AppleInternalCatalogResolver.isConfidentOriginalSongAlias(
                alias = alias,
                localizedTitle = "Hana",
                localizedArtist = "Fujii Kaze",
            )
        )
        assertFalse(
            AppleInternalCatalogResolver.isReusableOriginalSongAlias(
                alias = alias,
                localizedTitle = "Hana",
                localizedArtist = "Fujii Kaze",
            )
        )
    }

    @Test
    fun `rejects collaboration aliases that only localize the artist credit`() {
        val selected = AppleInternalCatalogResolver.selectOriginalAlias(
            variants = listOf(
                AppleInternalCatalogResolver.Alias(
                    "Same English Title",
                    "アメリカ人歌手, 日本人歌手",
                    "ja-JP",
                )
            ),
            localizedTitle = "Same English Title",
            localizedArtist = "American Artist, Japanese Artist",
        )

        assertEquals(null, selected)
        assertFalse(
            AppleInternalCatalogResolver.isConfidentOriginalSongAlias(
                alias = AppleInternalCatalogResolver.Alias(
                    "Same English Title",
                    "アメリカ人歌手, 日本人歌手",
                    "ja-JP",
                ),
                localizedTitle = "Same English Title",
                localizedArtist = "American Artist, Japanese Artist",
            )
        )
        assertFalse(
            AppleInternalCatalogResolver.isReusableOriginalSongAlias(
                alias = AppleInternalCatalogResolver.Alias(
                    "Home",
                    "チャーリー・プース、宇多田ヒカル",
                    "ja-JP",
                ),
                localizedTitle = "Home",
                localizedArtist = "Charlie Puth、Utada",
            )
        )
    }

    @Test
    fun `keeps collaboration aliases when the title provides original script evidence`() {
        val selected = AppleInternalCatalogResolver.selectOriginalAlias(
            variants = listOf(
                AppleInternalCatalogResolver.Alias(
                    "日本語の原題",
                    "American Artist, 日本人歌手",
                    "ja-JP",
                )
            ),
            localizedTitle = "Romanized Title",
            localizedArtist = "American Artist, Japanese Artist",
        )

        assertEquals("日本語の原題", selected?.title)
        assertTrue(
            AppleInternalCatalogResolver.isConfidentOriginalSongAlias(
                alias = requireNotNull(selected),
                localizedTitle = "Romanized Title",
                localizedArtist = "American Artist, Japanese Artist",
            )
        )
    }

    @Test
    fun `resolves romanized title even when artist already uses original script`() {
        val metadata = MediaMetadataCache.Metadata(
            id = "1882935962",
            title = "Michi Teyu Ku (Overflowing)",
            artist = "藤井风",
            genre = "J-Pop",
            duration = 315_000L,
            queueId = 1L
        )

        assertTrue(AppleInternalCatalogResolver.shouldResolve(metadata))
    }

    @Test
    fun `probes a catalog song when queue genre is missing`() {
        val metadata = MediaMetadataCache.Metadata(
            id = "1882935962",
            title = "Michi Teyu Ku (Overflowing)",
            artist = "Fujii Kaze",
            genre = null,
            duration = 315_000L,
            queueId = 1L,
        )

        assertTrue(AppleInternalCatalogResolver.shouldResolve(metadata))
    }

    @Test
    fun `probes original script title to share its confirmed region with album and artist`() {
        val metadata = MediaMetadataCache.Metadata(
            id = "1882935962",
            title = "満ちてゆく",
            artist = "藤井 風",
            genre = "J-Pop",
            duration = 315_000L,
            queueId = 1L
        )

        assertTrue(AppleInternalCatalogResolver.shouldResolve(metadata))
    }

    @Test
    fun `skips catalog lookup for non catalog media id`() {
        val metadata = MediaMetadataCache.Metadata(
            id = "local-track",
            title = "Michi Teyu Ku (Overflowing)",
            artist = "藤井风",
            genre = "J-Pop",
            duration = 315_000L,
            queueId = 1L
        )

        assertFalse(AppleInternalCatalogResolver.shouldResolve(metadata))
    }

    @Test
    fun `maps content UI language selections to storefronts`() {
        assertEquals(
            "cn",
            AppleInternalCatalogResolver.storefrontForContentUiLanguage(
                com.juren233.hyperlyricsenhanced.common.RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_CN
            )
        )
        assertEquals(
            "us",
            AppleInternalCatalogResolver.storefrontForContentUiLanguage(
                com.juren233.hyperlyricsenhanced.common.RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_US
            )
        )
        assertEquals(
            "kr",
            AppleInternalCatalogResolver.storefrontForContentUiLanguage(
                com.juren233.hyperlyricsenhanced.common.RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_KO_KR
            )
        )
        assertEquals(
            "jp",
            AppleInternalCatalogResolver.storefrontForContentUiLanguage(
                com.juren233.hyperlyricsenhanced.common.RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_JA_JP
            )
        )
        assertEquals(
            null,
            AppleInternalCatalogResolver.storefrontForContentUiLanguage(
                com.juren233.hyperlyricsenhanced.common.RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_NONE
            )
        )
    }

    @Test
    fun `maps content UI language selections to Apple catalog locale tags`() {
        val constants = com.juren233.hyperlyricsenhanced.common.RootConstants

        assertEquals(
            "zh-CN",
            AppleInternalCatalogResolver.languageTagForContentUiLanguage(
                constants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_CN
            )
        )
        assertEquals(
            "zh-Hans",
            AppleInternalCatalogResolver.languageTagForContentUiLanguage(
                constants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_US
            )
        )
        assertEquals(
            listOf("zh-Hans", "zh-CN"),
            AppleInternalCatalogResolver.languageTagsForContentUiLanguage(
                constants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_US
            )
        )
        assertEquals(
            "zh-HK",
            AppleInternalCatalogResolver.languageTagForContentUiLanguage(
                constants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANT_HK
            )
        )
        assertEquals(
            "zh-TW",
            AppleInternalCatalogResolver.languageTagForContentUiLanguage(
                constants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANT_TW
            )
        )
        assertEquals(
            "ko-KR",
            AppleInternalCatalogResolver.languageTagForContentUiLanguage(
                constants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_KO_KR
            )
        )
        assertEquals(
            "ja-JP",
            AppleInternalCatalogResolver.languageTagForContentUiLanguage(
                constants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_JA_JP
            )
        )
        assertEquals(
            null,
            AppleInternalCatalogResolver.languageTagForContentUiLanguage(
                constants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_NONE
            )
        )
    }

    @Test
    fun `rewrites Apple storefront header id and preserves account suffix`() {
        assertEquals(
            "143462-1,29",
            AppleInternalCatalogResolver.localizedStorefrontHeaderValue(
                storefront = "jp",
                currentValue = "143441-1,29",
            ),
        )
        assertEquals(
            "143465",
            AppleInternalCatalogResolver.localizedStorefrontHeaderValue(
                storefront = "cn",
                currentValue = null,
            ),
        )
        assertEquals(
            null,
            AppleInternalCatalogResolver.localizedStorefrontHeaderValue(
                storefront = "unknown",
                currentValue = "143441-1,29",
            ),
        )
    }

    @Test
    fun `extracts storefront from Apple content paths`() {
        assertEquals(
            "in",
            AppleInternalCatalogResolver.storefrontFromContentPath(
                listOf("v1", "catalog", "in", "playlists", "playlist-id")
            )
        )
        assertEquals(
            "us",
            AppleInternalCatalogResolver.storefrontFromContentPath(
                listOf("v1", "editorial", "us", "groupings")
            )
        )
        assertEquals(
            null,
            AppleInternalCatalogResolver.storefrontFromContentPath(
                listOf("v1", "me", "recommendations")
            )
        )
    }

    @Test
    fun `keeps radio playback requests on the account storefront`() {
        assertTrue(
            AppleInternalCatalogResolver.isAccountScopedPlaybackPath(
                listOf("v1", "catalog", "jp", "stations", "ra.123")
            )
        )
        assertTrue(
            AppleInternalCatalogResolver.isAccountScopedPlaybackPath(
                listOf("v1", "me", "radio", "recent")
            )
        )
        assertFalse(
            AppleInternalCatalogResolver.isAccountScopedPlaybackPath(
                listOf("v1", "catalog", "jp", "albums", "123")
            )
        )
    }

    @Test
    fun `prefers localized artist relationship over stale song artist snapshot`() {
        assertEquals(
            "梁静茹",
            AppleInternalCatalogResolver.selectLocalizedArtistName(
                attributeArtist = "양정여",
                relationshipArtists = listOf("梁静茹"),
                language = "zh-CN",
            )
        )
        assertEquals(
            "周杰伦、梁静茹",
            AppleInternalCatalogResolver.selectLocalizedArtistName(
                attributeArtist = "Jay Chou & Fish Leong",
                relationshipArtists = listOf("周杰伦", "梁静茹"),
                language = "zh-CN",
            )
        )
    }

    @Test
    fun `normalizes account artist names for cross song cache reuse`() {
        assertEquals(
            "karen mok",
            AppleInternalCatalogResolver.normalizedArtistNameKey("  Karen   MOK "),
        )
        assertEquals(
            "周杰伦、梁静茹",
            AppleInternalCatalogResolver.normalizedArtistNameKey("周杰伦、梁静茹"),
        )
    }

    @Test
    fun `keeps original album when the song alias is not confident`() {
        val rejectedAlias = AppleInternalCatalogResolver.Alias(
            title = "Reply",
            artist = "KZ, Cosmic Princess Kaguya!, かぐや(cv.夏吉ゆうこ)",
            language = "ja-JP",
            album = "超かぐや姫！",
        )

        assertEquals(
            "超かぐや姫！",
            originalAlbumFromResolution(
                alias = null,
                acceptableResults = listOf(rejectedAlias),
            )
        )
    }

    @Test
    fun `confident alias album wins over other resolved albums`() {
        val confident = AppleInternalCatalogResolver.Alias(
            title = "カワキヲアメク",
            artist = "美波",
            language = "ja-JP",
            album = "カワキヲアメク",
        )

        assertEquals(
            "カワキヲアメク",
            originalAlbumFromResolution(
                alias = confident,
                acceptableResults = listOf(
                    confident,
                    AppleInternalCatalogResolver.Alias(
                        title = "Crying for Rain",
                        artist = "Minami",
                        language = "en-US",
                        album = "Crying for Rain",
                    ),
                ),
            )
        )
    }

    @Test
    fun `returns null when neither alias nor results carry an album`() {
        assertEquals(
            null,
            originalAlbumFromResolution(
                alias = null,
                acceptableResults = listOf(
                    AppleInternalCatalogResolver.Alias(
                        title = "Reply",
                        artist = "KZ",
                        language = "ja-JP",
                        album = " ",
                    ),
                ),
            )
        )
    }
}
