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
 * subscriber are stored here. All access is synchronized at this boundary because Central callbacks and direct Binder
 * callbacks can arrive on different threads; request owners still serialize result delivery
 * with cancellation before entering this boundary.
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

    val currentAppleSong get() = synchronized(this) { appleInput }
    val currentAppleNativeSong get() = synchronized(this) { appleNative }
    val currentAppleHasNativeLyrics get() = synchronized(this) { appleNativeLyrics }
    val currentPublishedAppleSong get() = synchronized(this) { applePublished?.song }
    val currentPublishedAppleOnlineTranslationMatched get() = synchronized(this) { applePublished?.onlineMatched == true }
    val currentThirdPartySong get() = synchronized(this) { thirdPartyInput }
    val currentPublishedThirdPartySong get() = synchronized(this) { thirdPartyPublished?.song }
    val fallbackSongActive get() = synchronized(this) { appleFallbackSelected }
    val thirdPartyFallbackSongActive get() = synchronized(this) { thirdPartyFallbackSelected }
    val onlineMatchedTranslationActive get() = synchronized(this) { enrichmentMatched }
    val confirmedLyricsSourceSelection get() = synchronized(this) { lyricsSelection }

    @Synchronized
    fun rememberNative(song: Song?) { appleNative = song }

    @Synchronized
    fun acceptAppleInput(song: Song?, hasNativeLyrics: Boolean) {
        appleInput = song
        appleNativeLyrics = hasNativeLyrics
    }

    @Synchronized
    fun acceptThirdPartyInput(song: Song?) { thirdPartyInput = song }

    @Synchronized
    fun beginAppleTrack(nativeSong: Song?) {
        lyricsSelection = null
        appleNative = nativeSong
    }

    @Synchronized
    fun confirmLyricsSource(selection: ConfirmedLyricsSourceSelection) {
        // A delayed manual result may never change the next track's selected source.
        if (appleInput?.id == selection.songId) lyricsSelection = selection
    }

    @Synchronized
    fun acceptAppleSupplement(song: Song, notify: (Song) -> Boolean): Boolean {
        val current = appleInput ?: return false
        if (!SourceTrackIdentity.of(current).matches(SourceTrackIdentity.of(song))) return false
        val delivered = notify(song)
        appleInput = song
        appleNativeLyrics = false
        return delivered
    }

    @Synchronized
    fun selectAppleFallback() { appleFallbackSelected = true }
    @Synchronized
    fun selectThirdPartyFallback() { thirdPartyFallbackSelected = true }

    @Synchronized
    fun cancelAppleFallback(clearSong: Boolean) {
        appleFallbackSelected = false
        if (clearSong) {
            appleInput = null
            appleNativeLyrics = false
            appleNative = null
            lyricsSelection = null
        }
    }

    @Synchronized
    fun cancelThirdPartyFallback() { thirdPartyFallbackSelected = false }
    @Synchronized
    fun acceptEnrichment(matched: Boolean) { enrichmentMatched = matched }
    @Synchronized
    fun cancelEnrichment() { enrichmentMatched = false }

    @Synchronized
    fun publishApple(event: LyricPublicationEvent, notify: (Song?, Boolean) -> Unit) {
        applePublished = event
        notify(event.song, event.onlineMatched)
    }

    @Synchronized
    fun publishThirdParty(event: LyricPublicationEvent, notify: (Song?, Boolean) -> Unit) {
        thirdPartyPublished = event
        notify(event.song, event.onlineMatched)
    }

    @Synchronized
    fun refreshAppleDisplayMetadata(incoming: Song?, notify: (Song?, Boolean) -> Unit): Boolean {
        val event = applePublished ?: return false
        val updated = AppleSongUpdatePolicy.refreshDisplayMetadata(event.song, incoming) ?: return false
        publishApple(event.copy(song = updated, identity = SourceTrackIdentity.of(updated)), notify)
        return true
    }

    @Synchronized
    fun resetPublishedApple() { applePublished = null }

    @Synchronized
    fun resetThirdParty() {
        thirdPartyInput = null
        thirdPartyPublished = null
        thirdPartyFallbackSelected = false
    }

    @Synchronized
    fun reset() {
        cancelAppleFallback(clearSong = true)
        resetPublishedApple()
        resetThirdParty()
        cancelEnrichment()
    }
}
