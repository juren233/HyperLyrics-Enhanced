/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import android.os.ParcelFileDescriptor
import android.util.Log
import com.juren233.hyperlyricsenhanced.common.UIConstants
import dalvik.system.InMemoryDexClassLoader
import io.github.libxposed.api.XposedModule
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

object OfficialProviderRuntime {
    private const val TAG = "OfficialProviderRuntime"
    private val loadedPackages = ConcurrentHashMap.newKeySet<String>()
    private val loggedUnavailableReasons = ConcurrentHashMap.newKeySet<String>()

    fun installIfAvailable(
        module: XposedModule,
        targetClassLoader: ClassLoader,
        packageName: String,
        processName: String,
    ): Boolean {
        val definition = OfficialProviderCatalog.definitionForPackage(packageName) ?: return false
        if (packageName == OfficialProviderCatalog.APPLE_MUSIC_PACKAGE_NAME) return false
        if (packageName in loadedPackages) return true

        return try {
            val preferences = module.getRemotePreferences(UIConstants.PREF_NAME)
            val enabled = preferences.getBoolean(
                OfficialProviderCatalog.enabledKey(definition.id),
                false,
            )
            if (!enabled) {
                return logUnavailable(module, definition.id, packageName, "disabled")
            }
            val installedVersion = preferences.getInt(
                OfficialProviderCatalog.installedVersionKey(definition.id),
                0,
            )
            if (installedVersion <= 0) {
                return logUnavailable(module, definition.id, packageName, "installed_version_missing")
            }
            val remoteName = preferences.getString(
                OfficialProviderCatalog.activeFileKey(packageName),
                null,
            )?.takeIf(String::isNotBlank)
                ?: return logUnavailable(module, definition.id, packageName, "active_file_missing")
            if (remoteName !in module.listRemoteFiles()) {
                return logUnavailable(
                    module,
                    definition.id,
                    packageName,
                    "remote_file_missing:$remoteName",
                )
            }

            module.log(
                Log.INFO,
                TAG,
                "开始加载官方 Provider: id=${definition.id} package=$packageName " +
                    "version=$installedVersion file=$remoteName",
            )
            val packBytes = ParcelFileDescriptor.AutoCloseInputStream(
                module.openRemoteFile(remoteName),
            ).use { it.readBytes() }
            val verified = ProviderPackVerifier.verify(
                packBytes = packBytes,
                expectedTargetPackage = packageName,
            )
            require(verified.manifest.pluginId == definition.id) {
                "Provider Pack ID 与目录不一致: expected=${definition.id}, " +
                    "actual=${verified.manifest.pluginId}"
            }
            require(verified.manifest.versionCode == installedVersion) {
                "Provider Pack 版本与已安装记录不一致: expected=$installedVersion, " +
                    "actual=${verified.manifest.versionCode}"
            }
            val pluginLoader = InMemoryDexClassLoader(
                ByteBuffer.wrap(verified.classesDex),
                OfficialProviderRuntime::class.java.classLoader,
            )
            val entryClass = pluginLoader.loadClass(verified.manifest.entryClass)
            val plugin = runCatching {
                entryClass.getField("INSTANCE").get(null)
            }.getOrElse {
                entryClass.getDeclaredConstructor().newInstance()
            }
            require(plugin is OfficialProviderPlugin) {
                "Provider 入口未实现官方插件 API"
            }
            val host = OfficialProviderHookHost(
                module = module,
                targetClassLoader = targetClassLoader,
                packageName = packageName,
                processName = processName,
            )
            plugin.install(host)
            host.logInstalled(definition.id)
            loadedPackages += packageName
            module.log(
                Log.INFO,
                TAG,
                "官方 Provider 已加载: id=${definition.id} package=$packageName",
            )
            true
        } catch (error: Throwable) {
            module.log(
                Log.ERROR,
                TAG,
                "官方 Provider 加载失败: id=${definition.id} package=$packageName",
                error,
            )
            false
        }
    }

    private fun logUnavailable(
        module: XposedModule,
        pluginId: String,
        packageName: String,
        reason: String,
    ): Boolean {
        if (loggedUnavailableReasons.add("$packageName:$reason")) {
            module.log(
                Log.INFO,
                TAG,
                "官方 Provider 未加载: id=$pluginId package=$packageName reason=$reason",
            )
        }
        return false
    }
}
