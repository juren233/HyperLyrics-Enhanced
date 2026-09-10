/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.online.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleManualSourceRequestStateTest {
    private var nextId = 1L

    private fun request(
        songId: String = "a",
        contentType: String,
        source: Source = Source.NE,
    ) = OnlineSourceSwitchRequest(
        requestId = nextId++,
        songId = songId,
        contentType = contentType,
        requestedSource = source,
        startedAtMs = 0L,
    )

    @Test
    fun `translation and pronunciation requests keep their own requested source`() {
        val state = AppleManualSourceRequestState()
        assertNull(state.requestedTranslationSource())
        assertNull(state.requestedPronunciationSource())
        assertFalse(state.hasTemporarySource)

        state.acceptTranslationRequest(
            request(contentType = "translation", source = Source.QM),
            Source.QM,
        )
        state.acceptPronunciationRequest(
            request(contentType = "pronunciation", source = Source.KUGOU),
            Source.KUGOU,
        )

        assertEquals(Source.QM, state.requestedTranslationSource())
        assertEquals(Source.KUGOU, state.requestedPronunciationSource())
        assertTrue(state.hasTemporarySource)
        // Online RACE uses the translation request first, then pronunciation.
        assertEquals(Source.QM, state.requestedOnlineSource())
    }

    @Test
    fun `lyrics request only completes for the matching requested source`() {
        val state = AppleManualSourceRequestState()
        val lyrics = request(contentType = "lyrics", source = Source.LB)
        state.acceptLyricsRequest(lyrics)

        assertNull(state.lyricsRequestToComplete(Source.NE))
        assertSame(lyrics, state.lyricsRequestToComplete(Source.LB))
        // A null argument still matches the pending request.
        assertSame(lyrics, state.lyricsRequestToComplete(null))

        state.clearLyricsRequest(lyrics)
        assertNull(state.lyricsRequestToComplete(null))
    }

    @Test
    fun `clearing a stale lyrics request does not clear a newer one`() {
        val state = AppleManualSourceRequestState()
        val first = request(contentType = "lyrics")
        val second = request(contentType = "lyrics", source = Source.QM)
        state.acceptLyricsRequest(first)
        state.acceptLyricsRequest(second)

        state.clearLyricsRequest(first)
        assertSame(second, state.lyricsRequestToComplete(null))
    }

    @Test
    fun `fail only clears the request with the same id and content type`() {
        val state = AppleManualSourceRequestState()
        val stale = request(contentType = "translation")
        val current = request(contentType = "translation", source = Source.QM)
        state.acceptTranslationRequest(current, Source.QM)

        // A late failure for the superseded request must not clear the current one.
        state.failRequest(stale)
        assertEquals(Source.QM, state.requestedTranslationSource())
        assertFalse(state.onlineRequestsToComplete(null, null).isEmpty())

        state.failRequest(current)
        assertTrue(state.onlineRequestsToComplete(null, null).isEmpty())
        // Temp sources survive a failure, matching the original behaviour.
        assertEquals(Source.QM, state.requestedTranslationSource())
    }

    @Test
    fun `unequal content types never clear each other`() {
        val state = AppleManualSourceRequestState()
        val translation = request(contentType = "translation")
        state.acceptTranslationRequest(translation, Source.QM)
        // Same requestId is not reachable in practice; matching contentType is required.
        state.failRequest(translation.copy(contentType = "pronunciation"))
        assertFalse(state.onlineRequestsToComplete(null, null).isEmpty())
    }

    @Test
    fun `pending online request is scoped to the song`() {
        val state = AppleManualSourceRequestState()
        state.acceptTranslationRequest(request(songId = "song", contentType = "translation"), Source.QM)
        assertTrue(state.hasPendingOnlineRequest("song"))
        assertFalse(state.hasPendingOnlineRequest("other"))
    }

    @Test
    fun `absent requests and null song ids never report a pending switch`() {
        val state = AppleManualSourceRequestState()
        // No request at all must not match a null song id.
        assertFalse(state.hasPendingOnlineRequest(null))

        state.acceptTranslationRequest(request(songId = "song", contentType = "translation"), Source.QM)
        assertFalse(state.hasPendingOnlineRequest(null))
        assertFalse(state.hasPendingOnlineRequest("other"))
    }

    @Test
    fun `track change clears all five slots`() {
        val state = AppleManualSourceRequestState()
        state.acceptLyricsRequest(request(contentType = "lyrics"))
        state.acceptTranslationRequest(request(contentType = "translation"), Source.QM)
        state.acceptPronunciationRequest(request(contentType = "pronunciation"), Source.KUGOU)

        state.clearForTrackChange()

        assertNull(state.requestedTranslationSource())
        assertNull(state.requestedPronunciationSource())
        assertNull(state.requestedOnlineSource())
        assertNull(state.lyricsRequestToComplete(null))
        assertTrue(state.pendingRequests().isEmpty())
        assertFalse(state.hasTemporarySource)
    }

    @Test
    fun `completion reads both online requests and clearing drops both`() {
        val state = AppleManualSourceRequestState()
        val translation = request(contentType = "translation")
        val pronunciation = request(contentType = "pronunciation")
        state.acceptTranslationRequest(translation, Source.QM)
        state.acceptPronunciationRequest(pronunciation, Source.KUGOU)

        val toComplete = state.onlineRequestsToComplete(
            translationSource = Source.QM,
            pronunciationSource = null,
        )
        assertEquals(2, toComplete.size)
        assertEquals(Source.QM, toComplete[0].second)
        assertNull(toComplete[1].second)

        state.clearOnlineRequests()
        assertTrue(state.onlineRequestsToComplete(Source.QM, Source.KUGOU).isEmpty())
        // Confirmed/requested temp sources are retained, like the original code.
        assertEquals(Source.QM, state.requestedTranslationSource())
    }
}
