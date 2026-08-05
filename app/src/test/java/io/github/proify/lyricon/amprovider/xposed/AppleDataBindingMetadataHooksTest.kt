/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleDataBindingMetadataHooksTest {

    @Test
    fun `carousel holder keeps every captured song id`() {
        assertEquals(
            linkedSetOf("1797266719", "1559632900", "1559632901", "1643823927"),
            normalizedRecyclerBindingMediaIds(
                listOf(
                    "1797266719",
                    "1559632900",
                    "1559632901",
                    "1643823927",
                    "1797266719",
                    "",
                    "not-a-catalog-id",
                )
            ),
        )
    }

    @Test
    fun `carousel holder never registers a structural recycler refresh`() {
        assertFalse(
            shouldRegisterGenericRecyclerRefresh(
                mediaIds = linkedSetOf(
                    "1797266719",
                    "1559632900",
                    "1559632901",
                    "1643823927",
                ),
                dataBindingMediaId = null,
                blockMultiItemStructuralRefresh = true,
            )
        )
    }

    @Test
    fun `other carousel holders retain their existing recycler refresh fallback`() {
        assertTrue(
            shouldRegisterGenericRecyclerRefresh(
                mediaIds = linkedSetOf("1797266719", "1559632900"),
                dataBindingMediaId = null,
                blockMultiItemStructuralRefresh = false,
            )
        )
    }

    @Test
    fun `single song binding uses direct binding refresh instead of recycler rebuild`() {
        assertFalse(
            shouldRegisterGenericRecyclerRefresh(
                mediaIds = setOf("1505498782"),
                dataBindingMediaId = "1505498782",
                blockMultiItemStructuralRefresh = false,
            )
        )
    }

    @Test
    fun `single unbound recycler row retains precise item refresh fallback`() {
        assertTrue(
            shouldRegisterGenericRecyclerRefresh(
                mediaIds = setOf("1505498782"),
                dataBindingMediaId = null,
                blockMultiItemStructuralRefresh = false,
            )
        )
    }

    @Test
    fun `visible recycler metadata skips empty hidden and repeated bindings`() {
        val mediaIds = setOf("1505498782")

        assertFalse(
            shouldScheduleVisibleRecyclerMetadata(
                previousMediaIds = null,
                currentMediaIds = emptySet(),
                visible = true,
            )
        )
        assertFalse(
            shouldScheduleVisibleRecyclerMetadata(
                previousMediaIds = null,
                currentMediaIds = mediaIds,
                visible = false,
            )
        )
        assertFalse(
            shouldScheduleVisibleRecyclerMetadata(
                previousMediaIds = mediaIds,
                currentMediaIds = mediaIds,
                visible = true,
            )
        )
        assertTrue(
            shouldScheduleVisibleRecyclerMetadata(
                previousMediaIds = mediaIds,
                currentMediaIds = setOf("1519740112"),
                visible = true,
            )
        )
    }

    @Test
    fun `pending data binding alias suppresses duplicate queued refreshes`() {
        val requested = AppliedMetadataAlias(
            mediaId = "1445886021",
            alias = AppleInternalCatalogResolver.Alias(
                title = "Come Back to Me",
                artist = "宇多田ヒカル",
                album = "This Is the One",
                language = "ja-JP",
            ),
        )

        assertFalse(
            shouldScheduleDataBindingAliasRefresh(
                appliedAlias = null,
                pendingAlias = requested,
                requestedAlias = requested,
            )
        )
        assertFalse(
            shouldScheduleDataBindingAliasRefresh(
                appliedAlias = requested,
                pendingAlias = null,
                requestedAlias = requested,
            )
        )
        assertTrue(
            shouldScheduleDataBindingAliasRefresh(
                appliedAlias = requested,
                pendingAlias = null,
                requestedAlias = requested.copy(artist = "Utada"),
            )
        )
    }

    @Test
    fun `already rendered album header skips another binding refresh`() {
        assertTrue(
            dataBindingAliasAlreadyRendered(
                expectedTitle = "Pre: Prema",
                expectedSubtitle = "藤井 風",
                renderedTexts = listOf("Pre: Prema", "藤井 風 · 2025年"),
            )
        )
    }

    @Test
    fun `album header still refreshes when only its artist is stale`() {
        assertFalse(
            dataBindingAliasAlreadyRendered(
                expectedTitle = "Pre: Prema",
                expectedSubtitle = "藤井 風",
                renderedTexts = listOf("Pre: Prema", "藤井风 · 2025年"),
            )
        )
    }

    @Test
    fun `data binding refresh is rejected after the card targets another item`() {
        assertFalse(
            isDataBindingRefreshCurrent(
                currentMediaId = "l.AgkTCQ8",
                requestedMediaId = "1648875799",
                currentBindGeneration = 12L,
                scheduledBindGeneration = 12L,
            )
        )
    }

    @Test
    fun `data binding refresh is rejected after another bind generation starts`() {
        assertFalse(
            isDataBindingRefreshCurrent(
                currentMediaId = "1648875799",
                requestedMediaId = "1648875799",
                currentBindGeneration = 13L,
                scheduledBindGeneration = 12L,
            )
        )
    }

    @Test
    fun `data binding refresh remains valid for the same item and generation`() {
        assertTrue(
            isDataBindingRefreshCurrent(
                currentMediaId = "1648875799",
                requestedMediaId = "1648875799",
                currentBindGeneration = 12L,
                scheduledBindGeneration = 12L,
            )
        )
    }

    @Test
    fun `successful data binding variables do not invalidate the full header`() {
        assertEquals(
            DataBindingRefreshStrategy.VARIABLES_ONLY,
            dataBindingRefreshStrategy(
                expectedTitle = "Pre: Prema",
                expectedSubtitle = "藤井 風",
                titleApplied = true,
                subtitleApplied = true,
            ),
        )
    }

    @Test
    fun `missing data binding variable keeps the structural refresh fallback`() {
        assertEquals(
            DataBindingRefreshStrategy.FULL_INVALIDATE,
            dataBindingRefreshStrategy(
                expectedTitle = "Pre: Prema",
                expectedSubtitle = "藤井 風",
                titleApplied = true,
                subtitleApplied = false,
            ),
        )
        assertEquals(
            DataBindingRefreshStrategy.FULL_INVALIDATE,
            dataBindingRefreshStrategy(
                expectedTitle = null,
                expectedSubtitle = null,
                titleApplied = false,
                subtitleApplied = false,
            ),
        )
    }
}
