package com.juren233.hyperlyricsenhanced.root.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleCentralPositionPolicyTest {
    private val nowMs = 50_000L

    @Test
    fun `valid same-song central progress is preserved`() {
        val resolution = resolve(
            centralPosition = 21_000L,
            mediaPosition = 20_500L,
        )

        assertEquals(21_000L, resolution.position)
        assertEquals(
            AppleCentralPositionPolicy.Reason.CENTRAL_ACCEPTED,
            resolution.reason,
        )
    }

    @Test
    fun `old-song position beyond current duration is rejected without media reference`() {
        val resolution = resolve(
            centralPosition = 261_771L,
            mediaPosition = null,
            duration = 191_950L,
        )

        assertNull(resolution.position)
        assertEquals(
            AppleCentralPositionPolicy.Reason.REJECTED_OUTSIDE_DURATION,
            resolution.reason,
        )
    }

    @Test
    fun `old-song position beyond current duration is replaced by matching media progress`() {
        val resolution = resolve(
            centralPosition = 261_771L,
            mediaPosition = 20_000L,
            duration = 191_950L,
        )

        assertEquals(20_000L, resolution.position)
        assertEquals(
            AppleCentralPositionPolicy.Reason.MEDIA_REPLACED_OUTSIDE_DURATION,
            resolution.reason,
        )
    }

    @Test
    fun `in-range stale central progress is replaced by matching media progress`() {
        val resolution = resolve(
            centralPosition = 150_000L,
            mediaPosition = 20_000L,
            duration = 191_950L,
        )

        assertEquals(20_000L, resolution.position)
        assertEquals(
            AppleCentralPositionPolicy.Reason.MEDIA_REPLACED_DIVERGENT,
            resolution.reason,
        )
    }

    @Test
    fun `explicit seek remains immediate while media session catches up`() {
        val resolution = resolve(
            centralPosition = 120_000L,
            mediaPosition = 20_000L,
            duration = 191_950L,
            explicitSeek = true,
        )

        assertEquals(120_000L, resolution.position)
        assertEquals(
            AppleCentralPositionPolicy.Reason.EXPLICIT_SEEK_ACCEPTED,
            resolution.reason,
        )
    }

    @Test
    fun `reference from another song generation cannot override central progress`() {
        val resolution = AppleCentralPositionPolicy.resolve(
            centralPosition = 30_000L,
            currentSongDuration = 191_950L,
            currentSongGeneration = 2,
            mediaReference = mediaReference(position = 150_000L, songGeneration = 1),
            providerDelayMs = 0,
            nowMs = nowMs,
            explicitSeek = false,
        )

        assertEquals(30_000L, resolution.position)
        assertNull(resolution.mediaPosition)
    }

    @Test
    fun `same-song content republish keeps last valid position and rejected updates cannot replace it`() {
        val accepted = resolve(
            centralPosition = 45_000L,
            mediaPosition = 44_500L,
        )
        val stale = resolve(
            centralPosition = 261_771L,
            mediaPosition = null,
            duration = 191_950L,
        )
        val afterAccepted = AppleCentralPositionPolicy.restorablePosition(0L, accepted)
        val afterRejected = AppleCentralPositionPolicy.restorablePosition(afterAccepted, stale)

        assertEquals(45_000L, afterAccepted)
        assertEquals(45_000L, afterRejected)
    }

    @Test
    fun `same-track identity tolerates small duration differences`() {
        assertTrue(
            AppleCentralPositionPolicy.matchesTrack(
                firstTitle = "calle luna",
                firstArtist = "Mora",
                firstDuration = 191_950L,
                secondTitle = " Calle Luna ",
                secondArtist = "mora",
                secondDuration = 192_500L,
            )
        )
    }

    private fun resolve(
        centralPosition: Long,
        mediaPosition: Long?,
        duration: Long = 191_950L,
        explicitSeek: Boolean = false,
    ): AppleCentralPositionPolicy.Resolution = AppleCentralPositionPolicy.resolve(
        centralPosition = centralPosition,
        currentSongDuration = duration,
        currentSongGeneration = 1,
        mediaReference = mediaPosition?.let(::mediaReference),
        providerDelayMs = 0,
        nowMs = nowMs,
        explicitSeek = explicitSeek,
    )

    private fun mediaReference(
        position: Long,
        songGeneration: Int = 1,
    ) = AppleCentralPositionPolicy.MediaReference(
        songGeneration = songGeneration,
        title = "calle luna",
        artist = "Mora",
        duration = 191_950L,
        position = position,
        isPlaying = false,
        playbackSpeed = 0f,
        observedAtMs = nowMs,
    )
}
