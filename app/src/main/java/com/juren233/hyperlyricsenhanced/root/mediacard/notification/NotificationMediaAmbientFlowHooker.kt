package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.root.HookEntry
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.SystemUiEnhancementGate
import com.juren233.hyperlyricsenhanced.root.mediacard.MediaAmbientFlowPalette
import com.juren233.hyperlyricsenhanced.root.mediacard.MediaAmbientFlowPaletteExtractor
import com.juren233.hyperlyricsenhanced.root.mediacard.MediaArtworkSampler
import com.juren233.hyperlyricsenhanced.root.mediacard.background.MediaFlowArtwork
import com.juren233.hyperlyricsenhanced.root.mediacard.background.MediaFlowBackgroundView
import com.juren233.hyperlyricsenhanced.root.mediacard.background.MediaFlowOverlayLayout
import com.juren233.hyperlyricsenhanced.root.mediacard.background.MediaFlowTone
import com.juren233.hyperlyricsenhanced.root.mediacard.notification.background.NotificationMediaBackgroundController
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.root.utils.MediaCardDiagnosticLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object NotificationMediaAmbientFlowHooker {
    internal const val TAG = "NotificationMediaAmbientFlowHooker"
    private const val VIEW_TAG = "hyperlyricsenhanced.notification_media_ambient_flow"
    internal val controllerClassNames = listOf(
        NotificationMediaHookMethodProfile.VIEW_CONTROLLER_CLASS,
        "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewController"
    )

    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private val states = Collections.synchronizedMap(WeakHashMap<Any, AmbientFlowControllerState>())
    private val activeControllers = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    )
    internal val themeStates = Collections.synchronizedMap(WeakHashMap<Any, ControllerThemeState>())
    private val nativeApis = Collections.synchronizedMap(WeakHashMap<ClassLoader, NativeMusicBgApi>())
    private val themeApis = Collections.synchronizedMap(WeakHashMap<ClassLoader, CardThemeApi>())
    private val nativeUnavailableClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    @Volatile
    private var colorExecutor = newColorExecutor()

    @Volatile
    private var module: XposedModule? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var flowKeepAliveScheduled = false
    private var flowHoldLogged = false
    private var flowWakeLock: PowerManager.WakeLock? = null

    /**
     * 稳态息屏（doze）下无人持续持有 draw wake lock 时，SurfaceFlinger 不合成流光视图的
     * 帧，流光冻结、仅在歌词路径的 1s wake lock 窗口内跳格（AMBIENT-FLOW-AOD-001 真机
     * 证据）。该 tick 在存在已挂载控制器时常驻运行，仅在"非交互 + 流光应流动"时续期
     * draw wake lock 并对原生视图补发帧循环恢复。
     */
    private val flowKeepAlive = object : Runnable {
        override fun run() {
            val keepTicking = runCatching { flowKeepAliveTick() }
                .onFailure { HookLogger.e(TAG, "流光息屏保活失败", it) }
                .getOrDefault(false)
            if (keepTicking) {
                mainHandler.postDelayed(this, FLOW_KEEP_ALIVE_INTERVAL_MS)
            } else {
                flowKeepAliveScheduled = false
                releaseFlowWakeLock()
            }
        }
    }

    private val prefs
        get() = (module as? HookEntry)?.prefs

    fun initialize(xposedModule: XposedModule) {
        module = xposedModule
        NotificationMediaBackgroundController.initialize(xposedModule)
        if (colorExecutor.isShutdown) colorExecutor = newColorExecutor()
    }

    fun hook(xposedModule: XposedModule, classLoader: ClassLoader) {
        initialize(xposedModule)
        if (!hookedClassLoaders.add(classLoader)) return

        val controllerClass = controllerClassNames.firstNotNullOfOrNull { className ->
            runCatching { classLoader.loadClass(className) }.getOrNull()
        }
        if (controllerClass == null) {
            hookedClassLoaders.remove(classLoader)
            HookLogger.w(TAG, "通知中心媒体控制器不可用")
            return
        }
        var installed = 0
        val installedNativeUpdates = mutableSetOf<String>()
        TARGET_METHOD_NAMES
            .flatMap { methodName -> findNearestMethods(controllerClass, methodName) }
            .filter(::isTargetMethod)
            .forEach { method ->
                runCatching {
                    method.isAccessible = true
                    xposedModule.deoptimize(method)
                    xposedModule.hook(method)
                        .intercept(hookerFor(method) ?: return@runCatching)
                    installed++
                    if (method.name in NATIVE_BACKGROUND_UPDATE_METHODS) {
                        installedNativeUpdates.add(method.name)
                    }
                }.onFailure { error ->
                    HookLogger.e(TAG, "安装通知中心媒体 Hook 失败: method=${method.name}", error)
                }
            }
        NotificationMediaBackgroundController.setNativeHooksAvailable(
            classLoader,
            installedNativeUpdates.isNotEmpty()
        )
        if (installedNativeUpdates.isEmpty()) {
            HookLogger.w(TAG, "通知中心原生背景接口不可用，跳过自定义背景 Hook")
        }

        // The native card background never re-reads the controller context; the material
        // effects must be themed at their own apply entry or 卡片背景颜色 cannot reach it.
        runCatching {
            NotificationMediaMaterialThemeHooker.install(xposedModule, classLoader) {
                currentCardTheme()
            }
        }.onFailure { error ->
            HookLogger.w(TAG, "媒体材质主题接口不可用: reason=${error.message}")
        }

        runCatching {
            val seekBarClass = classLoader.loadClass(HYPER_PROGRESS_SEEK_BAR_CLASS)
            findNearestMethods(seekBarClass, "onDraw").forEach { method ->
                method.isAccessible = true
                xposedModule.deoptimize(method)
                xposedModule.hook(method).intercept(ProgressDrawHook())
                installed++
            }
        }.onFailure { error ->
                HookLogger.w(TAG, "通知中心进度条接口不可用: reason=${error.message}")
        }

        if (installed == 0) {
            hookedClassLoaders.remove(classLoader)
            HookLogger.w(TAG, "未找到兼容的通知中心媒体控制方法")
        } else {
            HookLogger.i(TAG, "通知中心媒体流光 Hook 已初始化: methods=$installed")
        }
    }

    fun isTargetMethod(method: Method): Boolean {
        if (method.declaringClass.name == HYPER_PROGRESS_SEEK_BAR_CLASS) {
            return method.name == "onDraw" && method.parameterCount == 1
        }
        if (!method.declaringClass.name.startsWith(CONTROLLER_PACKAGE)) return false
        return when (method.name) {
            "attach", "detach" -> true
            "bindMediaData" -> method.parameterTypes.isNotEmpty()
            NotificationMediaHookMethodProfile.UPDATE_FOREGROUND_COLORS ->
                method.parameterCount == 0 && method.returnType == Void.TYPE
            else -> false
        }
    }

    fun hookerFor(executable: Executable): Hooker? {
        val method = executable as? Method ?: return null
        if (!isTargetMethod(method)) return null
        return when (method.name) {
            "attach" -> ControllerHook(Action.ATTACH)
            "detach" -> ControllerHook(Action.DETACH)
            "bindMediaData" -> ControllerHook(Action.BIND)
            NotificationMediaHookMethodProfile.UPDATE_FOREGROUND_COLORS ->
                NativeBackgroundUpdateHook(method.name)
            "onDraw" -> ProgressDrawHook()
            else -> null
        }
    }

    fun releaseAll() {
        val snapshot = synchronized(states) { states.toMap() }
        val controllers = synchronized(activeControllers) { activeControllers.toList() }
        states.clear()
        activeControllers.clear()
        colorExecutor.shutdownNow()
        NotificationMediaBackgroundController.releaseAll()
        NotificationMediaMaterialThemeHooker.releaseAll()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cancelFlowKeepAliveInternal()
        } else {
            mainHandler.post(::cancelFlowKeepAliveInternal)
        }
        val cleanup = Runnable {
            controllers.forEach(::restoreCardTheme)
            snapshot.values.forEach(::disposeState)
            themeStates.clear()
            nativeApis.clear()
            themeApis.clear()
            nativeUnavailableClassLoaders.clear()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanup.run()
        } else {
            Handler(Looper.getMainLooper()).post(cleanup)
        }
    }

    class ControllerHook(private val action: Action) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val controller = chain.thisObject ?: return chain.proceed()
            MediaCardDiagnosticLogger.log(
                stage = "notification_ambient",
                event = "controller_callback_begin",
                details = "action=${action.name.lowercase()},controller=${MediaCardDiagnosticLogger.identity(controller)},arg0=${MediaCardDiagnosticLogger.identity(chain.args.firstOrNull())},enabled=${SystemUiEnhancementGate.isEnabled()}",
            )
            if (!SystemUiEnhancementGate.isEnabled()) {
                if (action == Action.DETACH) {
                    activeControllers.remove(controller)
                    removeView(controller, forgetState = true)
                    NotificationMediaBackgroundController.onDetach(controller)
                    restoreCardTheme(controller)
                }
                val result = chain.proceed()
                if (action == Action.ATTACH || action == Action.BIND) {
                    activeControllers.add(controller)
                }
                MediaCardDiagnosticLogger.log(
                    stage = "notification_ambient",
                    event = "controller_callback_complete",
                    details = "action=${action.name.lowercase()},controller=${MediaCardDiagnosticLogger.identity(controller)},enabled=false",
                )
                return result
            }
            if (action == Action.DETACH) {
                activeControllers.remove(controller)
                removeView(controller, forgetState = true)
                NotificationMediaBackgroundController.onDetach(controller)
                restoreCardTheme(controller)
            } else {
                runCatching {
                    if (NotificationMediaBackgroundController.isActive(controller)) {
                        restoreCardTheme(controller)
                    } else {
                        prepareCardTheme(controller)
                    }
                }.onFailure {
                    HookLogger.e(TAG, "准备通知中心媒体卡片主题失败", it)
                }
            }
            val result = chain.proceed()
            runCatching {
                when (action) {
                    Action.ATTACH -> {
                        activeControllers.add(controller)
                        syncView(controller)
                        scheduleFlowKeepAlive()
                    }
                    Action.BIND -> {
                        activeControllers.add(controller)
                        NotificationMediaBackgroundController.onBind(
                            controller,
                            chain.args.firstOrNull()
                        )
                        bind(controller, chain.args.firstOrNull())
                        scheduleFlowKeepAlive()
                    }
                    Action.DETACH -> {
                        cancelFlowKeepAliveIfIdle()
                    }
                }
            }.onFailure { error ->
                MediaCardDiagnosticLogger.log(
                    stage = "notification_ambient",
                    event = "controller_callback_failed",
                    reason = "exception",
                    details = "action=${action.name.lowercase()},controller=${MediaCardDiagnosticLogger.identity(controller)},error=${MediaCardDiagnosticLogger.sanitize(error.message)}",
                )
                HookLogger.e(
                    TAG,
                    "处理通知中心媒体流光失败: action=${action.name.lowercase()}",
                    error
                )
            }
            val state = states[controller]
            MediaCardDiagnosticLogger.log(
                stage = "notification_ambient",
                event = "controller_callback_complete",
                details = "action=${action.name.lowercase()},controller=${MediaCardDiagnosticLogger.identity(controller)},view=${MediaCardDiagnosticLogger.view(state?.view)},isPlaying=${state?.isPlaying},hasColors=${state?.hasColors},flowActive=${state?.flowActive},activeControllers=${synchronized(activeControllers) { activeControllers.size }}",
            )
            return result
        }
    }

    enum class Action { ATTACH, DETACH, BIND }

    class NativeBackgroundUpdateHook(private val methodName: String) : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (!SystemUiEnhancementGate.isEnabled()) return chain.proceed()
            val controller = chain.thisObject ?: return chain.proceed()
            if (BuildConfig.DEBUG) {
                HookLogger.d(
                    TAG,
                    "流光原生更新: method=$methodName active=${NotificationMediaBackgroundController.isActive(controller)}"
                )
            }
            return if (NotificationMediaBackgroundController.isActive(controller)) {
                null
            } else {
                chain.proceed()
            }
        }
    }

    class ProgressDrawHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (SystemUiEnhancementGate.isEnabled()) {
                chain.thisObject?.let(NotificationMediaBackgroundController::applySeekBarColor)
            }
            return chain.proceed()
        }
    }

    fun refreshCardTheme() {
        val refresh = Runnable {
            val snapshot = synchronized(activeControllers) { activeControllers.toList() }
            snapshot.forEach { controller ->
                runCatching {
                    refreshCardTheme(controller)
                    syncView(controller)
                }
                    .onFailure { HookLogger.e(TAG, "刷新通知中心媒体卡片主题失败", it) }
            }
            NotificationMediaMaterialThemeHooker.refresh()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) refresh.run()
        else Handler(Looper.getMainLooper()).post(refresh)
    }

    fun refreshBackgroundStyle() {
        val controllers = synchronized(activeControllers) { activeControllers.toList() }
        controllers.forEach { controller ->
            if (NotificationMediaBackgroundController.isActive(controller)) {
                restoreCardTheme(controller)
            }
        }
        NotificationMediaBackgroundController.refresh(controllers) { controller ->
            runCatching { refreshCardTheme(controller) }
                .onFailure { HookLogger.e(TAG, "恢复通知中心原生媒体背景失败", it) }
        }
        controllers.forEach(::syncView)
    }

    fun refreshAmbientFlow() {
        val refresh = Runnable {
            synchronized(activeControllers) { activeControllers.toList() }.forEach(::syncView)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) refresh.run()
        else Handler(Looper.getMainLooper()).post(refresh)
    }

    private fun scheduleFlowKeepAlive() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            scheduleFlowKeepAliveInternal()
        } else {
            mainHandler.post(::scheduleFlowKeepAliveInternal)
        }
    }

    private fun scheduleFlowKeepAliveInternal() {
        if (flowKeepAliveScheduled) return
        flowKeepAliveScheduled = true
        mainHandler.post(flowKeepAlive)
    }

    private fun cancelFlowKeepAliveIfIdle() {
        val hasControllers = synchronized(activeControllers) { activeControllers.isNotEmpty() }
        if (hasControllers) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cancelFlowKeepAliveInternal()
        } else {
            mainHandler.post(::cancelFlowKeepAliveInternal)
        }
    }

    private fun cancelFlowKeepAliveInternal() {
        mainHandler.removeCallbacks(flowKeepAlive)
        flowKeepAliveScheduled = false
        releaseFlowWakeLock()
    }

    /**
     * @return true 表示 tick 需要继续运行（仍存在已挂载控制器的流光状态）。
     */
    private fun flowKeepAliveTick(): Boolean {
        if (!SystemUiEnhancementGate.isEnabled()) return false
        if (currentMode() == RootConstants.NOTIFICATION_MEDIA_AMBIENT_FLOW_MODE_DISABLED) return false
        val snapshot = synchronized(states) { states.entries.toList() }
        if (snapshot.isEmpty()) return false
        val context = snapshot.firstNotNullOfOrNull { (_, state) -> state.view?.context }
        val powerManager = context?.getSystemService(PowerManager::class.java)
        // 亮屏交互时帧合成本就可用，不需要 wake lock，仅保持 tick 待命。
        if (powerManager == null || powerManager.isInteractive) {
            logFlowHold(false)
            return true
        }
        var holding = false
        snapshot.forEach { (controller, state) ->
            val view = state.view ?: return@forEach
            if (view.isShown) {
                // SystemUI 暂停时不重发 bindMediaData（150218 真机日志证实），媒体数据里的
                // isPlaying 会滞后；对可见卡片以 Lyricon 桥接播放状态（与 AOD 歌词 Hooker
                // 同源）为准周期校正并驱动移除/恢复。
                val bridgePlaying = LyriconDataBridge.currentPlaybackState
                if (bridgePlaying != null && bridgePlaying != state.isPlaying) {
                    state.isPlaying = bridgePlaying
                    if (shouldRemoveFlowForState(state)) {
                        removeFlowGracefully(controller)
                        return@forEach
                    }
                    syncPlayback(state)
                }
            }
            if (!flowShouldPlay(state) || !view.isShown) return@forEach
            holding = true
            if (view !is MediaFlowBackgroundView) {
                // 息屏稳态下没有 bindMediaData 重同步机会，周期性补发帧循环恢复兜底。
                state.nativeApi?.takeIf { it.accepts(view) }?.ensureFrameLoopEnabled(view)
            }
        }
        if (holding) {
            resolveFlowWakeLock(context)?.acquire(FLOW_WAKE_LOCK_TIMEOUT_MS)
        }
        logFlowHold(holding)
        return true
    }

    private fun resolveFlowWakeLock(context: Context): PowerManager.WakeLock? {
        flowWakeLock?.let { return it }
        return runCatching {
            context.getSystemService(PowerManager::class.java).newWakeLock(
                DRAW_WAKE_LOCK_LEVEL,
                "${context.packageName}:HyperLyricsFlowDraw"
            ).apply { setReferenceCounted(false) }
        }.onFailure {
            HookLogger.w(TAG, "创建流光息屏唤醒锁失败: reason=${it.message}")
        }.getOrNull()?.also { flowWakeLock = it }
    }

    private fun releaseFlowWakeLock() {
        flowWakeLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
        }
        flowHoldLogged = false
    }

    private fun logFlowHold(holding: Boolean) {
        if (holding == flowHoldLogged) return
        flowHoldLogged = holding
        HookLogger.i(TAG, "流光息屏帧提交保持: holding=$holding")
    }

    private fun prepareCardTheme(controller: Any) {
        val api = resolveThemeApi(controller.javaClass.classLoader) ?: return
        api.apply(controller, currentCardTheme(), refreshViews = false)
    }

    private fun refreshCardTheme(controller: Any) {
        if (NotificationMediaBackgroundController.isActive(controller)) return
        val api = resolveThemeApi(controller.javaClass.classLoader) ?: return
        api.apply(controller, currentCardTheme(), refreshViews = true)
    }

    private fun restoreCardTheme(controller: Any) {
        val api = resolveThemeApi(controller.javaClass.classLoader) ?: return
        api.restore(controller)
    }

    private fun resolveThemeApi(classLoader: ClassLoader?): CardThemeApi? {
        classLoader ?: return null
        themeApis[classLoader]?.let { return it }
        return runCatching { CardThemeApi.create(classLoader) }
            .onSuccess { themeApis[classLoader] = it }
            .onFailure { HookLogger.w(TAG, "通知中心媒体主题接口不可用: reason=${it.message}") }
            .getOrNull()
    }

    private fun syncView(controller: Any) {
        if (NotificationMediaBackgroundController.isActive(controller)) {
            removeView(controller)
            return
        }
        if (currentMode() != RootConstants.NOTIFICATION_MEDIA_AMBIENT_FLOW_MODE_DISABLED) {
            ensureView(controller)
            states[controller]?.lastMediaData?.let { bind(controller, it) }
        } else {
            removeView(controller)
        }
    }

    private fun bind(controller: Any, mediaData: Any?) {
        val state = states.getOrPut(controller) { AmbientFlowControllerState() }
        if (mediaData != null) state.lastMediaData = mediaData
        if (NotificationMediaBackgroundController.isActive(controller)) {
            removeView(controller)
            return
        }
        val mode = currentMode()
        if (mode == RootConstants.NOTIFICATION_MEDIA_AMBIENT_FLOW_MODE_DISABLED) {
            removeView(controller)
            return
        }
        mediaData ?: return
        val isPlaying = readField(mediaData, "isPlaying") == true
        state.isPlaying = isPlaying
        if (shouldRemoveFlowForState(state)) {
            removeFlowGracefully(controller)
            return
        }
        val view = ensureView(controller) ?: return
        configureCustomView(state)
        syncPlayback(state)

        val packageName = readField(mediaData, "packageName") as? String ?: return
        val artwork = readField(mediaData, "artwork") as? Icon
        val song = readField(mediaData, "song")?.toString().orEmpty()
        val artist = readField(mediaData, "artist")?.toString().orEmpty()
        val artworkUpdated = readField(controller, "isArtWorkUpdate") == true
        val paletteMode = if (isCustomMode(mode)) "custom" else mode.toString()
        val colorToken = "$paletteMode:$packageName:$song:$artist"
        if (BuildConfig.DEBUG) {
            HookLogger.d(
                TAG,
                "流光bind: song=$song artworkNull=${artwork == null} artworkUpdated=$artworkUpdated " +
                    "last=${state.colorToken} pending=${state.pendingColorToken} new=$colorToken"
            )
        }
        if (state.pendingColorToken == colorToken) return
        if (!artworkUpdated && state.colorToken == colorToken) return
        state.pendingColorToken = colorToken
        val request = state.colorRequest.incrementAndGet()
        val context = view.context

        colorExecutor.execute {
            val current = states[controller]
            if (current !== state || current.colorRequest.get() != request) return@execute
            val palette = runCatching {
                val drawable = loadArtwork(context, artwork, packageName)
                    ?: return@runCatching null
                if (isCustomMode(mode)) {
                    val bitmap = MediaArtworkSampler.sample(drawable) ?: return@runCatching null
                    try {
                        MediaFlowColorPayload.Custom(
                            MediaFlowArtwork.prepare(bitmap, blur = false) ?: return@runCatching null
                        )
                    } finally {
                        bitmap.recycle()
                    }
                } else {
                    extractPalette(mode, drawable, state.nativeApi)?.let {
                        MediaFlowColorPayload.Native(it)
                    }
                }
            }.getOrElse { error ->
            HookLogger.e(TAG, "提取通知中心媒体封面颜色失败", error)
                null
            }
            view.post {
                val latest = states[controller]
                if (latest === state && latest.colorRequest.get() == request) {
                    latest.pendingColorToken = null
                    if (palette != null) {
                        applyPalette(latest, palette)
                        latest.colorToken = colorToken
                    }
                }
            }
        }
    }

    private fun ensureView(controller: Any): View? {
        val state = states.getOrPut(controller) { AmbientFlowControllerState() }
        val customMode = isCustomMode(currentMode())
        state.view?.takeIf { it.parent != null && state.customView == customMode }?.let {
            return it
        }
        disposeState(state)

        val holder = readField(controller, "holder") ?: return null
        val mediaBg = readField(holder, "mediaBg") as? View ?: return null
        val parent = mediaBg.parent as? ViewGroup ?: return null
        val nativeApi = if (customMode) null else {
            resolveNativeApi(controller.javaClass.classLoader) ?: return null
        }

        for (index in 0 until parent.childCount) {
            val existing = parent.getChildAt(index)
            if (existing.tag == VIEW_TAG) {
                stopView(existing, state.nativeApi ?: nativeApi)
                parent.removeView(existing)
                break
            }
        }

        val view = if (customMode) {
            MediaFlowBackgroundView(mediaBg.context, appleMusicStyle = true)
        } else {
            requireNotNull(nativeApi).createView(mediaBg.context)
        }
        view.apply {
            tag = VIEW_TAG
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            outlineProvider = mediaBg.outlineProvider
            clipToOutline = true
        }
        val layoutParams = MediaFlowOverlayLayout.createConstraintFill(mediaBg.layoutParams) ?: run {
            HookLogger.w(
                TAG,
                "无法创建独立媒体背景约束，跳过流光视图"
            )
            stopView(view, nativeApi)
            return null
        }
        val index = (parent.indexOfChild(mediaBg) + 1).coerceAtMost(parent.childCount)
        parent.addView(view, index, layoutParams)
        state.colorRequest.incrementAndGet()
        state.view = view
        state.nativeApi = nativeApi
        state.customView = customMode
        state.colorToken = null
        state.pendingColorToken = null
        state.hasColors = false
        state.flowActive = false
        return view
    }

    private fun removeView(controller: Any, forgetState: Boolean = false) {
        val state = (if (forgetState) states.remove(controller) else states[controller]) ?: return
        state.colorRequest.incrementAndGet()
        state.pendingColorToken = null
        disposeState(state)
    }

    /**
     * "暂停时恢复默认"的优雅移除：与恢复播放的原生过渡对称，流光视图先淡出再真正移除。
     * 状态即刻与视图解绑（tick/bind 视为无流光），仅物理移除延迟到淡出结束；息屏下为
     * 淡出帧续一次唤醒锁，避免过渡中途冻结。视图尚未展示配色（无内容可淡出）时直接移除。
     */
    private fun removeFlowGracefully(controller: Any) {
        val state = states[controller] ?: return
        val view = state.view ?: return
        val nativeApi = state.nativeApi
        val context = view.context
        val wasShowing = state.hasColors && view.isShown
        state.colorRequest.incrementAndGet()
        state.pendingColorToken = null
        state.view = null
        state.nativeApi = null
        state.customView = false
        state.hasColors = false
        state.flowActive = false
        if (!wasShowing) {
            stopAndRemoveFlowView(view, nativeApi)
            return
        }
        if (flowWakeLock?.isHeld != true) {
            resolveFlowWakeLock(context)?.acquire(FLOW_WAKE_LOCK_TIMEOUT_MS)
        }
        view.animate()
            .alpha(0f)
            .setDuration(FLOW_FADE_OUT_DURATION_MS)
            .withEndAction { stopAndRemoveFlowView(view, nativeApi) }
            .start()
    }

    private fun stopAndRemoveFlowView(view: View, nativeApi: NativeMusicBgApi?) {
        view.animate().cancel()
        stopView(view, nativeApi)
        (view.parent as? ViewGroup)?.removeView(view)
        view.alpha = 1f
    }

    private fun disposeState(state: AmbientFlowControllerState) {
        val view = state.view ?: return
        stopView(view, state.nativeApi)
        (view.parent as? ViewGroup)?.removeView(view)
        state.view = null
        state.nativeApi = null
        state.customView = false
    }

    private fun stopView(view: View, nativeApi: NativeMusicBgApi?) {
        if (nativeApi != null && nativeApi.accepts(view)) {
            nativeApi.pause(view)
        }
    }

    private fun loadArtwork(
        context: Context,
        artwork: Icon?,
        packageName: String
    ): Drawable? {
        return runCatching {
            artwork?.loadDrawable(context)
                ?: context.packageManager.getApplicationIcon(packageName)
        }.getOrNull()
    }

    private fun extractPalette(
        mode: Int,
        drawable: Drawable,
        nativeApi: NativeMusicBgApi?
    ): MediaAmbientFlowPalette? {
        if (
            mode == RootConstants.NOTIFICATION_MEDIA_AMBIENT_FLOW_MODE_DYNAMIC &&
            nativeApi != null
        ) {
            return nativeApi.extractSystemPalette(drawable)
        }

        val bitmap = MediaArtworkSampler.sample(drawable) ?: return null
        return try {
            val mainColor = MediaAmbientFlowPaletteExtractor.extractCoverMainColor(bitmap)
                ?: return null
            if (BuildConfig.DEBUG) {
                HookLogger.d(TAG, "流光取色(封面色): mainColor=#%06X".format(mainColor))
            }
            nativeApi?.createPalette(mainColor)
        } finally {
            bitmap.recycle()
        }
    }

    private fun applyPalette(state: AmbientFlowControllerState, palette: MediaAmbientFlowPalette) {
        val view = state.view ?: return
        val nativeApi = state.nativeApi ?: return
        if (!nativeApi.accepts(view)) return
        nativeApi.setGradientColor(view, palette.mainColor, palette.colors)
        state.hasColors = true
        syncPlayback(state)
    }

    private fun applyPalette(state: AmbientFlowControllerState, payload: MediaFlowColorPayload) {
        when (payload) {
            is MediaFlowColorPayload.Native -> applyPalette(state, payload.palette)
            is MediaFlowColorPayload.Custom -> {
                val view = state.view as? MediaFlowBackgroundView ?: return
                state.hasColors = true
                view.visibility = View.VISIBLE
                view.update(
                    artwork = payload.artwork,
                    tone = currentFlowTone(view.context),
                    playing = state.isPlaying
                )
            }
        }
    }

    private fun syncPlayback(state: AmbientFlowControllerState) {
        val view = state.view ?: return
        if (view is MediaFlowBackgroundView) {
            configureCustomView(state)
            return
        }
        val nativeApi = state.nativeApi ?: return
        if (!nativeApi.accepts(view)) return
        val shouldPlay = flowShouldPlay(state)
        if (shouldPlay) {
            nativeApi.start(view)
            nativeApi.resume(view)
            // MusicBgView 的帧循环存在无自愈死态（pause 清 mNeedResumeShader +
            // resume 对未暂停状态 early-return），播放中显式恢复帧循环兜底。
            val kicked = if (view.isShown) nativeApi.ensureFrameLoopEnabled(view) else false
            if (BuildConfig.DEBUG) {
                HookLogger.d(
                    TAG,
                    "流光播放同步: shouldPlay=$shouldPlay, shown=${view.isShown}, " +
                        "kicked=$kicked, flowActive=${state.flowActive}",
                )
            }
        } else if (state.flowActive) {
            // 冗余 pause() 会无条件清掉原生 mNeedResumeShader 恢复标记，只在真实停播翻转时暂停。
            nativeApi.pause(view)
        }
        state.flowActive = shouldPlay
    }

    /**
     * "暂停时恢复默认"开启（默认）时暂停即停住流光；关闭后暂停态也保持流动。
     */
    private fun pauseRestoresDefault(): Boolean = prefs?.getBoolean(
        RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_AMBIENT_FLOW_PAUSE_RESTORE_DEFAULT,
        RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_AMBIENT_FLOW_PAUSE_RESTORE_DEFAULT
    ) ?: RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_AMBIENT_FLOW_PAUSE_RESTORE_DEFAULT

    /**
     * "暂停时恢复默认"开启时，暂停态整组移除流光视图，卡片回到无流光的原生默认背景；
     * 恢复播放由重新挂载。"封面流光"（CUSTOM_FULL）不显示该开关，保持原有暂停冻结行为。
     */
    private fun shouldRemoveFlowForState(state: AmbientFlowControllerState): Boolean =
        !isCustomMode(currentMode()) && pauseRestoresDefault() && !state.isPlaying

    private fun flowShouldPlay(state: AmbientFlowControllerState): Boolean =
        state.hasColors && (state.isPlaying || !pauseRestoresDefault())

    private fun configureCustomView(state: AmbientFlowControllerState) {
        val view = state.view as? MediaFlowBackgroundView ?: return
        view.visibility = if (state.hasColors) View.VISIBLE else View.INVISIBLE
        view.update(
            tone = currentFlowTone(view.context),
            playing = state.isPlaying && state.hasColors
        )
    }

    private fun resolveNativeApi(classLoader: ClassLoader?): NativeMusicBgApi? {
        classLoader ?: return null
        nativeApis[classLoader]?.let { return it }
        if (nativeUnavailableClassLoaders.contains(classLoader)) return null

        return runCatching { NativeMusicBgApi.create(classLoader) }
            .onSuccess { api ->
                nativeApis[classLoader] = api
            HookLogger.d(TAG, "使用原生 MusicBgView 渲染器")
            }
            .onFailure { error ->
                nativeUnavailableClassLoaders.add(classLoader)
            HookLogger.w(TAG, "原生 MusicBgView 不可用: reason=${error.message}")
            }
            .getOrNull()
    }

    private fun readField(receiver: Any, name: String): Any? {
        return findField(receiver.javaClass, name)?.let { field ->
            runCatching { field.get(receiver) }.getOrNull()
        }
    }

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { current.getDeclaredField(name) }.getOrNull()?.let { field ->
                field.isAccessible = true
                return field
            }
            current = current.superclass
        }
        return null
    }

    internal fun findNearestMethods(type: Class<*>, name: String): List<Method> {
        var current: Class<*>? = type
        while (current != null) {
            val methods = current.declaredMethods.filter { method ->
                method.name == name &&
                    !method.isBridge &&
                    !method.isSynthetic &&
                    !Modifier.isAbstract(method.modifiers)
            }
            if (methods.isNotEmpty()) return methods
            current = current.superclass
        }
        return emptyList()
    }

    private fun currentMode(): Int {
        if (!SystemUiEnhancementGate.isEnabled()) {
            return RootConstants.NOTIFICATION_MEDIA_AMBIENT_FLOW_MODE_DISABLED
        }
        return prefs?.getInt(
            RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_AMBIENT_FLOW_MODE,
            RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_AMBIENT_FLOW_MODE
        )?.coerceIn(
            RootConstants.NOTIFICATION_MEDIA_AMBIENT_FLOW_MODE_DISABLED,
            RootConstants.NOTIFICATION_MEDIA_AMBIENT_FLOW_MODE_CUSTOM_FULL
        ) ?: RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_AMBIENT_FLOW_MODE
    }

    private fun isCustomMode(mode: Int): Boolean =
        mode == RootConstants.NOTIFICATION_MEDIA_AMBIENT_FLOW_MODE_CUSTOM_FULL

    private fun currentFlowTone(context: Context): MediaFlowTone {
        val light = when (currentCardTheme()) {
            RootConstants.MEDIA_CARD_THEME_ALWAYS_LIGHT -> true
            RootConstants.MEDIA_CARD_THEME_ALWAYS_DARK -> false
            else -> context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        }
        return if (light) MediaFlowTone.LIGHT else MediaFlowTone.DARK
    }

    private fun currentCardTheme(): Int {
        if (!SystemUiEnhancementGate.isEnabled()) {
            return RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_CARD_THEME
        }
        return prefs?.getInt(
            RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_CARD_THEME,
            RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_CARD_THEME
        )?.coerceIn(
            RootConstants.MEDIA_CARD_THEME_FOLLOW_SYSTEM,
            RootConstants.MEDIA_CARD_THEME_ALWAYS_DARK
        ) ?: RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_CARD_THEME
    }

}
