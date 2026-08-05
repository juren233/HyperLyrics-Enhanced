/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import java.lang.ref.WeakReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleListenNowHooksTest {

    @Test
    fun `refreshes when Apple restores stale text for an applied alias`() {
        val alias = listenNowAlias()

        assertTrue(
            shouldRefreshListenNowDataBindingAlias(
                appliedAlias = alias,
                requestedAlias = alias,
                expectedTitle = "盗作",
                expectedSubtitle = "ヨルシカ",
                renderedTexts = listOf("Plagiarism", "Yorushika"),
            )
        )
    }

    @Test
    fun `skips refresh while an applied alias is still rendered`() {
        val alias = listenNowAlias()

        assertFalse(
            shouldRefreshListenNowDataBindingAlias(
                appliedAlias = alias,
                requestedAlias = alias,
                expectedTitle = "盗作",
                expectedSubtitle = "ヨルシカ",
                renderedTexts = listOf("盗作", "ヨルシカ"),
            )
        )
    }

    @Test
    fun `model build state accepts only the first exact catalog id`() {
        val entity = Any()
        val liveData = Any()
        val state = InAppListenNowModelBuildState(
            entity = WeakReference(entity),
            liveData = WeakReference(liveData),
            builderKey = listenNowBuilderKey(),
            initialCatalogId = null,
            builtAlias = null,
        )

        assertTrue(state.assignCatalogId("1722205323"))
        assertFalse(state.assignCatalogId("1519740112"))
        assertEquals("1722205323", state.catalogId)
        assertTrue(state.entity.get() === entity)
        assertTrue(state.liveData.get() === liveData)
    }

    @Test
    fun `artwork values normalize empty strings arrays and duplicates`() {
        assertEquals(emptyList<String>(), normalizedInAppArtworkValueUrls(null))
        assertEquals(emptyList<String>(), normalizedInAppArtworkValueUrls(emptyArray<String>()))
        assertEquals(
            listOf("https://example.test/cover.jpg"),
            normalizedInAppArtworkValueUrls(
                arrayOf(
                    "  https://example.test/cover.jpg  ",
                    null,
                    "",
                    "https://example.test/cover.jpg",
                )
            ),
        )
        assertEquals(
            listOf("https://example.test/single.jpg"),
            normalizedInAppArtworkValueUrls("  https://example.test/single.jpg "),
        )
    }

    @Test
    fun `skips a repeated lookup only for the matching non-empty seeded value`() {
        assertTrue(
            shouldSkipInAppListenNowArtworkLookup(
                keyMatches = true,
                currentUrls = listOf("https://example.test/cover.jpg"),
                seededUrls = listOf("https://example.test/cover.jpg"),
            )
        )
        assertFalse(
            shouldSkipInAppListenNowArtworkLookup(
                keyMatches = false,
                currentUrls = listOf("https://example.test/cover.jpg"),
                seededUrls = listOf("https://example.test/cover.jpg"),
            )
        )
        assertFalse(
            shouldSkipInAppListenNowArtworkLookup(
                keyMatches = true,
                currentUrls = emptyList(),
                seededUrls = listOf("https://example.test/cover.jpg"),
            )
        )
        assertFalse(
            shouldSkipInAppListenNowArtworkLookup(
                keyMatches = true,
                currentUrls = listOf("https://example.test/current.jpg"),
                seededUrls = listOf("https://example.test/seeded.jpg"),
            )
        )
        assertFalse(
            shouldSkipInAppListenNowArtworkLookup(
                keyMatches = true,
                currentUrls = listOf("https://example.test/cover.jpg"),
                seededUrls = emptyList(),
            )
        )
    }

    @Test
    fun `keeps the builder identity across the delegate id namespace change`() {
        val builderKey = listenNowBuilderKey()
        val delegateKey = builderKey.copy(id = "1722205323")

        assertEquals(
            builderKey,
            preferredInAppListenNowArtworkKey(
                builderKey = builderKey,
                delegateKey = delegateKey,
            ),
        )
        assertEquals(
            delegateKey,
            preferredInAppListenNowArtworkKey(
                builderKey = null,
                delegateKey = delegateKey,
            ),
        )
    }

    @Test
    fun `maps a local library id only through the exact card LiveData`() {
        val liveData = Any()
        val builderKey = listenNowBuilderKey()

        assertEquals(
            "1722205323",
            listenNowCatalogIdForExactCard(
                builderLiveData = liveData,
                delegateLiveData = liveData,
                builderKey = builderKey,
                delegateKey = builderKey.copy(id = "1722205323"),
            ),
        )
    }

    @Test
    fun `rejects a catalog mapping from another card instance`() {
        val builderKey = listenNowBuilderKey()

        assertNull(
            listenNowCatalogIdForExactCard(
                builderLiveData = Any(),
                delegateLiveData = Any(),
                builderKey = builderKey,
                delegateKey = builderKey.copy(id = "1722205323"),
            )
        )
    }

    @Test
    fun `rejects mismatched card identity and conflicting catalog ids`() {
        val liveData = Any()
        val builderKey = listenNowBuilderKey()
        val delegateKey = builderKey.copy(id = "1722205323")

        assertNull(
            listenNowCatalogIdForExactCard(
                builderLiveData = liveData,
                delegateLiveData = liveData,
                builderKey = null,
                delegateKey = delegateKey,
            )
        )
        assertNull(
            listenNowCatalogIdForExactCard(
                builderLiveData = liveData,
                delegateLiveData = liveData,
                builderKey = builderKey,
                delegateKey = null,
            )
        )
        listOf(
            delegateKey.copy(persistentId = delegateKey.persistentId + 1L),
            delegateKey.copy(contentType = 4),
            delegateKey.copy(artworkIdentity = "another-artwork-token"),
            delegateKey.copy(id = "l.not-a-catalog-id"),
        ).forEach { mismatchedDelegate ->
            assertNull(
                listenNowCatalogIdForExactCard(
                    builderLiveData = liveData,
                    delegateLiveData = liveData,
                    builderKey = builderKey,
                    delegateKey = mismatchedDelegate,
                )
            )
        }
        assertNull(
            listenNowCatalogIdForExactCard(
                builderLiveData = liveData,
                delegateLiveData = liveData,
                builderKey = builderKey.copy(id = "1519740112"),
                delegateKey = delegateKey,
            )
        )
    }

    private fun listenNowAlias(): AppliedMetadataAlias = AppliedMetadataAlias(
        mediaId = "1519740112",
        alias = AppleInternalCatalogResolver.Alias(
            title = "盗作",
            artist = "ヨルシカ",
            album = "盗作",
            language = "ja-JP",
        ),
    )

    private fun listenNowBuilderKey(): InAppListenNowArtworkContinuityKey =
        InAppListenNowArtworkContinuityKey(
            id = "l.AgkTCQ8",
            persistentId = 7_598_459_202_544_610_309L,
            contentType = 3,
            artworkIdentity = "shared-artwork-token",
        )
}
