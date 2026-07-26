package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import android.view.Gravity
import com.juren233.hyperlyricsenhanced.common.RootConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AodMediaLyricPolicyTest {
    @Test
    fun `shows next song preview from the final lyric start`() {
        assertFalse(
            AodMediaLyricPolicy.shouldShowNextSongPreview(
                enabled = true,
                positionMs = 54_999L,
                durationMs = 60_000L,
                hasActualLyrics = true,
                lastLyricStartMs = 55_000L,
            )
        )
        assertTrue(
            AodMediaLyricPolicy.shouldShowNextSongPreview(
                enabled = true,
                positionMs = 55_000L,
                durationMs = 60_000L,
                hasActualLyrics = true,
                lastLyricStartMs = 55_000L,
            )
        )
    }

    @Test
    fun `shows next song preview only in final five seconds without lyrics`() {
        assertFalse(
            AodMediaLyricPolicy.shouldShowNextSongPreview(
                enabled = true,
                positionMs = 54_999L,
                durationMs = 60_000L,
                hasActualLyrics = false,
                lastLyricStartMs = -1L,
            )
        )
        assertTrue(
            AodMediaLyricPolicy.shouldShowNextSongPreview(
                enabled = true,
                positionMs = 55_000L,
                durationMs = 60_000L,
                hasActualLyrics = false,
                lastLyricStartMs = -1L,
            )
        )
        assertFalse(
            AodMediaLyricPolicy.shouldShowNextSongPreview(
                enabled = true,
                positionMs = 60_000L,
                durationMs = 60_000L,
                hasActualLyrics = false,
                lastLyricStartMs = -1L,
            )
        )
    }

    @Test
    fun `does not show next song preview when disabled`() {
        assertFalse(
            AodMediaLyricPolicy.shouldShowNextSongPreview(
                enabled = false,
                positionMs = 59_000L,
                durationMs = 60_000L,
                hasActualLyrics = false,
                lastLyricStartMs = -1L,
            )
        )
    }

    @Test
    fun `formats next song title and artist`() {
        assertEquals(
            "下一首：Song-Artist",
            AodMediaLyricPolicy.formatNextSongPreview("Song", "Artist")
        )
        assertEquals(
            "下一首：Song",
            AodMediaLyricPolicy.formatNextSongPreview("Song", "")
        )
        assertEquals("", AodMediaLyricPolicy.formatNextSongPreview("", ""))
    }

    @Test
    fun `aligns next song preview using its independent position`() {
        assertEquals(
            AodLyricAlignment.LEFT,
            AodMediaLyricPolicy.nextSongPreviewAlignment(
                RootConstants.AOD_NEXT_SONG_PREVIEW_POSITION_LEFT
            )
        )
        assertEquals(
            AodLyricAlignment.CENTER,
            AodMediaLyricPolicy.nextSongPreviewAlignment(
                RootConstants.AOD_NEXT_SONG_PREVIEW_POSITION_CENTER
            )
        )
        assertEquals(
            AodLyricAlignment.RIGHT,
            AodMediaLyricPolicy.nextSongPreviewAlignment(
                RootConstants.AOD_NEXT_SONG_PREVIEW_POSITION_RIGHT
            )
        )
        assertEquals(
            AodLyricAlignment.CENTER,
            AodMediaLyricPolicy.nextSongPreviewAlignment(Int.MAX_VALUE)
        )
        assertEquals(
            RootConstants.AOD_NEXT_SONG_PREVIEW_POSITION_CENTER,
            RootConstants.DEFAULT_HOOK_AOD_NEXT_SONG_PREVIEW_POSITION
        )
    }

    @Test
    fun `suppresses title placeholder only for non text mode songs without lyrics`() {
        assertTrue(
            AodMediaLyricPolicy.shouldSuppressNoLyricPlaceholder(
                isTextMode = false,
                hasActualLyrics = false,
            )
        )
        assertFalse(
            AodMediaLyricPolicy.shouldSuppressNoLyricPlaceholder(
                isTextMode = true,
                hasActualLyrics = false,
            )
        )
        assertFalse(
            AodMediaLyricPolicy.shouldSuppressNoLyricPlaceholder(
                isTextMode = false,
                hasActualLyrics = true,
            )
        )
    }

    @Test
    fun `uses configured independent gravity for embedded song information`() {
        assertEquals(
            Gravity.LEFT or Gravity.CENTER_VERTICAL,
            AodMediaLyricPolicy.embeddedSongInfoGravity(
                RootConstants.AOD_SONG_INFO_POSITION_LEFT
            )
        )
        assertEquals(
            Gravity.CENTER,
            AodMediaLyricPolicy.embeddedSongInfoGravity(
                RootConstants.AOD_SONG_INFO_POSITION_CENTER
            )
        )
        assertEquals(
            Gravity.RIGHT or Gravity.CENTER_VERTICAL,
            AodMediaLyricPolicy.embeddedSongInfoGravity(
                RootConstants.AOD_SONG_INFO_POSITION_RIGHT
            )
        )
    }

    @Test
    fun `shows lyrics only while enabled playing and in full aod`() {
        assertTrue(
            AodMediaLyricPolicy.shouldShow(
                enabled = true,
                fullAod = true,
                playing = true,
                hasLyric = true,
                packageMatches = true
            )
        )
    }

    @Test
    fun `keeps lyrics visible while paused when configured`() {
        assertTrue(
            AodMediaLyricPolicy.shouldShow(
                enabled = true,
                fullAod = true,
                playing = false,
                hasLyric = true,
                packageMatches = true,
                pauseStyle = RootConstants.AOD_PAUSE_STYLE_KEEP_LYRICS,
            )
        )
        assertFalse(
            AodMediaLyricPolicy.shouldShow(
                enabled = true,
                fullAod = true,
                playing = false,
                hasLyric = true,
                packageMatches = true,
                pauseStyle = RootConstants.AOD_PAUSE_STYLE_RESTORE,
            )
        )
    }

    @Test
    fun `restores controls when screen is on or playback is paused`() {
        assertFalse(
            AodMediaLyricPolicy.shouldShow(
                enabled = true,
                fullAod = false,
                playing = true,
                hasLyric = true,
                packageMatches = true
            )
        )
        assertFalse(
            AodMediaLyricPolicy.shouldShow(
                enabled = true,
                fullAod = true,
                playing = false,
                hasLyric = true,
                packageMatches = true
            )
        )
    }

    @Test
    fun `keeps native controls without lyrics or for another player`() {
        assertFalse(
            AodMediaLyricPolicy.shouldShow(
                enabled = true,
                fullAod = true,
                playing = true,
                hasLyric = false,
                packageMatches = true
            )
        )
        assertFalse(
            AodMediaLyricPolicy.shouldShow(
                enabled = true,
                fullAod = true,
                playing = true,
                hasLyric = true,
                packageMatches = false
            )
        )
    }

    @Test
    fun `compacts classic spacing only for a single main lyric line`() {
        assertTrue(AodMediaLyricPolicy.shouldCompactClassicMain(lineCount = 1))
        assertFalse(AodMediaLyricPolicy.shouldCompactClassicMain(lineCount = 2))
        assertFalse(AodMediaLyricPolicy.shouldCompactClassicMain(lineCount = 3))
    }

    @Test
    fun `grows the whole card to the measured lyric bottom without a fixed cap`() {
        assertEquals(
            185,
            AodMediaLyricPolicy.requiredCardHeight(
                nativeCardHeight = 185,
                lyricBottom = 140,
                bottomPadding = 12
            )
        )
        assertEquals(
            232,
            AodMediaLyricPolicy.requiredCardHeight(
                nativeCardHeight = 185,
                lyricBottom = 220,
                bottomPadding = 12
            )
        )
        assertEquals(
            352,
            AodMediaLyricPolicy.requiredCardHeight(
                nativeCardHeight = 185,
                lyricBottom = 340,
                bottomPadding = 12
            )
        )
    }

    @Test
    fun `uses the lower album edge as the real metadata boundary`() {
        assertEquals(
            65,
            AodMediaLyricPolicy.contentAnchorBottom(
                albumBottom = 65,
                artistBottom = 45
            )
        )
    }

    @Test
    fun `uses the cover inset as both lock screen lyric side margins`() {
        assertEquals(
            AodHorizontalMargins(left = 24, right = 24),
            AodMediaLyricPolicy.lockScreenHorizontalMargins(
                playerWidth = 400,
                cardLeft = 0,
                cardRight = 400,
                albumLeft = 24,
            )
        )
    }

    @Test
    fun `keeps equal in-card margins when the media background is offset`() {
        assertEquals(
            AodHorizontalMargins(left = 32, right = 28),
            AodMediaLyricPolicy.lockScreenHorizontalMargins(
                playerWidth = 420,
                cardLeft = 8,
                cardRight = 416,
                albumLeft = 32,
            )
        )
    }

    @Test
    fun `adds the configured extra inset to both lock screen lyric margins`() {
        assertEquals(
            AodHorizontalMargins(left = 27, right = 27),
            AodMediaLyricPolicy.lockScreenHorizontalMargins(
                playerWidth = 400,
                cardLeft = 0,
                cardRight = 400,
                albumLeft = 24,
                extraInset = 3,
            )
        )
    }

    @Test
    fun `centers a short lyric inside the native aod background`() {
        val lyricTop = AodMediaLyricPolicy.centeredLyricTop(
            nativeCardHeight = 138,
            anchorBottom = 65,
            lyricHeight = 18,
            topGap = 17,
            bottomPadding = 21
        )

        assertEquals(90, lyricTop)
        assertEquals(
            138,
            AodMediaLyricPolicy.requiredCardHeight(
                nativeCardHeight = 138,
                lyricBottom = lyricTop + 18,
                bottomPadding = 21
            )
        )
    }

    @Test
    fun `keeps tall lyrics at the minimum top gap and grows the card`() {
        val lyricTop = AodMediaLyricPolicy.centeredLyricTop(
            nativeCardHeight = 138,
            anchorBottom = 65,
            lyricHeight = 124,
            topGap = 17,
            bottomPadding = 21
        )

        assertEquals(82, lyricTop)
        assertEquals(
            227,
            AodMediaLyricPolicy.requiredCardHeight(
                nativeCardHeight = 138,
                lyricBottom = lyricTop + 124,
                bottomPadding = 21
            )
        )
    }

    @Test
    fun `does not cap classic aod lyrics at the old fixed height`() {
        assertEquals(
            246,
            AodMediaLyricPolicy.classicOverlayHeight(
                contentHeight = 246,
                availableHeight = 420
            )
        )
    }

    @Test
    fun `caps classic aod lyrics only at the physical bottom boundary`() {
        assertEquals(
            240,
            AodMediaLyricPolicy.classicOverlayHeight(
                contentHeight = 300,
                availableHeight = 240
            )
        )
    }

    @Test
    fun `starts lock screen lyrics during the non interactive aod transition`() {
        assertTrue(
            AodMediaLyricPolicy.isLockScreenAodActive(
                fullAod = true,
                interactive = true,
                playerShown = true
            )
        )
        assertTrue(
            AodMediaLyricPolicy.isLockScreenAodActive(
                fullAod = false,
                interactive = false,
                playerShown = true
            )
        )
        assertFalse(
            AodMediaLyricPolicy.isLockScreenAodActive(
                fullAod = false,
                interactive = true,
                playerShown = true
            )
        )
        assertFalse(
            AodMediaLyricPolicy.isLockScreenAodActive(
                fullAod = false,
                interactive = false,
                playerShown = false
            )
        )
    }

    @Test
    fun `keeps main translation backing vocal and its translation in display order`() {
        val content = AodMediaLyricPolicy.assembleContent(
            main = "Main lyric",
            translation = "主句翻译",
            backing = "Backing vocal",
            backingTranslation = "伴唱翻译",
            roma = "Main lyric pronunciation"
        )

        assertEquals("Main lyric", content.main)
        assertEquals("主句翻译", content.translation)
        assertEquals("Backing vocal", content.backing)
        assertEquals("伴唱翻译", content.backingTranslation)
    }

    @Test
    fun `hides every translation layer while preserving original lyric rows`() {
        val content = AodMediaLyricPolicy.assembleContent(
            main = "First",
            translation = "第一句翻译",
            backing = "First backing",
            backingTranslation = "第一句伴唱翻译",
            roma = null,
            overlappingMain = "Second",
            overlappingTranslation = "第二句翻译",
            overlappingBacking = "Second backing",
            overlappingBackingTranslation = "第二句伴唱翻译",
            next = "Next",
            showNext = true,
            translationDisplay = false,
        )

        assertEquals("First", content.main)
        assertEquals("", content.translation)
        assertEquals("First backing", content.backing)
        assertEquals("", content.backingTranslation)
        assertEquals("Second", content.overlappingMain)
        assertEquals("", content.overlappingTranslation)
        assertEquals("Second backing", content.overlappingBacking)
        assertEquals("", content.overlappingBackingTranslation)
        assertEquals("Next", content.next)
    }

    @Test
    fun `swaps each original lyric and translation row without moving next lyric`() {
        assertEquals(
            listOf(
                AodLyricRow.MAIN,
                AodLyricRow.TRANSLATION,
                AodLyricRow.BACKING,
                AodLyricRow.BACKING_TRANSLATION,
                AodLyricRow.OVERLAPPING_MAIN,
                AodLyricRow.OVERLAPPING_TRANSLATION,
                AodLyricRow.OVERLAPPING_BACKING,
                AodLyricRow.OVERLAPPING_BACKING_TRANSLATION,
                AodLyricRow.NEXT,
            ),
            AodMediaLyricPolicy.orderedLyricRows(swapTranslation = false),
        )
        assertEquals(
            listOf(
                AodLyricRow.TRANSLATION,
                AodLyricRow.MAIN,
                AodLyricRow.BACKING_TRANSLATION,
                AodLyricRow.BACKING,
                AodLyricRow.OVERLAPPING_TRANSLATION,
                AodLyricRow.OVERLAPPING_MAIN,
                AodLyricRow.OVERLAPPING_BACKING_TRANSLATION,
                AodLyricRow.OVERLAPPING_BACKING,
                AodLyricRow.NEXT,
            ),
            AodMediaLyricPolicy.orderedLyricRows(swapTranslation = true),
        )
    }

    @Test
    fun `uses pronunciation only as the original single secondary row fallback`() {
        val withoutBacking = AodMediaLyricPolicy.assembleContent(
            main = "Main lyric",
            translation = null,
            backing = null,
            backingTranslation = null,
            roma = "Main lyric pronunciation"
        )
        val withBacking = AodMediaLyricPolicy.assembleContent(
            main = "Main lyric",
            translation = null,
            backing = "Backing vocal",
            backingTranslation = null,
            roma = "Main lyric pronunciation"
        )

        assertEquals("Main lyric pronunciation", withoutBacking.translation)
        assertEquals("", withBacking.translation)
        assertEquals("Backing vocal", withBacking.backing)
    }

    @Test
    fun `does not show a detached backing vocal translation`() {
        val content = AodMediaLyricPolicy.assembleContent(
            main = "Main lyric",
            translation = "主句翻译",
            backing = null,
            backingTranslation = "伴唱翻译",
            roma = null
        )

        assertEquals("", content.backing)
        assertEquals("", content.backingTranslation)
    }

    @Test
    fun `uses the overlapping lyric second line direction for duet aod display`() {
        val content = AodMediaLyricPolicy.assembleContent(
            main = "Left lyric",
            translation = null,
            backing = "Right lyric",
            backingTranslation = null,
            roma = null,
            mainAlignedRight = false,
            backingAlignedRight = true,
            duetLyrics = true,
        )

        assertEquals(AodLyricAlignment.LEFT, content.mainAlignment)
        assertEquals(AodLyricAlignment.RIGHT, content.backingAlignment)
    }

    @Test
    fun `keeps both overlapping lyric content hierarchies for aod`() {
        val content = AodMediaLyricPolicy.assembleContent(
            main = "First",
            translation = "第一句翻译",
            backing = "First backing",
            backingTranslation = "第一句伴唱翻译",
            roma = null,
            overlappingMain = "Second",
            overlappingTranslation = "第二句翻译",
            overlappingBacking = "Second backing",
            overlappingBackingTranslation = "第二句伴唱翻译",
        )

        assertEquals("First", content.main)
        assertEquals("第一句翻译", content.translation)
        assertEquals("First backing", content.backing)
        assertEquals("第一句伴唱翻译", content.backingTranslation)
        assertEquals("Second", content.overlappingMain)
        assertEquals("第二句翻译", content.overlappingTranslation)
        assertEquals("Second backing", content.overlappingBacking)
        assertEquals("第二句伴唱翻译", content.overlappingBackingTranslation)
    }

    @Test
    fun `keeps the existing lock screen and classic text sizes as defaults`() {
        assertEquals(18, RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_MAIN_TEXT_SIZE)
        assertEquals(17, RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_BACKING_TEXT_SIZE)
        assertEquals(15, RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_TRANSLATION_TEXT_SIZE)
        assertEquals(26, RootConstants.DEFAULT_HOOK_CLASSIC_AOD_MAIN_TEXT_SIZE)
        assertEquals(23, RootConstants.DEFAULT_HOOK_CLASSIC_AOD_BACKING_TEXT_SIZE)
        assertEquals(21, RootConstants.DEFAULT_HOOK_CLASSIC_AOD_TRANSLATION_TEXT_SIZE)
    }

    @Test
    fun `uses valid text sizes and falls back from values outside their range`() {
        assertEquals(
            30,
            AodMediaLyricPolicy.sanitizeTextSize(
                value = 30,
                defaultValue = 18,
                min = 12,
                max = 40,
            )
        )
        assertEquals(
            18,
            AodMediaLyricPolicy.sanitizeTextSize(
                value = 41,
                defaultValue = 18,
                min = 12,
                max = 40,
            )
        )
    }

    @Test
    fun `keeps next lyric hidden by default and uses translation as default style`() {
        val content = AodMediaLyricPolicy.assembleContent(
            main = "Current",
            translation = null,
            backing = null,
            backingTranslation = null,
            roma = null,
            next = "Next",
            showNext = RootConstants.DEFAULT_HOOK_AOD_SHOW_NEXT_LYRIC,
        )

        assertEquals("", content.next)
        assertEquals(
            RootConstants.AOD_NEXT_LYRIC_STYLE_TRANSLATION,
            RootConstants.DEFAULT_HOOK_AOD_NEXT_LYRIC_STYLE,
        )
        assertEquals(
            RootConstants.AOD_NEXT_LYRIC_STYLE_TRANSLATION,
            AodMediaLyricPolicy.sanitizeNextLyricStyle(value = -1),
        )
    }

    @Test
    fun `keeps all lyrics centered while duet lyrics are disabled`() {
        assertEquals(
            AodLyricAlignment.CENTER,
            AodMediaLyricPolicy.lyricAlignment(
                duetLyrics = false,
                alignedRight = true,
                groupVocals = false,
                centerGroupVocals = false,
            ),
        )
    }

    @Test
    fun `aligns duet lyrics left and right when enabled`() {
        assertEquals(
            AodLyricAlignment.LEFT,
            AodMediaLyricPolicy.lyricAlignment(
                duetLyrics = true,
                alignedRight = false,
                groupVocals = false,
                centerGroupVocals = false,
            ),
        )
        assertEquals(
            AodLyricAlignment.RIGHT,
            AodMediaLyricPolicy.lyricAlignment(
                duetLyrics = true,
                alignedRight = true,
                groupVocals = false,
                centerGroupVocals = false,
            ),
        )
    }

    @Test
    fun `centers every lyric layer for a non-duet song when enabled`() {
        assertEquals(
            AodLyricAlignment.CENTER,
            AodMediaLyricPolicy.lyricAlignment(
                duetLyrics = true,
                centerNonDuetSong = true,
                alignedRight = false,
                groupVocals = false,
                centerGroupVocals = false,
            ),
        )
        assertEquals(
            AodLyricAlignment.CENTER,
            AodMediaLyricPolicy.lyricAlignment(
                duetLyrics = true,
                centerNonDuetSong = true,
                alignedRight = true,
                groupVocals = false,
                centerGroupVocals = false,
            ),
        )
    }

    @Test
    fun `centers group vocals only when the dependent option is enabled`() {
        assertEquals(
            AodLyricAlignment.LEFT,
            AodMediaLyricPolicy.lyricAlignment(
                duetLyrics = true,
                alignedRight = false,
                groupVocals = true,
                centerGroupVocals = false,
            ),
        )
        assertEquals(
            AodLyricAlignment.CENTER,
            AodMediaLyricPolicy.lyricAlignment(
                duetLyrics = true,
                alignedRight = false,
                groupVocals = true,
                centerGroupVocals = true,
            ),
        )
    }

    @Test
    fun `keeps current and next lyric alignment independent`() {
        val content = AodMediaLyricPolicy.assembleContent(
            main = "Left singer",
            translation = null,
            backing = null,
            backingTranslation = null,
            roma = null,
            next = "Right singer",
            showNext = true,
            mainAlignedRight = false,
            nextAlignedRight = true,
            duetLyrics = true,
        )

        assertEquals(AodLyricAlignment.LEFT, content.mainAlignment)
        assertEquals(AodLyricAlignment.RIGHT, content.nextAlignment)
    }

    @Test
    fun `keeps duet and group vocal centering disabled by default`() {
        assertFalse(RootConstants.DEFAULT_HOOK_AOD_DUET_LYRICS)
        assertFalse(RootConstants.DEFAULT_HOOK_AOD_CENTER_GROUP_VOCALS)
    }

    @Test
    fun `shows a distinct next lyric only when enabled`() {
        val next = AodMediaLyricPolicy.assembleContent(
            main = "Current",
            translation = null,
            backing = null,
            backingTranslation = null,
            roma = null,
            next = "Next",
            showNext = true,
        )
        val duplicate = AodMediaLyricPolicy.assembleContent(
            main = "Current",
            translation = null,
            backing = null,
            backingTranslation = null,
            roma = null,
            next = "Current",
            showNext = true,
        )

        assertEquals("Next", next.next)
        assertEquals("", duplicate.next)
    }

    @Test
    fun `hides next lyric while main translation is displayed`() {
        val content = AodMediaLyricPolicy.assembleContent(
            main = "Current",
            translation = "Translation",
            backing = null,
            backingTranslation = null,
            roma = null,
            next = "Next",
            showNext = true,
        )

        assertEquals("Translation", content.translation)
        assertEquals("", content.next)
    }

    @Test
    fun `hides next lyric while backing vocal translation is displayed`() {
        val content = AodMediaLyricPolicy.assembleContent(
            main = "Current",
            translation = null,
            backing = "Backing",
            backingTranslation = "Backing translation",
            roma = null,
            next = "Next",
            showNext = true,
        )

        assertEquals("Backing translation", content.backingTranslation)
        assertEquals("", content.next)
    }

    @Test
    fun `grows lock screen card for measured content including next lyric`() {
        assertEquals(
            278,
            AodMediaLyricPolicy.requiredCardHeight(
                nativeCardHeight = 138,
                lyricBottom = 257,
                bottomPadding = 21,
            )
        )
    }
}
