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

internal class OfficialProviderHookHost(
    internal val module: XposedModule,
    internal val targetClassLoader: ClassLoader,
    override val packageName: String,
    override val processName: String,
) : OfficialProviderHost {
    internal val tag = "OfficialProviderHookHost"
    private val dexRegistrationLock = Any()
    private val dexHookTasks = ConcurrentHashMap<String, DexHookRegistration>()
    private val dexHookByQueryKey = ConcurrentHashMap<String, DexHookRegistration>()
    private val dexBatchTasks = ConcurrentHashMap<String, DexBatchRegistration>()
    private val dexBatchByQueryKey = ConcurrentHashMap<String, DexBatchRegistration>()
    internal val dexWatchdog = if (BuildConfig.DEBUG) {
        DexMethodWatchdog { event -> logDexWatchdogEvent(event) }
    } else {
        null
    }
    internal val dexWatchdogTimeoutScheduler: ScheduledExecutorService? = if (BuildConfig.DEBUG) {
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

    internal fun dispatchApplicationCreated(
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
        require(
            target.firstParameterTypeName == null || target.firstParameterTypeName.isNotBlank(),
        ) {
            "Provider 构造 Hook 首参类型不能为空"
        }
        require(
            target.firstParameterTypeName == null || target.parameterTypeNames.isEmpty(),
        ) {
            "Provider 构造 Hook 首参约束与完整参数列表互斥"
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


    internal class ApplicationCreatedHooker(
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

    internal class ApplicationOnCreateHooker(
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

    internal class PlaybackStateHooker(
        private val host: OfficialProviderHookHost,
        internal val module: XposedModule,
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

    internal class MetadataHooker(
        private val host: OfficialProviderHookHost,
        internal val module: XposedModule,
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

    internal class AfterMethodHooker(
        internal val module: XposedModule,
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
    internal class MethodResultHooker(
        internal val module: XposedModule,
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
    internal class AfterConstructorHooker(
        internal val module: XposedModule,
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

    internal class DexBatchRegistration(
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

    internal class DexHookRegistration(
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

    internal companion object {
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

    internal inner class DexKitSession(
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
