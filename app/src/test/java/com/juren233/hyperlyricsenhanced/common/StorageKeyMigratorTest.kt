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

    @Test
    fun `legacy line to word switch maps to TTML only mode`() {
        assertEquals(
            RootConstants.APPLE_MUSIC_LINE_TO_WORD_SOURCE_MODE_TTML_ONLY,
            StorageKeyMigrator.lineToWordModeWrite(true, emptySet()),
        )
    }

    @Test
    fun `disabled or missing legacy line to word switch keeps default mode`() {
        assertEquals(null, StorageKeyMigrator.lineToWordModeWrite(false, emptySet()))
        assertEquals(null, StorageKeyMigrator.lineToWordModeWrite(null, emptySet()))
    }

    @Test
    fun `existing mode key is never overwritten by legacy line to word switch`() {
        assertEquals(
            null,
            StorageKeyMigrator.lineToWordModeWrite(
                true,
                setOf(RootConstants.KEY_HOOK_APPLE_MUSIC_LINE_TO_WORD_SOURCE_MODE),
            ),
        )
    }

    @Test
    fun `restored legacy line to word switch clears the device mode key`() {
        assertEquals(
            setOf(RootConstants.KEY_HOOK_APPLE_MUSIC_LINE_TO_WORD_SOURCE_MODE),
            StorageKeyMigrator.keysSupersededByRestoredLegacy(setOf(upgradeKey, "key_other")),
        )
    }

    @Test
    fun `restored legacy LunaBeat switch clears every key along the migration chain`() {
        assertEquals(
            setOf(sourceKey, upgradeKey, RootConstants.KEY_HOOK_APPLE_MUSIC_LINE_TO_WORD_SOURCE_MODE),
            StorageKeyMigrator.keysSupersededByRestoredLegacy(setOf("key_hook_apple_music_lunabeat_word_lyrics")),
        )
    }

    @Test
    fun `keys carried by the backup itself are never cleared`() {
        assertEquals(
            setOf(upgradeKey),
            StorageKeyMigrator.keysSupersededByRestoredLegacy(
                setOf(
                    "key_hook_apple_music_lunabeat_word_lyrics",
                    sourceKey,
                    RootConstants.KEY_HOOK_APPLE_MUSIC_LINE_TO_WORD_SOURCE_MODE,
                ),
            ),
        )
        assertEquals(
            emptySet<String>(),
            StorageKeyMigrator.keysSupersededByRestoredLegacy(
                setOf(RootConstants.KEY_HOOK_APPLE_MUSIC_LINE_TO_WORD_SOURCE_MODE, "key_other"),
            ),
        )
    }

    @Test
    fun `NetEase and QQ Music in the more apps list move to their own switch keys`() {
        val kugou = "com.kugou.android.lite"
        assertEquals(
            StorageKeyMigrator.ListBackedSwitchMigration(
                packages = setOf(kugou),
                enabledKeys = setOf(neteaseSwitchKey, qqSwitchKey),
            ),
            StorageKeyMigrator.listBackedSwitchMigration(
                setOf("com.netease.cloudmusic", "com.tencent.qqmusic", kugou),
            ),
        )
    }

    @Test
    fun `more apps list without NetEase or QQ Music needs no switch migration`() {
        assertEquals(null, StorageKeyMigrator.listBackedSwitchMigration(setOf("com.kugou.android.lite")))
        assertEquals(null, StorageKeyMigrator.listBackedSwitchMigration(emptySet()))
    }

    @Test
    fun `restored more apps list decides NetEase and QQ Music switches it does not carry`() {
        assertEquals(
            setOf(neteaseSwitchKey, qqSwitchKey),
            StorageKeyMigrator.keysSupersededByRestoredLegacy(setOf(MediaSessionOnlineFallbackPreferences.PACKAGES)),
        )
        assertEquals(
            setOf(qqSwitchKey),
            StorageKeyMigrator.keysSupersededByRestoredLegacy(
                setOf(MediaSessionOnlineFallbackPreferences.PACKAGES, neteaseSwitchKey),
            ),
        )
    }

    private val neteaseSwitchKey = "key_hook_online_translation_app_netease"
    private val qqSwitchKey = "key_hook_online_translation_app_qq"
}
