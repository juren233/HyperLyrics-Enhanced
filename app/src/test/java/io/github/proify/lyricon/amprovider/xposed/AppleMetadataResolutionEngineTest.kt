package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMetadataResolutionEngineTest {

    @Test
    fun `confirmed original metadata wins over configured metadata`() {
        val original = alias("Original", "Original Artist", "ja")
        val configured = alias("Configured", "Configured Artist", "zh-Hans")

        val selected = AppleMetadataResolutionEngine.selectEffectiveMetadataAlias(
            restoreOriginalEnabled = true,
            originalMetadataResolved = true,
            originalMetadata = original,
            originalArtistResolved = true,
            originalArtist = null,
            localizedMetadata = configured,
            localizedArtist = null,
        )

        assertSame(original, selected)
    }

    @Test
    fun `configured metadata is visible while original resolution is pending`() {
        val configured = alias("Configured", "Configured Artist", "zh-Hans")

        val selected = AppleMetadataResolutionEngine.selectEffectiveMetadataAlias(
            restoreOriginalEnabled = true,
            originalMetadataResolved = false,
            originalMetadata = null,
            originalArtistResolved = false,
            originalArtist = null,
            localizedMetadata = configured,
            localizedArtist = null,
        )

        assertSame(configured, selected)
    }

    @Test
    fun `resolved original miss falls back to configured alias`() {
        val configured = alias("Configured", "Configured Artist", "zh-Hans")

        val selected = AppleMetadataResolutionEngine.selectEffectiveMetadataAlias(
            restoreOriginalEnabled = true,
            originalMetadataResolved = true,
            originalMetadata = null,
            originalArtistResolved = true,
            originalArtist = null,
            localizedMetadata = configured,
            localizedArtist = null,
        )

        assertSame(configured, selected)
    }

    @Test
    fun `associated artist alias requires one non collaboration artist id`() {
        assertEquals(
            "10",
            AppleMetadataResolutionEngine.sharedAssociatedArtistId(
                artistIds = listOf("10"),
                artistCredit = "Artist",
            ),
        )
        assertNull(
            AppleMetadataResolutionEngine.sharedAssociatedArtistId(
                artistIds = listOf("10", "11"),
                artistCredit = "Artist A & Artist B",
            )
        )
        assertNull(
            AppleMetadataResolutionEngine.sharedAssociatedArtistId(
                artistIds = listOf("10"),
                artistCredit = "Artist A feat. Artist B",
            )
        )
    }

    @Test
    fun `request plan keeps original first distinct from configured first`() {
        val originalFirst = AppleMetadataResolutionEngine.inAppOriginalResolutionPlan(
            mediaIds = listOf("1", "1", "2"),
            awaitingLocalizedIds = setOf("2"),
            mode = InAppOriginalResolutionMode.ORIGINAL_FIRST,
        )
        val configuredFirst = AppleMetadataResolutionEngine.inAppOriginalResolutionPlan(
            mediaIds = listOf("1", "1", "2"),
            awaitingLocalizedIds = setOf("2"),
            mode = InAppOriginalResolutionMode.AFTER_LOCALIZED,
        )

        assertEquals(listOf("1", "2"), originalFirst.beforeLocalized)
        assertFalse(originalFirst.resolveLocalizedImmediately)
        assertEquals(listOf("1"), configuredFirst.afterLocalized)
        assertTrue(configuredFirst.resolveLocalizedImmediately)
    }

    @Test
    fun `request decision waits for both song and safe associated artist state`() {
        assertTrue(
            AppleMetadataResolutionEngine.shouldRequestEffectiveMetadataResolution(
                restoreOriginalEnabled = true,
                originalMetadataResolved = true,
                hasOriginalMetadata = true,
                hasAssociatedArtists = true,
                originalArtistResolved = false,
                hasLocalizedMetadata = true,
            )
        )
        assertFalse(
            AppleMetadataResolutionEngine.shouldRequestEffectiveMetadataResolution(
                restoreOriginalEnabled = true,
                originalMetadataResolved = true,
                hasOriginalMetadata = true,
                hasAssociatedArtists = true,
                originalArtistResolved = true,
                hasLocalizedMetadata = true,
            )
        )
    }

    private fun alias(
        title: String,
        artist: String,
        language: String,
    ): AppleInternalCatalogResolver.Alias = AppleInternalCatalogResolver.Alias(
        title = title,
        artist = artist,
        language = language,
        album = "Album",
    )
}
