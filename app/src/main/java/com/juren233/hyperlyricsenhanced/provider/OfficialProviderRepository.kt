/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import android.content.Context
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.PrefsBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

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
    val needsRepair: Boolean = false,
) {
    val installed: Boolean get() = installedVersionCode > 0
    val updateAvailable: Boolean
        get() = installed && !needsRepair && (catalog.versionCode ?: 0) > installedVersionCode
}

data class OfficialProviderUiState(
    val items: List<OfficialProviderItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val busyPluginIds: Set<String> = emptySet(),
)

object OfficialProviderRepository {
    private const val MAX_CATALOG_BYTES = 512 * 1024
    private const val MAX_PACK_BYTES = 16 * 1024 * 1024

    /** 官方 R2 公开源（Cloudflare 自定义域名）；目录与 Pack 的回退地址都由它推导。 */
    internal const val R2_PUBLIC_BASE = "https://hleplugins.juren233.top/"

    private const val GITHUB_REPO_URL = "https://github.com/juren233/HLE-Providers/"

    /**
     * 目录源按优先级排列：官方 GitHub raw 恒定首位，自建 R2 其次，公共 CDN 镜像仅兜底。
     * 同一次加载的 catalog 与签名必须取自同一源，避免两文件版本错位导致签名校验失败。
     */
    internal val CATALOG_BASE_URLS = listOf(
        "https://raw.githubusercontent.com/juren233/HLE-Providers/main/catalog/",
        "${R2_PUBLIC_BASE}catalog/",
        "https://cdn.jsdelivr.net/gh/juren233/HLE-Providers@main/catalog/",
        "https://fastly.jsdelivr.net/gh/juren233/HLE-Providers@main/catalog/",
        "https://gcore.jsdelivr.net/gh/juren233/HLE-Providers@main/catalog/",
    )

    /** 第三方 GitHub 加速镜像，仅在直连与 R2 均失败后兜底，内容最终由目录内 sha256 把关。 */
    internal val PACK_MIRROR_PREFIXES = listOf(
        "https://ghproxy.net/",
        "https://ghfast.top/",
        "https://gh.llkk.cc/",
    )

    /**
     * Pack 候选地址：直连 GitHub Releases → R2（对象键复刻仓库相对路径）→ 加速镜像。
     * 目录中的 assetUrl 恒为 GitHub 地址（validateAssetUrl 保证），R2 地址由此推导。
     */
    internal fun packUrlCandidates(assetUrl: String): List<String> = buildList {
        add(assetUrl)
        add(R2_PUBLIC_BASE + assetUrl.removePrefix(GITHUB_REPO_URL))
        PACK_MIRROR_PREFIXES.forEach { add(it + assetUrl) }
    }

