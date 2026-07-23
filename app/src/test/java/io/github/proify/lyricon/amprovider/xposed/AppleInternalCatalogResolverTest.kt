package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleInternalCatalogResolverTest {

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
            listOf("zh-Hans-CN", "zh-Hant-TW"),
            AppleInternalCatalogResolver.languageTagsForGenre("Mandopop")
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
    fun `skips catalog lookup when title already uses original script`() {
        val metadata = MediaMetadataCache.Metadata(
            id = "1882935962",
            title = "満ちてゆく",
            artist = "藤井 風",
            genre = "J-Pop",
            duration = 315_000L,
            queueId = 1L
        )

        assertFalse(AppleInternalCatalogResolver.shouldResolve(metadata))
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
            "zh-CN",
            AppleInternalCatalogResolver.languageTagForContentUiLanguage(
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
}
