/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppleMusicProviderHookOrderTest {

    @Test
    fun `provider installs hook groups in the protected baseline order`() {
        assertEquals(
            PROTECTED_HOOK_ORDER,
            AppleMusicProvider.hookModuleIdsForBuild(debug = true),
        )
        assertEquals(
            PROTECTED_HOOK_ORDER.filterNot(DEBUG_ONLY_HOOKS::contains),
            AppleMusicProvider.hookModuleIdsForBuild(debug = false),
        )
    }

    @Test
    fun `Apple Music remote preference listener keeps a strong owner reference`() {
        val relativeSourcePath =
            "src/main/java/io/github/proify/lyricon/amprovider/xposed/" +
                "AppleMusicProviderOrchestrator.kt"
        val sourceFile = listOf(File("app/$relativeSourcePath"), File(relativeSourcePath))
            .first(File::isFile)
        val source = sourceFile.readText()
        val listenerField = "private var contentUiLanguagePreferenceListener:"
        val listenerAssignment = "contentUiLanguagePreferenceListener = listener"
        val listenerRegistration = "prefs.registerOnSharedPreferenceChangeListener(listener)"

        assertTrue(
            "Remote SharedPreferences listeners are weakly held and need a strong field owner",
            source.contains(listenerField) && source.contains(listenerAssignment),
        )
        assertTrue(
            "The strong listener reference must be assigned before registration",
            source.indexOf(listenerAssignment) in 0 until source.indexOf(listenerRegistration),
        )
        assertTrue(
            "Volume balance changes must emit a runtime diagnostic before reconciliation",
            source.contains("event=preference_changed") &&
                source.contains("playbackHooks.onVolumeBalancePreferenceChanged()"),
        )
    }

    @Test
    fun `missing lyrics refresh preserves native return to lyrics callback order`() {
        val calls = mutableListOf<String>()

        refreshMissingLyricsNowPlaying(
            mediaId = "635770202",
            refreshMetadataCallbacks = { id -> calls += "metadata:$id" },
            refreshPlaybackItemBindings = { id -> calls += "binding:$id" },
        )

        assertEquals(
            listOf("metadata:635770202", "binding:635770202"),
            calls,
        )
    }

    private companion object {
        val PROTECTED_HOOK_ORDER = listOf(
            "hookMetadataSurfaceLifecycle",
            "hookTranslationPreference",
            "hookMediaApiLocalization",
            "hookContentHttpLocalization",
            "hookExoMediaPlayer",
            "hookAtmosVolumeDiagnostics",
            "hookMediaMetadataChange",
            "hookContentItemMetadata",
            "hookInAppLibraryEntities",
            "hookCollectionPageMetadataRefresh",
            "hookArtistProfileTopSongs",
            "hookArtistProfileMetadata",
            "hookRecentlySearchedMetadata",
            "hookInAppArtworkContinuity",
            "hookInAppListenNowArtworkContinuity",
            "hookInAppLibraryEpoxyRefresh",
            "hookInAppLibraryComposeRefresh",
            "hookDebugListenNowArtworkLifecycle",
            "hookVisibleMetadataDiagnostics",
            "hookInAppDataBindingRefresh",
            "hookInAppListenNowMetadataBinding",
            "hookRecyclerViewCentralBinding",
            "hookInAppMetadata",
            "hookInAppPlaybackItemConversion",
            "hookInAppActionSheetMetadata",
            "hookMediaSessionMetadata",
            "hookMediaSessionQueue",
            "hookPlaybackNotificationMetadata",
            "hookAppleOfficialPronunciationLanguageMatching",
            "hookAppleLyricsPreferredLanguages",
            "hookApplePronunciationWordRendering",
            "hookLyricBuildMethod",
            "hookAppleNativeLyricsPresentation",
            "hookAppleSystemFontWeight",
            "hookAppleLyricsBlurEffect",
            "hookAppleLyricsUiDiagnostics",
            "hookAppleLyricsBindingDiagnostics",
            "hookAppleLyricsSourceMenu",
            "hookAppleMissingLyricsSupplement",
            "hookLyricsNetworkRequest",
            "hookLyricsCookies",
            "hookFinalLyricsHttp",
        )

        val DEBUG_ONLY_HOOKS = setOf(
            "hookAtmosVolumeDiagnostics",
            "hookDebugListenNowArtworkLifecycle",
            "hookVisibleMetadataDiagnostics",
            "hookAppleLyricsUiDiagnostics",
            "hookAppleLyricsBindingDiagnostics",
            "hookLyricsNetworkRequest",
            "hookLyricsCookies",
            "hookFinalLyricsHttp",
        )
    }
}
