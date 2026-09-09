/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.ApplePronunciationVisibilityPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.online.OnlineTranslationSourcePreferences
import com.juren233.hyperlyricsenhanced.online.model.Source

internal fun LyriconSource.onPreferenceChanged(key: String?) {
    val packageName = activeProviderPackageName
    if (packageName != null && key == providerDelayKey(packageName)) {
        activeProviderDelayMs = readProviderDelay(packageName)
        return
    }
    if (key == RootConstants.KEY_HOOK_APPLE_MUSIC_MATCH_ONLINE_TRANSLATION ||
        key == RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SALT_PREFER_ONLINE ||
        OnlineTranslationSourcePreferences.isSourcePreference(key) ||
        OnlineTranslationSourcePreferences.isAppPreference(key)
    ) {
        mainHandler.post { applyOnlineTranslationPreferenceChange(key.orEmpty()) }
    }
    if (key == RootConstants.KEY_HOOK_APPLE_MUSIC_RESTORE_CJK_ORIGINAL_METADATA) {
        mainHandler.post { applyOriginalMetadataPreferenceChange() }
    }
    if (key == RootConstants.KEY_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_LYRICS) {
        mainHandler.post { applySimplifiedLyricsPreferenceChange() }
    }
    if (key == RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION) {
        mainHandler.post { applyNativeOnlineTranslationPreferenceChange() }
    }
    if (key == RootConstants.KEY_HOOK_APPLE_MUSIC_FILL_MISSING_LYRICS) {
        mainHandler.post { applyMissingLyricsPreferenceChange() }
    }
    if (key == RootConstants.KEY_HOOK_APPLE_MUSIC_HIDE_MANDARIN_PINYIN) {
        mainHandler.post { applyMandarinPinyinPreferenceChange() }
    }
    if (key == RootConstants.KEY_HOOK_APPLE_MUSIC_LUNABEAT_WORD_LYRICS) {
        mainHandler.post { applyLunaBeatWordLyricsPreferenceChange() }
    }
}

private fun LyriconSource.applyLunaBeatWordLyricsPreferenceChange() {
    val nativeSong = currentAppleNativeSong ?: currentAppleSong ?: return
    cancelFallback(clearAppleSong = false, reason = "lunabeat_preference_changed")
    if (isLunaBeatWordLyricsEnabled()) {
        scheduleFallback(
            baseSong = nativeSong,
            delayMs = 0L,
            preferredSourceOverride = Source.LB,
            strictSource = hasAppleNativeLyrics(nativeSong) || !isFillMissingLyricsEnabled(),
        )
        return
    }
    if (currentAppleSong?.metadata
            ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE) == Source.LB.name
    ) {
        publication.acceptAppleInput(nativeSong, hasAppleNativeLyrics(nativeSong))
        publication.cancelAppleFallback(clearSong = false)
        stopMediaPositionPolling()
        publishAppleSong(nativeSong, restorePosition = true)
        if (!hasAppleNativeLyrics(nativeSong) && isFillMissingLyricsEnabled()) {
            scheduleFallback(baseSong = nativeSong, delayMs = 0L)
        }
    }
}

private fun LyriconSource.applyNativeOnlineTranslationPreferenceChange() {
    val nativeSong = currentAppleSong
    val publishedSong = currentPublishedAppleSong

    if (!isNativeOnlineTranslationEnabled()) {
        directBridge?.clearOnlineTranslation(publishedSong?.id ?: nativeSong?.id)
        if (!isOnlineTranslationEnabledFor(LyriconSource.APPLE_MUSIC_PACKAGE)) {
            cancelOnlineTranslation(
                clearAttempt = true,
                clearMatched = true,
                reason = "online_translation_fully_disabled",
            )
        }
        return
    }

    if (currentPublishedAppleOnlineTranslationMatched && publishedSong != null) {
        directBridge?.publishOnlineTranslation(
            ApplePronunciationVisibilityPolicy.filterSong(
                song = publishedSong,
                hideMandarinPinyin = isHideMandarinPinyinEnabled(),
    )
)

        return
    }

    if (
        nativeSong != null &&
        !nativeSong.lyrics.isNullOrEmpty() &&
        needsOnlineEnrichment(nativeSong)
    ) {
        scheduleOnlineTranslation(nativeSong)
    }
}

private fun LyriconSource.applyMissingLyricsPreferenceChange() {
    val nativeSong = currentAppleSong
    if (!isFillMissingLyricsEnabled()) {
        directBridge?.clearMissingLyricsSupplement(nativeSong?.id)
        return
    }
    if (nativeSong == null || !needsMissingLyricsSourceRecovery(nativeSong)) return
    scheduleFallback(nativeSong, 0L)
}

private fun LyriconSource.applyMandarinPinyinPreferenceChange() {
    val nativeSong = currentAppleSong ?: return
    cancelOnlineTranslation(
        clearAttempt = true,
        clearMatched = true,
        reason = "mandarin_pinyin_preference_changed",
    )
    publishAppleSong(nativeSong, restorePosition = true)
    if (
        isAppleTranslationEnrichmentEnabled() &&
        !nativeSong.lyrics.isNullOrEmpty() &&
        needsOnlineEnrichment(nativeSong)
    ) {
        scheduleOnlineTranslation(nativeSong)
    }
}

