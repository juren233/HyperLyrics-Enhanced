/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProviderTargetStateStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun store(): ProviderTargetStateStore =
        ProviderTargetStateStore(temporaryFolder.newFolder("hle_provider"))

    @Test
    fun markerRoundTripAndClear() {
        val store = store()
        val state = ProviderLoadMarker.MarkerState(29, 2, 1_800_000_000_000L)

        store.writeMarker("netease", state)
        assertEquals(state, store.readMarker("netease"))

        store.clearMarker("netease")
        assertNull(store.readMarker("netease"))
    }

    @Test
    fun badVersionsMergeAndPersist() {
        val store = store()

        store.addBadVersion("netease", 30)
        store.addBadVersion("netease", 30)
        store.addBadVersion("netease", 29)

        assertEquals(setOf(29, 30), store.readBadVersions("netease"))
        assertEquals(emptySet<Int>(), store.readBadVersions("qqmusic"))
    }

    @Test
    fun loadedVersionRoundTrip() {
        val store = store()

        assertEquals(0, store.readLoadedVersion("netease"))

        store.writeLoadedVersion("netease", 29)
        assertEquals(29, store.readLoadedVersion("netease"))
    }

    @Test
    fun operationsAreSafeOnMissingFiles() {
        val store = store()

        assertNull(store.readMarker("netease"))
        assertTrue(store.readBadVersions("netease").isEmpty())
        assertEquals(0, store.readLoadedVersion("netease"))
        store.clearMarker("netease")
    }

    @Test
    fun writesSurviveReopeningTheDirectory() {
        val dir = temporaryFolder.newFolder("hle_provider")
        val first = ProviderTargetStateStore(dir)
        first.writeLoadedVersion("netease", 29)
        first.addBadVersion("netease", 28)

        val second = ProviderTargetStateStore(dir)
        assertEquals(29, second.readLoadedVersion("netease"))
        assertEquals(setOf(28), second.readBadVersions("netease"))
    }
}

class ProviderTargetStateParserTest {

    @Test
    fun parsesRootReadbackOutput() {
        val lines = listOf(
            "=== /data/user/0/com.netease.cloudmusic/files/hle_provider/netease.loaded",
            "29",
            "=== /data/user/0/com.netease.cloudmusic/files/hle_provider/netease.bad",
            "30",
            "28",
            "=== /data/user/0/com.tencent.qqmusic/files/hle_provider/qqmusic.loaded",
            "14",
        )

        val snapshot = ProviderTargetStateParser.parse(lines)

        assertEquals(setOf(28, 30), snapshot.badVersionsByPlugin["netease"])
        assertEquals(29, snapshot.loadedVersionByPlugin["netease"])
        assertEquals(14, snapshot.loadedVersionByPlugin["qqmusic"])
    }

    @Test
    fun unionsMultipleTargetsAndTakesMaxLoadedVersion() {
        val lines = listOf(
            "=== /data/user/0/com.netease.cloudmusic/files/hle_provider/netease.loaded",
            "27",
            "=== /data/user/0/com.hihonor.cloudmusic/files/hle_provider/netease.loaded",
            "29",
            "=== /data/user/0/com.hihonor.cloudmusic/files/hle_provider/netease.bad",
            "28",
        )

        val snapshot = ProviderTargetStateParser.parse(lines)

        assertEquals(29, snapshot.loadedVersionByPlugin["netease"])
        assertEquals(setOf(28), snapshot.badVersionsByPlugin["netease"])
    }

    @Test
    fun ignoresUnknownPluginIdsJunkLinesAndMarkerFiles() {
        val lines = listOf(
            "=== /data/user/0/com.example.app/files/hle_provider/not-a-known-plugin.loaded",
            "99",
            "=== /data/user/0/com.netease.cloudmusic/files/hle_provider/netease.marker",
            "29:2:1800000000000",
            "some junk line without header",
            "=== /data/user/0/com.netease.cloudmusic/files/hle_provider/netease.loaded",
            "not-a-number",
        )

        val snapshot = ProviderTargetStateParser.parse(lines)

        assertTrue(snapshot.badVersionsByPlugin.isEmpty())
        assertTrue(snapshot.loadedVersionByPlugin.isEmpty())
    }

