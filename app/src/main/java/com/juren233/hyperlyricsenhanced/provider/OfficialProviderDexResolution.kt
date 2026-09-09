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
import android.os.SystemClock
import android.util.Log
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.common.dexkit.DexMethodWatchdog
import com.juren233.hyperlyricsenhanced.common.dexkit.DexResolutionSource
import com.juren233.hyperlyricsenhanced.common.dexkit.DexWatchdogEvent
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.AnnotationElementMatcher
import org.luckypray.dexkit.query.matchers.AnnotationMatcher
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal fun OfficialProviderHookHost.resolveDexMethod(
    application: Application,
    cacheKey: String,
    query: OfficialProviderDexMethodQuery,
    session: OfficialProviderHookHost.DexKitSession,
    forceFresh: Boolean = false,
): OfficialProviderMethodTarget {
    val preferences = application.getSharedPreferences(
        OfficialProviderHookHost.DEX_METHOD_CACHE_PREFERENCES,
        Context.MODE_PRIVATE,
    )
    if (forceFresh) preferences.edit().remove(cacheKey).apply()
    val cachedTarget = if (forceFresh) {
        null
    } else {
        OfficialProviderDexMethodCacheCodec.decode(
            preferences.getString(cacheKey, null),
        )?.takeIf { OfficialProviderDexMethodCacheCodec.matches(it, query) }
    }

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
            recordDexWatchdogResolution(
                query = query,
                source = DexResolutionSource.CACHE,
                cacheWritten = false,
                target = cachedTarget,
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
        ?.takeIf { !forceFresh }
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
                recordDexWatchdogResolution(
                    query = query,
                    source = DexResolutionSource.PREFERRED_TARGET,
                    cacheWritten = true,
                    target = preferredTarget,
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
                if (query.declaringClassFieldTypeNames.isNotEmpty()) {
                    declaredClass(
                        ClassMatcher().apply {
                            query.declaringClassFieldTypeNames.forEach(::addFieldForType)
                        },
                    )
                }
                query.requiredStrings.forEach(::addEqString)
                query.requiredInvokedMethodDescriptors.forEach(::addInvoke)
                query.requiredInvokedMethodNames.forEach { methodName ->
                    addInvoke(MethodMatcher().name(methodName))
                }
                query.requiredCallerMethodNames.forEach { methodName ->
                    addCaller(MethodMatcher().name(methodName))
                }
                query.requiredMethodAnnotation?.let { constraint ->
                    addAnnotation(
                        AnnotationMatcher().apply {
                            constraint.annotationTypeName?.let { typeName ->
                                type(typeName, StringMatchType.Equals, false)
                            }
                            addElement(
                                AnnotationElementMatcher().apply {
                                    constraint.elementName?.let { elementName ->
                                        name(elementName, StringMatchType.Equals, false)
                                    }
                                    stringValue(
                                        constraint.elementValue,
                                        StringMatchType.Equals,
                                        false,
                                    )
                                },
                            )
                        },
                    )
                }
                query.parameterTypeNames?.let(::paramTypes)
                query.returnTypeName?.let(::returnType)
                query.returnTypeNamePrefix?.let { prefix ->
                    returnType(prefix, StringMatchType.StartsWith, false)
                }
            }
        }
        val found = bridge.findMethod(finder)
        val semanticMatches = found.filter { method ->
            if (query.forbiddenInvokedMethodDescriptors.isEmpty()) {
                true
            } else {
                OfficialProviderDexMethodSemanticFilter.accepts(
                    invokedMethodDescriptors = method.invokes.map { it.descriptor },
                    forbiddenInvokedMethodDescriptors =
                        query.forbiddenInvokedMethodDescriptors,
                )
            }
        }
        if (semanticMatches.size != found.size) {
            module.log(
                Log.INFO,
                tag,
                "官方 Provider DexKit 调用负约束过滤: package=$packageName " +
                    "process=$processName key=${query.cacheKey} " +
                    "before=${found.size} after=${semanticMatches.size}",
            )
        }
        val matches = semanticMatches
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
    recordDexWatchdogResolution(
        query = query,
        source = DexResolutionSource.DEXKIT,
        cacheWritten = true,
        target = target,
    )
    return target
}

