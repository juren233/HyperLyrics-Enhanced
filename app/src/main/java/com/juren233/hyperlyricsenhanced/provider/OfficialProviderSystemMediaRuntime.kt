/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import android.app.Application
import android.os.ParcelFileDescriptor
import android.util.Log
import com.juren233.hyperlyricsenhanced.common.UIConstants
import dalvik.system.InMemoryDexClassLoader
import io.github.libxposed.api.XposedModule
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/** Loads Provider Packs that observe public SystemUI MediaSession state. */
object OfficialProviderSystemMediaRuntime {
    private const val TAG = "OfficialProviderSystemMediaRuntime"
    private val activeRuntimes = ConcurrentHashMap<String, ActiveRuntime>()

    fun installIfAvailable(
        module: XposedModule,
        application: Application,
    ): Boolean {
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
        activeRuntimes.values.forEach { runtime ->
            runCatching { runtime.plugin.releaseSystemMedia() }
            runtime.host.release()
        }
        activeRuntimes.clear()
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

            val packBytes = ParcelFileDescriptor.AutoCloseInputStream(
                module.openRemoteFile(remoteName),
            ).use { it.readBytes() }
            val verified = ProviderPackVerifier.verify(packBytes, targetPackage)
            check(verified.manifest.pluginId == definition.id)
            check(verified.manifest.versionCode == installedVersion)
            val loader = InMemoryDexClassLoader(
                ByteBuffer.wrap(verified.classesDex),
                OfficialProviderSystemMediaRuntime::class.java.classLoader,
            )
            val entryClass = loader.loadClass(verified.manifest.entryClass)
            val plugin = runCatching {
                entryClass.getField("INSTANCE").get(null)
            }.getOrElse { entryClass.getDeclaredConstructor().newInstance() }
            check(plugin is OfficialProviderSystemMediaPlugin) {
                "Provider 不是 SystemMedia 插件"
            }
            val host = OfficialProviderSystemMediaHostImpl(application, targetPackage)
            plugin.installSystemMedia(host)
            activeRuntimes[definition.id] = ActiveRuntime(plugin, host)
            module.log(
                Log.INFO,
                TAG,
                "SystemMedia Provider 已加载: id=${definition.id} package=$targetPackage " +
                    "version=$installedVersion",
            )
            true
        }.onFailure { error ->
            module.log(
                Log.WARN,
                TAG,
                "SystemMedia Provider 未加载: id=${definition.id} package=$targetPackage " +
                    "reason=${error.message}",
            )
        }.getOrDefault(false)
    }

    private data class ActiveRuntime(
        val plugin: OfficialProviderSystemMediaPlugin,
        val host: OfficialProviderSystemMediaHostImpl,
    )
}
