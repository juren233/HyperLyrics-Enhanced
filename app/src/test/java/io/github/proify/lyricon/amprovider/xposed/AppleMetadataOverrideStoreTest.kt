package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMetadataOverrideStoreTest {

    @Test
    fun `configured and original aliases remain independent`() {
        val store = AppleMetadataOverrideStore()
        val configured = alias("Configured", "Configured Artist", "zh-Hans")
        val original = alias("Original", "Original Artist", "ja")

        store.rememberConfiguredMetadata("1", configured)
        store.rememberOriginalMetadata("1", original, confirmed = true)

        assertSame(configured, store.configuredMetadata("1"))
        assertSame(original, store.originalMetadata("1"))
        assertTrue(store.isOriginalMetadataConfirmed("1"))
    }

    @Test
    fun `unconfirmed original cannot replace confirmed original`() {
        val store = AppleMetadataOverrideStore()
        val confirmed = alias("Confirmed", "Artist", "ja")
        val candidate = alias("Candidate", "Artist", "ja")

        store.rememberOriginalMetadata("1", confirmed, confirmed = true)
        store.rememberOriginalMetadata("1", candidate, confirmed = false)

        assertSame(confirmed, store.originalMetadata("1"))
        assertTrue(store.isOriginalMetadataConfirmed("1"))
    }

    @Test
    fun `associated artist ids are normalized merged and reverse indexed`() {
        val store = AppleMetadataOverrideStore()

        assertTrue(store.mergeAssociatedArtistIds("song", listOf(" 10 ", "0", "bad")))
        assertFalse(store.mergeAssociatedArtistIds("song", listOf("10")))
        assertTrue(store.mergeAssociatedArtistIds("song", listOf("11")))
        store.trackAssociatedMediaId("id:10", "song")

        assertEquals(listOf("10", "11"), store.associatedArtistIds("song"))
        assertEquals(setOf("song"), store.associatedMediaIds("id:10"))
    }

    @Test
    fun `request registries reject duplicates and allow completion`() {
        val store = AppleMetadataOverrideStore()

        assertTrue(store.beginConfiguredRequest("configured"))
        assertFalse(store.beginConfiguredRequest("configured"))
        store.finishConfiguredRequest("configured")
        assertTrue(store.beginConfiguredRequest("configured"))

        assertTrue(store.beginOriginalRequest("original"))
        assertFalse(store.beginOriginalRequest("original"))
        store.finishOriginalRequest("original")
        assertTrue(store.beginOriginalRequest("original"))

        assertTrue(store.beginAssociatedArtistRequest("artist"))
        assertFalse(store.beginAssociatedArtistRequest("artist"))
        store.finishAssociatedArtistRequest("artist")
        assertTrue(store.beginAssociatedArtistRequest("artist"))
    }

    @Test
    fun `cache miss retry state remains separate from pending state`() {
        val store = AppleMetadataOverrideStore()

        assertTrue(store.markOriginalPending("1"))
        store.recordOriginalCacheMiss("1", 123L)
        store.markOriginalResolved("1")

        assertTrue(store.isOriginalPending("1"))
        assertTrue(store.isOriginalResolved("1"))
        assertEquals(123L, store.originalCacheMissUptimeMillis("1"))

        store.clearOriginalPending("1")
        store.clearOriginalCacheMiss("1")
        assertFalse(store.isOriginalPending("1"))
        assertNull(store.originalCacheMissUptimeMillis("1"))
    }

    @Test
    fun `configuration event clears overrides and requests but preserves identity facts`() {
        val store = AppleMetadataOverrideStore()
        val value = alias("Title", "Artist", "ja")
        store.rememberConfiguredMetadata("1", value)
        store.rememberOriginalMetadata("1", value, confirmed = true)
        store.rememberConfiguredArtist("1", value)
        store.rememberOriginalArtist("1", value)
        store.mergeAccountMetadata("1", AccountMetadata("Title", "Artist"))
        store.mergeLookupIds("1", setOf("1", "2"))
        store.rememberEntityType("1", AppleInternalCatalogResolver.LocalizedEntityType.SONG)
        store.mergeArtistKeys("1", setOf("id:9"))
        store.mergeAssociatedArtistIds("1", listOf("9"))
        store.markOriginalPending("1")
        store.markConfiguredMiss("miss")
        store.updateCurrentPlaybackOverride(value)

        store.onConfigurationChanged()

        assertNull(store.configuredMetadata("1"))
        assertNull(store.originalMetadata("1"))
        assertNull(store.configuredArtist("1"))
        assertNull(store.originalArtist("1"))
        assertEquals(AccountMetadata("Title", "Artist"), store.accountMetadata("1"))
        assertEquals(setOf("1", "2"), store.lookupIds("1"))
        assertEquals(
            AppleInternalCatalogResolver.LocalizedEntityType.SONG,
            store.entityType("1"),
        )
        assertEquals(setOf("id:9"), store.artistKeys("1"))
        assertEquals(listOf("9"), store.associatedArtistIds("1"))
        assertFalse(store.isOriginalPending("1"))
        assertFalse(store.isConfiguredMiss("miss"))
        assertNull(store.currentPlaybackOverride())
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