    @Test
    fun emptyInputYieldsEmptySnapshot() {
        val snapshot = ProviderTargetStateParser.parse(emptyList())

        assertEquals(ProviderTargetStateSnapshot.EMPTY, snapshot)
    }
}

class ProviderHealthStoreScriptTest {

    @Test
    fun readScriptOnlyTouchesGivenTargetPackages() {
        val script = OfficialProviderHealthStore.buildReadScript(
            listOf("com.netease.cloudmusic", "com.hihonor.cloudmusic"),
        )

        val lines = script.trim().lineSequence().filter(String::isNotBlank).toList()
        assertEquals(3, lines.size)
        assertTrue(
            lines[0].startsWith(
                "for f in /data/user/*/com.netease.cloudmusic/files/hle_provider/* " +
                    "/data_mirror/data_ce/null/*/com.netease.cloudmusic/files/hle_provider/*;",
            ),
        )
        // 状态文件无结尾换行，cat 后必须补 echo，避免下一个头行被接在内容后面。
        assertTrue(lines[0].endsWith("cat \"\$f\" 2>/dev/null; echo; done"))
        assertTrue(lines[1].startsWith("for f in /data/user/*/com.hihonor.cloudmusic/files/hle_provider/* "))
        assertEquals("exit 0", lines[2])
    }

    @Test
    fun readScriptStripsQuoteCharactersFromPackageNames() {
        val script = OfficialProviderHealthStore.buildReadScript(listOf("com.'evil'"))

        assertTrue("com.evil" in script)
        assertFalse("'" in script)
    }

    @Test
    fun clearScriptRemovesOnlyRequestedSuffixes() {
        val script = OfficialProviderHealthStore.buildClearScript(
            pluginId = "netease",
            targetPackages = listOf("com.netease.cloudmusic"),
            fileSuffixes = setOf("marker"),
        )
        val lines = script.trim().lineSequence().toList()
        assertEquals(2, lines.size)
        assertEquals(
            "rm -f /data/user/*/com.netease.cloudmusic/files/hle_provider/netease.marker " +
                "/data_mirror/data_ce/null/*/com.netease.cloudmusic/files/hle_provider/netease.marker " +
                "2>/dev/null",
            lines.first(),
        )

        val fullScript = OfficialProviderHealthStore.buildClearScript(
            pluginId = "netease",
            targetPackages = listOf("com.netease.cloudmusic"),
            fileSuffixes = setOf("marker", "bad", "loaded"),
        )
        val fullLines = fullScript.trim().lineSequence().filter { it != "exit 0" }.toList()
        assertEquals(3, fullLines.size)
        assertTrue(fullLines.any { it.endsWith("netease.marker 2>/dev/null") })
        assertTrue(fullLines.any { it.endsWith("netease.bad 2>/dev/null") })
        assertTrue(fullLines.any { it.endsWith("netease.loaded 2>/dev/null") })
    }
}

class ProviderApplyStateTest {

    @Test
    fun noBadgeWhenNotInstalledOrUpToDate() {
        assertEquals(
            ProviderApplyState.Badge.NONE,
            ProviderApplyState.badge(0, 0, emptySet()),
        )
        assertEquals(
            ProviderApplyState.Badge.NONE,
            ProviderApplyState.badge(29, 29, emptySet()),
        )
        assertEquals(
            ProviderApplyState.Badge.NONE,
            ProviderApplyState.badge(29, 0, emptySet()),
        )
    }