internal fun OfficialProviderHookHost.startDexBatchResolution(
    registration: OfficialProviderHookHost.DexBatchRegistration,
    forceFresh: Boolean,
    reason: String,
) {
    if (!registration.resolutionRunning.compareAndSet(false, true)) return
    Thread(
        {
            val result = this.DexKitSession(registration.application).use { session ->
                runCatching {
                    val resolvedByKey = LinkedHashMap<String, OfficialProviderMethodTarget>()
                    registration.queries.map { rawQuery ->
                        val query = materializeQuery(rawQuery, resolvedByKey)
                        val cacheKey = OfficialProviderDexMethodCacheCodec.cacheKey(
                            packageName = packageName,
                            processName = processName,
                            versionCode = registration.versionCode,
                            lastUpdateTime = registration.lastUpdateTime,
                            query = query,
                        )
                        registration.runtimeCacheKeys.add(cacheKey)
                        registerDexWatchdog(rawQuery.cacheKey, cacheKey)
                        resolveDexMethod(
                            application = registration.application,
                            cacheKey = cacheKey,
                            query = query,
                            session = session,
                            forceFresh = forceFresh,
                        ).also { target ->
                            check(resolvedByKey.put(rawQuery.cacheKey, target) == null) {
                                "Provider DexKit 查询 cacheKey 重复: ${rawQuery.cacheKey}"
                            }
                        }
                    }
                }
            }
            registration.resolutionRunning.set(false)
            result.onSuccess { targets ->
                if (forceFresh) {
                    module.log(
                        Log.INFO,
                        tag,
                        "官方 Provider DexKit 批量自修复成功: package=$packageName " +
                            "process=$processName reason=$reason " +
                            "keys=${registration.queries.joinToString { it.cacheKey }}",
                    )
                }
                runCatching { registration.callback.onMethodsResolved(targets) }
                    .onFailure { error ->
                        module.log(
                            Log.ERROR,
                            tag,
                            "官方 Provider DexKit 批量回调失败: package=$packageName " +
                                "process=$processName reason=$reason",
                            error,
                        )
                    }
            }.onFailure { error ->
                module.log(
                    Log.ERROR,
                    tag,
                    "官方 Provider DexKit 批量解析失败: package=$packageName " +
                        "process=$processName repair=$forceFresh reason=$reason " +
                        "keys=${registration.queries.joinToString { it.cacheKey }}",
                    error,
                )
                if (!forceFresh) {
                    requestDexBatchRepair(
                        registration = registration,
                        reason = "initial_failure",
                        detail = error.message,
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

internal fun OfficialProviderHookHost.startDexHookResolution(
    registration: OfficialProviderHookHost.DexHookRegistration,
    forceFresh: Boolean,
    reason: String,
) {
    if (!registration.resolutionRunning.compareAndSet(false, true)) return
    Thread(
        {
            val generation = registration.activation.current()
            val result = this.DexKitSession(registration.application).use { session ->
                runCatching {
                    val materializedQuery = materializeQuery(registration.query, emptyMap())
                    registerDexWatchdog(
                        registration.query.cacheKey,
                        registration.runtimeCacheKey,
                    )
                    val target = resolveDexMethod(
                        application = registration.application,
                        cacheKey = registration.runtimeCacheKey,
                        query = materializedQuery,
                        session = session,
                        forceFresh = forceFresh,
                    )
                    installAfterMethod(
                        target = target,
                        callback = OfficialProviderMethodCallback { receiver, arguments ->
                            if (registration.activation.isActive(generation)) {
                                registration.callback.onMethodCalled(receiver, arguments)
                            }
                        },
                        watchdogCacheKey = registration.query.cacheKey,
                    )
                    target
                }
            }
            registration.resolutionRunning.set(false)
            result.onSuccess { target ->
                if (forceFresh) {
                    module.log(
                        Log.INFO,
                        tag,
                        "官方 Provider DexKit 单方法自修复成功: package=$packageName " +
                            "process=$processName reason=$reason " +
                            "key=${registration.query.cacheKey} target=${describe(target)}",
                    )
                }
            }.onFailure { error ->
                module.log(
                    Log.ERROR,
                    tag,
                    "官方 Provider DexKit Hook 失败: package=$packageName " +
                        "process=$processName repair=$forceFresh reason=$reason " +
                        "key=${registration.query.cacheKey}",
                    error,
                )
                if (!forceFresh) {
                    requestDexHookRepair(
                        registration = registration,
                        reason = "initial_failure",
                        detail = error.message,
                    )
                }
            }
            if (!forceFresh && registration.repairRequested.get()) {
                startDexHookResolution(
                    registration = registration,
                    forceFresh = true,
                    reason = registration.repairReason,
                )
            }
        },
        "HLE-Provider-DexKit",
    ).apply {
        isDaemon = true
        start()
    }
}

internal fun OfficialProviderHookHost.requestDexHookRepair(
    registration: OfficialProviderHookHost.DexHookRegistration,
    reason: String,
    detail: String?,
) {
    if (!registration.repairGate.tryStart()) return
    registration.activation.replace()
    registration.repairReason = reason
    registration.repairRequested.set(true)
    invalidateDexMethodCache(registration.application, registration.runtimeCacheKey)
    val safeDetail = detail
        ?.replace('\n', ' ')
        ?.replace('\r', ' ')
        ?.take(OfficialProviderHookHost.MAX_WATCHDOG_DETAIL_LENGTH)
    module.log(
        Log.WARN,
        tag,
        "官方 Provider DexKit 触发一次有界单方法自修复: package=$packageName " +
            "process=$processName reason=$reason detail=$safeDetail " +
            "key=${registration.query.cacheKey}",
    )
    startDexHookResolution(registration, forceFresh = true, reason = reason)
}

internal fun OfficialProviderHookHost.requestDexBatchRepair(
    registration: OfficialProviderHookHost.DexBatchRegistration,
    reason: String,
    detail: String?,
) {
    if (!registration.repairGate.tryStart()) return
    registration.runtimeCacheKeys.forEach { cacheKey ->
        invalidateDexMethodCache(registration.application, cacheKey)
    }
    val safeDetail = detail
        ?.replace('\n', ' ')
        ?.replace('\r', ' ')
        ?.take(OfficialProviderHookHost.MAX_WATCHDOG_DETAIL_LENGTH)
    module.log(
        Log.WARN,
        tag,
        "官方 Provider DexKit 触发一次有界批量自修复: package=$packageName " +
            "process=$processName reason=$reason detail=$safeDetail " +
            "keys=${registration.queries.joinToString { it.cacheKey }}",
    )
    startDexBatchResolution(registration, forceFresh = true, reason = reason)
}

internal fun OfficialProviderHookHost.materializeQuery(
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
    val materializedFieldTypes = buildList {
        addAll(query.declaringClassFieldTypeNames)
        query.declaringClassFieldReferences.forEach { add(resolve(it)) }
    }
    return query.copy(
        declaringClassName = query.declaringClassReference?.let(::resolve)
            ?: query.declaringClassName,
        declaringClassReference = null,
        parameterTypeNames = materializedParameterTypes,
        parameterTypeReferences = emptyMap(),
        returnTypeName = query.returnTypeReference?.let(::resolve) ?: query.returnTypeName,
        returnTypeReference = null,
        declaringClassFieldTypeNames = materializedFieldTypes,
        declaringClassFieldReferences = emptyList(),
    )
}

internal fun OfficialProviderHookHost.invalidateDexMethodCache(application: Application, cacheKey: String) {
    application.getSharedPreferences(
        OfficialProviderHookHost.DEX_METHOD_CACHE_PREFERENCES,
        Context.MODE_PRIVATE,
    ).edit().remove(cacheKey).apply()
}

internal fun OfficialProviderHookHost.registerDexWatchdog(cacheKey: String, runtimeCacheKey: String) {
    dexWatchdog?.register(cacheKey, runtimeCacheKey)
}

internal fun OfficialProviderHookHost.recordDexWatchdogResolution(
    query: OfficialProviderDexMethodQuery,
    source: DexResolutionSource,
    cacheWritten: Boolean,
    target: OfficialProviderMethodTarget,
) {
    val watchdog = dexWatchdog ?: return
    watchdog.resolved(
        cacheKey = query.cacheKey,
        source = source,
        cacheWritten = cacheWritten,
        target = describe(target),
    )
    armDexWatchdog(query.cacheKey)
}

internal fun OfficialProviderHookHost.armDexWatchdog(cacheKey: String) {
    val scheduler = dexWatchdogTimeoutScheduler ?: return
    scheduler.schedule(
        { dexWatchdog?.timeout(cacheKey) },
        OfficialProviderHookHost.DEX_WATCHDOG_TIMEOUT_MS,
        TimeUnit.MILLISECONDS,
    )
}

internal fun OfficialProviderHookHost.logDexWatchdogEvent(event: DexWatchdogEvent) {
    val detail = event.detail
        ?.replace('\n', ' ')
        ?.replace('\r', ' ')
        ?.take(OfficialProviderHookHost.MAX_WATCHDOG_DETAIL_LENGTH)
    module.log(
        Log.INFO,
        tag,
        "[ProviderDexWatchdog] stage=${event.stage}, result=${event.result}, " +
            "package=$packageName, process=$processName, key=${event.cacheKey}, " +
            "runtimeKey=${event.runtimeCacheKey}, " +
            "source=${event.source?.name?.lowercase()}, " +
            "cacheWritten=${event.cacheWritten}, hookInstalled=${event.hookInstalled}, " +
            "callbackCount=${event.callbackCount}, validationCount=${event.validationCount}, " +
            "validObserved=${event.validObserved}, target=${event.target}, detail=$detail",
    )
}

internal fun OfficialProviderHookHost.installAfterMethod(
    target: OfficialProviderMethodTarget,
    callback: OfficialProviderMethodCallback,
    watchdogCacheKey: String? = null,
) {
    require(target.className.isNotBlank()) { "Provider Hook className 不能为空" }
    require(target.methodName.isNotBlank()) { "Provider Hook methodName 不能为空" }
    val method = resolveMethod(target)
    val descriptor = describe(target)
    module.hook(method).intercept(
        OfficialProviderHookHost.AfterMethodHooker(
            module = module,
            descriptor = descriptor,
            callback = callback,
            onFirstCallback = watchdogCacheKey?.let { key ->
                { dexWatchdog?.callback(key) }
            },
        ),
    )
    watchdogCacheKey?.let { key ->
        dexWatchdog?.hookInstalled(key, descriptor)
    }
    module.log(Log.INFO, tag, "官方 Provider 方法 Hook 已安装: target=$descriptor")
}

internal fun OfficialProviderHookHost.resolveMethod(target: OfficialProviderMethodTarget): java.lang.reflect.Method {
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

/** 根据原始二进制类名和参数描述符解析精确构造函数。 */
internal fun OfficialProviderHookHost.resolveConstructor(
    target: OfficialProviderConstructorTarget,
): java.lang.reflect.Constructor<*> {
    val targetClass = Class.forName(target.className, false, targetClassLoader)
    val constructor = if (target.firstParameterTypeName != null) {
        // 链式解析模式：完整参数列表随版本漂移，按首参（服务接口）唯一匹配。
        val firstParameterType = resolveParameterType(target.firstParameterTypeName)
        val candidates = targetClass.declaredConstructors.filter { candidate ->
            candidate.parameterTypes.isNotEmpty() &&
                candidate.parameterTypes.first() == firstParameterType
        }
        require(candidates.size == 1) {
            "Provider 构造函数首参匹配必须唯一: ${target.className} " +
                "first=${target.firstParameterTypeName} count=${candidates.size}"
        }
        candidates.single()
    } else {
        val parameterTypes = target.parameterTypeNames.map(::resolveParameterType).toTypedArray()
        targetClass.getDeclaredConstructor(*parameterTypes)
    }
    return constructor.apply { isAccessible = true }
}

internal fun OfficialProviderHookHost.selectDexMethodMatch(
    application: Application,
    query: OfficialProviderDexMethodQuery,
    matches: List<OfficialProviderMethodTarget>,
): OfficialProviderMethodTarget {
    if (matches.size == 1) return matches.single()
    val baseline = OfficialProviderDexMethodBaselineCodec.decode(
        application.getSharedPreferences(
            OfficialProviderHookHost.DEX_METHOD_BASELINE_PREFERENCES,
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

internal fun OfficialProviderHookHost.recordDexMethodBaseline(
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
            .mapNotNull { field -> field.type.name.takeIf { isStableRuntimeType(it) } }
            .groupingBy { it }
            .eachCount(),
        parameterCount = method.parameterCount,
        stableParameterTypeNames = method.parameterTypes.map { type ->
            type.name.takeIf { isStableRuntimeType(it) }
        },
        stableReturnTypeName = method.returnType.name.takeIf { isStableRuntimeType(it) },
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
                    type.name.takeIf { isStableRuntimeType(it) }
                },
                stableReturnTypeName = method.returnType.name.takeIf { isStableRuntimeType(it) },
                isStatic = Modifier.isStatic(method.modifiers),
                ordinal = 0,
            ),
        ),
    )
    application.getSharedPreferences(
        OfficialProviderHookHost.DEX_METHOD_BASELINE_PREFERENCES,
        Context.MODE_PRIVATE,
    ).edit()
        .putString(
            dexBaselineKey(query),
            OfficialProviderDexMethodBaselineCodec.encode(baseline),
        )
        .apply()
}

internal fun OfficialProviderHookHost.methodOrdinal(method: Method, baseline: OfficialProviderDexMethodBaseline): Int =
    method.declaringClass.declaredMethods
        .filter { candidate -> baseline.matchesMethod(candidate) }
        .indexOfFirst { candidate ->
            candidate.name == method.name &&
                candidate.parameterTypes.contentEquals(method.parameterTypes)
        }
        .coerceAtLeast(0)

internal fun OfficialProviderDexMethodBaseline.matchesClass(clazz: Class<*>): Boolean {
    if (kotlin.math.abs(clazz.declaredFields.size - fieldCount) > OfficialProviderHookHost.CLASS_COUNT_TOLERANCE) {
        return false
    }
    if (kotlin.math.abs(clazz.declaredMethods.size - methodCount) > OfficialProviderHookHost.CLASS_COUNT_TOLERANCE) {
        return false
    }
    if (kotlin.math.abs(clazz.interfaces.size - interfaceCount) > 1) return false
    val fieldTypes = clazz.declaredFields
        .mapNotNull { field -> field.type.name.takeIf { isStableRuntimeType(it) } }
        .groupingBy { it }
        .eachCount()
    return stableFieldTypeCounts.all { (type, count) -> (fieldTypes[type] ?: 0) >= count }
}

internal fun OfficialProviderDexMethodBaseline.matchesMethod(method: Method): Boolean =
    method.parameterCount == parameterCount &&
        Modifier.isStatic(method.modifiers) == isStatic &&
        stableParameterTypeNames.withIndex().all { (index, typeName) ->
            typeName == null || method.parameterTypes.getOrNull(index)?.name == typeName
        } &&
        (stableReturnTypeName == null || method.returnType.name == stableReturnTypeName)

internal fun isStableRuntimeType(typeName: String): Boolean =
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

internal fun OfficialProviderHookHost.dexBaselineKey(query: OfficialProviderDexMethodQuery): String =
    "$packageName:$processName:${query.cacheKey}"

internal fun OfficialProviderHookHost.selectDexKitThreadCount(application: Application): Int {
    val availableProcessors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    val availableMemory = runCatching {
        val manager = application.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        info.availMem
    }.getOrDefault(0L)
    return if (availableProcessors >= 4 && availableMemory >= OfficialProviderHookHost.TWO_GIB_BYTES) 2 else 1
}

internal fun OfficialProviderHookHost.describe(target: OfficialProviderMethodTarget): String = buildString {
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

/** 生成用于中文诊断日志的构造函数描述。 */
internal fun OfficialProviderHookHost.describe(target: OfficialProviderConstructorTarget): String = buildString {
    append(target.className)
    append("#<init>(")
    if (target.firstParameterTypeName != null) {
        append(target.firstParameterTypeName)
        append(",…)")
    } else {
        append(target.parameterTypeNames.joinToString())
        append(')')
    }
}

internal fun OfficialProviderHookHost.resolveParameterType(typeName: String): Class<*> = when (typeName) {
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

internal fun OfficialProviderHookHost.resolveReturnType(typeName: String): Class<*> = when (typeName) {
    "void" -> Void.TYPE
    else -> resolveParameterType(typeName)
}