    private val catalogClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private val packClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
    private val json = Json {
        // 新版目录可能新增字段；旧版 App 忽略未知字段而不是让整个插件页报错。
        ignoreUnknownKeys = true
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

                val installedManifest = OfficialProviderInstaller.readInstalledManifest(
                    context = context,
                    pluginId = definition.id,
                    versionCode = installedVersionCode,
                )
                val versionNameKey = OfficialProviderCatalog.installedVersionNameKey(definition.id)
                val storedVersionName = PrefsBridge.getString(versionNameKey)
                val installedVersionName = installedManifest?.versionName
                    ?: storedVersionName
                    ?: installedManifest?.versionName?.also { PrefsBridge.putString(versionNameKey, it) }

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
                    needsRepair = installedManifest == null,
                )
            }
        }

    suspend fun loadItems(context: Context? = null): List<OfficialProviderItem> {
        val catalog = fetchVerifiedCatalog(context)
        val document = json.decodeFromString<ProviderCatalogDocument>(
            catalog.catalogBytes.toString(Charsets.UTF_8),
        )
        require(document.schemaVersion == 1) { "Provider 目录格式不兼容" }
        // 目录由签名整体担保，但内容按前向兼容解读：新版目录新增插件 ID、或给既有插件
        // 新增目标包名时，旧版 App 忽略新增部分继续工作，而不是让整个插件页报错。
        // 只有真正的数据错误（重复插件、缺少内置插件、包名归属冲突）才判定目录无效。
        val entriesById = document.providers.associateBy { entry -> entry.id }
        require(entriesById.size == document.providers.size) {
            "Provider 目录包含重复插件"
        }
        document.providers.forEach { entry ->
            if (OfficialProviderCatalog.definitionForId(entry.id) == null) {
                OfficialProviderAcquisitionLog.warn(
                    "插件目录包含当前 App 版本未知的插件，已忽略: id=${entry.id} " +
                        "app=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})",
                )
            } else {
                validateCatalogEntry(entry)
            }
        }
        OfficialProviderCatalog.definitions.forEach { definition ->
            require(definition.id in entriesById) {
                "Provider 目录与内置允许列表不一致"
            }
        }

        return OfficialProviderCatalog.definitions.map { definition ->
            val entry = requireNotNull(entriesById[definition.id]).copy(
                displayName = definition.displayName,
            )
            val installedVersionCode = PrefsBridge.getInt(
                OfficialProviderCatalog.installedVersionKey(definition.id),
                0,
            )
            val installedManifest = if (installedVersionCode > 0 && context != null) {
                OfficialProviderInstaller.readInstalledManifest(
                    context = context,
                    pluginId = definition.id,
                    versionCode = installedVersionCode,
                )
            } else {
                null
            }
            val storedVersionName = PrefsBridge.getString(
                OfficialProviderCatalog.installedVersionNameKey(definition.id),
            )
            val installedVersionName = installedManifest?.versionName
                ?: storedVersionName
                ?: entry.versionName?.takeIf { installedVersionCode == entry.versionCode }
            if (storedVersionName == null && installedVersionName != null) {
                PrefsBridge.putString(
                    OfficialProviderCatalog.installedVersionNameKey(definition.id),
                    installedVersionName,
                )
            }
            val needsRepair = installedVersionCode > 0 && context != null && installedManifest == null
            OfficialProviderItem(
                catalog = entry,
                installedVersionCode = installedVersionCode,
                installedVersionName = installedVersionName,
                enabled = PrefsBridge.getBoolean(
                    OfficialProviderCatalog.enabledKey(definition.id),
                    false,
                ),
                needsRepair = needsRepair,
            )
        }
    }

    internal class VerifiedCatalog(
        val catalogBytes: ByteArray,
        val signatureBytes: ByteArray,
    )

    private suspend fun fetchVerifiedCatalog(context: Context?): VerifiedCatalog {
        var lastError: Throwable? = null
        OfficialProviderAcquisitionLog.info(
            "插件目录加载开始: 候选源=${CATALOG_BASE_URLS.size} " +
                "app=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})",
        )
        for (base in CATALOG_BASE_URLS) {
            val host = runCatching { URI(base).host }.getOrNull() ?: base
            try {
                val catalogBytes = fetch("${base}catalog.json", MAX_CATALOG_BYTES)
                val signatureText = fetch("${base}catalog.sig", 4096)
                    .toString(Charsets.UTF_8)
                    .trim()
                val signatureBytes = Base64.getDecoder().decode(signatureText)
                require(
                    ProviderPackVerifier.verifyDetachedSignature(catalogBytes, signatureBytes)
                ) { "Provider 目录签名无效" }
                val verified = VerifiedCatalog(catalogBytes, signatureBytes)
                writeCatalogCache(context, verified)
                OfficialProviderAcquisitionLog.info(
                    "插件目录加载成功: host=$host bytes=${catalogBytes.size} " +
                        "signatureBytes=${signatureBytes.size}",
                )
                return verified
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                OfficialProviderAcquisitionLog.warn(
                    "插件目录源失败: host=$host " +
                        "error=${OfficialProviderAcquisitionLog.describe(e)}",
                )
            }
        }
        val cached = readCatalogCache(context)
        if (cached != null) {
            OfficialProviderAcquisitionLog.warn(
                "插件目录改用本地缓存: bytes=${cached.catalogBytes.size} " +
                    "lastError=${OfficialProviderAcquisitionLog.describe(lastError)}",
            )
            return cached
        }
        OfficialProviderAcquisitionLog.error(
            "插件目录加载失败: 所有候选源与本地缓存均不可用",
            lastError,
        )
        throw lastError ?: IllegalStateException("Provider 目录不可用")
    }

    private fun catalogCacheFile(context: Context): File =
        File(context.cacheDir, "official_provider_catalog_v1.bin")

    private fun writeCatalogCache(context: Context?, catalog: VerifiedCatalog) {
        if (context == null) return
        runCatching {
            val signature = Base64.getEncoder().encodeToString(catalog.signatureBytes)
            catalogCacheFile(context)
                .writeBytes(encodeCatalogCache(signature, catalog.catalogBytes))
        }
    }

    private fun readCatalogCache(context: Context?): VerifiedCatalog? {
        if (context == null) return null
        val cached = runCatching {
            decodeCatalogCache(catalogCacheFile(context).takeIf(File::isFile)?.readBytes())
        }.getOrNull() ?: return null
        return runCatching {
            require(
                ProviderPackVerifier.verifyDetachedSignature(
                    cached.catalogBytes,
                    cached.signatureBytes,
                )
            ) { "Provider 目录签名无效" }
            cached
        }.getOrNull()
    }

    /** 缓存布局：Base64 签名行 + '\n' + 目录原文；Base64 签名不含换行，首个换行即分隔符。 */
    internal fun encodeCatalogCache(signatureBase64: String, catalogBytes: ByteArray): ByteArray =
        signatureBase64.toByteArray(Charsets.US_ASCII) + '\n'.code.toByte() + catalogBytes

    internal fun decodeCatalogCache(bytes: ByteArray?): VerifiedCatalog? {
        if (bytes == null) return null
        val separator = bytes.indexOf('\n'.code.toByte())
        if (separator <= 0) return null
        return try {
            VerifiedCatalog(
                catalogBytes = bytes.copyOfRange(separator + 1, bytes.size),
                signatureBytes = Base64.getDecoder().decode(
                    String(bytes, 0, separator, Charsets.US_ASCII),
                ),
            )
        } catch (e: IllegalArgumentException) {
            null
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
        OfficialProviderAcquisitionLog.info(
            "插件安装开始: id=${entry.id} 目录版本=${entry.versionCode} " +
                "targets=${entry.targetPackages.joinToString()}",
        )
        val packBytes = fetchPackWithMirrors(assetUrl, expectedSha256)
        val installed = OfficialProviderInstaller.install(context, packBytes)
        if (installed.pluginId != entry.id || installed.versionCode != entry.versionCode) {
            OfficialProviderAcquisitionLog.error(
                "插件与目录不一致: 目录(id=${entry.id} version=${entry.versionCode}) " +
                    "实际(id=${installed.pluginId} version=${installed.versionCode})",
            )
        }
        require(installed.pluginId == entry.id) { "下载的 Provider 与目录不一致" }
        require(installed.versionCode == entry.versionCode) { "Provider 版本与目录不一致" }
        OfficialProviderAcquisitionLog.info(
            "插件安装完成: id=${installed.pluginId} version=${installed.versionCode} " +
                "name=${installed.versionName} bytes=${packBytes.size}",
        )
        return installed
    }

    suspend fun repair(
        context: Context,
        item: OfficialProviderItem,
    ): ProviderPackManifest {
        val entry = if (item.catalog.available && item.catalog.assetUrl != null) {
            item.catalog
        } else {
            val items = loadItems(context)
            items.firstOrNull { it.catalog.id == item.catalog.id }?.catalog
                ?: throw IllegalStateException("未在官方目录中找到该插件")
        }
        return downloadAndInstall(context, item.copy(catalog = entry))
    }

    fun setEnabled(pluginId: String, enabled: Boolean) {
        OfficialProviderInstaller.setEnabled(pluginId, enabled)
    }

    private fun validateCatalogEntry(entry: ProviderCatalogEntry) {
        requireNotNull(OfficialProviderCatalog.definitionForId(entry.id)) {
            "Provider 目录包含未知插件"
        }
        require(
            entry.targetPackages.isNotEmpty() &&
                entry.targetPackages.all {
                    OfficialProviderCatalog.isTargetPackageCompatible(entry.id, it)
                } &&
                OfficialProviderCatalog.APPLE_MUSIC_PACKAGE_NAME !in entry.targetPackages,
        ) { "Provider 目录目标包名无效" }
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

    private suspend fun fetchPackWithMirrors(assetUrl: String, expectedSha256: String): ByteArray {
        var lastError: Throwable? = null
        val candidates = packUrlCandidates(assetUrl)
        OfficialProviderAcquisitionLog.info(
            "插件文件下载开始: 候选源=${candidates.size} 期望摘要=${expectedSha256.take(12)}",
        )
        for (url in candidates) {
            try {
                val bytes = fetch(url, MAX_PACK_BYTES, packClient)
                val actualSha256 = sha256(bytes)
                if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    OfficialProviderAcquisitionLog.warn(
                        "插件文件摘要不一致: url=$url bytes=${bytes.size} " +
                            "expected=${expectedSha256.take(12)} actual=${actualSha256.take(12)}",
                    )
                }
                require(actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    "Provider Pack 与目录摘要不一致"
                }
                OfficialProviderAcquisitionLog.info(
                    "插件文件下载成功: url=$url bytes=${bytes.size}",
                )
                return bytes
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                OfficialProviderAcquisitionLog.warn(
                    "插件文件下载源失败: url=$url " +
                        "error=${OfficialProviderAcquisitionLog.describe(e)}",
                )
            }
        }
        throw lastError ?: IllegalStateException("Provider Pack 下载不可用")
    }

    private suspend fun fetch(
        url: String,
        maxBytes: Int,
        client: OkHttpClient = catalogClient,
    ): ByteArray =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).get().build()
            val startedAtNanos = System.nanoTime()
            client.newCall(request).execute().use { response ->
                val headerElapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000
                if (response.isSuccessful) {
                    OfficialProviderAcquisitionLog.info(
                        "下载响应成功: status=${response.code} host=${request.url.host} " +
                            "url=$url elapsed=${headerElapsedMs}ms",
                    )
                } else {
                    OfficialProviderAcquisitionLog.warn(
                        "下载响应失败: status=${response.code} host=${request.url.host} " +
                            "url=$url elapsed=${headerElapsedMs}ms",
                    )
                }
                check(response.isSuccessful) {
                    "下载失败: HTTP ${response.code} (${request.url.host})"
                }
                val body = requireNotNull(response.body)
                val bytes = body.bytes()
                require(bytes.size <= maxBytes) { "下载内容超过大小限制" }
                OfficialProviderAcquisitionLog.info(
                    "下载内容就绪: host=${request.url.host} bytes=${bytes.size} limit=$maxBytes " +
                        "elapsed=${(System.nanoTime() - startedAtNanos) / 1_000_000}ms",
                )
                bytes
            }
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