private fun LyriconSource.applyOriginalMetadataPreferenceChange() {
    val nativeSong = currentAppleSong ?: return
    originalMetadataRequestKey = null
    cancelFallback(clearAppleSong = false, reason = "original_metadata_preference_changed")
    cancelOnlineTranslation(
        clearAttempt = true,
        clearMatched = true,
        reason = "original_metadata_preference_changed",
    )
    val originalMetadataPlan = AppleOnlineTranslationRequestPolicy.originalMetadataLookupPlan(
        shouldRequestOriginalMetadataForOnlineLookup(nativeSong)
    )
    if (originalMetadataPlan.requestOriginalMetadata) {
        requestOriginalMetadata(nativeSong, "preference_changed")
    }
    if (
        !originalMetadataPlan.waitForResult &&
        needsMissingLyricsSourceRecovery(nativeSong) &&
        isOnlineTranslationEnabledFor(LyriconSource.APPLE_MUSIC_PACKAGE)
    ) {
        scheduleFallback(nativeSong, 0L)
    } else if (
        !originalMetadataPlan.waitForResult &&
        !nativeSong.lyrics.isNullOrEmpty() &&
        needsOnlineEnrichment(nativeSong) &&
        isAppleTranslationEnrichmentEnabled()
    ) {
        scheduleOnlineTranslation(nativeSong)
    }
}

private fun LyriconSource.applyOnlineTranslationPreferenceChange(key: String) {
    if (activeCentralPlayerPackageName != null &&
        activeCentralPlayerPackageName != LyriconSource.APPLE_MUSIC_PACKAGE
    ) {
        val song = currentThirdPartySong ?: return
        cancelThirdPartyFallback(reason = "third_party_preference_changed")
        cancelOnlineTranslation(
            clearAttempt = true,
            clearMatched = true,
            reason = "third_party_preference_changed",
        )
        if (currentPublishedThirdPartySong != song) {
            publishThirdPartySong(song, restorePosition = true)
        }
        reevaluateThirdPartyOnlineMatching(song)
        return
    }

    val nativeSong = currentAppleSong ?: return
    val sourcePreferenceChanged = OnlineTranslationSourcePreferences.isSourcePreference(key)
    val overlayEnabled = isOnlineTranslationEnabledFor(LyriconSource.APPLE_MUSIC_PACKAGE)
    val nativeEnabled = isNativeOnlineTranslationEnabled()

    if (!overlayEnabled && !nativeEnabled && !isFillMissingLyricsEnabled()) {
        val shouldRestoreNative = fallbackSongActive || onlineMatchedTranslationActive
        cancelFallback(clearAppleSong = false, reason = "online_translation_disabled")
        cancelOnlineTranslation(
            clearAttempt = true,
            clearMatched = true,
            reason = "online_translation_disabled",
        )
        if (shouldRestoreNative) {
            publishAppleSong(nativeSong, restorePosition = true)
        }
        return
    }

    if (needsMissingLyricsSourceRecovery(nativeSong)) {
        if (!overlayEnabled && !isFillMissingLyricsEnabled()) return
        val delayMs = if (sourcePreferenceChanged && fallbackSongActive) {
            0L
        } else {
            LyriconSource.APPLE_LYRICS_GRACE_MS
        }
        scheduleFallback(nativeSong, delayMs)
        observeAppleMediaSession(force = true)
        return
    }

    if (!needsOnlineEnrichment(nativeSong)) return

    if (!overlayEnabled) {
        val shouldRestoreNative = fallbackSongActive || onlineMatchedTranslationActive
        cancelFallback(clearAppleSong = false, reason = "overlay_online_disabled")
        if (shouldRestoreNative) {
            publishAppleSong(nativeSong, restorePosition = true)
        }
    } else if (sourcePreferenceChanged && currentPublishedAppleSong != nativeSong) {
        publishAppleSong(nativeSong, restorePosition = true)
    }

    cancelOnlineTranslation(
        clearAttempt = true,
        clearMatched = false,
        reason = if (sourcePreferenceChanged) {
            "source_configuration_changed"
        } else {
            "online_translation_enabled"
        },
    )
    scheduleOnlineTranslation(nativeSong)
}

private fun LyriconSource.applySimplifiedLyricsPreferenceChange() {
    val song = currentPublishedAppleSong ?: return
    publishAppleSong(
        song = song,
        restorePosition = true,
        onlineTranslationMatched = currentPublishedAppleOnlineTranslationMatched
    )
}

internal fun LyriconSource.providerDelayKey(packageName: String): String {
    return RootConstants.KEY_HOOK_LYRICON_PROVIDER_DELAY_PREFIX + packageName
}

internal fun LyriconSource.readProviderDelay(packageName: String): Int {
    return prefs?.getInt(
        providerDelayKey(packageName),
        RootConstants.DEFAULT_HOOK_LYRICON_PROVIDER_DELAY
    )?.coerceIn(
        RootConstants.MIN_HOOK_LYRICON_PROVIDER_DELAY,
        RootConstants.MAX_HOOK_LYRICON_PROVIDER_DELAY
    ) ?: RootConstants.DEFAULT_HOOK_LYRICON_PROVIDER_DELAY
}
