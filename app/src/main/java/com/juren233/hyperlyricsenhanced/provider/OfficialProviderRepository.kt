/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import android.content.Context
import com.juren233.hyperlyricsenhanced.common.PrefsBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.security.MessageDigest
import java.util.Base64

@Serializable
private data class ProviderCatalogDocument(
    val schemaVersion: Int,
    val providers: List<ProviderCatalogEntry>,
)

@Serializable
data class ProviderCatalogEntry(
    val id: String,
    val displayName: String,
    val targetPackages: List<String>,
    val available: Boolean,
    val versionName: String? = null,
    val versionCode: Int? = null,
    val assetUrl: String? = null,
    val sha256: String? = null,
)

data class OfficialProviderItem(
    val catalog: ProviderCatalogEntry,
    val installedVersionCode: Int,
    val installedVersionName: String?,
    val enabled: Boolean,
) {
    val installed: Boolean get() = installedVersionCode > 0
    val updateAvailable: Boolean
        get() = installed && (catalog.versionCode ?: 0) > installedVersionCode
}

data class OfficialProviderUiState(
    val items: List<OfficialProviderItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val busyPluginIds: Set<String> = emptySet(),
)

object OfficialProviderRepository {
    private const val CATALOG_URL =
        "https://raw.githubusercontent.com/juren233/HLE-Providers/main/catalog/catalog.json"
    private const val CATALOG_SIGNATURE_URL =
        "https://raw.githubusercontent.com/juren233/HLE-Providers/main/catalog/catalog.sig"
    private const val MAX_CATALOG_BYTES = 512 * 1024
    private const val MAX_PACK_BYTES = 16 * 1024 * 1024

    private val client = OkHttpClient()
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    /**
     * Returns the official packs that are already installed locally.
     *
     * This intentionally does not touch the network. The main Provider page can
     * therefore render its installed-plugin section immediately, while the
     * remote catalog is only needed by the download screen.
     */
    suspend fun loadInstalledItems(context: Context): List<OfficialProviderItem> =
        withContext(Dispatchers.IO) {
            OfficialProviderCatalog.definitions.mapNotNull { definition ->
                val installedVersionCode = PrefsBridge.getInt(
                    OfficialProviderCatalog.installedVersionKey(definition.id),
                    0,
                )
                if (installedVersionCode <= 0) return@mapNotNull null

                val versionNameKey = OfficialProviderCatalog.installedVersionNameKey(definition.id)
                val installedVersionName = PrefsBridge.getString(versionNameKey)
                    ?: OfficialProviderInstaller.readInstalledManifest(
                        context = context,
                        pluginId = definition.id,
                        versionCode = installedVersionCode,
                    )?.versionName?.also { PrefsBridge.putString(versionNameKey, it) }

                OfficialProviderItem(
                    catalog = ProviderCatalogEntry(
                        id = definition.id,
                        displayName = definition.displayName,
                        targetPackages = definition.targetPackages.toList(),
                        available = false,
                    ),
                    installedVersionCode = installedVersionCode,
                    installedVersionName = installedVersionName,
                    enabled = PrefsBridge.getBoolean(
                        OfficialProviderCatalog.enabledKey(definition.id),
                        false,
                    ),
                )
            }
        }

