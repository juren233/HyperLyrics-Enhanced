/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.island

import android.graphics.drawable.Drawable
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandExpandedMediaBinderMethodProfileTest {
    @Test
    fun `recognizes the OS4 artwork refresh descriptor`() {
        assertTrue(
            IslandExpandedMediaBinderMethodProfile.isArtworkUpdate(
                name = IslandExpandedMediaBinderMethodProfile.OS4_ARTWORK_METHOD,
                returnTypeName = Void.TYPE.name,
                parameterTypeNames = listOf(
                    IslandExpandedMediaBinderMethodProfile.MEDIA_DATA_CLASS,
                    Drawable::class.java.name,
                ),
            ),
        )
    }

    @Test
    fun `rejects non OS4 artwork refresh descriptors`() {
        assertFalse(
            IslandExpandedMediaBinderMethodProfile.isArtworkUpdate(
                name = IslandExpandedMediaBinderMethodProfile.OS4_ARTWORK_METHOD,
                returnTypeName = Void.TYPE.name,
                parameterTypeNames = listOf(
                    "decompiler.alias.MediaData",
                    Drawable::class.java.name,
                ),
            ),
        )
        assertFalse(
            IslandExpandedMediaBinderMethodProfile.isArtworkUpdate(
                name = IslandExpandedMediaBinderMethodProfile.OS4_ARTWORK_METHOD,
                returnTypeName = "java.lang.Object",
                parameterTypeNames = listOf(
                    IslandExpandedMediaBinderMethodProfile.MEDIA_DATA_CLASS,
                    Drawable::class.java.name,
                ),
            ),
        )
    }

    @Test
    fun `keeps the legacy one parameter artwork target`() {
        assertTrue(
            IslandExpandedMediaBinderMethodProfile.isArtworkUpdate(
                name = IslandExpandedMediaBinderMethodProfile.LEGACY_ARTWORK_METHOD,
                returnTypeName = Void.TYPE.name,
                parameterTypeNames = listOf(Drawable::class.java.name),
            ),
        )
    }
}
