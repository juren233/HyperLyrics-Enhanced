/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.UIConstants
import dalvik.system.InMemoryDexClassLoader
import io.github.libxposed.api.XposedModule
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

object OfficialProviderRuntime {
    private const val TAG = "OfficialProviderRuntime"

    /** 插件加载成功后经过该时长仍未退出，视为一次健康运行并清除崩溃计数标记。 */
    private const val HEALTHY_GRACE_MS = 30_000L

    private val loadedPackages = ConcurrentHashMap.newKeySet<String>()
    private val loggedUnavailableReasons = ConcurrentHashMap.newKeySet<String>()

    fun installIfAvailable(
        module: XposedModule,
        targetClassLoader: ClassLoader,
        packageName: String,
        processName: String,
    ): Boolean {
        val definition = OfficialProviderCatalog.definitionForPackage(packageName) ?: return false
        if (definition.systemMediaRuntime) return false
        if (packageName in loadedPackages) return true

        return try {
            // 远程偏好在被 Hook 进程里是只读的（libxposed API 契约），
            // 只用于读取模块下发的开关与安装记录。
            val preferences = module.getRemotePreferences(UIConstants.PREF_NAME)
            val isAppleMusic = definition.id == OfficialProviderCatalog.APPLE_MUSIC_PLUGIN_ID
            val enabled = preferences.getBoolean(
                OfficialProviderCatalog.enabledKey(definition.id),
                isAppleMusic,
            )
            if (!enabled) {
                return logUnavailable(module, definition.id, packageName, "disabled")
            }
            val installedVersion = preferences.getInt(
                OfficialProviderCatalog.installedVersionKey(definition.id),
                0,
            )
            val remoteName = preferences.getString(
                OfficialProviderCatalog.activeFileKey(packageName),
                null,
            )?.takeIf(String::isNotBlank)

            // 模块升级后内置基线包可能比之前下载的远程包更新，此时优先加载基线包；
            // 只读基线包清单比较版本，不读取 DEX。
            if (isAppleMusic && installedVersion > 0 &&
                (ProviderBaselinePack.manifest(module, definition.id)?.versionCode ?: 0) >
                installedVersion
            ) {
                loadBaseline(
                    module = module,
                    targetClassLoader = targetClassLoader,
                    definition = definition,
                    packageName = packageName,
                    processName = processName,
                )?.let { return it }
            }
            if (installedVersion <= 0 || remoteName == null) {
                if (isAppleMusic) {
                    loadBaseline(
                        module = module,
                        targetClassLoader = targetClassLoader,
                        definition = definition,
                        packageName = packageName,
                        processName = processName,
                    )?.let { return it }
                }
                return logUnavailable(
                    module,
                    definition.id,
                    packageName,
                    if (installedVersion <= 0) "installed_version_missing" else "active_file_missing",
                )
            }

            // 崩溃计数、坏版本、实际加载版本写入目标 App 自己的 filesDir；
            // 目录不可用时退化为无标记模式继续加载，绝不阻断插件本身。
            val store = ProviderTargetStateStore.forTargetProcess(packageName)

            // 崩溃回退只在主进程计数：secondary process 生命周期天然更短，快进快出
            // 会把正常退出误算成崩溃。
            val isMainProcess = processName == packageName
            val previousVersionCode = preferences.getInt(
                OfficialProviderCatalog.previousVersionKey(definition.id),
                0,
            )
            // 坏版本对所有进程生效，避免主进程已回退而子进程仍加载坏版本。
            val badVersionCodes = store?.readBadVersions(definition.id).orEmpty()
            val decision = if (isMainProcess) {
                val marker = store?.readMarker(definition.id)
                val previousExitReason = ProviderProcessExit.previousExitReason(packageName, marker)
                val previousExitWasCrash = previousExitReason?.let(ProviderProcessExit::isCrashReason) ?: false
                if (BuildConfig.DEBUG && marker != null) {
                    module.log(
                        Log.INFO,
                        TAG,
                        "留存标记的上次退出: id=${definition.id} marker=${ProviderLoadMarker.encode(marker)} " +
                            "exitReason=$previousExitReason crash=$previousExitWasCrash",
                    )
                }
                ProviderLoadMarker.decide(
                    pendingVersionCode = installedVersion,
                    marker = marker,
                    previousVersionCode = previousVersionCode,
                    badVersionCodes = badVersionCodes,
                    previousExitWasCrash = previousExitWasCrash,
                )
            } else if (installedVersion in badVersionCodes) {
                ProviderLoadMarker.decide(
                    pendingVersionCode = installedVersion,
                    marker = null,
                    previousVersionCode = previousVersionCode,
                    badVersionCodes = badVersionCodes,
                )
            } else {
                ProviderLoadMarker.Decision.LoadNormally(
                    versionCode = installedVersion,
                    newMarker = ProviderLoadMarker.MarkerState(
                        installedVersion,
                        1,
                        System.currentTimeMillis(),
                    ),
                )
            }

            when (decision) {
                is ProviderLoadMarker.Decision.Rollback -> {
                    store?.addBadVersion(definition.id, decision.badVersionCode)
                    val fallbackVersion = decision.fallbackVersionCode
                    if (fallbackVersion == null) {
                        // 基线包若能加载，loadBaseline 会重写已加载版本；否则本次没有加载任何版本。
                        store?.clearLoadedVersion(definition.id)
                        if (isAppleMusic) {
                            loadBaseline(
                                module = module,
                                targetClassLoader = targetClassLoader,
                                definition = definition,
                                packageName = packageName,
                                processName = processName,
                            )?.let { return it }
                        }
                        return logUnavailable(
                            module, definition.id, packageName, "rollback_without_previous",
                        )
                    }
                    val fallbackName = OfficialProviderCatalog.remoteFileName(
                        definition.id,
                        fallbackVersion,
                    )
                    if (fallbackName !in module.listRemoteFiles()) {
                        if (isAppleMusic) {
                            loadBaseline(
                                module = module,
                                targetClassLoader = targetClassLoader,
                                definition = definition,
                                packageName = packageName,
                                processName = processName,
                            )?.let { return it }
                        }
                        return logUnavailable(
                            module, definition.id, packageName, "rollback_file_missing:$fallbackName",
                        )
                    }
                    module.log(
                        Log.WARN,
                        TAG,
                        "插件连续异常启动，回退上一版本: id=${definition.id} " +
                            "bad=${decision.badVersionCode} fallback=$fallbackVersion",
                    )
                    loadPlugin(
                        module = module,
                        targetClassLoader = targetClassLoader,
                        definition = definition,
                        packageName = packageName,
                        processName = processName,
                        remoteName = fallbackName,
                        expectedVersion = fallbackVersion,
                        store = store,
                        scheduleMarkerClear = false,
                    )
                }

                is ProviderLoadMarker.Decision.LoadNormally -> {
                    if (remoteName !in module.listRemoteFiles()) {
                        if (isAppleMusic) {
                            loadBaseline(
                                module = module,
                                targetClassLoader = targetClassLoader,
                                definition = definition,
                                packageName = packageName,
                                processName = processName,
                            )?.let { return it }
                        }
                        return logUnavailable(
                            module,
                            definition.id,
                            packageName,
                            "remote_file_missing:$remoteName",
                        )
                    }
                    if (isMainProcess) {
                        store?.writeMarker(
                            definition.id,
                            decision.newMarker.copy(pid = android.os.Process.myPid()),
                        )
                    }
                    loadPlugin(
                        module = module,
                        targetClassLoader = targetClassLoader,
                        definition = definition,
                        packageName = packageName,
                        processName = processName,
                        remoteName = remoteName,
                        expectedVersion = installedVersion,
                        store = store,
                        scheduleMarkerClear = isMainProcess,
                    )
                }
            }
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

    private fun loadBaseline(
        module: XposedModule,
        targetClassLoader: ClassLoader,
        definition: OfficialProviderCatalog.Definition,
        packageName: String,
        processName: String,
    ): Boolean? {
        val removedByUser = runCatching {
            module.getRemotePreferences(UIConstants.PREF_NAME)
                .getBoolean(OfficialProviderCatalog.removedKey(definition.id), false)
        }.getOrDefault(false)
        if (removedByUser) return null
        val verified = ProviderBaselinePack.verified(module, definition.id) ?: return null
        // 不匹配时返回 null 而不是抛出，调用方还能继续尝试远程包。
        if (verified.manifest.pluginId != definition.id ||
            packageName !in verified.manifest.targetPackages
        ) {
            module.log(
                Log.WARN,
                TAG,
                "基线包与当前目标不匹配，跳过: expected=${definition.id}/$packageName " +
                    "actual=${verified.manifest.pluginId}/${verified.manifest.targetPackages}",
            )
            return null
        }
        return installVerified(
            module = module,
            targetClassLoader = targetClassLoader,
            definition = definition,
            packageName = packageName,
            processName = processName,
            verified = verified,
            expectedVersion = verified.manifest.versionCode,
            source = "baseline",
            // 只用于写入「实际加载版本」供界面读回；基线包不参与崩溃标记与坏版本回退。
            store = ProviderTargetStateStore.forTargetProcess(packageName),
            scheduleMarkerClear = false,
            loadTiming = "baseline",
        )
    }

    private fun loadPlugin(
        module: XposedModule,
        targetClassLoader: ClassLoader,
        definition: OfficialProviderCatalog.Definition,
        packageName: String,
        processName: String,
        remoteName: String,
        expectedVersion: Int,
        store: ProviderTargetStateStore?,
        scheduleMarkerClear: Boolean,
    ): Boolean {
        module.log(
            Log.INFO,
            TAG,
            "开始加载官方 Provider: id=${definition.id} package=$packageName " +
                "version=$expectedVersion file=$remoteName",
        )
        val readStartNs = System.nanoTime()
        val packBytes = ParcelFileDescriptor.AutoCloseInputStream(
            module.openRemoteFile(remoteName),
        ).use { it.readBytes() }
        val readNs = System.nanoTime() - readStartNs
        val verificationCache = runCatching {
            module.getRemotePreferences(UIConstants.PREF_NAME)
                .getString(OfficialProviderCatalog.VERIFIED_PACK_CACHE_KEY, null)
        }.getOrNull()
        val verifyStartNs = System.nanoTime()
        val verified = ProviderPackVerifier.verify(
            packBytes = packBytes,
            expectedTargetPackage = packageName,
            verificationCache = verificationCache,
            verificationCacheKey = remoteName,
        )
        val verifyNs = System.nanoTime() - verifyStartNs
        require(verified.manifest.pluginId == definition.id) {
            "Provider Pack ID 与目录不一致: expected=${definition.id}, " +
                "actual=${verified.manifest.pluginId}"
        }
        require(verified.manifest.versionCode == expectedVersion) {
            "Provider Pack 版本与已安装记录不一致: expected=$expectedVersion, " +
                "actual=${verified.manifest.versionCode}"
        }
        return installVerified(
            module = module,
            targetClassLoader = targetClassLoader,
            definition = definition,
            packageName = packageName,
            processName = processName,
            verified = verified,
            expectedVersion = expectedVersion,
            source = "remote:$remoteName",
            store = store,
            scheduleMarkerClear = scheduleMarkerClear,
            loadTiming = "readUs=${readNs / 1_000} verifyUs=${verifyNs / 1_000} " +
                "cacheHit=${verified.verificationCacheHit}",
        )
    }

    private fun installVerified(
        module: XposedModule,
        targetClassLoader: ClassLoader,
        definition: OfficialProviderCatalog.Definition,
        packageName: String,
        processName: String,
        verified: VerifiedProviderPack,
        expectedVersion: Int,
        source: String,
        store: ProviderTargetStateStore?,
        scheduleMarkerClear: Boolean,
        loadTiming: String,
    ): Boolean {
        val loaderStartNs = System.nanoTime()
        val pluginLoader = InMemoryDexClassLoader(
            verified.dexFiles.map { ByteBuffer.wrap(it) }.toTypedArray(),
            ProviderPackParentClassLoader(
                delegate = OfficialProviderRuntime::class.java.classLoader
                    ?: error("缺少模块类加载器"),
                blockedPrefix = "com.juren233.hle.providers.${definition.id}.",
            ),
        )
        val entryClass = pluginLoader.loadClass(verified.manifest.entryClass)
        val plugin = runCatching {
            entryClass.getField("INSTANCE").get(null)
        }.getOrElse {
            entryClass.getDeclaredConstructor().newInstance()
        }
        val loaderNs = System.nanoTime() - loaderStartNs
        require(plugin is OfficialProviderPlugin) {
            "Provider 入口未实现官方插件 API"
        }
        val host = OfficialProviderHookHost(
            module = module,
            targetClassLoader = targetClassLoader,
            packageName = packageName,
            processName = processName,
            pluginVersionCode = verified.manifest.versionCode,
        )
        val installStartNs = System.nanoTime()
        plugin.install(host)
        val installNs = System.nanoTime() - installStartNs
        host.logInstalled(definition.id)
        loadedPackages += packageName
        store?.writeLoadedVersion(definition.id, expectedVersion)
        if (BuildConfig.DEBUG) {
            module.log(
                Log.INFO,
                TAG,
                "已加载版本记录: id=${definition.id} package=$packageName source=$source " +
                    "version=$expectedVersion storeAvailable=${store != null} " +
                    "readBack=${store?.readLoadedVersion(definition.id)}",
            )
        }
        if (scheduleMarkerClear) {
            scheduleMarkerClear(store, definition.id)
        }
        if (BuildConfig.DEBUG) {
            module.log(
                Log.INFO,
                TAG,
                "官方 Provider 加载耗时: id=${definition.id} package=$packageName source=$source " +
                    "$loadTiming dex=${verified.dexFiles.size} " +
                    "loaderUs=${loaderNs / 1_000} installUs=${installNs / 1_000} " +
                    "hooks=${host.hookInstallCount()} hookUs=${host.hookInstallDurationNanos() / 1_000}",
            )
        }
        module.log(
            Log.INFO,
            TAG,
            "官方 Provider 已加载: id=${definition.id} package=$packageName " +
                "version=$expectedVersion source=$source",
        )
        return true
    }

    private class ProviderPackParentClassLoader(
        delegate: ClassLoader,
        private val blockedPrefix: String,
    ) : ClassLoader(delegate) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.startsWith(blockedPrefix)) throw ClassNotFoundException(name)
            return super.loadClass(name, resolve)
        }
    }

    /** 健康运行 [HEALTHY_GRACE_MS] 后清除崩溃计数标记；期间进程死亡则标记留存。 */
    private fun scheduleMarkerClear(store: ProviderTargetStateStore?, pluginId: String) {
        if (store == null) return
        Handler(Looper.getMainLooper()).postDelayed({
            store.clearMarker(pluginId)
        }, HEALTHY_GRACE_MS)
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
