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

internal object OfficialProviderDexMethodSemanticFilter {
    fun accepts(
        invokedMethodDescriptors: Collection<String>,
        forbiddenInvokedMethodDescriptors: Collection<String>,
    ): Boolean {
        if (forbiddenInvokedMethodDescriptors.isEmpty()) return true
        val forbidden = forbiddenInvokedMethodDescriptors.toHashSet()
        return invokedMethodDescriptors.none(forbidden::contains)
    }
}

internal class OfficialProviderDexRepairGate {
    private val started = AtomicBoolean(false)

    fun tryStart(): Boolean = started.compareAndSet(false, true)
}

internal class OfficialProviderDexHookActivation {
    private val generation = AtomicLong(0L)

    fun current(): Long = generation.get()

    fun replace(): Long = generation.incrementAndGet()

    fun isActive(candidate: Long): Boolean = generation.get() == candidate
}

/**
 * Tracks whether a Provider has already received Metadata for each MediaSession.
 *
 * Some players create and populate their MediaSession before the Provider Pack finishes
 * installing its hooks. The first observed callback can therefore be setPlaybackState(), while
 * the current Metadata is already available through MediaSession.controller. Missing snapshots
 * remain retryable; a delivered snapshot or a real setMetadata() callback suppresses duplicates.
 */
internal class OfficialProviderMediaSessionMetadataGate {
    enum class SnapshotDecision {
        DELIVER,
        MISSING_FIRST,
        MISSING_REPEATED,
        ALREADY_DELIVERED,
        NO_SESSION,
    }

    private val deliveredSessions = WeakHashMap<Any, Unit>()
    private val missingSessions = WeakHashMap<Any, Unit>()

    @Synchronized
    fun recordExplicit(session: Any?) {
        session ?: return
        deliveredSessions[session] = Unit
        missingSessions.remove(session)
    }

    @Synchronized
    fun release(session: Any?) {
        session ?: return
        deliveredSessions.remove(session)
    }

