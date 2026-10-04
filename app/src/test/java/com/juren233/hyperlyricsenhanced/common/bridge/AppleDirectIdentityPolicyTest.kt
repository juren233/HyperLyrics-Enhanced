/* Copyright 2026 juren233. Licensed under the Apache License, Version 2.0. */
package com.juren233.hyperlyricsenhanced.common.bridge

import com.juren233.hyperlyricsenhanced.common.bridge.AppleDirectIdentityPolicy.Decision.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AppleDirectIdentityPolicyTest {
    private val systemUi = "com.android.systemui"
    private fun evaluate(uid: Int, packageName: String?, expectedUid: Int?) =
        AppleDirectIdentityPolicy.evaluate(34, systemUi,
            { AppleDirectIdentityPolicy.Sender(uid, packageName) }, { expectedUid })

    @Test fun `full user scoped SystemUI UID is accepted and system appId alone is rejected`() {
        assertEquals(AUTHENTICATED, evaluate(1_000, systemUi, 1_000).decision)
        assertEquals(AUTHENTICATED, evaluate(10_225, systemUi, 10_225).decision)
        assertEquals(AUTHENTICATED, evaluate(1_001_234, systemUi, 1_001_234).decision)
        assertEquals(REJECTED_UID, evaluate(1_234, systemUi, 1_001_234).decision)
        assertEquals(REJECTED_UID, evaluate(1_000, systemUi, 1_001_234).decision)
    }

    @Test fun `known wrong package and known wrong UID cannot use legacy fallback`() {
        assertEquals(REJECTED_PACKAGE, evaluate(1_234, "other.package", 1_234).decision)
        assertEquals(REJECTED_PACKAGE, evaluate(-1, "other.package", null).decision)
        assertEquals(REJECTED_UID, evaluate(1_235, null, 1_234).decision)
        assertEquals(REJECTED_UID_LOOKUP_UNAVAILABLE, evaluate(1_234, null, null).decision)
    }

    @Test fun `unknown identity is explicitly compatible rather than authenticated`() {
        for ((uid, pkg) in listOf(-1 to null, -1 to systemUi)) {
            val result = evaluate(uid, pkg, 1_234)
            assertTrue(result.decision.accepted)
            assertEquals(LEGACY_UNKNOWN_IDENTITY, result.decision)
        }
        assertEquals(LEGACY_UNKNOWN_IDENTITY, evaluate(-1, null, null).decision)
    }

    @Test fun `verified full UID authenticates even when the package name is unavailable`() {
        val result = evaluate(1_001_234, null, 1_001_234)
        assertTrue(result.decision.accepted)
        assertEquals(AUTHENTICATED, result.decision)
        assertEquals(REJECTED_UID, evaluate(1_234, null, 1_001_234).decision)
        assertEquals(REJECTED_UID_LOOKUP_UNAVAILABLE, evaluate(1_001_234, null, null).decision)
    }

    @Test fun `lookup failure rejects known UID but a later receipt retries and recovers`() {
        var lookups = 0
        val lookup = { if (++lookups == 1) null else 1_234 }
        val sender = { AppleDirectIdentityPolicy.Sender(1_234, systemUi) }
        val first = AppleDirectIdentityPolicy.evaluate(34, systemUi, sender, lookup)
        val second = AppleDirectIdentityPolicy.evaluate(34, systemUi, sender, lookup)
        assertEquals(REJECTED_UID_LOOKUP_UNAVAILABLE, first.decision)
        assertEquals(AUTHENTICATED, second.decision)
        assertEquals(2, lookups)
    }

    @Test fun `Android 13 invokes neither sender identity APIs nor UID lookup`() {
        val result = AppleDirectIdentityPolicy.evaluate(33, systemUi,
            { error("API34 identity access on Android 13") },
            { error("unnecessary identity lookup on Android 13") })
        assertEquals(LEGACY_UNKNOWN_IDENTITY, result.decision)
        assertEquals(-1, result.sender.uid)
        assertNull(result.sender.packageName)
    }

    @Test fun `both SystemUI reply broadcasts use the guarded identity sharing adapter`() {
        val root = listOf(File("app/src/main/java"), File("src/main/java")).first(File::isDirectory)
        val bridge = File(root, "com/juren233/hyperlyricsenhanced/root/source/AppleMusicDirectBridge.kt").readText()
        val metadata = File(root, "com/juren233/hyperlyricsenhanced/root/source/LyriconSourceOnlineApply.kt").readText()
        val adapter = File(root, "com/juren233/hyperlyricsenhanced/common/bridge/AppleDirectBroadcastIdentity.kt").readText()
        assertTrue(bridge.contains("app.sendAppleDirectBroadcast(intent)"))
        assertTrue(metadata.contains("application.sendAppleDirectBroadcast("))
        assertTrue(adapter.contains("if (Build.VERSION.SDK_INT >= 34)"))
        assertTrue(adapter.contains("setShareIdentityEnabled(true)"))
        assertTrue(adapter.contains("sendBroadcast(intent, null, options)"))
        assertTrue(adapter.contains("} else {\n        sendBroadcast(intent)"))
    }
}
