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

    fun installIfAvailable(
        module: XposedModule,
        targetClassLoader: ClassLoader,
        packageName: String,
    ): Boolean {
        val definition = OfficialProviderCatalog.definitionForPackage(packageName) ?: return false
        if (packageName == OfficialProviderCatalog.APPLE_MUSIC_PACKAGE_NAME) return false
        if (packageName in loadedPackages) return true

        val preferences = module.getRemotePreferences(UIConstants.PREF_NAME)
        if (!preferences.getBoolean(OfficialProviderCatalog.enabledKey(definition.id), false)) {
            return false
        }
        val remoteName = preferences.getString(
            OfficialProviderCatalog.activeFileKey(packageName),
            null,
        ) ?: return false
        if (remoteName !in module.listRemoteFiles()) return false

        return runCatching {
            val packBytes = ParcelFileDescriptor.AutoCloseInputStream(
                module.openRemoteFile(remoteName),
            ).use { it.readBytes() }
            val verified = ProviderPackVerifier.verify(
                packBytes = packBytes,
                expectedTargetPackage = packageName,
            )
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
        }.onFailure { error ->
            module.log(
                Log.ERROR,
                TAG,
                "官方 Provider 加载失败: id=${definition.id} package=$packageName",
                error,
            )
        }.getOrDefault(false)
    }
}
