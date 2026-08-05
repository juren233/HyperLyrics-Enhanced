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

class AppleLyricsProviderPolicyTest {

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
    fun `renders current third party source labels for the Apple menu`() {
        assertEquals("QQ发音", sourceMenuLabel("QM", "pronunciation"))
        assertEquals("网易发音", sourceMenuLabel("NE", "pronunciation"))
        assertEquals("QQ翻译", sourceMenuLabel("QM", "translation"))
        assertEquals("网易翻译", sourceMenuLabel("NE", "translation"))
        assertEquals(
            "切换中",
            sourceMenuLabel(
                "NE",
                "translation",
                OnlineSourceMenuStatus.SWITCHING,
            ),
        )
        assertEquals(
            "切换失败",
            sourceMenuLabel(
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
            sourceMenuPresentation(
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
            sourceMenuPresentation(
                actualSource = "QM",
                pendingTargetSource = null,
                failedSource = "QM",
            ),
        )
        assertNull(
            sourceMenuPresentation(
                actualSource = null,
                pendingTargetSource = null,
                failedSource = null,
            )
        )
    }

    @Test
    fun `source menu exposes only third party content that is actually consumed`() {
        assertNull(
            effectiveOnlineSourceSelection(
                storedSource = "NE",
                confirmedSource = null,
                onlineContentConsumed = false,
            )
        )
        assertEquals(
            "NE",
            effectiveOnlineSourceSelection(
                storedSource = "NE",
                confirmedSource = null,
                onlineContentConsumed = true,
            )
        )
        assertEquals(
            "QM",
            effectiveOnlineSourceSelection(
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
            sourceMenuWidth(
                -2,
                320,
                80,
            ),
        )
        assertEquals(
            360,
            sourceMenuWidth(
                320,
                360,
            ),
        )
        assertEquals(1, sourceMenuWidth(-2, 0))
    }

    @Test
    fun `native translation presentation waits until the source menu closes`() {
        assertTrue(
            shouldDeferNativeTranslationPresentationRefresh(
                activeMenuSongId = "1775825199",
                popupShowing = true,
                expectedSongId = "1775825199",
            )
        )
        assertFalse(
            shouldDeferNativeTranslationPresentationRefresh(
                activeMenuSongId = "1775825199",
                popupShowing = false,
                expectedSongId = "1775825199",
            )
        )
        assertFalse(
            shouldDeferNativeTranslationPresentationRefresh(
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
}
