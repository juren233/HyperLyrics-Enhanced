/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import android.app.Application
import android.os.ParcelFileDescriptor
import android.util.Log
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.root.utils.RuntimeResourceCleanup
import com.juren233.hyperlyricsenhanced.root.utils.RuntimeResourceCleanupException
import dalvik.system.InMemoryDexClassLoader
import io.github.libxposed.api.XposedModule
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/** Loads Provider Packs that observe public SystemUI MediaSession state. */
object OfficialProviderSystemMediaRuntime {
    private const val TAG = "OfficialProviderSystemMediaRuntime"
    private val activeRuntimes = ConcurrentHashMap<String, ActiveRuntime>()
    private var cleanupFailure: RuntimeResourceCleanupException? = null

    fun installIfAvailable(
        module: XposedModule,
        application: Application,
    ): Boolean {
        cleanupFailure?.let { throw it }
        var installed = false
        OfficialProviderCatalog.definitions
            .filter { it.systemMediaRuntime }
            .forEach { definition ->
                val targetPackage = definition.targetPackages.singleOrNull() ?: return@forEach
                if (installDefinition(module, application, definition, targetPackage)) {
                    installed = true
                }
            }
        return installed
    }

    fun releaseAll() {
        val previousRuntimes = activeRuntimes.values.toList()
        activeRuntimes.clear()
        val cleanup = RuntimeResourceCleanup()
        cleanup.attempt("previous SystemMedia cleanup") { cleanupFailure?.let { throw it } }
        previousRuntimes.forEach { runtime ->
            cleanup.attempt("SystemMedia plugin") { runtime.plugin.releaseSystemMedia() }
            cleanup.attempt("SystemMedia host") { runtime.host.release() }
        }
        cleanupFailure = cleanup.failureOrNull()
        cleanupFailure?.let { throw it }
    }

    private fun installDefinition(
        module: XposedModule,
        application: Application,
        definition: OfficialProviderCatalog.Definition,
        targetPackage: String,
    ): Boolean {
        if (activeRuntimes.containsKey(definition.id)) return true
        return runCatching {
            val preferences = module.getRemotePreferences(UIConstants.PREF_NAME)
            check(
                preferences.getBoolean(
                    OfficialProviderCatalog.enabledKey(definition.id),
                    false,
                ),
            ) { "disabled" }
            val installedVersion = preferences.getInt(
                OfficialProviderCatalog.installedVersionKey(definition.id),
                0,
            )
            check(installedVersion > 0) { "installed_version_missing" }
            val remoteName = preferences.getString(
                OfficialProviderCatalog.activeFileKey(targetPackage),
                null,
            )?.takeIf(String::isNotBlank) ?: error("active_file_missing")
            check(remoteName in module.listRemoteFiles()) { "remote_file_missing:$remoteName" }

            val readStartNs = System.nanoTime()
            val packBytes = ParcelFileDescriptor.AutoCloseInputStream(
                module.openRemoteFile(remoteName),
            ).use { it.readBytes() }
            val readNs = System.nanoTime() - readStartNs
            val verifyStartNs = System.nanoTime()
            val verified = ProviderPackVerifier.verify(
                packBytes = packBytes,
                expectedTargetPackage = targetPackage,
                verificationCache = preferences.getString(
                    OfficialProviderCatalog.VERIFIED_PACK_CACHE_KEY,
                    null,
                ),
                verificationCacheKey = remoteName,
            )
            val verifyNs = System.nanoTime() - verifyStartNs
            check(verified.manifest.pluginId == definition.id)
            check(verified.manifest.versionCode == installedVersion)
            val loaderStartNs = System.nanoTime()
            val loader = InMemoryDexClassLoader(
                verified.dexFiles.map { ByteBuffer.wrap(it) }.toTypedArray(),
                OfficialProviderSystemMediaRuntime::class.java.classLoader,
            )
            val entryClass = loader.loadClass(verified.manifest.entryClass)
            val plugin = runCatching {
                entryClass.getField("INSTANCE").get(null)
            }.getOrElse { entryClass.getDeclaredConstructor().newInstance() }
            val loaderNs = System.nanoTime() - loaderStartNs
            check(plugin is OfficialProviderSystemMediaPlugin) {
                "Provider 不是 SystemMedia 插件"
            }
            val host = OfficialProviderSystemMediaHostImpl(application, targetPackage)
            // Plugin installation may subscribe before throwing; rollback must own the host.
            activeRuntimes[definition.id] = ActiveRuntime(plugin, host)
            val installStartNs = System.nanoTime()
            plugin.installSystemMedia(host)
            val installNs = System.nanoTime() - installStartNs
            if (BuildConfig.DEBUG) {
                module.persistentLog(
                    Log.INFO,
                    TAG,
                    "SystemMedia Provider 加载耗时: id=${definition.id} package=$targetPackage " +
                        "readUs=${readNs / 1_000} verifyUs=${verifyNs / 1_000} " +
                        "cacheHit=${verified.verificationCacheHit} " +
                        "dex=${verified.dexFiles.size} loaderUs=${loaderNs / 1_000} " +
                        "installUs=${installNs / 1_000}",
                )
            }
            runCatching { module.persistentLog(
                Log.INFO,
                TAG,
                "SystemMedia Provider 已加载: id=${definition.id} package=$targetPackage " +
                    "version=$installedVersion",
            ) }
            true
        }.onFailure { error ->
            activeRuntimes.remove(definition.id)?.let { runtime ->
                val cleanup = RuntimeResourceCleanup()
                cleanup.attempt("failed SystemMedia plugin") { runtime.plugin.releaseSystemMedia() }
                cleanup.attempt("failed SystemMedia host") { runtime.host.release() }
                cleanup.failureOrNull()?.let { failure ->
                    cleanupFailure = failure
                    error.addSuppressed(failure)
                    throw error
                }
            }
            runCatching { module.persistentLog(
                Log.WARN,
                TAG,
                "SystemMedia Provider 未加载: id=${definition.id} package=$targetPackage " +
                    "reason=${error.message}",
            ) }
        }.getOrDefault(false)
    }

    private data class ActiveRuntime(
        val plugin: OfficialProviderSystemMediaPlugin,
        val host: OfficialProviderSystemMediaHostImpl,
    )
}
