/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import android.app.Application
import android.app.ActivityManager
import android.app.Instrumentation
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Static host for official Provider Packs.
 *
 * Only this class touches libxposed. Pack callbacks receive ordinary Android
 * values and never receive [XposedModule] or [XposedInterface.Chain].
 */
internal class OfficialProviderHookHost(
    private val module: XposedModule,
    private val targetClassLoader: ClassLoader,
    override val packageName: String,
    override val processName: String,
) : OfficialProviderHost {
    private val tag = "OfficialProviderHookHost"
    private val dexHookTasks = ConcurrentHashMap.newKeySet<String>()

    override fun hookApplication(callback: OfficialProviderApplicationCallback) {
        val method = Instrumentation::class.java.getDeclaredMethod(
            "callApplicationOnCreate",
            Application::class.java,
        )
        module.hook(method).intercept(
            ApplicationCreatedHooker(module, packageName, callback),
        )
        module.log(
            Log.INFO,
            tag,
            "官方 Provider 生命周期 Hook 已安装: package=$packageName",
        )
    }

    override fun hookMediaSession(
        playbackStateCallback: OfficialProviderPlaybackStateCallback,
        metadataCallback: OfficialProviderMetadataCallback,
    ) {
        val mediaSessionClass = Class.forName(
            MediaSession::class.java.name,
            false,
            targetClassLoader,
        )
        val setPlaybackState = mediaSessionClass.getDeclaredMethod(
            "setPlaybackState",
            PlaybackState::class.java,
        )
        module.hook(setPlaybackState).intercept(
            PlaybackStateHooker(module, packageName, playbackStateCallback),
        )

        val setMetadata = mediaSessionClass.getDeclaredMethod(
            "setMetadata",
            MediaMetadata::class.java,
        )
        module.hook(setMetadata).intercept(
            MetadataHooker(module, packageName, metadataCallback),
        )
        module.log(
            Log.INFO,
            tag,
            "官方 Provider MediaSession Hook 已安装: package=$packageName",
        )
    }

    override fun hookAfterMethod(
        target: OfficialProviderMethodTarget,
        callback: OfficialProviderMethodCallback,
    ) {
        installAfterMethod(target, callback)
    }

    override fun hookAfterDexMethod(
        application: Application,
        query: OfficialProviderDexMethodQuery,
        callback: OfficialProviderMethodCallback,
    ) {
        require(application.packageName == packageName) {
            "Provider DexKit Application 与目标包不一致"
        }
        OfficialProviderDexMethodQueryValidator.validate(query)

        val packageInfo = application.packageManager.getPackageInfo(packageName, 0)
        val cacheKey = OfficialProviderDexMethodCacheCodec.cacheKey(
            packageName = packageName,
            processName = processName,
            versionCode = packageInfo.longVersionCode,
            lastUpdateTime = packageInfo.lastUpdateTime,
            query = query,
        )
        if (!dexHookTasks.add(cacheKey)) return

        Thread(
            {
                runCatching {
                    val target = resolveDexMethod(application, cacheKey, query)
                    runCatching { installAfterMethod(target, callback) }
                        .getOrElse { firstError ->
                            invalidateDexMethodCache(application, cacheKey)
                            module.log(
                                Log.WARN,
                                tag,
                                "官方 Provider Hook 安装失败，清除缓存并重新查询: " +
                                    "package=$packageName process=$processName key=${query.cacheKey}",
                                firstError,
                            )
                            val repairedTarget = resolveDexMethod(
                                application = application,
                                cacheKey = cacheKey,
                                query = query.copy(preferredTarget = null),
                            )
                            installAfterMethod(repairedTarget, callback)
                        }
                }.onFailure { error ->
                    module.log(
                        Log.ERROR,
                        tag,
                        "官方 Provider DexKit Hook 失败: package=$packageName " +
                            "process=$processName key=${query.cacheKey}",
                        error,
                    )
                }
            },
            "HLE-Provider-DexKit",
        ).apply {
            isDaemon = true
            start()
        }
    }

    override fun resolveDexMethods(
        application: Application,
        queries: List<OfficialProviderDexMethodQuery>,
        callback: OfficialProviderDexMethodsCallback,
    ) {
        require(application.packageName == packageName) {
            "Provider DexKit Application 与目标包不一致"
        }
        require(queries.isNotEmpty()) { "Provider DexKit 查询列表不能为空" }
        queries.forEach(OfficialProviderDexMethodQueryValidator::validate)
        val packageInfo = application.packageManager.getPackageInfo(packageName, 0)
        val taskKey = queries.joinToString("|") { query ->
            OfficialProviderDexMethodCacheCodec.cacheKey(
                packageName = packageName,
                processName = processName,
                versionCode = packageInfo.longVersionCode,
                lastUpdateTime = packageInfo.lastUpdateTime,
                query = query,
            )
        }
        if (!dexHookTasks.add(taskKey)) return

        Thread(
            {
                runCatching {
                    queries.map { query ->
                        val cacheKey = OfficialProviderDexMethodCacheCodec.cacheKey(
                            packageName = packageName,
                            processName = processName,
                            versionCode = packageInfo.longVersionCode,
                            lastUpdateTime = packageInfo.lastUpdateTime,
                            query = query,
                        )
                        resolveDexMethod(application, cacheKey, query)
                    }
                }.onSuccess(callback::onMethodsResolved)
                    .onFailure { error ->
                        module.log(
                            Log.ERROR,
                            tag,
                            "官方 Provider DexKit 批量解析失败: package=$packageName " +
                                "process=$processName keys=${queries.joinToString { it.cacheKey }}",
                            error,
                        )
                    }
            },
            "HLE-Provider-DexKit",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun resolveDexMethod(
        application: Application,
        cacheKey: String,
        query: OfficialProviderDexMethodQuery,
    ): OfficialProviderMethodTarget {
        val preferences = application.getSharedPreferences(
            DEX_METHOD_CACHE_PREFERENCES,
            Context.MODE_PRIVATE,
        )
        val cachedTarget = OfficialProviderDexMethodCacheCodec.decode(
            preferences.getString(cacheKey, null),
        )?.takeIf { OfficialProviderDexMethodCacheCodec.matches(it, query) }

        if (cachedTarget != null) {
            val cachedResolution = runCatching { resolveMethod(cachedTarget) }
            if (cachedResolution.isSuccess) {
                module.log(
                    Log.INFO,
                    tag,
                    "官方 Provider DexKit 缓存命中: package=$packageName " +
                        "process=$processName target=${describe(cachedTarget)}",
                )
                return cachedTarget
            }
            preferences.edit().remove(cacheKey).apply()
            module.log(
                Log.WARN,
                tag,
                "官方 Provider DexKit 缓存失效，重新查询: package=$packageName " +
                    "process=$processName key=${query.cacheKey}",
                cachedResolution.exceptionOrNull(),
            )
        } else if (preferences.contains(cacheKey)) {
            preferences.edit().remove(cacheKey).apply()
        }

        query.preferredTarget
            ?.takeIf { OfficialProviderDexMethodCacheCodec.matches(it, query) }
            ?.let { preferredTarget ->
                val preferredResolution = runCatching { resolveMethod(preferredTarget) }
                if (preferredResolution.isSuccess) {
                    preferences.edit()
                        .putString(
                            cacheKey,
                            OfficialProviderDexMethodCacheCodec.encode(preferredTarget),
                        )
                        .apply()
                    module.log(
                        Log.INFO,
                        tag,
                        "官方 Provider 首选目标命中并缓存: package=$packageName " +
                            "process=$processName target=${describe(preferredTarget)}",
                    )
                    return preferredTarget
                }
                module.log(
                    Log.WARN,
                    tag,
                    "官方 Provider 首选目标失效，进入 DexKit: package=$packageName " +
                        "process=$processName key=${query.cacheKey}",
                    preferredResolution.exceptionOrNull(),
                )
            }

        val startNanos = System.nanoTime()
        val threadCount = selectDexKitThreadCount(application)
        module.log(
            Log.INFO,
            tag,
            "官方 Provider DexKit 开始查询: package=$packageName process=$processName " +
                "key=${query.cacheKey} threads=$threadCount",
        )
        ensureDexKitLoaded(module)
        val target = DexKitBridge.create(application.applicationInfo.sourceDir).use { bridge ->
            bridge.setThreadNum(threadCount)
            bridge.setMaxConcurrentQueries(1)
            val finder = FindMethod().apply {
                matcher {
                    query.declaringClassName?.let(::declaredClass)
                    query.declaringClassNamePrefix?.let { prefix ->
                        declaredClass(prefix, StringMatchType.StartsWith, false)
                    }
                    query.requiredStrings.forEach(::addEqString)
                    query.requiredInvokedMethodDescriptors.forEach(::addInvoke)
                }
            }
            val matches = bridge.findMethod(finder)
                .map { method ->
                    OfficialProviderMethodTarget(
                        className = method.className,
                        methodName = method.methodName,
                        parameterTypeNames = method.paramTypeNames,
                        returnTypeName = method.returnTypeName,
                        isStatic = Modifier.isStatic(method.modifiers),
                    )
                }
                .distinct()
                .filter { OfficialProviderDexMethodCacheCodec.matches(it, query) }
            require(matches.size == 1) {
                "Provider DexKit 查询结果必须唯一: key=${query.cacheKey} " +
                    "count=${matches.size} targets=${matches.joinToString { describe(it) }}"
            }
            matches.single()
        }

        resolveMethod(target)
        preferences.edit()
            .putString(cacheKey, OfficialProviderDexMethodCacheCodec.encode(target))
            .apply()
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L
        module.log(
            Log.INFO,
            tag,
            "官方 Provider DexKit 查询并校验成功: package=$packageName " +
                "process=$processName elapsedMs=$elapsedMs target=${describe(target)}",
        )
        return target
    }

    private fun invalidateDexMethodCache(application: Application, cacheKey: String) {
        application.getSharedPreferences(
            DEX_METHOD_CACHE_PREFERENCES,
            Context.MODE_PRIVATE,
        ).edit().remove(cacheKey).apply()
    }

    private fun installAfterMethod(
        target: OfficialProviderMethodTarget,
        callback: OfficialProviderMethodCallback,
    ) {
        require(target.className.isNotBlank()) { "Provider Hook className 不能为空" }
        require(target.methodName.isNotBlank()) { "Provider Hook methodName 不能为空" }
        val method = resolveMethod(target)
        val descriptor = describe(target)
        module.hook(method).intercept(AfterMethodHooker(module, descriptor, callback))
        module.log(Log.INFO, tag, "官方 Provider 方法 Hook 已安装: target=$descriptor")
    }

    private fun resolveMethod(target: OfficialProviderMethodTarget): java.lang.reflect.Method {
        val targetClass = Class.forName(target.className, false, targetClassLoader)
        val parameterTypes = target.parameterTypeNames.map(::resolveParameterType).toTypedArray()
        return targetClass.getDeclaredMethod(target.methodName, *parameterTypes).apply {
            isAccessible = true
            require(returnType == resolveReturnType(target.returnTypeName)) {
                "Provider Hook 返回类型不匹配: ${returnType.name}"
            }
            require(Modifier.isStatic(modifiers) == target.isStatic) {
                "Provider Hook static 约束不匹配"
            }
        }
    }

    private fun selectDexKitThreadCount(application: Application): Int {
        val availableProcessors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val availableMemory = runCatching {
            val manager = application.getSystemService(ActivityManager::class.java)
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            info.availMem
        }.getOrDefault(0L)
        return if (availableProcessors >= 4 && availableMemory >= TWO_GIB_BYTES) 2 else 1
    }

    private fun describe(target: OfficialProviderMethodTarget): String = buildString {
        append(target.className)
        append('#')
        append(target.methodName)
        append('(')
        append(target.parameterTypeNames.joinToString())
        append(')')
        append(':')
        append(target.returnTypeName)
        append(if (target.isStatic) "[static]" else "[instance]")
    }

    private fun resolveParameterType(typeName: String): Class<*> = when (typeName) {
        "boolean" -> Boolean::class.javaPrimitiveType!!
        "byte" -> Byte::class.javaPrimitiveType!!
        "char" -> Char::class.javaPrimitiveType!!
        "short" -> Short::class.javaPrimitiveType!!
        "int" -> Int::class.javaPrimitiveType!!
        "long" -> Long::class.javaPrimitiveType!!
        "float" -> Float::class.javaPrimitiveType!!
        "double" -> Double::class.javaPrimitiveType!!
        else -> Class.forName(typeName, false, targetClassLoader)
    }

    private fun resolveReturnType(typeName: String): Class<*> = when (typeName) {
        "void" -> Void.TYPE
        else -> resolveParameterType(typeName)
    }

    private class ApplicationCreatedHooker(
        private val module: XposedModule,
        private val expectedPackageName: String,
        private val callback: OfficialProviderApplicationCallback,
    ) : XposedInterface.Hooker {
        private val firstCallbackRecorded = AtomicBoolean(false)

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            val application = chain.args.firstOrNull() as? Application
            if (application?.packageName == expectedPackageName) {
                runCatching { callback.onApplicationCreated(application) }
                    .onSuccess {
                        if (firstCallbackRecorded.compareAndSet(false, true)) {
                            module.log(
                                Log.INFO,
                                "OfficialProviderHookHost",
                                "官方 Provider 生命周期 Hook 首次命中: " +
                                    "package=$expectedPackageName",
                            )
                        }
                    }
                    .onFailure { error ->
                        module.log(
                            Log.ERROR,
                            "OfficialProviderHookHost",
                            "官方 Provider 生命周期回调失败: " +
                                "package=$expectedPackageName error=${error.message}",
                        )
                    }
            }
            return result
        }
    }

    private class PlaybackStateHooker(
        private val module: XposedModule,
        private val packageName: String,
        private val callback: OfficialProviderPlaybackStateCallback,
    ) : XposedInterface.Hooker {
        private val firstCallbackRecorded = AtomicBoolean(false)

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            runCatching {
                callback.onPlaybackStateChanged(chain.args.firstOrNull() as? PlaybackState)
            }.onSuccess {
                if (firstCallbackRecorded.compareAndSet(false, true)) {
                    module.log(
                        Log.INFO,
                        "OfficialProviderHookHost",
                        "官方 Provider PlaybackState Hook 首次命中: package=$packageName",
                    )
                }
            }.onFailure { error ->
                module.log(
                    Log.ERROR,
                    "OfficialProviderHookHost",
                    "官方 Provider PlaybackState 回调失败: " +
                        "package=$packageName error=${error.message}",
                )
            }
            return result
        }
    }

    private class MetadataHooker(
        private val module: XposedModule,
        private val packageName: String,
        private val callback: OfficialProviderMetadataCallback,
    ) : XposedInterface.Hooker {
        private val firstCallbackRecorded = AtomicBoolean(false)

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            runCatching {
                callback.onMetadataChanged(chain.args.firstOrNull() as? MediaMetadata)
            }.onSuccess {
                if (firstCallbackRecorded.compareAndSet(false, true)) {
                    module.log(
                        Log.INFO,
                        "OfficialProviderHookHost",
                        "官方 Provider Metadata Hook 首次命中: package=$packageName",
                    )
                }
            }.onFailure { error ->
                module.log(
                    Log.ERROR,
                    "OfficialProviderHookHost",
                    "官方 Provider Metadata 回调失败: " +
                        "package=$packageName error=${error.message}",
                )
            }
            return result
        }
    }

    private class AfterMethodHooker(
        private val module: XposedModule,
        private val descriptor: String,
        private val callback: OfficialProviderMethodCallback,
    ) : XposedInterface.Hooker {
        private val firstCallbackRecorded = AtomicBoolean(false)

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            runCatching {
                callback.onMethodCalled(chain.thisObject, chain.args.toTypedArray())
            }.onSuccess {
                if (firstCallbackRecorded.compareAndSet(false, true)) {
                    module.log(
                        Log.INFO,
                        "OfficialProviderHookHost",
                        "官方 Provider 方法 Hook 首次命中: target=$descriptor",
                    )
                }
            }.onFailure { error ->
                module.log(
                    Log.ERROR,
                    "OfficialProviderHookHost",
                    "官方 Provider 方法回调失败: target=$descriptor error=${error.message}",
                )
            }
            return result
        }
    }

    fun logInstalled(pluginId: String) {
        module.log(
            Log.INFO,
            tag,
            "官方 Provider Hook 已安装: id=$pluginId package=$packageName",
        )
    }

    private companion object {
        const val DEX_METHOD_CACHE_PREFERENCES =
            "com.juren233.hyperlyricsenhanced.official_provider_dex_methods"
        const val TWO_GIB_BYTES = 2L * 1024L * 1024L * 1024L
        val dexKitLoaded = AtomicBoolean(false)
        val dexKitLoadLock = Any()

        fun ensureDexKitLoaded(module: XposedModule) {
            if (dexKitLoaded.get()) return
            synchronized(dexKitLoadLock) {
                if (dexKitLoaded.get()) return
                val nativeLibrary = java.io.File(
                    module.getModuleApplicationInfo().nativeLibraryDir,
                    "libdexkit.so",
                )
                require(nativeLibrary.isFile) {
                    "DexKit native library missing: ${nativeLibrary.absolutePath}"
                }
                System.load(nativeLibrary.absolutePath)
                dexKitLoaded.set(true)
            }
        }
    }
}