    @Synchronized
    fun claimSnapshot(session: Any?, hasMetadata: Boolean): SnapshotDecision {
        session ?: return SnapshotDecision.NO_SESSION
        if (deliveredSessions.containsKey(session)) {
            return SnapshotDecision.ALREADY_DELIVERED
        }
        if (!hasMetadata) {
            return if (missingSessions.put(session, Unit) == null) {
                SnapshotDecision.MISSING_FIRST
            } else {
                SnapshotDecision.MISSING_REPEATED
            }
        }
        deliveredSessions[session] = Unit
        missingSessions.remove(session)
        return SnapshotDecision.DELIVER
    }
}

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
    private val dexRegistrationLock = Any()
    private val dexHookTasks = ConcurrentHashMap<String, DexHookRegistration>()
    private val dexHookByQueryKey = ConcurrentHashMap<String, DexHookRegistration>()
    private val dexBatchTasks = ConcurrentHashMap<String, DexBatchRegistration>()
    private val dexBatchByQueryKey = ConcurrentHashMap<String, DexBatchRegistration>()
    private val dexWatchdog = if (BuildConfig.DEBUG) {
        DexMethodWatchdog { event -> logDexWatchdogEvent(event) }
    } else {
        null
    }
    private val dexWatchdogTimeoutScheduler: ScheduledExecutorService? = if (BuildConfig.DEBUG) {
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "HLE-Provider-DexWatchdog").apply { isDaemon = true }
        }
    } else {
        null
    }

    private val handledApplications = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Application, Boolean>()),
    )
    @Volatile
    private var registeredApplicationCallback: OfficialProviderApplicationCallback? = null

    private fun dispatchApplicationCreated(
        application: Application,
        callback: OfficialProviderApplicationCallback,
        source: String,
    ) {
        if (application.packageName != packageName) return
        if (!handledApplications.add(application)) return
        runCatching { callback.onApplicationCreated(application) }
            .onSuccess {
                module.log(
                    Log.INFO,
                    tag,
                    "官方 Provider 生命周期回调成功: package=$packageName source=$source app=${application::class.java.name}",
                )
            }
            .onFailure { error ->
                handledApplications.remove(application)
                module.log(
                    Log.ERROR,
                    tag,
                    "官方 Provider 生命周期回调失败: package=$packageName source=$source error=${error.message}",
                    error,
                )
            }
    }

    private fun findCurrentApplication(): Application? = runCatching {
        val activityThreadClass = Class.forName(
            "android.app.ActivityThread",
            false,
            targetClassLoader,
        )
        val currentApplicationMethod = activityThreadClass.getDeclaredMethod("currentApplication")
        currentApplicationMethod.invoke(null) as? Application
    }.getOrNull()

    fun ensureApplicationDispatched(source: String = "lazy_fallback") {
        if (handledApplications.isNotEmpty()) return
        val callback = registeredApplicationCallback ?: return
        val app = findCurrentApplication() ?: return
        dispatchApplicationCreated(app, callback, source)
    }

    override fun hookApplication(callback: OfficialProviderApplicationCallback) {
        registeredApplicationCallback = callback
        findCurrentApplication()?.let { app ->
            dispatchApplicationCreated(app, callback, "current_application")
        }

        runCatching {
            val method = Instrumentation::class.java.getDeclaredMethod(
                "callApplicationOnCreate",
                Application::class.java,
            )
            module.hook(method).intercept(
                ApplicationCreatedHooker(this, callback),
            )
        }

        runCatching {
            val method = Application::class.java.getDeclaredMethod("onCreate")
            module.hook(method).intercept(
                ApplicationOnCreateHooker(this, callback),
            )
        }

        module.log(
            Log.INFO,
            tag,
            "官方 Provider 生命周期 Hook 已安装: package=$packageName",
        )
    }

    override fun getBooleanPreference(key: String, default: Boolean): Boolean =
        module.getRemotePreferences(UIConstants.PREF_NAME).getBoolean(key, default)

    override fun hookMediaSession(
        playbackStateCallback: OfficialProviderPlaybackStateCallback,
        metadataCallback: OfficialProviderMetadataCallback,
    ) {
        val metadataGate = OfficialProviderMediaSessionMetadataGate()
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
            PlaybackStateHooker(
                host = this,
                module = module,
                packageName = packageName,
                processName = processName,
                callback = playbackStateCallback,
                metadataCallback = metadataCallback,
                metadataGate = metadataGate,
            ),
        )

        val setMetadata = mediaSessionClass.getDeclaredMethod(
            "setMetadata",
            MediaMetadata::class.java,
        )
        module.hook(setMetadata).intercept(
            MetadataHooker(
                host = this,
                module = module,
                packageName = packageName,
                callback = metadataCallback,
                metadataGate = metadataGate,
            ),
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

    override fun hookMethodResult(
        target: OfficialProviderMethodTarget,
        callback: OfficialProviderMethodResultCallback,
    ) {
        require(target.className.isNotBlank()) { "Provider 结果 Hook className 不能为空" }
        require(target.methodName.isNotBlank()) { "Provider 结果 Hook methodName 不能为空" }
        val method = resolveMethod(target)
        val descriptor = describe(target)
        module.hook(method).intercept(
            MethodResultHooker(
                module = module,
                descriptor = descriptor,
                callback = callback,
            ),
        )
        module.log(Log.INFO, tag, "官方 Provider 方法结果 Hook 已安装: target=$descriptor")
    }

    override fun hookAfterConstructor(
        target: OfficialProviderConstructorTarget,
        callback: OfficialProviderConstructorCallback,
    ) {
        require(target.className.isNotBlank()) { "Provider 构造 Hook className 不能为空" }
        require(target.parameterTypeNames.all(String::isNotBlank)) {
            "Provider 构造 Hook 参数类型不能为空"
        }
        val constructor = resolveConstructor(target)
        val descriptor = describe(target)
        module.hook(constructor).intercept(
            AfterConstructorHooker(
                module = module,
                descriptor = descriptor,
                callback = callback,
            ),
        )
        module.log(Log.INFO, tag, "官方 Provider 构造 Hook 已安装: target=$descriptor")
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
        val registration = synchronized(dexRegistrationLock) {
            if (dexHookTasks.containsKey(cacheKey)) return
            require(!dexBatchByQueryKey.containsKey(query.cacheKey)) {
                "Provider DexKit 查询 cacheKey 已被批量解析注册: ${query.cacheKey}"
            }
            DexHookRegistration(
                application = application,
                query = query,
                callback = callback,
                runtimeCacheKey = cacheKey,
            ).also { created ->
                dexHookTasks[cacheKey] = created
                dexHookByQueryKey[query.cacheKey] = created
            }
        }
        startDexHookResolution(registration, forceFresh = false, reason = "initial")
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
        require(queries.map { it.cacheKey }.distinct().size == queries.size) {
            "Provider DexKit 批量查询 cacheKey 不能重复"
        }
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
        val registration = synchronized(dexRegistrationLock) {
            if (dexBatchTasks.containsKey(taskKey)) return
            val conflict = queries.firstOrNull { query ->
                dexBatchByQueryKey.containsKey(query.cacheKey) ||
                    dexHookByQueryKey.containsKey(query.cacheKey)
            }
            require(conflict == null) {
                "Provider DexKit 查询 cacheKey 已被其他批次注册: ${conflict?.cacheKey}"
            }
            DexBatchRegistration(
                application = application,
                queries = queries.toList(),
                callback = callback,
                versionCode = packageInfo.longVersionCode,
                lastUpdateTime = packageInfo.lastUpdateTime,
            ).also { created ->
                dexBatchTasks[taskKey] = created
                queries.forEach { query -> dexBatchByQueryKey[query.cacheKey] = created }
            }
        }
        startDexBatchResolution(registration, forceFresh = false, reason = "initial")
    }

    override fun reportDexMethodValidation(
        cacheKey: String,
        valid: Boolean,
        detail: String?,
    ) {
        if (cacheKey.isBlank()) return
        val safeDetail = detail?.take(MAX_WATCHDOG_DETAIL_LENGTH)
        dexWatchdog?.validation(cacheKey, valid, safeDetail)
        if (!valid) {
            val batch = dexBatchByQueryKey[cacheKey]
            if (batch != null) {
                requestDexBatchRepair(
                    registration = batch,
                    reason = "runtime_invalid:$cacheKey",
                    detail = safeDetail,
                )
            } else {
                dexHookByQueryKey[cacheKey]?.let { hook ->
                    requestDexHookRepair(
                        registration = hook,
                        reason = "runtime_invalid:$cacheKey",
                        detail = safeDetail,
                    )
                }
            }
        }
    }

    override fun isDiagnosticEnabled(): Boolean = BuildConfig.DEBUG

    override fun reportDiagnostic(tag: String, message: String) {
        if (!BuildConfig.DEBUG) return
        val safeTag = tag
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(MAX_PROVIDER_DIAGNOSTIC_TAG_LENGTH)
            .ifBlank { "Provider" }
        val safeMessage = message
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(MAX_PROVIDER_DIAGNOSTIC_MESSAGE_LENGTH)
        module.log(
            Log.INFO,
            "OfficialProvider/$safeTag",
            safeMessage,
        )
    }

    private fun resolveDexMethod(
        application: Application,
        cacheKey: String,
        query: OfficialProviderDexMethodQuery,
        session: DexKitSession,
        forceFresh: Boolean = false,
    ): OfficialProviderMethodTarget {
        val preferences = application.getSharedPreferences(
            DEX_METHOD_CACHE_PREFERENCES,
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
                    query.requiredStrings.forEach(::addEqString)
                    query.requiredInvokedMethodDescriptors.forEach(::addInvoke)
                    query.requiredInvokedMethodNames.forEach { methodName ->
                        addInvoke(MethodMatcher().name(methodName))
                    }
                    query.requiredCallerMethodNames.forEach { methodName ->
                        addCaller(MethodMatcher().name(methodName))
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

    private fun startDexBatchResolution(
        registration: DexBatchRegistration,
        forceFresh: Boolean,
        reason: String,
    ) {
        if (!registration.resolutionRunning.compareAndSet(false, true)) return
        Thread(
            {
                val result = DexKitSession(registration.application).use { session ->
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

    private fun startDexHookResolution(
        registration: DexHookRegistration,
        forceFresh: Boolean,
        reason: String,
    ) {
        if (!registration.resolutionRunning.compareAndSet(false, true)) return
        Thread(
            {
                val generation = registration.activation.current()
                val result = DexKitSession(registration.application).use { session ->
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

    private fun requestDexHookRepair(
        registration: DexHookRegistration,
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
            ?.take(MAX_WATCHDOG_DETAIL_LENGTH)
        module.log(
            Log.WARN,
            tag,
            "官方 Provider DexKit 触发一次有界单方法自修复: package=$packageName " +
                "process=$processName reason=$reason detail=$safeDetail " +
                "key=${registration.query.cacheKey}",
        )
        startDexHookResolution(registration, forceFresh = true, reason = reason)
    }

    private fun requestDexBatchRepair(
        registration: DexBatchRegistration,
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
            ?.take(MAX_WATCHDOG_DETAIL_LENGTH)
        module.log(
            Log.WARN,
            tag,
            "官方 Provider DexKit 触发一次有界批量自修复: package=$packageName " +
                "process=$processName reason=$reason detail=$safeDetail " +
                "keys=${registration.queries.joinToString { it.cacheKey }}",
        )
        startDexBatchResolution(registration, forceFresh = true, reason = reason)
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

    private fun registerDexWatchdog(cacheKey: String, runtimeCacheKey: String) {
        dexWatchdog?.register(cacheKey, runtimeCacheKey)
    }

    private fun recordDexWatchdogResolution(
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

    private fun armDexWatchdog(cacheKey: String) {
        val scheduler = dexWatchdogTimeoutScheduler ?: return
        scheduler.schedule(
            { dexWatchdog?.timeout(cacheKey) },
            DEX_WATCHDOG_TIMEOUT_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun logDexWatchdogEvent(event: DexWatchdogEvent) {
        val detail = event.detail
            ?.replace('\n', ' ')
            ?.replace('\r', ' ')
            ?.take(MAX_WATCHDOG_DETAIL_LENGTH)
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

    private fun installAfterMethod(
        target: OfficialProviderMethodTarget,
        callback: OfficialProviderMethodCallback,
        watchdogCacheKey: String? = null,
    ) {
        require(target.className.isNotBlank()) { "Provider Hook className 不能为空" }
        require(target.methodName.isNotBlank()) { "Provider Hook methodName 不能为空" }
        val method = resolveMethod(target)
        val descriptor = describe(target)
        module.hook(method).intercept(
            AfterMethodHooker(
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

    /** 根据原始二进制类名和参数描述符解析精确构造函数。 */
    private fun resolveConstructor(
        target: OfficialProviderConstructorTarget,
    ): java.lang.reflect.Constructor<*> {
        val targetClass = Class.forName(target.className, false, targetClassLoader)
        val parameterTypes = target.parameterTypeNames.map(::resolveParameterType).toTypedArray()
        return targetClass.getDeclaredConstructor(*parameterTypes).apply {
            isAccessible = true
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

    /** 生成用于中文诊断日志的构造函数描述。 */
    private fun describe(target: OfficialProviderConstructorTarget): String = buildString {
        append(target.className)
        append("#<init>(")
        append(target.parameterTypeNames.joinToString())
        append(')')
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
        private val host: OfficialProviderHookHost,
        private val callback: OfficialProviderApplicationCallback,
    ) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            val application = chain.args.firstOrNull() as? Application
            if (application != null) {
                host.dispatchApplicationCreated(application, callback, "instrumentation")
            }
            return result
        }
    }

    private class ApplicationOnCreateHooker(
        private val host: OfficialProviderHookHost,
        private val callback: OfficialProviderApplicationCallback,
    ) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            val application = chain.thisObject as? Application
            if (application != null) {
                host.dispatchApplicationCreated(application, callback, "application_on_create")
            }
            return result
        }
    }

    private class PlaybackStateHooker(
        private val host: OfficialProviderHookHost,
        private val module: XposedModule,
        private val packageName: String,
        private val processName: String,
        private val callback: OfficialProviderPlaybackStateCallback,
        private val metadataCallback: OfficialProviderMetadataCallback,
        private val metadataGate: OfficialProviderMediaSessionMetadataGate,
    ) : XposedInterface.Hooker {
        private val firstCallbackRecorded = AtomicBoolean(false)
        private val callbackSequence = AtomicLong(0L)

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            host.ensureApplicationDispatched("media_session_playback_state")
            val state = chain.args.firstOrNull() as? PlaybackState
            val sequence = callbackSequence.incrementAndGet()
            deliverCurrentMetadataSnapshot(chain.thisObject as? MediaSession)
            runCatching {
                callback.onPlaybackStateChanged(state)
            }.onSuccess {
                val firstHit = firstCallbackRecorded.compareAndSet(false, true)
                if (BuildConfig.DEBUG) {
                    val now = SystemClock.elapsedRealtime()
                    module.log(
                        Log.INFO,
                        "OfficialProviderHookHost",
                        "[LyricPositionDiag] stage=media_session_state_hook, " +
                            "result=callback_completed, firstHit=$firstHit, sequence=$sequence, " +
                            "player=$packageName, process=$processName, state=${state?.state}, " +
                            "position=${state?.position}, updatedAt=${state?.lastPositionUpdateTime}, " +
                            "now=$now, anchorAgeMs=${state?.lastPositionUpdateTime?.let { now - it }}, " +
                            "speed=${state?.playbackSpeed}, buffered=${state?.bufferedPosition}",
                    )
                } else if (firstHit) {
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

        private fun deliverCurrentMetadataSnapshot(session: MediaSession?) {
            val snapshot = runCatching { session?.controller?.metadata }
                .onFailure { error ->
                    module.log(
                        Log.ERROR,
                        "OfficialProviderHookHost",
                        "官方 Provider MediaSession Metadata 快照读取失败: " +
                            "package=$packageName error=${error.message}",
                    )
                }
                .getOrNull()
            when (metadataGate.claimSnapshot(session, snapshot != null)) {
                OfficialProviderMediaSessionMetadataGate.SnapshotDecision.DELIVER -> {
                    runCatching {
                        metadataCallback.onMetadataChanged(snapshot)
                    }.onSuccess {
                        if (BuildConfig.DEBUG) {
                            module.log(
                                Log.INFO,
                                "OfficialProviderHookHost",
                                "[MediaSessionSnapshotDiag] " +
                                    "stage=media_session_metadata_snapshot, result=delivered, " +
                                    "player=$packageName, process=$processName, " +
                                    "mediaId=${snapshot?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)}, " +
                                    "title=${snapshot?.getString(MediaMetadata.METADATA_KEY_TITLE)}",
                            )
                        }
                    }.onFailure { error ->
                        metadataGate.release(session)
                        module.log(
                            Log.ERROR,
                            "OfficialProviderHookHost",
                            "官方 Provider MediaSession Metadata 快照回调失败: " +
                                "package=$packageName error=${error.message}",
                        )
                    }
                }

                OfficialProviderMediaSessionMetadataGate.SnapshotDecision.MISSING_FIRST -> {
                    if (BuildConfig.DEBUG) {
                        module.log(
                            Log.INFO,
                            "OfficialProviderHookHost",
                            "[MediaSessionSnapshotDiag] " +
                                "stage=media_session_metadata_snapshot, result=missing_retryable, " +
                                "player=$packageName, process=$processName",
                        )
                    }
                }

                OfficialProviderMediaSessionMetadataGate.SnapshotDecision.MISSING_REPEATED,
                OfficialProviderMediaSessionMetadataGate.SnapshotDecision.ALREADY_DELIVERED,
                OfficialProviderMediaSessionMetadataGate.SnapshotDecision.NO_SESSION -> Unit
            }
        }
    }

    private class MetadataHooker(
        private val host: OfficialProviderHookHost,
        private val module: XposedModule,
        private val packageName: String,
        private val callback: OfficialProviderMetadataCallback,
        private val metadataGate: OfficialProviderMediaSessionMetadataGate,
    ) : XposedInterface.Hooker {
        private val firstCallbackRecorded = AtomicBoolean(false)

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            host.ensureApplicationDispatched("media_session_metadata")
            val session = chain.thisObject
            metadataGate.recordExplicit(session)
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
                metadataGate.release(session)
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
        private val onFirstCallback: (() -> Unit)? = null,
    ) : XposedInterface.Hooker {
        private val firstCallbackRecorded = AtomicBoolean(false)

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            if (firstCallbackRecorded.compareAndSet(false, true)) {
                onFirstCallback?.invoke()
                module.log(
                    Log.INFO,
                    "OfficialProviderHookHost",
                    "官方 Provider 方法 Hook 首次命中: target=$descriptor",
                )
            }
            runCatching {
                callback.onMethodCalled(chain.thisObject, chain.args.toTypedArray())
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

    /** 允许 Provider 在失败时回退原值的前提下观察或包装方法返回值。 */
    private class MethodResultHooker(
        private val module: XposedModule,
        private val descriptor: String,
        private val callback: OfficialProviderMethodResultCallback,
    ) : XposedInterface.Hooker {
        private val firstCallbackRecorded = AtomicBoolean(false)

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val original = chain.proceed()
            if (firstCallbackRecorded.compareAndSet(false, true)) {
                module.log(
                    Log.INFO,
                    "OfficialProviderHookHost",
                    "官方 Provider 方法结果 Hook 首次命中: target=$descriptor",
                )
            }
            return runCatching {
                callback.onMethodReturned(
                    chain.thisObject,
                    chain.args.toTypedArray(),
                    original,
                )
            }.onFailure { error ->
                module.log(
                    Log.ERROR,
                    "OfficialProviderHookHost",
                    "官方 Provider 方法结果回调失败: target=$descriptor error=${error.message}",
                )
            }.getOrDefault(original)
        }
    }

    /** 在原构造函数完成后转发实例和原始参数，并记录首次真实命中。 */
    private class AfterConstructorHooker(
        private val module: XposedModule,
        private val descriptor: String,
        private val callback: OfficialProviderConstructorCallback,
    ) : XposedInterface.Hooker {
        private val firstCallbackRecorded = AtomicBoolean(false)

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            if (firstCallbackRecorded.compareAndSet(false, true)) {
                module.log(
                    Log.INFO,
                    "OfficialProviderHookHost",
                    "官方 Provider 构造 Hook 首次命中: target=$descriptor",
                )
            }
            runCatching {
                callback.onConstructed(chain.thisObject, chain.args.toTypedArray())
            }.onFailure { error ->
                module.log(
                    Log.ERROR,
                    "OfficialProviderHookHost",
                    "官方 Provider 构造回调失败: target=$descriptor error=${error.message}",
                )
            }
            return result
        }
    }

    private class DexBatchRegistration(
        val application: Application,
        val queries: List<OfficialProviderDexMethodQuery>,
        val callback: OfficialProviderDexMethodsCallback,
        val versionCode: Long,
        val lastUpdateTime: Long,
    ) {
        val repairGate = OfficialProviderDexRepairGate()
        val resolutionRunning = AtomicBoolean(false)
        val runtimeCacheKeys = ConcurrentHashMap.newKeySet<String>()
    }

    private class DexHookRegistration(
        val application: Application,
        val query: OfficialProviderDexMethodQuery,
        val callback: OfficialProviderMethodCallback,
        val runtimeCacheKey: String,
    ) {
        val repairGate = OfficialProviderDexRepairGate()
        val resolutionRunning = AtomicBoolean(false)
        val activation = OfficialProviderDexHookActivation()
        val repairRequested = AtomicBoolean(false)

        @Volatile
        var repairReason: String = "runtime_invalid"
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
        const val DEX_WATCHDOG_TIMEOUT_MS = 30_000L
        const val MAX_WATCHDOG_DETAIL_LENGTH = 256
        const val MAX_PROVIDER_DIAGNOSTIC_TAG_LENGTH = 64
        const val MAX_PROVIDER_DIAGNOSTIC_MESSAGE_LENGTH = 1_024
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
