package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleInternalCatalogResolverTest {

    @Test
    fun `detects coroutine suspension across classloader boundaries by enum name`() {
        assertTrue(
            isCoroutineSuspended(
                TestCoroutineState.COROUTINE_SUSPENDED,
            )
        )
        assertFalse(
            isCoroutineSuspended(
                TestCoroutineState.COMPLETED,
            )
        )
        assertFalse(isCoroutineSuspended(null))
    }

    @Test
    fun `visible metadata requests leapfrog queued page and background work`() {
        val priorities = listOf(
            RequestPriority.BACKGROUND,
            RequestPriority.ACTIVE_PAGE,
            RequestPriority.VISIBLE,
            RequestPriority.VISIBLE,
        )

        assertEquals(
            2,
            selectNextRequestIndex(priorities),
        )
    }

    @Test
    fun `request promotion never lowers an existing priority`() {
        assertEquals(
            RequestPriority.VISIBLE,
            higherPriority(
                RequestPriority.VISIBLE,
                RequestPriority.BACKGROUND,
            ),
        )
        assertEquals(
            RequestPriority.ACTIVE_PAGE,
            higherPriority(
                RequestPriority.BACKGROUND,
                RequestPriority.ACTIVE_PAGE,
            ),
        )
    }

    @Test
    fun `new request scope demotes media outside the current page`() {
        val visible = setOf("2")
        val activePage = setOf("3")

        assertEquals(
            RequestPriority.BACKGROUND,
            priorityForRequestScope(
                mediaId = "1",
                visibleMediaIds = visible,
                activePageMediaIds = activePage,
            ),
        )
        assertEquals(
            RequestPriority.VISIBLE,
            priorityForRequestScope(
                mediaId = "2",
                visibleMediaIds = visible,
                activePageMediaIds = activePage,
            ),
        )
        assertEquals(
            RequestPriority.ACTIVE_PAGE,
            priorityForRequestScope(
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
            canStartRequest(
                priority = RequestPriority.BACKGROUND,
                totalRunning = 2,
                backgroundRunning = 2,
                maxRunning = 3,
                maxBackgroundRunning = 2,
            )
        )
        assertTrue(
            canStartRequest(
                priority = RequestPriority.VISIBLE,
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
            shouldCacheCatalogIdentity(
                isrc = null,
                genres = emptyList(),
            )
        )
        assertTrue(
            shouldCacheCatalogIdentity(
                isrc = "TWA451600011",
                genres = emptyList(),
            )
        )
        assertTrue(
            shouldCacheCatalogIdentity(
                isrc = null,
                genres = listOf("Mandopop"),
            )
        )
        assertTrue(
            shouldRetryEmptyCatalogIdentity(
                mediaId = "1158763998",
                title = "派對動物",
                artist = "Mayday",
                genre = null,
                isrc = null,
                catalogGenres = emptyList(),
            )
        )
        assertFalse(
            shouldRetryEmptyCatalogIdentity(
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
            LocalizedEntityType.SONG.path,
        )
        assertEquals(
            "albums",
            LocalizedEntityType.ALBUM.path,
        )
        assertEquals(
            "artists",
            LocalizedEntityType.ARTIST.path,
        )
    }

    @Test
    fun `metadata cache keys are isolated by entity type locale and storefront selection`() {
        val song = localizedMetadataCacheKey(
            selection = 1,
            entityType = LocalizedEntityType.SONG,
            mediaId = "123",
        )
        val album = localizedMetadataCacheKey(
            selection = 1,
            entityType = LocalizedEntityType.ALBUM,
            mediaId = "123",
        )
        val otherSelection = localizedMetadataCacheKey(
            selection = 2,
            entityType = LocalizedEntityType.SONG,
            mediaId = "123",
        )
        val chineseOriginal = originalEntityCacheKey(
            entityType = LocalizedEntityType.SONG,
            language = "zh-Hans-CN",
            mediaId = "123",
        )
        val japaneseOriginal = originalEntityCacheKey(
            entityType = LocalizedEntityType.SONG,
            language = "ja-JP",
            mediaId = "123",
        )

        assertTrue(song != album)
        assertTrue(song != otherSelection)
        assertTrue(chineseOriginal != japaneseOriginal)
        assertTrue(
            storefrontForContentUiLanguage(1) !=
                storefrontForContentUiLanguage(2)
        )
    }

    @Test
    fun `original entity cache probes direct and compatibility IDs in stable precedence order`() {
        val keys = originalEntityCacheLookupKeys(
            entityType = LocalizedEntityType.ALBUM,
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
            isLocalizedArtistAliasCacheKey(
                "2:ARTIST_ALIAS:V2:id:18756224",
            )
        )
        assertFalse(
            isLocalizedArtistAliasCacheKey(
                "2:ARTIST:18756224",
            )
        )
        assertFalse(
            isLocalizedArtistAliasCacheKey(
                "2:SONG:1542953977",
            )
        )
    }

    @Test
    fun `rejects the account current language as an original storefront`() {
        assertEquals(
            null,
            storefrontForOriginalLanguage("current"),
        )
        assertEquals(
            null,
            supportedOriginalLanguageOrNull("current"),
        )
        assertEquals(
            "cn",
            storefrontForOriginalLanguage("zh-TW"),
        )
    }

    @Test
    fun `does not persist current account aliases as original metadata`() {
        val currentAlias = Alias(
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
            canonicalCachedOriginalAlias(currentAlias),
        )
        assertEquals(
            emptyList<Alias>(),
            regionalOriginalAliases(
                aliases = listOf(currentAlias),
                languages = emptyList(),
            ),
        )
        assertEquals(
            listOf(originalAlias),
            regionalOriginalAliases(
                aliases = listOf(currentAlias, originalAlias),
                languages = listOf("zh-Hans-CN"),
            ),
        )
    }

    @Test
    fun `keeps verified songs separate from in app entity candidates`() {
        val mediaId = "255921025"

        assertTrue(
            originalSongCacheKey(mediaId) !=
                originalDirectEntityCacheKey(
                    LocalizedEntityType.SONG,
                    mediaId,
                )
        )
    }

    @Test
    fun `original metadata cache keys use the post hook pollution schema`() {
        assertTrue(
            originalSongCacheKey("1542953977")
                .startsWith("V2:"),
        )
        assertTrue(
            originalDirectEntityCacheKey(
                LocalizedEntityType.ARTIST,
                "18756224",
            ).startsWith("V2:"),
        )
        assertTrue(
            originalEntityCacheKey(
                entityType = LocalizedEntityType.ARTIST,
                language = "ja-JP",
                mediaId = "18756224",
            ).startsWith("V2:"),
        )
    }

    @Test
    fun `exact catalog id wins over former id candidates`() {
        val exact = Alias(
            "我不难过",
            "孙燕姿",
            "zh-Hans-CN",
            "未完成",
        )
        val wrongFormerId = Alias(
            "I Am Fine",
            "孙燕姿",
            "zh-Hans-CN",
            "MADE FOR LOSER EMO限定",
        )

        assertEquals(
            exact,
            selectExactOriginalEntityAlias(
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
        val exact = Alias(
            "English Title",
            "English Artist",
            "zh-Hans-CN",
            "English Album",
        )

        assertEquals(
            exact,
            selectExactOriginalEntityAlias(
                mediaId = "exact-id",
                lookupIds = listOf("exact-id"),
                resolved = mapOf("exact-id" to exact),
                sourceLanguage = "zh-Hans-CN",
            ),
        )
    }

    @Test
    fun `configured storefront exact identity can complete simplified Chinese original lookup`() {
        val exact = Alias(
            "海阔天空",
            "邓紫棋",
            "zh-CN",
            "T.I.M.E. - EP",
        )
        val unrelated = Alias(
            "Infinite",
            "G.E.M.",
            "en-US",
            "T.I.M.E. - EP",
        )

        assertEquals(
            exact,
            selectExactIdentityAlias(
                aliases = listOf(unrelated, exact),
                sourceLanguage = "zh-Hans-CN",
            ),
        )
    }

    @Test
    fun `configured storefront identity does not confirm a different language`() {
        assertEquals(
            null,
            selectExactIdentityAlias(
                aliases = listOf(
                    Alias(
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
            languageTagsForGenre("J-Pop")
        )
        assertEquals(
            listOf("ko-KR"),
            languageTagsForGenre("K-Pop")
        )
        assertEquals(
            listOf("zh-Hans-CN"),
            languageTagsForGenre("Mandopop")
        )
        assertEquals(
            listOf("zh-Hans-CN"),
            languageTagsForGenre("Cantopop")
        )
        assertEquals(
            listOf("zh-Hans-CN"),
            languageTagsForGenre("国语流行")
        )
        assertEquals(
            listOf("ja-JP"),
            languageTagsForGenre("日本流行")
        )
    }

    @Test
    fun `uses CJK ISRC countries to avoid unnecessary regional queries`() {
        assertEquals(
            listOf("ja-JP"),
            languageTagsForOriginalMetadata(
                genre = null,
                isrc = "JPPO02400480",
            )
        )
        assertEquals(
            listOf("ko-KR"),
            languageTagsForOriginalMetadata(
                genre = null,
                isrc = "KRA252400001",
            )
        )
        assertEquals(
            listOf("zh-Hans-CN"),
            languageTagsForOriginalMetadata(
                genre = null,
                isrc = "CNZ632400001",
            )
        )
        assertEquals(
            listOf("zh-Hans-CN"),
            languageTagsForOriginalMetadata(
                genre = null,
                isrc = "HKA612400001",
            )
        )
        assertEquals(
            listOf("zh-Hans-CN"),
            languageTagsForOriginalMetadata(
                genre = null,
                isrc = "TWA452400001",
            )
        )
        assertEquals(
            listOf("zh-Hans-CN"),
            languageTagsForOriginalMetadata(
                genre = null,
                isrc = "MOA612400001",
            )
        )
        assertEquals(
            emptyList<String>(),
            languageTagsForOriginalMetadata(
                genre = null,
                isrc = "USUM72400001",
            )
        )
    }

    @Test
    fun `prefers catalog genre over distributor ISRC country`() {
        assertEquals(
            listOf("zh-Hans-CN"),
            languageTagsForOriginalMetadata(
                null,
                listOf("Mandopop"),
                "FR10S2241109",
            )
        )
        assertEquals(
            listOf("zh-Hans-CN"),
            languageTagsForOriginalMetadata(
                null,
                listOf("Mandopop"),
                null,
            )
        )
        assertEquals(
            listOf("zh-Hans-CN"),
            languageTagsForOriginalMetadata(
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
            languageTagsForOriginalMetadata(
                "Pop",
                listOf("Pop"),
                "KRA252400001",
            )
        )
    }

    @Test
    fun `selects original script metadata`() {
        val selected = selectOriginalAlias(
            variants = listOf(
                Alias("Kawakiwoameku", "Minami", "en-US"),
                Alias("カワキヲアメク", "美波", "ja-JP")
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
                canonicalOriginalLanguage(language),
            )
        }
    }

    @Test
    fun `rejects all English aliases for a Chinese original source`() {
        assertFalse(
            isAcceptableOriginalAlias(
                Alias("HANA", "Masshiro", "zh-Hans-CN"),
                "zh-Hans-CN",
            )
        )
        assertTrue(
            isAcceptableOriginalAlias(
                Alias("I Am Fine", "孙燕姿", "zh-Hans-CN"),
                "zh-Hans-CN",
            )
        )
        assertTrue(
            isAcceptableOriginalAlias(
                Alias("白色", "HANA", "zh-Hans-CN"),
                "zh-Hans-CN",
            )
        )
    }

    @Test
    fun `invalidates traditional Chinese cached aliases`() {
        assertEquals(
            null,
            canonicalCachedOriginalAlias(
                Alias("純白", "HANA", "zh-Hant-TW"),
            ),
        )
        assertEquals(
            "zh-Hans-CN",
            canonicalCachedOriginalAlias(
                Alias("纯白", "HANA", "zh-CN"),
            )?.language,
        )
    }

    @Test
    fun `prefers original script title over localized artist only`() {
        val selected = selectOriginalAlias(
            variants = listOf(
                Alias(
                    "Michi Teyu Ku (Overflowing)",
                    "藤井风",
                    "zh-Hans-CN"
                ),
                Alias("満ちてゆく", "藤井 風", "ja-JP")
            ),
            localizedTitle = "Michi Teyu Ku (Overflowing)",
            localizedArtist = "Fujii Kaze"
        )

        assertEquals("満ちてゆく", selected?.title)
        assertEquals("藤井 風", selected?.artist)
    }

    @Test
    fun `does not treat localized title with original artist as strong alias`() {
        val alias = Alias(
            "Michi Teyu Ku (Overflowing)",
            "藤井风",
            "zh-Hans-CN"
        )

        assertEquals(
            false,
            isOriginalTitle(
                alias,
                "Michi Teyu Ku (Overflowing)"
            )
        )
    }

    @Test
    fun `rejects a romanized solo title when only the artist was localized`() {
        val alias = Alias(
            "Hana",
            "藤井 風",
            "ja-JP",
            "Pre: Prema",
        )

        assertEquals(
            null,
            selectOriginalAlias(
                variants = listOf(alias),
                localizedTitle = "Hana",
                localizedArtist = "Fujii Kaze",
            ),
        )
        assertFalse(
            isConfidentOriginalSongAlias(
                alias = alias,
                localizedTitle = "Hana",
                localizedArtist = "Fujii Kaze",
            )
        )
        assertFalse(
            isReusableOriginalSongAlias(
                alias = alias,
                localizedTitle = "Hana",
                localizedArtist = "Fujii Kaze",
            )
        )
    }

    @Test
    fun `rejects collaboration aliases that only localize the artist credit`() {
        val selected = selectOriginalAlias(
            variants = listOf(
                Alias(
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
            isConfidentOriginalSongAlias(
                alias = Alias(
                    "Same English Title",
                    "アメリカ人歌手, 日本人歌手",
                    "ja-JP",
                ),
                localizedTitle = "Same English Title",
                localizedArtist = "American Artist, Japanese Artist",
            )
        )
        assertFalse(
            isReusableOriginalSongAlias(
                alias = Alias(
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
        val selected = selectOriginalAlias(
            variants = listOf(
                Alias(
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
            isConfidentOriginalSongAlias(
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

        assertTrue(shouldResolve(metadata))
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

        assertTrue(shouldResolve(metadata))
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

        assertTrue(shouldResolve(metadata))
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

        assertFalse(shouldResolve(metadata))
    }

    @Test
    fun `maps content UI language selections to storefronts`() {
        assertEquals(
            "cn",
            storefrontForContentUiLanguage(
                com.juren233.hyperlyricsenhanced.common.RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_CN
            )
        )
        assertEquals(
            "us",
            storefrontForContentUiLanguage(
                com.juren233.hyperlyricsenhanced.common.RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_US
            )
        )
        assertEquals(
            "kr",
            storefrontForContentUiLanguage(
                com.juren233.hyperlyricsenhanced.common.RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_KO_KR
            )
        )
        assertEquals(
            "jp",
            storefrontForContentUiLanguage(
                com.juren233.hyperlyricsenhanced.common.RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_JA_JP
            )
        )
        assertEquals(
            null,
            storefrontForContentUiLanguage(
                com.juren233.hyperlyricsenhanced.common.RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_NONE
            )
        )
    }

    @Test
    fun `maps content UI language selections to Apple catalog locale tags`() {
        val constants = com.juren233.hyperlyricsenhanced.common.RootConstants

        assertEquals(
            "zh-CN",
            languageTagForContentUiLanguage(
                constants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_CN
            )
        )
        assertEquals(
            "zh-Hans",
            languageTagForContentUiLanguage(
                constants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_US
            )
        )
        assertEquals(
            listOf("zh-Hans", "zh-CN"),
            languageTagsForContentUiLanguage(
                constants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_US
            )
        )
        assertEquals(
            "zh-HK",
            languageTagForContentUiLanguage(
                constants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANT_HK
            )
        )
        assertEquals(
            "zh-TW",
            languageTagForContentUiLanguage(
                constants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANT_TW
            )
        )
        assertEquals(
            "ko-KR",
            languageTagForContentUiLanguage(
                constants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_KO_KR
            )
        )
        assertEquals(
            "ja-JP",
            languageTagForContentUiLanguage(
                constants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_JA_JP
            )
        )
        assertEquals(
            null,
            languageTagForContentUiLanguage(
                constants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_NONE
            )
        )
    }

    @Test
    fun `rewrites Apple storefront header id and preserves account suffix`() {
        assertEquals(
            "143462-1,29",
            localizedStorefrontHeaderValue(
                storefront = "jp",
                currentValue = "143441-1,29",
            ),
        )
        assertEquals(
            "143465",
            localizedStorefrontHeaderValue(
                storefront = "cn",
                currentValue = null,
            ),
        )
        assertEquals(
            null,
            localizedStorefrontHeaderValue(
                storefront = "unknown",
                currentValue = "143441-1,29",
            ),
        )
    }

    @Test
    fun `extracts storefront from Apple content paths`() {
        assertEquals(
            "in",
            storefrontFromContentPath(
                listOf("v1", "catalog", "in", "playlists", "playlist-id")
            )
        )
        assertEquals(
            "us",
            storefrontFromContentPath(
                listOf("v1", "editorial", "us", "groupings")
            )
        )
        assertEquals(
            null,
            storefrontFromContentPath(
                listOf("v1", "me", "recommendations")
            )
        )
    }

    @Test
    fun `keeps radio playback requests on the account storefront`() {
        assertTrue(
            isAccountScopedPlaybackPath(
                listOf("v1", "catalog", "jp", "stations", "ra.123")
            )
        )
        assertTrue(
            isAccountScopedPlaybackPath(
                listOf("v1", "me", "radio", "recent")
            )
        )
        assertFalse(
            isAccountScopedPlaybackPath(
                listOf("v1", "catalog", "jp", "albums", "123")
            )
        )
    }

    @Test
    fun `prefers localized artist relationship over stale song artist snapshot`() {
        assertEquals(
            "梁静茹",
            selectLocalizedArtistName(
                attributeArtist = "양정여",
                relationshipArtists = listOf("梁静茹"),
                language = "zh-CN",
            )
        )
        assertEquals(
            "周杰伦、梁静茹",
            selectLocalizedArtistName(
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
            normalizedArtistNameKey("  Karen   MOK "),
        )
        assertEquals(
            "周杰伦、梁静茹",
            normalizedArtistNameKey("周杰伦、梁静茹"),
        )
    }

    @Test
    fun `keeps original album when the song alias is not confident`() {
        val rejectedAlias = Alias(
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
        val confident = Alias(
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
                    Alias(
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
                    Alias(
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
