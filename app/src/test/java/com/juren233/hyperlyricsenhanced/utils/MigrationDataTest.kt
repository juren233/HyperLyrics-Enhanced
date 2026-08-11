package com.juren233.hyperlyricsenhanced.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationDataTest {
    @Test
    fun `7 4 notice uses the plugin system copy with requested spacing`() {
        val item = MigrationData.notes
            .single { note -> note.versionCode == 140000 }
            .items
            .single()

        assertEquals("Lyricon歌词源已升级至插件体系", item.text)
        assertEquals(
            "\n现在 HyperLyrics Enhanced 已将 Lyricon 的词幕服务模块与主流音乐平台的 Provider 模块整合在一起，作为插件功能，实现无需额外模块，还能满足各取所需的需求。\n\n更多详情请在“歌词设置”-“Lyricon配置”查看。",
            item.summary,
        )
    }

    @Test
    fun `notice applies when current build exactly reaches its threshold`() {
        val notes = MigrationData.notesForUpgrade(
            lastSeenVersionCode = 131000,
            currentVersionCode = 140000,
            currentVersionName = "7.4.0-beta",
        )

        assertEquals(listOf(140000), notes.map(MigrationNote::versionCode).distinct())
    }

    @Test
    fun `notice remains applicable after development build number increases`() {
        val notes = MigrationData.notesForUpgrade(
            lastSeenVersionCode = 131000,
            currentVersionCode = 142037,
            currentVersionName = "7.4.2",
        )

        assertEquals(listOf(140000), notes.map(MigrationNote::versionCode).distinct())
    }

    @Test
    fun `confirmed notice is not shown again on later builds`() {
        assertTrue(
            MigrationData.notesForUpgrade(
                lastSeenVersionCode = 140001,
                currentVersionCode = 140002,
                currentVersionName = "7.4.0-beta.2",
            ).isEmpty(),
        )
    }

    @Test
    fun `notice stops at the next minor version`() {
        assertTrue(
            MigrationData.notesForUpgrade(
                lastSeenVersionCode = 0,
                currentVersionCode = 150000,
                currentVersionName = "7.5.0",
            ).isEmpty(),
        )
    }

    @Test
    fun `same minor number in another major version does not match`() {
        assertTrue(
            MigrationData.notesForUpgrade(
                lastSeenVersionCode = 0,
                currentVersionCode = 840000,
                currentVersionName = "8.4.0",
            ).isEmpty(),
        )
    }

    @Test
    fun `fresh install within the same minor line receives the notice`() {
        val notes = MigrationData.notesForUpgrade(
            lastSeenVersionCode = 0,
            currentVersionCode = 141000,
            currentVersionName = "7.4.1",
        )

        assertEquals(listOf(140000), notes.map(MigrationNote::versionCode).distinct())
    }

    @Test
    fun `downgrade or unchanged build does not show an upgrade notice`() {
        assertTrue(
            MigrationData.notesForUpgrade(
                lastSeenVersionCode = 140001,
                currentVersionCode = 140001,
                currentVersionName = "7.4.0-beta",
            ).isEmpty(),
        )
        assertTrue(
            MigrationData.notesForUpgrade(
                lastSeenVersionCode = 140001,
                currentVersionCode = 140000,
                currentVersionName = "7.4.0-beta",
            ).isEmpty(),
        )
    }

    @Test
    fun `historical notices keep exact build behavior`() {
        assertEquals(
            listOf(100001),
            MigrationData.notesForUpgrade(
                lastSeenVersionCode = 0,
                currentVersionCode = 100001,
                currentVersionName = "7.0.0",
            ).map(MigrationNote::versionCode).distinct(),
        )
        assertTrue(
            MigrationData.notesForUpgrade(
                lastSeenVersionCode = 0,
                currentVersionCode = 100002,
                currentVersionName = "7.0.0",
            ).isEmpty(),
        )
    }
}
