/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageKeyMigratorTest {
    private val sourceKey = "key_hook_online_source_lunabeat"
    private val upgradeKey = "key_hook_apple_music_upgrade_line_to_word_lyrics"

    @Test
    fun `legacy LunaBeat switch fans out to both new keys`() {
        assertEquals(
            mapOf(sourceKey to true, upgradeKey to true),
            StorageKeyMigrator.lunaBeatWordLyricsWrites(true, emptySet()),
        )
        assertEquals(
            mapOf(sourceKey to false, upgradeKey to false),
            StorageKeyMigrator.lunaBeatWordLyricsWrites(false, emptySet()),
        )
    }

    @Test
    fun `existing new keys are never overwritten`() {
        assertEquals(
            mapOf(upgradeKey to true),
            StorageKeyMigrator.lunaBeatWordLyricsWrites(true, setOf(sourceKey)),
        )
        assertEquals(
            emptyMap<String, Boolean>(),
            StorageKeyMigrator.lunaBeatWordLyricsWrites(true, setOf(sourceKey, upgradeKey)),
        )
    }

    @Test
    fun `no legacy value means nothing to migrate`() {
        assertEquals(emptyMap<String, Boolean>(), StorageKeyMigrator.lunaBeatWordLyricsWrites(null, emptySet()))
    }
}