    suspend fun loadItems(): List<OfficialProviderItem> {
        val catalogBytes = fetch(CATALOG_URL, MAX_CATALOG_BYTES)
        val signatureText = fetch(CATALOG_SIGNATURE_URL, 4096)
            .toString(Charsets.UTF_8)
            .trim()
        val signatureBytes = Base64.getDecoder().decode(signatureText)
        require(
            ProviderPackVerifier.verifyDetachedSignature(catalogBytes, signatureBytes)
        ) { "Provider 目录签名无效" }

        val document = json.decodeFromString<ProviderCatalogDocument>(
            catalogBytes.toString(Charsets.UTF_8),
        )
        require(document.schemaVersion == 1) { "Provider 目录格式不兼容" }
        val entriesById = document.providers.associateBy { entry ->
            validateCatalogEntry(entry)
            entry.id
        }
        require(entriesById.size == document.providers.size) {
            "Provider 目录包含重复插件"
        }
        require(entriesById.keys == OfficialProviderCatalog.definitions.map { it.id }.toSet()) {
            "Provider 目录与内置允许列表不一致"
        }

        return OfficialProviderCatalog.definitions.map { definition ->
            val entry = requireNotNull(entriesById[definition.id])
            val installedVersionCode = PrefsBridge.getInt(
                OfficialProviderCatalog.installedVersionKey(definition.id),
                0,
            )
            val storedVersionName = PrefsBridge.getString(
                OfficialProviderCatalog.installedVersionNameKey(definition.id),
            )
            val installedVersionName = storedVersionName
                ?: entry.versionName?.takeIf { installedVersionCode == entry.versionCode }
            if (storedVersionName == null && installedVersionName != null) {
                PrefsBridge.putString(
                    OfficialProviderCatalog.installedVersionNameKey(definition.id),
                    installedVersionName,
                )
            }
            OfficialProviderItem(
                catalog = entry,
                installedVersionCode = installedVersionCode,
                installedVersionName = installedVersionName,
                enabled = PrefsBridge.getBoolean(
                    OfficialProviderCatalog.enabledKey(definition.id),
                    false,
                ),
            )
        }
    }

    suspend fun downloadAndInstall(
        context: Context,
        item: OfficialProviderItem,
    ): ProviderPackManifest {
        val entry = item.catalog
        require(entry.available) { "该 Provider 尚未发布" }
        val assetUrl = requireNotNull(entry.assetUrl)
        validateAssetUrl(assetUrl)
        val expectedSha256 = requireNotNull(entry.sha256)
        val packBytes = fetch(assetUrl, MAX_PACK_BYTES)
        require(sha256(packBytes).equals(expectedSha256, ignoreCase = true)) {
            "Provider Pack 与目录摘要不一致"
        }
        val installed = OfficialProviderInstaller.install(context, packBytes)
        require(installed.pluginId == entry.id) { "下载的 Provider 与目录不一致" }
        require(installed.versionCode == entry.versionCode) { "Provider 版本与目录不一致" }
        return installed
    }

    fun setEnabled(pluginId: String, enabled: Boolean) {
        OfficialProviderInstaller.setEnabled(pluginId, enabled)
    }

    private fun validateCatalogEntry(entry: ProviderCatalogEntry) {
        val definition = requireNotNull(OfficialProviderCatalog.definitionForId(entry.id)) {
            "Provider 目录包含未知插件"
        }
        require(entry.targetPackages.toSet() == definition.targetPackages) {
            "Provider 目录目标包名无效"
        }
        if (entry.available) {
            require(!entry.versionName.isNullOrBlank())
            require((entry.versionCode ?: 0) > 0)
            require(entry.sha256?.matches(Regex("[0-9a-f]{64}")) == true)
            validateAssetUrl(requireNotNull(entry.assetUrl))
        } else {
            require(entry.assetUrl == null && entry.sha256 == null) {
                "未发布 Provider 不得声明下载地址"
            }
        }
    }

    private fun validateAssetUrl(url: String) {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host == "github.com") {
            "Provider 下载地址必须使用 GitHub HTTPS"
        }
        require(uri.path.startsWith("/juren233/HLE-Providers/releases/download/")) {
            "Provider 下载地址不属于官方仓库"
        }
    }

    private suspend fun fetch(url: String, maxBytes: Int): ByteArray =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "下载失败: HTTP ${response.code}" }
                val body = requireNotNull(response.body)
                val bytes = body.bytes()
                require(bytes.size <= maxBytes) { "下载内容超过大小限制" }
                bytes
            }
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
