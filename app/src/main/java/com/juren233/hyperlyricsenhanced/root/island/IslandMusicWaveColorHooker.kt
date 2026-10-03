package com.juren233.hyperlyricsenhanced.root.island

import com.juren233.hyperlyricsenhanced.root.reload.SystemUiHookLifetime
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.IslandMusicWaveColorMode
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.color.ColorExtractor
import com.juren233.hyperlyricsenhanced.common.media.MediaMetadataHelper
import com.juren233.hyperlyricsenhanced.root.HookEntry
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.SystemUiEnhancementGate
import com.juren233.hyperlyricsenhanced.root.mediacard.MediaArtworkSampler
import com.juren233.hyperlyricsenhanced.root.utils.CoverColorHelper
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal object IslandMusicWaveColorHooker {
    private const val TAG = "IslandMusicWaveColorHooker"
    private const val ICON_HOLDER_CLASS =
        "miui.systemui.dynamicisland.module.IslandIconViewHolder"
    private const val ICON_HOLDER_COMPANION_CLASS =
        "miui.systemui.dynamicisland.module.IslandIconViewHolder\$Companion"
    private const val NATIVE_COLOR_ALPHA = 230

    /** 岛重绑回调触发媒体重取的节流间隔，避免逐句重绑时反复查询 MediaSession。 */
    private const val MEDIA_REFRESH_MIN_INTERVAL_MS = 1_500L

    /** 切歌后元数据可能晚于歌词到达，安排延迟复查以纠正竞态取到的旧封面。 */
    private const val MEDIA_RECHECK_DELAY_MS = 2_000L

    /** 延迟复查最大次数（间隔按次数递增：2s、4s）。 */
    private const val MEDIA_RECHECK_MAX_ATTEMPTS = 2

    /** 模式对账看门狗（窗口内）周期：窗口内事件触发链失效时，模式切换最迟延迟一个周期生效。 */
    private const val MODE_WATCHDOG_INTERVAL_MS = 1_000L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private val trackedLottieViews = WeakHashMap<View, Boolean>()
    private val trackedHolders = WeakHashMap<Any, Boolean>()
    @Volatile
    private var registerLottieCallbackMethod: java.lang.reflect.Method? = null

    /** 防止"重挂回调 → 触发自身 Hook → 又重挂"的递归。 */
    @Volatile
    private var reapplyingLottieCallbacks = false
    private val firstColorCallbacks = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private val firstClampHits = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private val colorRequest = AtomicInteger()
    private val colorCache = LruCache<String, WaveColors>(8)

    @Volatile
    private var colorExecutor: ExecutorService = newColorExecutor()

    @Volatile
    private var module: XposedModule? = null

    @Volatile
    private var colorAccessor: ColorAccessor? = null

    @Volatile
    private var desiredColors: WaveColors? = null

    @Volatile
    private var desiredToken: String? = null

    @Volatile
    private var pendingToken: String? = null

    @Volatile
    private var inputToken: String? = null

    @Volatile
    private var nativeColors: WaveColors? = null

    @Volatile
    private var overrideApplied = false

    @Volatile
    private var lastArtworkRef: java.lang.ref.WeakReference<Bitmap>? = null

    @Volatile
    private var mediaTriggersInstalled = false

    @Volatile
    private var lastMediaFetchAt: Long = 0L

    @Volatile
    private var lastAppliedMode: Int = IslandMusicWaveColorMode.UNSPECIFIED

    private val watchdogLock = Any()
    private var watchdogScheduled = false

    @Volatile
    private var watchdogDeadlineMs = 0L

    private val mediaRecheckToken = AtomicInteger()

    private val songChangedListener = {
        scheduleColorsFromMediaChange()
    }

    private val prefs: SharedPreferences?
        get() = (module as? HookEntry)?.prefs

    /** 诊断追踪日志仅存在于 debug 构建，用于排查切句时律动动画重置等运行时问题。 */
    private inline fun trace(message: () -> String) {
        if (BuildConfig.DEBUG) {
            HookLogger.i(TAG, "[trace] ${message()}")
        }
    }

    fun hook(xposedModule: XposedModule, classLoader: ClassLoader) {
        module = xposedModule
        ensureMediaTriggers()
        if (colorExecutor.isShutdown) colorExecutor = newColorExecutor()
        if (!hookedClassLoaders.add(classLoader)) return

        try {
            val holderClass = classLoader.loadClass(ICON_HOLDER_CLASS)
            colorAccessor = ColorAccessor(
                topField = holderClass.getDeclaredField("gradientTopColor").apply {
                    isAccessible = true
                },
                bottomField = holderClass.getDeclaredField("gradientBottomColor").apply {
                    isAccessible = true
                }
            )

            val colorInputMethods = holderClass.declaredMethods
                .filter { IslandMusicWaveMethodProfile.isLegacyColorMethod(it) ||
                    IslandMusicWaveMethodProfile.isOs4ColorMethod(it) }
                .distinctBy { it.name + it.parameterTypes.contentToString() }
            colorInputMethods.forEach { method ->
                method.isAccessible = true
                xposedModule.deoptimize(method)
                xposedModule.hook(method).intercept(SetLottieColorHook(method.name))
            }
            if (colorInputMethods.isEmpty()) {
                HookLogger.w(TAG, "音频律动原生取色接口不可用: targets=setLottieColor/getLottieColor")
            } else {
                HookLogger.i(
                    TAG,
                    "音频律动原生取色接口已匹配: targets=${colorInputMethods.joinToString { it.name }}"
                )
            }

            installNativeColorWriteClamp(xposedModule, classLoader)

            val lottieViewField = holderClass.getDeclaredField("lottieView").apply {
                isAccessible = true
            }
            val picInfoField = holderClass.getDeclaredField("picInfo").apply {
                isAccessible = true
            }
            val registerCallbackMethod = holderClass.declaredMethods.firstOrNull {
                it.name == "registerLottieCallback" && it.parameterTypes.isEmpty()
            }
            if (registerCallbackMethod != null) {
                registerCallbackMethod.isAccessible = true
                registerLottieCallbackMethod = registerCallbackMethod
                xposedModule.deoptimize(registerCallbackMethod)
                xposedModule.hook(registerCallbackMethod).intercept(
                    RegisterLottieCallbackHook(lottieViewField, picInfoField)
                )
            } else {
                HookLogger.w(TAG, "音频律动刷新接口不可用: target=registerLottieCallback")
            }

            HookLogger.i(TAG, "音频律动封面色 Hook 已初始化")
        } catch (e: ClassNotFoundException) {
            hookedClassLoaders.remove(classLoader)
            HookLogger.w(TAG, "当前插件不支持音频律动封面色: reason=${e.message}")
        } catch (e: NoSuchFieldException) {
            hookedClassLoaders.remove(classLoader)
            HookLogger.w(TAG, "音频律动颜色字段不可用: reason=${e.message}")
        } catch (e: Throwable) {
            hookedClassLoaders.remove(classLoader)
            HookLogger.e(TAG, "初始化音频律动封面色 Hook 失败", e)
        }
    }

    fun refresh() {
        runOnMain {
            armModeWatchdog()
            val sharedPrefs = prefs
            if (sharedPrefs == null || !isEnabled(sharedPrefs)) {
                trace { "refresh: 功能未启用，恢复原生色" }
                restoreNativeColors()
                return@runOnMain
            }
            if (refreshColorsFromMediaSession("preference_refresh", allowDeferredRetry = false)) {
                return@runOnMain
            }
            val lastBitmap = lastArtworkRef?.get()
            if (lastBitmap != null && !lastBitmap.isRecycled) {
                // 媒体会话暂不可用时退回最近的封面，仍按最新偏好重算，保证模式切换立即生效
                trace { "refresh: 媒体会话不可用，按最近封面重取色 bitmap=${System.identityHashCode(lastBitmap)}" }
                scheduleOptimizedColors(lastBitmap, sharedPrefs)
            } else {
                trace { "refresh: 无可用封面，重放期望色" }
                desiredColors?.let { colorAccessor?.write(it) }
                invalidateTrackedLottieViews()
            }
        }
    }

    /** 渲染管线捎带入口：宿主内容刷新已取得最新媒体信息时直接喂给取色管线。 */
    fun onMediaArtworkUpdated(mediaInfo: MediaMetadataHelper.MediaInfo?) {
        val sharedPrefs = prefs ?: return
        val artwork = mediaInfo?.albumArt
        if (artwork == null || artwork.isRecycled) return
        lastArtworkRef = java.lang.ref.WeakReference(artwork)
        if (!isEnabled(sharedPrefs)) return
        val lyricSong = LyriconDataBridge.currentSong
        val lyricTitle = lyricSong?.name?.takeIf { it.isNotBlank() }
            ?: LyriconDataBridge.currentSongName?.takeIf { it.isNotBlank() }
        if (IslandSlotContentAssembler.shouldRejectArtworkForTitleMismatch(
                lyricTitle = lyricTitle,
                mediaTitle = mediaInfo.title,
                lyricArtist = lyricSong?.artist?.takeIf { it.isNotBlank() },
                mediaArtist = mediaInfo.artist,
                mediaAlbum = mediaInfo.album,
            )
        ) {
            // 切歌竞态：封面可能还是上一首的，不采用，交给切歌监听的延迟复查纠正
            trace { "渲染封面与歌词标题不一致，跳过本次律动取色" }
            scheduleDeferredMediaRecheck()
            return
        }
        runOnMain {
            runCatching { scheduleOptimizedColors(artwork, sharedPrefs) }.onFailure { e ->
                HookLogger.e(TAG, "应用音频律动颜色失败", e)
            }
        }
    }

    private fun ensureMediaTriggers() {
        if (mediaTriggersInstalled) return
        mediaTriggersInstalled = true
        LyriconDataBridge.addSongChangedListener(songChangedListener)
        armModeWatchdog()
    }

    /**
     * 模式对账看门狗（窗口化）：同一首歌内颜色只来自封面，封面不变颜色就不该变，
     * 常驻 1Hz 对账是纯空转。仅在事件（初始化/切歌/设置修改/取色应用）后布防一个
     * [IslandWaveColorWatchdogPolicy.WINDOW_MS] 窗口；功能关闭且原生色已还原时不再
     * 布防。窗口内发现「设置的模式 ≠ 已应用的模式」立即补一次重取/还原。
     */
    private val modeWatchdog = object : Runnable {
        override fun run() {
            synchronized(watchdogLock) { watchdogScheduled = false }
            runCatching {
                val sharedPrefs = prefs
                if (sharedPrefs != null && isEnabled(sharedPrefs)) {
                    val mode = IslandRuntimePreferenceReader.getMusicWaveColorMode(sharedPrefs)
                    if (mode != lastAppliedMode) {
                        HookLogger.i(TAG, "模式对账触发重取色: applied=$lastAppliedMode, current=$mode")
                        refresh()
                    }
                } else if (sharedPrefs != null && overrideApplied) {
                    HookLogger.i(TAG, "模式对账触发恢复原生色")
                    refresh()
                }
            }.onFailure { e ->
                HookLogger.e(TAG, "模式对账看门狗异常", e)
            }
            if (!IslandWaveColorWatchdogPolicy.shouldKeepRunning(
                    nowMs = SystemClock.uptimeMillis(),
                    deadlineMs = watchdogDeadlineMs,
                )
            ) {
                return
            }
            synchronized(watchdogLock) {
                if (watchdogScheduled) return
                watchdogScheduled = true
            }
            mainHandler.postDelayed(this, MODE_WATCHDOG_INTERVAL_MS)
        }
    }

    private fun armModeWatchdog() {
        val sharedPrefs = prefs ?: return
        if (!IslandWaveColorWatchdogPolicy.shouldArm(
                featureEnabled = isEnabled(sharedPrefs),
                overrideApplied = overrideApplied,
            )
        ) {
            return
        }
        watchdogDeadlineMs = SystemClock.uptimeMillis() + IslandWaveColorWatchdogPolicy.WINDOW_MS
        synchronized(watchdogLock) {
            if (watchdogScheduled) return
            watchdogScheduled = true
        }
        mainHandler.postDelayed(modeWatchdog, MODE_WATCHDOG_INTERVAL_MS)
    }

    private fun scheduleColorsFromMediaChange() {
        val sharedPrefs = prefs ?: return
        if (!isEnabled(sharedPrefs)) return
        runOnMain {
            armModeWatchdog()
            runCatching {
                refreshColorsFromMediaSession("song_changed", allowDeferredRetry = true)
            }.onFailure { e ->
                HookLogger.e(TAG, "切歌后重取音频律动颜色失败", e)
            }
        }
    }

    /**
     * 从当前媒体会话封面按最新偏好重取色。必须在主线程调用。
     * @return true 表示已按媒体封面调度重算；false 表示媒体信息不可用或与歌词竞态不匹配。
     */
    private fun refreshColorsFromMediaSession(
        reason: String,
        allowDeferredRetry: Boolean
    ): Boolean {
        val sharedPrefs = prefs ?: return deferredRetryFailure(allowDeferredRetry)
        val context = resolveColorContext() ?: return deferredRetryFailure(allowDeferredRetry)
        val packageName = LyriconDataBridge.currentLyricPackageName?.takeIf { it.isNotEmpty() }
            ?: LyriconDataBridge.activePackageName?.takeIf { it.isNotEmpty() }
            ?: return deferredRetryFailure(allowDeferredRetry)
        lastMediaFetchAt = SystemClock.uptimeMillis()
        val mediaInfo = runCatching {
            MediaMetadataHelper.getMediaInfo(context, packageName, HookLogger)
        }.getOrNull() ?: return deferredRetryFailure(allowDeferredRetry)
        val artwork = mediaInfo.albumArt
        if (artwork == null || artwork.isRecycled) {
            return deferredRetryFailure(allowDeferredRetry)
        }
        val lyricSong = LyriconDataBridge.currentSong
        val lyricTitle = lyricSong?.name?.takeIf { it.isNotBlank() }
            ?: LyriconDataBridge.currentSongName?.takeIf { it.isNotBlank() }
        if (IslandSlotContentAssembler.shouldRejectArtworkForTitleMismatch(
                lyricTitle = lyricTitle,
                mediaTitle = mediaInfo.title,
                lyricArtist = lyricSong?.artist?.takeIf { it.isNotBlank() },
                mediaArtist = mediaInfo.artist,
                mediaAlbum = mediaInfo.album,
            )
        ) {
            // 切歌竞态：MediaSession 元数据可能仍是上一首，封面不可信，交给延迟复查
            trace { "媒体封面与歌词标题不一致，跳过本轮: reason=$reason" }
            return deferredRetryFailure(allowDeferredRetry)
        }
        lastArtworkRef = java.lang.ref.WeakReference(artwork)
        trace { "按媒体会话封面重取色: reason=$reason, bitmap=${System.identityHashCode(artwork)}" }
        scheduleOptimizedColors(artwork, sharedPrefs)
        return true
    }

    private fun deferredRetryFailure(allowDeferredRetry: Boolean): Boolean {
        if (allowDeferredRetry) scheduleDeferredMediaRecheck()
        return false
    }

    private fun scheduleDeferredMediaRecheck(attempt: Int = 1) {
        if (attempt > MEDIA_RECHECK_MAX_ATTEMPTS) return
        val token = mediaRecheckToken.incrementAndGet()
        mainHandler.postDelayed({
            if (mediaRecheckToken.get() != token) return@postDelayed
            runCatching {
                val refreshed = refreshColorsFromMediaSession(
                    "deferred_recheck_$attempt",
                    allowDeferredRetry = false,
                )
                if (!refreshed) scheduleDeferredMediaRecheck(attempt + 1)
            }.onFailure { e ->
                HookLogger.e(TAG, "延迟复查音频律动封面失败", e)
            }
        }, MEDIA_RECHECK_DELAY_MS * attempt)
    }

    private fun resolveColorContext(): Context? {
        (module as? HookEntry)?.runtimeAppContext()?.let { return it }
        return synchronized(trackedLottieViews) {
            trackedLottieViews.keys.firstOrNull()
        }?.context
    }

    /**
     * 原生取色协程会把 MIUI 自算颜色异步写回静态字段，落在我们覆盖之后就会出现一帧
     * 原生色（闪烁）。钳制写入：覆盖生效期间，原生写色后立即用期望色覆盖回来。
     * 类名与方法名已对照 20260912 手机 SystemUI 插件原始 dex 验证
     * （Lmiui/systemui/dynamicisland/module/IslandIconViewHolder$Companion;）。
     */
    private fun installNativeColorWriteClamp(xposedModule: XposedModule, classLoader: ClassLoader) {
        val companionClass = runCatching {
            classLoader.loadClass(ICON_HOLDER_COMPANION_CLASS)
        }.getOrNull()
        if (companionClass == null) {
            HookLogger.w(TAG, "音频律动颜色写入钳制不可用: 类不存在")
            return
        }
        val clampMethods = companionClass.declaredMethods
            .filter { it.name == "setGradientTopColor" || it.name == "setGradientBottomColor" }
            .distinctBy { it.name + it.parameterTypes.contentToString() }
        if (clampMethods.isEmpty()) {
            HookLogger.w(TAG, "音频律动颜色写入钳制不可用: 未找到静态色 setter")
            return
        }
        clampMethods.forEach { method ->
            method.isAccessible = true
            xposedModule.deoptimize(method)
            xposedModule.hook(method).intercept(NativeColorWriteClampHook(method.name))
        }
        HookLogger.i(
            TAG,
            "音频律动颜色写入钳制已安装: targets=${clampMethods.joinToString { it.name }}"
        )
    }

    internal fun snapshotForReload(): Array<Any> =
        synchronized(trackedHolders) { trackedHolders.keys.toTypedArray() }

    internal fun restoreAfterReload(holders: List<Any>) {
        holders.forEach { holder ->
            runCatching { registerLottieCallbackMethod?.invoke(holder) }
                .onFailure { HookLogger.e(TAG, "热重载后恢复音频律动失败", it) }
        }
        refresh()
    }

    internal fun releaseForReload() {
        mediaRecheckToken.incrementAndGet()
        LyriconDataBridge.removeSongChangedListener(songChangedListener)
        mediaTriggersInstalled = false
        watchdogDeadlineMs = 0L
        synchronized(watchdogLock) { watchdogScheduled = false }
        cleanup()
        mainHandler.removeCallbacksAndMessages(null)
        colorExecutor.shutdownNow()
    }

    fun cleanup() {
        colorRequest.incrementAndGet()
        colorExecutor.shutdown()
        runOnMain {
            restoreNativeColors()
            synchronized(trackedLottieViews) {
                trackedLottieViews.clear()
            }
            synchronized(trackedHolders) {
                trackedHolders.clear()
            }
            firstColorCallbacks.clear()
            nativeColors = null
            colorAccessor = null
            lastArtworkRef = null
        }
    }

    private fun scheduleOptimizedColors(
        bitmap: Bitmap,
        sharedPrefs: SharedPreferences
    ) {
        // cleanup()（如环境重初始化）会关掉取色线程；不复活的话异步重算会静默失败，
        // 表现为切模式/切歌后颜色永远停留在旧值，只有重放路径还活着。
        if (colorExecutor.isShutdown) {
            colorExecutor = newColorExecutor()
            trace { "取色线程已复活" }
        }
        val useGradient = IslandMusicWaveColorMode.usesCoverGradient(
            IslandRuntimePreferenceReader.getMusicWaveColorMode(sharedPrefs)
        )
        val nextInputToken =
            "${System.identityHashCode(bitmap)}:${bitmap.generationId}:" +
                "${bitmap.width}x${bitmap.height}:$useGradient"
        if (inputToken == nextInputToken) {
            if (pendingToken != null) {
                trace { "调度早退: 输入未变且有在途任务 inputToken=$inputToken" }
                return
            }
            val token = desiredToken
            val colors = desiredColors
            trace { "调度早退: 输入未变，重放期望色 token=$token" }
            if (token != null && colors != null) applyOptimizedColors(colors, token)
            return
        }
        inputToken = nextInputToken
        trace { "调度重算: inputToken=$nextInputToken" }

        val sample = MediaArtworkSampler.sample(bitmap) ?: return
        val token = "${MediaArtworkSampler.fingerprint(sample)}:$useGradient"

        if (desiredToken == token) {
            sample.recycle()
            trace { "调度早退: 同一封面已取色，直接复用 token=$token" }
            desiredColors?.let { applyOptimizedColors(it, token) }
            return
        }
        if (pendingToken == token) {
            sample.recycle()
            return
        }

        colorCache.get(token)?.let { colors ->
            sample.recycle()
            applyOptimizedColors(colors, token)
            return
        }

        val request = colorRequest.incrementAndGet()
        pendingToken = token
        runCatching {
            colorExecutor.execute {
                if (colorRequest.get() != request || pendingToken != token) {
                    sample.recycle()
                    return@execute
                }
                val colors = try {
                    colorsFromPalette(
                        ColorExtractor.extractThemePalette(
                            sample,
                            if (useGradient) 4 else 1
                        ).onBlackBackground,
                        useGradient
                    )
                } catch (e: Throwable) {
            HookLogger.e(TAG, "提取音频律动颜色失败", e)
                    null
                } finally {
                    sample.recycle()
                }

                runOnMain {
                    if (colors != null) colorCache.put(token, colors)
                    if (colorRequest.get() != request || pendingToken != token) return@runOnMain
                    pendingToken = null
                    val currentPrefs = prefs
                    if (colors == null || currentPrefs == null || !isEnabled(currentPrefs)) {
                        if (colors == null) inputToken = null
                        return@runOnMain
                    }
                    val currentGradient = IslandMusicWaveColorMode.usesCoverGradient(
                        IslandRuntimePreferenceReader.getMusicWaveColorMode(currentPrefs)
                    )
                    if (currentGradient != useGradient) return@runOnMain
                    applyOptimizedColors(colors, token)
                }
            }
        }.onFailure { e ->
            sample.recycle()
            if (colorRequest.get() == request && pendingToken == token) pendingToken = null
            HookLogger.e(TAG, "调度音频律动取色任务失败", e)
        }
    }

    private fun applyOptimizedColors(colors: WaveColors, token: String) {
        // 可见的 Lottie 不会因静态字段变化而自动重读（用户实测必须重绑才变色），
        // 因此记录应用前颜色，仅在颜色真正变化时重挂原生颜色回调。
        val colorsChanged = desiredColors != colors
        desiredColors = colors
        desiredToken = token
        if (pendingToken == token) pendingToken = null
        val accessor = colorAccessor ?: return
        if (!overrideApplied && nativeColors == null) {
            nativeColors = accessor.read()
        }
        accessor.write(colors)
        overrideApplied = true
        prefs?.let { lastAppliedMode = IslandRuntimePreferenceReader.getMusicWaveColorMode(it) }
        armModeWatchdog()
        trace { "应用取色: token=$token, colors=$colors, colorsChanged=$colorsChanged" }
        if (colorsChanged) {
            reapplyLottieValueCallbacks()
        } else {
            invalidateTrackedLottieViews()
        }
    }

    /**
     * 重挂原生颜色回调（等价原生重绑时的刷新动作，不重启动画）。
     * 原生回调读取静态色字段的时机由 Lottie 回调绑定决定，字段写入本身不触发重绘取值，
     * 这就是"必须暂停/播放才变色"的根因。
     */
    private fun reapplyLottieValueCallbacks() {
        val method = registerLottieCallbackMethod
        if (method == null) {
            invalidateTrackedLottieViews()
            return
        }
        reapplyingLottieCallbacks = true
        try {
            val holders = synchronized(trackedHolders) { trackedHolders.keys.toList() }
            holders.forEach { holder ->
                runCatching { method.invoke(holder) }.onFailure { e ->
                    HookLogger.w(
                        TAG,
                        "重挂律动颜色回调失败: holder=${System.identityHashCode(holder)}",
                        e
                    )
                }
            }
            trace { "重挂律动颜色回调: holders=${holders.size}" }
        } finally {
            reapplyingLottieCallbacks = false
        }
        invalidateTrackedLottieViews()
    }

    private fun restoreNativeColors(rootView: ViewGroup? = null) {
        colorRequest.incrementAndGet()
        desiredColors = null
        desiredToken = null
        pendingToken = null
        inputToken = null
        lastAppliedMode = IslandMusicWaveColorMode.UNSPECIFIED
        val wasApplied = overrideApplied
        if (overrideApplied) {
            nativeColors?.let { colorAccessor?.write(it) }
            overrideApplied = false
        }
        if (wasApplied) {
            // 静态字段写回对可见 Lottie 不生效，必须同样重挂回调才能立即回到原生色
            reapplyLottieValueCallbacks()
        } else {
            invalidateTrackedLottieViews()
        }
        rootView?.let(::invalidateLottieViews)
    }

    private fun isEnabled(sharedPrefs: SharedPreferences): Boolean {
        return SystemUiEnhancementGate.isEnabled() && sharedPrefs.getBoolean(
            RootConstants.KEY_HOOK_ISLAND_RIGHT_ICON,
            RootConstants.DEFAULT_HOOK_ISLAND_RIGHT_ICON
        ) && IslandMusicWaveColorMode.isEnabled(
            IslandRuntimePreferenceReader.getMusicWaveColorMode(sharedPrefs)
        )
    }

    private fun withNativeAlpha(color: Int): Int {
        return (color and 0x00FFFFFF) or (NATIVE_COLOR_ALPHA shl 24)
    }

    private fun colorsFromPalette(colors: List<Int>, useGradient: Boolean): WaveColors? {
        val primary = colors.firstOrNull() ?: return null
        if (!useGradient) {
            return WaveColors(
                top = withNativeAlpha(primary),
                bottom = withNativeAlpha(primary)
            )
        }
        val gradientColors = CoverColorHelper.resolveNativeGradientColors(colors.toIntArray())
            ?: return WaveColors(
                top = withNativeAlpha(primary),
                bottom = withNativeAlpha(primary)
            )
        return WaveColors(
            top = withNativeAlpha(gradientColors.first),
            bottom = withNativeAlpha(gradientColors.second)
        )
    }

    private fun newColorExecutor(): ExecutorService {
        return Executors.newSingleThreadExecutor { task ->
            Thread(task, "HyperLyrics Enhanced-MusicWaveColor").apply { isDaemon = true }
        }
    }

    private fun invalidateTrackedLottieViews() {
        val views = synchronized(trackedLottieViews) {
            trackedLottieViews.keys.toList()
        }
        views.forEach(View::invalidate)
    }

    private fun invalidateLottieViews(view: View) {
        if (view.javaClass.name == "com.airbnb.lottie.LottieAnimationView") {
            view.invalidate()
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                invalidateLottieViews(view.getChildAt(index))
            }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private class SetLottieColorHook(private val methodName: String) : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (SystemUiHookLifetime.retired) return chain.proceed()
            val result = chain.proceed()
            runCatching {
                val bitmap = chain.args.firstOrNull() as? Bitmap ?: return@runCatching
                lastArtworkRef = java.lang.ref.WeakReference(bitmap)
                val holder = chain.thisObject
                val classLoader = holder?.javaClass?.classLoader
                if (classLoader != null && firstColorCallbacks.add(classLoader)) {
                    HookLogger.i(
                        TAG,
                        "音频律动取色首次回调: method=$methodName, " +
                            "return=${result?.javaClass?.name ?: "null"}"
                    )
                }
                nativeColors = readNativeColors(result, holder)
                trace {
                    "取色回调: method=$methodName, bitmap=${System.identityHashCode(bitmap)}, " +
                        "gen=${bitmap.generationId}, ${bitmap.width}x${bitmap.height}, " +
                        "nativeColors=$nativeColors"
                }

                val sharedPrefs = prefs ?: return@runCatching
                if (!isEnabled(sharedPrefs)) {
                    trace { "回调时功能未启用，重置覆盖状态" }
                    colorRequest.incrementAndGet()
                    desiredColors = null
                    desiredToken = null
                    pendingToken = null
                    inputToken = null
                    overrideApplied = false
                    invalidateTrackedLottieViews()
                    return@runCatching
                }

                runOnMain {
                    runCatching {
                        // 统一以媒体会话封面为取色源：原生缓存封面（64px 调色板位图）与
                        // 媒体封面采样指纹不同，双源调度会令颜色来回跳（闪烁）。
                        if (!refreshColorsFromMediaSession("native_color_callback", allowDeferredRetry = false)) {
                            if (!bitmap.isRecycled) {
                                trace { "媒体封面不可用，回退原生回调封面取色" }
                                scheduleOptimizedColors(bitmap, sharedPrefs)
                            }
                        }
                    }.onFailure { e ->
            HookLogger.e(TAG, "应用音频律动颜色失败", e)
                    }
                }
            }.onFailure { e ->
            HookLogger.e(TAG, "读取原生音频律动颜色失败", e)
            }
            return result
        }
    }

    private fun readNativeColors(result: Any?, holder: Any?): WaveColors? {
        val pair = result ?: return colorAccessor?.read(holder)
        val top = readPairInt(pair, "a") ?: return colorAccessor?.read(holder)
        val bottom = readPairInt(pair, "b") ?: return colorAccessor?.read(holder)
        return WaveColors(top, bottom)
    }

    private class NativeColorWriteClampHook(private val methodName: String) : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (SystemUiHookLifetime.retired) return chain.proceed()
            val result = chain.proceed()
            runCatching {
                if (!overrideApplied) return@runCatching
                val colors = desiredColors ?: return@runCatching
                val sharedPrefs = prefs ?: return@runCatching
                if (!isEnabled(sharedPrefs)) return@runCatching
                // 记录原生当前想写的颜色，作为关闭功能恢复原生色时的最新值
                (chain.args.firstOrNull() as? Int)?.let { value ->
                    recordNativeColor(methodName, value)
                }
                val holder = chain.thisObject
                val classLoader = holder?.javaClass?.classLoader
                    ?: IslandMusicWaveColorHooker.module?.javaClass?.classLoader
                if (classLoader != null && firstClampHits.add(classLoader)) {
                    HookLogger.i(
                        TAG,
                        "音频律动颜色钳制首次命中: method=$methodName"
                    )
                }
                colorAccessor?.write(colors)
                trace { "钳制原生颜色写入: method=$methodName, 已恢复期望色" }
            }.onFailure { e ->
                HookLogger.e(TAG, "钳制原生音频律动颜色写入失败", e)
            }
            return result
        }
    }

    private fun recordNativeColor(methodName: String, value: Int) {
        val current = nativeColors
        nativeColors = if (methodName == "setGradientTopColor") {
            WaveColors(top = value, bottom = current?.bottom ?: value)
        } else {
            WaveColors(top = current?.top ?: value, bottom = value)
        }
    }

    private fun readPairInt(pair: Any, fieldName: String): Int? {
        return runCatching {
            val field = pair.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }
            (field.get(pair) as? Number)?.toInt()
        }.getOrNull()
    }

    private class RegisterLottieCallbackHook(
        private val lottieViewField: Field,
        private val picInfoField: Field
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (SystemUiHookLifetime.retired) return chain.proceed()
            val result = chain.proceed()
            runCatching {
                val holder = chain.thisObject ?: return@runCatching
                synchronized(trackedHolders) {
                    trackedHolders[holder] = true
                }
                if (!isMusicWave(picInfoField.get(holder))) return@runCatching

                val lottieView = lottieViewField.get(holder) as? View ?: return@runCatching
                synchronized(trackedLottieViews) {
                    trackedLottieViews[lottieView] = true
                }
                // 模块主动重挂回调期间不做取色调度，避免递归与重复查询
                if (reapplyingLottieCallbacks) {
                    lottieView.invalidate()
                    return@runCatching
                }
                val sharedPrefs = prefs
                val applied = sharedPrefs != null && isEnabled(sharedPrefs)
                trace {
                    "律动刷新回调: holder=${System.identityHashCode(holder)}, " +
                        "lottie=${System.identityHashCode(lottieView)}, " +
                        "lottieHash=${lottieView.hashCode()}, enabled=$applied, " +
                        "hasDesired=${desiredColors != null}"
                }
                if (sharedPrefs != null && isEnabled(sharedPrefs)) {
                    val now = SystemClock.uptimeMillis()
                    val refreshScheduled = now - lastMediaFetchAt >= MEDIA_REFRESH_MIN_INTERVAL_MS &&
                        refreshColorsFromMediaSession("lottie_rebind", allowDeferredRetry = false)
                    if (!refreshScheduled) {
                        desiredColors?.let { colorAccessor?.write(it, holder) }
                    }
                }
                lottieView.invalidate()
            }.onFailure { e ->
            HookLogger.e(TAG, "刷新音频律动动画失败", e)
            }
            return result
        }

        private fun isMusicWave(picInfo: Any?): Boolean {
            val pic = picInfo?.javaClass?.methods
                ?.firstOrNull { it.name == "getPic" && it.parameterTypes.isEmpty() }
                ?.invoke(picInfo) as? String
            return pic == "musicWave" || pic == "musicPause"
        }
    }

    private data class WaveColors(
        val top: Int,
        val bottom: Int
    )

    private data class ColorAccessor(
        val topField: Field,
        val bottomField: Field
    ) {
        fun read(holder: Any? = null): WaveColors = WaveColors(
            top = getInt(topField, holder),
            bottom = getInt(bottomField, holder)
        )

        fun write(colors: WaveColors, holder: Any? = null) {
            setInt(topField, colors.top, holder)
            setInt(bottomField, colors.bottom, holder)
        }

        private fun getInt(field: Field, holder: Any?): Int {
            return if (java.lang.reflect.Modifier.isStatic(field.modifiers)) {
                field.getInt(null)
            } else {
                field.getInt(holder)
            }
        }

        private fun setInt(field: Field, value: Int, holder: Any?) {
            if (java.lang.reflect.Modifier.isStatic(field.modifiers)) {
                field.setInt(null, value)
            } else if (holder != null) {
                field.setInt(holder, value)
            }
        }
    }
}
