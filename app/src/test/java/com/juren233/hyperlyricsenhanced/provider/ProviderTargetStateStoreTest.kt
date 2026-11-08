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

        store.writeLoadedVersion("netease", 29, "1.0.28")
        assertEquals(29, store.readLoadedVersion("netease"))
    }

    @Test
    fun loadedVersionKeepsTheNameOnItsOwnLineAndReadsLegacyFiles() {
        val dir = temporaryFolder.newFolder("hle_provider")
        val store = ProviderTargetStateStore(dir)

        store.writeLoadedVersion("netease", 29, "1.0.28\nbroken")
        assertEquals("29\n1.0.28", java.io.File(dir, "netease.loaded").readText())

        store.writeLoadedVersion("netease", 30, null)
        assertEquals("30", java.io.File(dir, "netease.loaded").readText())

        // 之前的版本只写 versionCode 一行。
        java.io.File(dir, "netease.loaded").writeText("27")
        assertEquals(27, store.readLoadedVersion("netease"))
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
        first.writeLoadedVersion("netease", 29, "1.0.28")
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
    fun loadedVersionNameFollowsTheRecordWithTheHighestVersion() {
        val snapshot = ProviderTargetStateParser.parse(
            listOf(
                "=== /data/user/0/com.apple.android.music/files/hle_provider/applemusic.loaded",
                "28",
                "1.0.27",
                "=== /data_mirror/data_ce/null/0/com.apple.android.music/files/hle_provider/applemusic.loaded",
                "30",
                "1.0.29",
                "=== /data/user/0/com.netease.cloudmusic/files/hle_provider/netease.loaded",
                "31",
                "1.0.30",
                "=== /data/user/0/com.hihonor.cloudmusic/files/hle_provider/netease.loaded",
                "29",
                "1.0.28",
                // 旧格式：只有 versionCode。
                "=== /data/user/0/com.tencent.qqmusic/files/hle_provider/qqmusic.loaded",
                "14",
                "",
            ),
        )

        assertEquals(30, snapshot.loadedVersionByPlugin["applemusic"])
        assertEquals("1.0.29", snapshot.loadedVersionNameByPlugin["applemusic"])
        assertEquals(31, snapshot.loadedVersionByPlugin["netease"])
        assertEquals("1.0.30", snapshot.loadedVersionNameByPlugin["netease"])
        assertEquals(14, snapshot.loadedVersionByPlugin["qqmusic"])
        assertEquals(null, snapshot.loadedVersionNameByPlugin["qqmusic"])
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
    fun appliedVersionIsTreatedAsLoadedUntilTargetRestartsOrFails() {
        // 立即生效后目标还没重启：.loaded 仍是旧值，按已加载新版处理，不再提示重启。
        assertEquals(30, afterApply(loaded = 29, installed = 30, applied = 30))
        assertEquals(
            ProviderApplyState.Badge.NONE,
            ProviderApplyState.badge(30, 30, afterApply(loaded = 29, installed = 30, applied = 30), emptySet()),
        )
        // 对旧版本执行过的记录对新装版本无效。
        assertEquals(29, afterApply(loaded = 29, installed = 31, applied = 30))
        // 没执行过。
        assertEquals(29, afterApply(loaded = 29, installed = 30, applied = 0))
        // 新版本被记为坏版本后，以读回值为准，仍走回退/禁用判定。
        assertEquals(29, afterApply(loaded = 29, installed = 30, applied = 30, bad = setOf(30)))
        // 正在运行更早内置的更新版本（30）：立即生效后下次加载的是已装 28。
        assertEquals(28, afterApply(loaded = 30, installed = 28, applied = 28))
        // 内置基线比已装新：立即生效后下次加载的是基线。
        assertEquals(31, afterApply(loaded = 29, installed = 29, effective = 31, applied = 29))
    }

    private fun afterApply(
        loaded: Int,
        installed: Int,
        effective: Int = installed,
        applied: Int,
        bad: Set<Int> = emptySet(),
    ) = ProviderApplyState.loadedAfterApply(
        loadedVersionCode = loaded,
        installedVersionCode = installed,
        effectiveVersionCode = effective,
        appliedVersionCode = applied,
        badVersionCodes = bad,
    )

    private fun badge(installed: Int, loaded: Int, bad: Set<Int> = emptySet(), effective: Int = installed) =
        ProviderApplyState.badge(
            installedVersionCode = installed,
            effectiveVersionCode = effective,
            loadedVersionCode = loaded,
            badVersionCodes = bad,
        )

    @Test
    fun restartPendingWhenLoadedNewerThanWhatTheNextStartLoads() {
        // 旧测试包内置的基线 30 仍在运行，换装的核心只内置 28、已装也是 28。
        assertEquals(ProviderApplyState.Badge.RESTART_PENDING, badge(installed = 28, loaded = 30))
        // 内置基线 30 比已装 28 新：运行的就是下次会加载的版本。
        assertEquals(ProviderApplyState.Badge.NONE, badge(installed = 28, loaded = 30, effective = 30))
    }

    @Test
    fun noBadgeWhenNotInstalledOrUpToDate() {
        assertEquals(
            ProviderApplyState.Badge.NONE,
            badge(installed = 0, loaded = 0),
        )
        assertEquals(
            ProviderApplyState.Badge.NONE,
            badge(installed = 29, loaded = 29),
        )
        assertEquals(
            ProviderApplyState.Badge.NONE,
            badge(installed = 29, loaded = 0),
        )
    }

    @Test
    fun restartPendingWhenLoadedOlderThanInstalled() {
        assertEquals(
            ProviderApplyState.Badge.RESTART_PENDING,
            badge(installed = 29, loaded = 28),
        )
    }

    @Test
    fun rolledBackTakesPrecedenceOverRestartPending() {
        // 已安装版本在坏版本集合里：实际运行的是回退后的上一版，重启也不会
        // 改变什么，应显示「已回退」而不是「重启后生效」。
        assertEquals(
            ProviderApplyState.Badge.ROLLED_BACK,
            badge(installed = 29, loaded = 28, bad = setOf(29)),
        )
    }
}

class ProviderApplyStateDisabledTest {

    @Test
    fun badInstalledWithoutLowerLoadedVersionIsDisabledNotRolledBack() {
        // 酷我：已装 15 在坏版本里，没有上一版，目标进程不加载任何版本，`.loaded` 残留 15。
        assertEquals(
            ProviderApplyState.Badge.DISABLED,
            ProviderApplyState.badge(15, 15, 15, setOf(15)),
        )
        assertEquals(
            ProviderApplyState.Badge.DISABLED,
            ProviderApplyState.badge(15, 15, 0, setOf(15)),
        )
    }

    @Test
    fun badInstalledWithLowerLoadedVersionIsRolledBack() {
        assertEquals(
            ProviderApplyState.Badge.ROLLED_BACK,
            ProviderApplyState.badge(15, 15, 14, setOf(15)),
        )
    }
}

class ProviderTargetStateSnapshotCodecTest {

    @Test
    fun snapshotSurvivesJsonRoundTrip() {
        val snapshot = ProviderTargetStateSnapshot(
            badVersionsByPlugin = mapOf("netease" to setOf(28, 30), "qqmusic" to setOf(14)),
            loadedVersionByPlugin = mapOf("netease" to 29, "kugou" to 7),
            loadedVersionNameByPlugin = mapOf("netease" to "1.0.28"),
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
