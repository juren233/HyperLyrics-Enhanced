/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.lyric.model.Song

/** Origin is retained independently of the song's available content lanes. */
internal enum class LyricPublicationOrigin { NATIVE, AUTOMATIC, MANUAL }

internal data class LyricPublicationEvent(
    val song: Song?,
    val origin: LyricPublicationOrigin,
    val onlineMatched: Boolean = false,
    val identity: SourceTrackIdentity? = song?.let(SourceTrackIdentity::of),
)

/**
 * Owns only source selection and publication caches. No jobs, preferences, Android Views or
 * subscriber are stored here. Callers retain the existing main-thread dispatch; request
 * owners serialize result delivery with cancellation before entering this boundary.
 */
internal class LyriconPublication {
    private var appleInput: Song? = null
    private var appleNative: Song? = null
    private var appleNativeLyrics = false
    private var applePublished: LyricPublicationEvent? = null
    private var thirdPartyInput: Song? = null
    private var thirdPartyPublished: LyricPublicationEvent? = null
    private var appleFallbackSelected = false
    private var thirdPartyFallbackSelected = false
    private var enrichmentMatched = false
    private var lyricsSelection: ConfirmedLyricsSourceSelection? = null

    val currentAppleSong get() = appleInput
    val currentAppleNativeSong get() = appleNative
    val currentAppleHasNativeLyrics get() = appleNativeLyrics
    val currentPublishedAppleSong get() = applePublished?.song
    val currentPublishedAppleOnlineTranslationMatched get() = applePublished?.onlineMatched == true
    val currentThirdPartySong get() = thirdPartyInput
    val currentPublishedThirdPartySong get() = thirdPartyPublished?.song
    val fallbackSongActive get() = appleFallbackSelected
    val thirdPartyFallbackSongActive get() = thirdPartyFallbackSelected
    val onlineMatchedTranslationActive get() = enrichmentMatched
    val confirmedLyricsSourceSelection get() = lyricsSelection

    fun rememberNative(song: Song?) { appleNative = song }

    fun acceptAppleInput(song: Song?, hasNativeLyrics: Boolean) {
        appleInput = song
        appleNativeLyrics = hasNativeLyrics
    }

    fun acceptThirdPartyInput(song: Song?) { thirdPartyInput = song }

    fun beginAppleTrack(nativeSong: Song?) {
        lyricsSelection = null
        appleNative = nativeSong
    }

    fun confirmLyricsSource(selection: ConfirmedLyricsSourceSelection) {
        // A delayed manual result may never change the next track's selected source.
        if (appleInput?.id == selection.songId) lyricsSelection = selection
    }

    fun acceptAppleSupplement(song: Song, notify: (Song) -> Boolean): Boolean {
        val current = appleInput ?: return false
        if (!SourceTrackIdentity.of(current).matches(SourceTrackIdentity.of(song))) return false
        val delivered = notify(song)
        appleInput = song
        appleNativeLyrics = false
        return delivered
    }

    fun selectAppleFallback() { appleFallbackSelected = true }
    fun selectThirdPartyFallback() { thirdPartyFallbackSelected = true }

    fun cancelAppleFallback(clearSong: Boolean) {
        appleFallbackSelected = false
        if (clearSong) {
            appleInput = null
            appleNativeLyrics = false
            appleNative = null
            lyricsSelection = null
        }
    }

    fun cancelThirdPartyFallback() { thirdPartyFallbackSelected = false }
    fun acceptEnrichment(matched: Boolean) { enrichmentMatched = matched }
    fun cancelEnrichment() { enrichmentMatched = false }

    fun publishApple(event: LyricPublicationEvent, notify: (Song?, Boolean) -> Unit) {
        applePublished = event
        notify(event.song, event.onlineMatched)
    }

    fun publishThirdParty(event: LyricPublicationEvent, notify: (Song?, Boolean) -> Unit) {
        thirdPartyPublished = event
        notify(event.song, event.onlineMatched)
    }

    fun resetPublishedApple() { applePublished = null }

    fun resetThirdParty() {
        thirdPartyInput = null
        thirdPartyPublished = null
        thirdPartyFallbackSelected = false
    }

    fun reset() {
        cancelAppleFallback(clearSong = true)
        resetPublishedApple()
        resetThirdParty()
        cancelEnrichment()
    }
}
