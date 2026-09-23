/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPackVerifierTest {
    private val json = Json

    @Test
    fun acceptsEd25519SignatureWithTheRepositoryPublicKey() {
        val payload = "provider-verifier-test".toByteArray(Charsets.UTF_8)
        val signature = Base64.getDecoder().decode(
            "xmmdDnDBTwALsFLqQvtd4ZPUCkqzSmf0nHYb0U7788BRqCV55jjNFlTYABJvtcSmPAghJmGZP50nGVq2OPshCg==",
        )

        assertTrue(ProviderPackVerifier.verifyDetachedSignature(payload, signature))
    }

    @Test
    fun rejectsModifiedEd25519Signature() {
        val payload = "provider-verifier-test".toByteArray(Charsets.UTF_8)
        val signature = Base64.getDecoder().decode(
            "xmmdDnDBTwALsFLqQvtd4ZPUCkqzSmf0nHYb0U7788BRqCV55jjNFlTYABJvtcSmPAghJmGZP50nGVq2OPshCg==",
        ).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }

        assertFalse(ProviderPackVerifier.verifyDetachedSignature(payload, signature))
    }

    @Test
    fun rejectsPackWithAnUnexpectedFile() {
        val pack = zipPack(extraEntryName = "payload.txt")

        assertThrows(IllegalArgumentException::class.java) {
            ProviderPackVerifier.verify(pack)
        }
    }

    @Test
    fun rejectsPackThatTargetsAppleMusic() {
        val pack = zipPack(
            manifest = defaultManifest().copy(
                targetPackages = listOf(OfficialProviderCatalog.APPLE_MUSIC_PACKAGE_NAME),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            ProviderPackVerifier.verify(pack)
        }
    }

    @Test
    fun rejectsPackWhenDexDigestDoesNotMatchManifest() {
        val pack = zipPack(
            manifest = defaultManifest().copy(classesSha256 = "0".repeat(64)),
        )

        assertThrows(IllegalArgumentException::class.java) {
            ProviderPackVerifier.verify(pack)
        }
    }

    @Test
    fun acceptsPackManifestThatAddsTargetPackagesUnknownToThisApp() {
        val pack = zipPack(
            manifest = defaultManifest().copy(
                targetPackages = listOf("cn.kuwo.player", "cn.example.futuremusic"),
                classesSha256 = dexSha256(),
            ),
        )

        // 内容校验全部通过后在签名一步失败，说明未知新包名被放行而不是被目标校验拒绝
        val exception = assertThrows(IllegalArgumentException::class.java) {
            ProviderPackVerifier.verify(pack)
        }
        assertEquals("Provider Pack 签名无效", exception.message)
    }

    @Test
    fun rejectsPackManifestThatClaimsAnotherPluginsTargetPackage() {
        val pack = zipPack(
            manifest = defaultManifest().copy(
                targetPackages = listOf("cn.kuwo.player", "com.tencent.qqmusic"),
            ),
        )

        val exception = assertThrows(IllegalArgumentException::class.java) {
            ProviderPackVerifier.verify(pack)
        }
        assertEquals("Provider 目标软件与内置允许列表不一致", exception.message)
    }

    @Test
    fun rejectsPackManifestWithoutTargetPackages() {
        val pack = zipPack(
            manifest = defaultManifest().copy(targetPackages = emptyList()),
        )

        val exception = assertThrows(IllegalArgumentException::class.java) {
            ProviderPackVerifier.verify(pack)
        }
        assertEquals("Provider 目标软件与内置允许列表不一致", exception.message)
    }

    @Test
    fun toleratesManifestFieldsAddedByNewerReleases() {
        val manifestJson = json.encodeToString(
            defaultManifest().copy(
                classesSha256 = dexSha256(),
            ),
        ).dropLast(1) + ",\"nextTrackPreviewPolicy\":\"v2\"}"
        val pack = zipPack(manifestJsonOverride = manifestJson)

        val exception = assertThrows(IllegalArgumentException::class.java) {
            ProviderPackVerifier.verify(pack)
        }
        assertEquals("Provider Pack 签名无效", exception.message)
    }

    private fun dexSha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(byteArrayOf(0x64, 0x65, 0x78))
            .joinToString("") { "%02x".format(it) }

    private fun zipPack(
        manifest: ProviderPackManifest = defaultManifest(),
        extraEntryName: String? = null,
        manifestJsonOverride: String? = null,
    ): ByteArray {
        val dex = byteArrayOf(0x64, 0x65, 0x78)
        val manifestBytes = manifestJsonOverride?.toByteArray(Charsets.UTF_8) ?: json.encodeToString(
            manifest.copy(
                classesSha256 = manifest.classesSha256.ifBlank {
                    "5f6e0f4a1f7c9bc7a8f0b6f4c6d4f7f0a5a4e4dcf48e1d4e1b4bf0a3c1a7f9c2"
                },
            ),
        ).toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifestBytes)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write(dex)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("signature.ed25519"))
                zip.write(ByteArray(64))
                zip.closeEntry()
                extraEntryName?.let { name ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(byteArrayOf(1))
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }
    }

    private fun defaultManifest() = ProviderPackManifest(
        schemaVersion = 1,
        pluginApiVersion = OfficialProviderCatalog.PLUGIN_API_VERSION,
        pluginId = "kuwo",
        displayName = "酷我音乐",
        versionName = "1.0.0",
        versionCode = 1,
        minCoreVersionCode = 0,
        entryClass = "com.juren233.hle.providers.kuwo.KuwoPluginEntry",
        providerPackageName = "com.juren233.hyperlyricsenhanced.provider.kuwo",
        targetPackages = listOf("cn.kuwo.player"),
        classesSha256 = "",
    )
}
