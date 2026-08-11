/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialProviderDexMethodSemanticFilterTest {
    @Test
    fun `rejects candidate that invokes a forbidden descriptor`() {
        assertFalse(
            OfficialProviderDexMethodSemanticFilter.accepts(
                invokedMethodDescriptors = listOf(
                    "Lcom/tencent/qqmusic/openapisdk/model/SongInfo;->getSongId()J",
                    "Lcom/tencent/qqmusic/qplayer/core/player/MusicPlayList;->f(J)Ljava/lang/Object;",
                ),
                forbiddenInvokedMethodDescriptors = listOf(
                    "Lcom/tencent/qqmusic/openapisdk/model/SongInfo;->getSongId()J",
                ),
            ),
        )
    }

    @Test
    fun `accepts candidate whose invokes do not contain forbidden descriptors`() {
        assertTrue(
            OfficialProviderDexMethodSemanticFilter.accepts(
                invokedMethodDescriptors = listOf(
                    "Lcom/tencent/qqmusic/qplayer/core/player/MusicPlayListManager;->u()Ljava/lang/Object;",
                ),
                forbiddenInvokedMethodDescriptors = listOf(
                    "Lcom/tencent/qqmusic/openapisdk/model/SongInfo;->getSongId()J",
                ),
            ),
        )
    }

    @Test
    fun `accepts without a negative constraint`() {
        assertTrue(
            OfficialProviderDexMethodSemanticFilter.accepts(
                invokedMethodDescriptors = listOf("Lexample/Owner;->a()V"),
                forbiddenInvokedMethodDescriptors = emptyList(),
            ),
        )
    }
}
