package com.juren233.hyperlyricsenhanced.common.lyric

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleOriginalMetadataPolicyTest {
    @Test
    fun `hides configured region metadata while original region is pending`() {
        assertFalse(
            AppleOriginalMetadataPolicy.shouldExposeLocalizedMetadata(
                restoreOriginalEnabled = true,
                originalResolved = false,
                hasOriginalMetadata = false,
            )
        )
    }

    @Test
    fun `exposes configured region metadata after original lookup misses`() {
        assertTrue(
            AppleOriginalMetadataPolicy.shouldExposeLocalizedMetadata(
                restoreOriginalEnabled = true,
                originalResolved = true,
                hasOriginalMetadata = false,
            )
        )
    }

    @Test
    fun `always exposes confirmed original metadata`() {
        assertTrue(
            AppleOriginalMetadataPolicy.shouldExposeLocalizedMetadata(
                restoreOriginalEnabled = true,
                originalResolved = false,
                hasOriginalMetadata = true,
            )
        )
    }

    @Test
    fun `resolves romanized CJK catalog songs`() {
        assertTrue(
            AppleOriginalMetadataPolicy.shouldResolveCjkOriginalMetadata(
                mediaId = "1882935962",
                title = "Michi Teyu Ku",
                artist = "Fujii Kaze",
                genre = "J-Pop",
            )
        )
    }

    @Test
    fun `skips titles already using CJK script`() {
        assertFalse(
            AppleOriginalMetadataPolicy.shouldResolveCjkOriginalMetadata(
                mediaId = "1882935962",
                title = "満ちてゆく",
                artist = "藤井 風",
                genre = "J-Pop",
            )
        )
    }

    @Test
    fun `skips non CJK catalog genres`() {
        assertFalse(
            AppleOriginalMetadataPolicy.shouldResolveCjkOriginalMetadata(
                mediaId = "123456",
                title = "Hello",
                artist = "Adele",
                genre = "Pop",
            )
        )
    }

    @Test
    fun `recognizes localized CJK catalog genres`() {
        assertTrue(AppleOriginalMetadataPolicy.isCjkGenre("国语流行"))
        assertTrue(AppleOriginalMetadataPolicy.isCjkGenre("日本流行"))
        assertTrue(AppleOriginalMetadataPolicy.isCjkGenre("韓語流行"))
    }

    @Test
    fun `resolves a romanized artist when title already uses original script`() {
        assertTrue(
            AppleOriginalMetadataPolicy.shouldResolveCjkOriginalMetadata(
                mediaId = "1882935962",
                title = "満ちてゆく",
                artist = "Fujii Kaze",
                genre = "J-Pop",
            )
        )
    }

    @Test
    fun `probes a romanized catalog title when genre is missing`() {
        assertTrue(
            AppleOriginalMetadataPolicy.shouldProbeCjkOriginalMetadata(
                mediaId = "1882935962",
                title = "Michi Teyu Ku (Overflowing)",
                artist = "Fujii Kaze",
                genre = null,
            )
        )
    }
}
