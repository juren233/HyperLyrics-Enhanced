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
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
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
                DexKitSession(application).use { session ->
                    runCatching {
                    val materializedQuery = materializeQuery(query, emptyMap())
                    val target = resolveDexMethod(
                        application,
                        cacheKey,
                        materializedQuery,
                        session,
                    )
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
                                query = materializedQuery.copy(preferredTarget = null),
                                session = session,
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
                DexKitSession(application).use { session ->
                    runCatching {
                    val resolvedByKey = LinkedHashMap<String, OfficialProviderMethodTarget>()
                    queries.map { rawQuery ->
                        val query = materializeQuery(rawQuery, resolvedByKey)
                        val cacheKey = OfficialProviderDexMethodCacheCodec.cacheKey(
                            packageName = packageName,
                            processName = processName,
                            versionCode = packageInfo.longVersionCode,
                            lastUpdateTime = packageInfo.lastUpdateTime,
                            query = query,
                        )
                        resolveDexMethod(application, cacheKey, query, session).also { target ->
                            check(resolvedByKey.put(rawQuery.cacheKey, target) == null) {
                                "Provider DexKit 查询 cacheKey 重复: ${rawQuery.cacheKey}"
                            }
                        }
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
        session: DexKitSession,
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
                recordDexMethodBaseline(application, query, cachedTarget)
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
                    recordDexMethodBaseline(application, query, preferredTarget)
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
        val target = session.bridge(threadCount).let { bridge ->
            val finder = FindMethod().apply {
                matcher {
                    query.declaringClassName?.let(::declaredClass)
                    query.declaringClassNamePrefix?.let { prefix ->
                        declaredClass(prefix, StringMatchType.StartsWith, false)
                    }
                    query.requiredStrings.forEach(::addEqString)
                    query.requiredInvokedMethodDescriptors.forEach(::addInvoke)
                    query.requiredInvokedMethodNames.forEach { methodName ->
                        addInvoke(MethodMatcher().name(methodName))
                    }
                    query.parameterTypeNames?.let(::paramTypes)
                    query.returnTypeName?.let(::returnType)
                    query.returnTypeNamePrefix?.let { prefix ->
                        returnType(prefix, StringMatchType.StartsWith, false)
                    }
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
            selectDexMethodMatch(application, query, matches)
        }

        resolveMethod(target)
        recordDexMethodBaseline(application, query, target)
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

    private fun materializeQuery(
        query: OfficialProviderDexMethodQuery,
        resolvedByKey: Map<String, OfficialProviderMethodTarget>,
    ): OfficialProviderDexMethodQuery {
        fun resolve(reference: OfficialProviderDexTypeReference): String {
            val target = checkNotNull(resolvedByKey[reference.queryCacheKey]) {
                "Provider DexKit 类型引用必须指向更早的查询: " +
                    "query=${query.cacheKey} reference=${reference.queryCacheKey}"
            }
            return when (reference.source) {
                OfficialProviderDexTypeSource.DECLARING_CLASS -> target.className
                OfficialProviderDexTypeSource.RETURN_TYPE -> target.returnTypeName
                OfficialProviderDexTypeSource.PARAMETER_TYPE ->
                    target.parameterTypeNames.getOrNull(reference.parameterIndex)
                        ?: error(
                            "Provider DexKit 参数类型引用越界: " +
                                "query=${query.cacheKey} reference=${reference.queryCacheKey} " +
                                "index=${reference.parameterIndex}",
                        )
            }
        }

        val materializedParameterTypes = query.parameterTypeNames?.toMutableList()
        query.parameterTypeReferences.forEach { (index, reference) ->
            checkNotNull(materializedParameterTypes)[index] = resolve(reference)
        }
        return query.copy(
            declaringClassName = query.declaringClassReference?.let(::resolve)
                ?: query.declaringClassName,
            declaringClassReference = null,
            parameterTypeNames = materializedParameterTypes,
            parameterTypeReferences = emptyMap(),
            returnTypeName = query.returnTypeReference?.let(::resolve) ?: query.returnTypeName,
            returnTypeReference = null,
        )
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

    private fun selectDexMethodMatch(
        application: Application,
        query: OfficialProviderDexMethodQuery,
        matches: List<OfficialProviderMethodTarget>,
    ): OfficialProviderMethodTarget {
        if (matches.size == 1) return matches.single()
        val baseline = OfficialProviderDexMethodBaselineCodec.decode(
            application.getSharedPreferences(
                DEX_METHOD_BASELINE_PREFERENCES,
                Context.MODE_PRIVATE,
            ).getString(dexBaselineKey(query), null),
        )
        val repaired = baseline?.let { seed ->
            matches.filter { target ->
                runCatching { resolveMethod(target) }.getOrNull()?.let { method ->
                    seed.matchesClass(method.declaringClass) &&
                        seed.matchesMethod(method) &&
                        seed.ordinal == methodOrdinal(method, seed)
                } == true
            }
        }.orEmpty()
        require(repaired.size == 1) {
            "Provider DexKit 查询结果必须唯一: key=${query.cacheKey} " +
                "count=${matches.size} repaired=${repaired.size} " +
                "targets=${matches.joinToString { describe(it) }}"
        }
        module.log(
            Log.INFO,
            tag,
            "官方 Provider DexKit 使用跨版本结构基线消歧: package=$packageName " +
                "process=$processName key=${query.cacheKey} target=${describe(repaired.single())}",
        )
        return repaired.single()
    }

    private fun recordDexMethodBaseline(
        application: Application,
        query: OfficialProviderDexMethodQuery,
        target: OfficialProviderMethodTarget,
    ) {
        val method = runCatching { resolveMethod(target) }.getOrNull() ?: return
        val clazz = method.declaringClass
        val baseline = OfficialProviderDexMethodBaseline(
            fieldCount = clazz.declaredFields.size,
            methodCount = clazz.declaredMethods.size,
            interfaceCount = clazz.interfaces.size,
            stableFieldTypeCounts = clazz.declaredFields
                .mapNotNull { field -> field.type.name.takeIf(::isStableRuntimeType) }
                .groupingBy { it }
                .eachCount(),
            parameterCount = method.parameterCount,
            stableParameterTypeNames = method.parameterTypes.map { type ->
                type.name.takeIf(::isStableRuntimeType)
            },
            stableReturnTypeName = method.returnType.name.takeIf(::isStableRuntimeType),
            isStatic = Modifier.isStatic(method.modifiers),
            ordinal = methodOrdinal(
                method,
                OfficialProviderDexMethodBaseline(
                    fieldCount = clazz.declaredFields.size,
                    methodCount = clazz.declaredMethods.size,
                    interfaceCount = clazz.interfaces.size,
                    stableFieldTypeCounts = emptyMap(),
                    parameterCount = method.parameterCount,
                    stableParameterTypeNames = method.parameterTypes.map { type ->
                        type.name.takeIf(::isStableRuntimeType)
                    },
                    stableReturnTypeName = method.returnType.name.takeIf(::isStableRuntimeType),
                    isStatic = Modifier.isStatic(method.modifiers),
                    ordinal = 0,
                ),
            ),
        )
        application.getSharedPreferences(
            DEX_METHOD_BASELINE_PREFERENCES,
            Context.MODE_PRIVATE,
        ).edit()
            .putString(
                dexBaselineKey(query),
                OfficialProviderDexMethodBaselineCodec.encode(baseline),
            )
            .apply()
    }

    private fun methodOrdinal(method: Method, baseline: OfficialProviderDexMethodBaseline): Int =
        method.declaringClass.declaredMethods
            .filter { candidate -> baseline.matchesMethod(candidate) }
            .indexOfFirst { candidate ->
                candidate.name == method.name &&
                    candidate.parameterTypes.contentEquals(method.parameterTypes)
            }
            .coerceAtLeast(0)

    private fun OfficialProviderDexMethodBaseline.matchesClass(clazz: Class<*>): Boolean {
        if (kotlin.math.abs(clazz.declaredFields.size - fieldCount) > CLASS_COUNT_TOLERANCE) {
            return false
        }
        if (kotlin.math.abs(clazz.declaredMethods.size - methodCount) > CLASS_COUNT_TOLERANCE) {
            return false
        }
        if (kotlin.math.abs(clazz.interfaces.size - interfaceCount) > 1) return false
        val fieldTypes = clazz.declaredFields
            .mapNotNull { field -> field.type.name.takeIf(::isStableRuntimeType) }
            .groupingBy { it }
            .eachCount()
        return stableFieldTypeCounts.all { (type, count) -> (fieldTypes[type] ?: 0) >= count }
    }

    private fun OfficialProviderDexMethodBaseline.matchesMethod(method: Method): Boolean =
        method.parameterCount == parameterCount &&
            Modifier.isStatic(method.modifiers) == isStatic &&
            stableParameterTypeNames.withIndex().all { (index, typeName) ->
                typeName == null || method.parameterTypes.getOrNull(index)?.name == typeName
            } &&
            (stableReturnTypeName == null || method.returnType.name == stableReturnTypeName)

    private fun isStableRuntimeType(typeName: String): Boolean =
        typeName == "void" ||
            typeName in setOf(
                "boolean",
                "byte",
                "char",
                "short",
                "int",
                "long",
                "float",
                "double",
            ) ||
            typeName.startsWith("java.") ||
            typeName.startsWith("android.") ||
            typeName.startsWith("kotlin.") ||
            typeName.startsWith("androidx.")

    private fun dexBaselineKey(query: OfficialProviderDexMethodQuery): String =
        "$packageName:$processName:${query.cacheKey}"

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
        const val DEX_METHOD_BASELINE_PREFERENCES =
            "com.juren233.hyperlyricsenhanced.official_provider_dex_method_baselines"
        const val CLASS_COUNT_TOLERANCE = 4
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

    private inner class DexKitSession(
        private val application: Application,
    ) : AutoCloseable {
        private var bridge: DexKitBridge? = null

        fun bridge(threadCount: Int): DexKitBridge {
            bridge?.let { return it }
            ensureDexKitLoaded(module)
            return DexKitBridge.create(application.applicationInfo.sourceDir).also { created ->
                created.setThreadNum(threadCount)
                created.setMaxConcurrentQueries(1)
                bridge = created
            }
        }

        override fun close() {
            bridge?.close()
            bridge = null
        }
    }
}