    @Test
    fun restartPendingWhenLoadedOlderThanInstalled() {
        assertEquals(
            ProviderApplyState.Badge.RESTART_PENDING,
            ProviderApplyState.badge(29, 28, emptySet()),
        )
    }

    @Test
    fun rolledBackTakesPrecedenceOverRestartPending() {
        // 已安装版本在坏版本集合里：实际运行的是回退后的上一版，重启也不会
        // 改变什么，应显示「已回退」而不是「重启后生效」。
        assertEquals(
            ProviderApplyState.Badge.ROLLED_BACK,
            ProviderApplyState.badge(29, 28, setOf(29)),
        )
    }
}

class ProviderApplyStateDisabledTest {

    @Test
    fun badInstalledWithoutLowerLoadedVersionIsDisabledNotRolledBack() {
        // 酷我：已装 15 在坏版本里，没有上一版，目标进程不加载任何版本，`.loaded` 残留 15。
        assertEquals(
            ProviderApplyState.Badge.DISABLED,
            ProviderApplyState.badge(15, 15, setOf(15)),
        )
        assertEquals(
            ProviderApplyState.Badge.DISABLED,
            ProviderApplyState.badge(15, 0, setOf(15)),
        )
    }

    @Test
    fun badInstalledWithLowerLoadedVersionIsRolledBack() {
        assertEquals(
            ProviderApplyState.Badge.ROLLED_BACK,
            ProviderApplyState.badge(15, 14, setOf(15)),
        )
    }
}

class ProviderTargetStateSnapshotCodecTest {

    @Test
    fun snapshotSurvivesJsonRoundTrip() {
        val snapshot = ProviderTargetStateSnapshot(
            badVersionsByPlugin = mapOf("netease" to setOf(28, 30), "qqmusic" to setOf(14)),
            loadedVersionByPlugin = mapOf("netease" to 29, "kugou" to 7),
        )

        val encoded = kotlinx.serialization.json.Json.encodeToString(
            ProviderTargetStateSnapshot.serializer(),
            snapshot,
        )
        val decoded = kotlinx.serialization.json.Json.decodeFromString(
            ProviderTargetStateSnapshot.serializer(),
            encoded,
        )

        assertEquals(snapshot, decoded)
    }

    @Test
    fun emptySnapshotEncodesCompactAndDecodesToEmpty() {
        val encoded = kotlinx.serialization.json.Json.encodeToString(
            ProviderTargetStateSnapshot.serializer(),
            ProviderTargetStateSnapshot.EMPTY,
        )
        val decoded = kotlinx.serialization.json.Json.decodeFromString(
            ProviderTargetStateSnapshot.serializer(),
            encoded,
        )

        assertEquals(ProviderTargetStateSnapshot.EMPTY, decoded)
        assertTrue(decoded.badVersionsByPlugin.isEmpty())
        assertTrue(decoded.loadedVersionByPlugin.isEmpty())
    }
}

class ProviderTargetStateParserNoTrailingNewlineTest {

    @Test
    fun filesWithoutTrailingNewlineStayAttributedToTheirOwnPlugin() {
        // 脚本在 cat 后补 echo；每个文件内容独占一行，头行不会被接在内容后面。
        val snapshot = ProviderTargetStateParser.parse(
            listOf(
                "=== /data_mirror/data_ce/null/0/com.apple.android.music/files/hle_provider/applemusic.bad",
                "4",
                "=== /data_mirror/data_ce/null/0/com.apple.android.music/files/hle_provider/applemusic.loaded",
                "20",
                "=== /data_mirror/data_ce/null/0/com.kugou.android/files/hle_provider/kugou.loaded",
                "19",
                "",
            ),
        )

        assertEquals(setOf(4), snapshot.badVersionsByPlugin["applemusic"])
        assertEquals(20, snapshot.loadedVersionByPlugin["applemusic"])
        assertEquals(19, snapshot.loadedVersionByPlugin["kugou"])
    }
}
