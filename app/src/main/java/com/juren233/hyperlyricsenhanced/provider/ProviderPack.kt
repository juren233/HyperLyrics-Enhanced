/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import android.util.Log
import com.juren233.hyperlyricsenhanced.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.spec.InvalidKeySpecException
import java.util.Base64
import java.util.zip.ZipInputStream

@Serializable
data class ProviderPackManifest(
    val schemaVersion: Int,
    val pluginApiVersion: Int,
    val pluginId: String,
    val displayName: String,
    val versionName: String,
    val versionCode: Int,
    val minCoreVersionCode: Int,
    val entryClass: String,
    val providerPackageName: String,
    val targetPackages: List<String>,
    val classesSha256: String,
)

data class VerifiedProviderPack(
    val manifest: ProviderPackManifest,
    val classesDex: ByteArray,
)

object ProviderPackVerifier {
    private const val TAG = "HLEProvider/Verifier"
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val DEX_ENTRY = "classes.dex"
    private const val SIGNATURE_ENTRY = "signature.ed25519"
    private const val MAX_PACK_BYTES = 16 * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 64 * 1024
    private const val MAX_DEX_BYTES = 12 * 1024 * 1024
    private const val MAX_SIGNATURE_BYTES = 256
    private const val SIGNATURE_SEPARATOR: Byte = 0
    private val allowedEntries = setOf(MANIFEST_ENTRY, DEX_ENTRY, SIGNATURE_ENTRY)
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    private const val PUBLIC_KEY_DER_BASE64 =
        "MCowBQYDK2VwAyEAUQ05FgAS98xeNOtYppCBq1BUjEhwdxrChjwpK5sRgQU="

    fun verify(
        packBytes: ByteArray,
        expectedTargetPackage: String? = null,
        coreVersionCode: Int = BuildConfig.VERSION_CODE,
    ): VerifiedProviderPack {
        require(packBytes.size <= MAX_PACK_BYTES) { "Provider Pack 超过大小限制" }
        val entries = readEntries(packBytes)
        require(entries.keys == allowedEntries) { "Provider Pack 文件集合无效" }

        val manifestBytes = requireNotNull(entries[MANIFEST_ENTRY])
        val classesDex = requireNotNull(entries[DEX_ENTRY])
        val signatureBytes = requireNotNull(entries[SIGNATURE_ENTRY])
        val manifest = json.decodeFromString<ProviderPackManifest>(
            manifestBytes.toString(Charsets.UTF_8),
        )

        require(manifest.schemaVersion == 1) { "不支持的 Provider Pack 格式" }
        require(manifest.pluginApiVersion == OfficialProviderCatalog.PLUGIN_API_VERSION) {
            "Provider 插件 API 不兼容"
        }
        require(manifest.minCoreVersionCode <= coreVersionCode) {
            "Provider 需要更新版本的 HyperLyrics Enhanced"
        }
        require(manifest.pluginId.matches(Regex("[a-z0-9][a-z0-9-]{1,47}"))) {
            "Provider 插件 ID 无效"
        }
        require(manifest.entryClass.startsWith("com.juren233.hle.providers.")) {
            "Provider 入口类不在允许命名空间"
        }
        require(
            manifest.providerPackageName ==
                OfficialProviderCatalog.OFFICIAL_PROVIDER_PACKAGE_PREFIX + manifest.pluginId
        ) { "Provider 来源标识无效" }

        val definition = requireNotNull(
            OfficialProviderCatalog.definitionForId(manifest.pluginId)
        ) { "Provider 不在内置允许列表" }
        require(manifest.targetPackages.toSet() == definition.targetPackages) {
            "Provider 目标软件与内置允许列表不一致"
        }
        require(OfficialProviderCatalog.APPLE_MUSIC_PACKAGE_NAME !in manifest.targetPackages) {
            "Apple Music 只能使用内置 Provider"
        }
        expectedTargetPackage?.let { expected ->
            require(expected in manifest.targetPackages) { "Provider 不支持当前音乐软件" }
            require(OfficialProviderCatalog.definitionForPackage(expected)?.id == manifest.pluginId) {
                "Provider 与当前音乐软件不匹配"
            }
        }

        require(sha256(classesDex).equals(manifest.classesSha256, ignoreCase = true)) {
            "Provider DEX 摘要不匹配"
        }
        require(
            verifyDetachedSignature(
                manifestBytes + byteArrayOf(SIGNATURE_SEPARATOR) + classesDex,
                signatureBytes,
            )
        ) {
            "Provider Pack 签名无效"
        }
        return VerifiedProviderPack(manifest, classesDex)
    }

    private fun readEntries(packBytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(packBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && entry.name in allowedEntries) {
                    "Provider Pack 包含不允许的文件"
                }
                require(result[entry.name] == null) { "Provider Pack 包含重复文件" }
                val limit = when (entry.name) {
                    MANIFEST_ENTRY -> MAX_MANIFEST_BYTES
                    DEX_ENTRY -> MAX_DEX_BYTES
                    SIGNATURE_ENTRY -> MAX_SIGNATURE_BYTES
                    else -> error("unreachable")
                }
                result[entry.name] = zip.readEntryBytes(limit)
                zip.closeEntry()
            }
        }
        return result
    }

    private fun ZipInputStream.readEntryBytes(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "Provider Pack 文件超过大小限制" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    fun verifyDetachedSignature(
        payload: ByteArray,
        signatureBytes: ByteArray,
    ): Boolean {
        val result = runCatching {
            val publicKey = decodeEd25519PublicKey()
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(payload, 0, payload.size)
            verifier.verifySignature(signatureBytes)
        }
        if (BuildConfig.DEBUG && result.isFailure) {
            runCatching {
                Log.e(
                    TAG,
                    "Ed25519 verification failed before result " +
                        "payloadSha256=${sha256(payload)} signatureBytes=${signatureBytes.size}",
                    result.exceptionOrNull(),
                )
            }
        }
        if (BuildConfig.DEBUG && result.isSuccess) {
            runCatching {
                Log.d(
                    TAG,
                    "verify implementation=BouncyCastle-Ed25519Signer result=${result.getOrNull()} " +
                        "payloadSha256=${sha256(payload)} signatureBytes=${signatureBytes.size}",
                )
            }
        }
        return result.getOrDefault(false)
    }

    private fun decodeEd25519PublicKey(): ByteArray {
        val der = Base64.getDecoder().decode(PUBLIC_KEY_DER_BASE64)
        require(der.size == 44) { "Ed25519 公钥 DER 长度无效" }
        val prefix = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03,
            0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
        )
        require(der.copyOfRange(0, prefix.size).contentEquals(prefix)) {
            InvalidKeySpecException("Ed25519 公钥不是预期的 X.509 SubjectPublicKeyInfo")
        }
        return der.copyOfRange(prefix.size, der.size)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
