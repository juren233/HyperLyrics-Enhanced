package com.juren233.hyperlyricsenhanced.root.mediacard.island

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RuntimeShader
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.root.HookEntry
import com.juren233.hyperlyricsenhanced.root.SystemUiEnhancementGate
import com.juren233.hyperlyricsenhanced.root.island.IslandAlbumCoverStyleHooker
import com.juren233.hyperlyricsenhanced.root.island.IslandProbeUtils
import com.juren233.hyperlyricsenhanced.root.mediacard.MediaAmbientFlowPalette
import com.juren233.hyperlyricsenhanced.root.mediacard.MediaAmbientFlowPaletteExtractor
import com.juren233.hyperlyricsenhanced.root.mediacard.MediaArtworkSampler
import com.juren233.hyperlyricsenhanced.root.mediacard.background.MediaFlowArtwork
import com.juren233.hyperlyricsenhanced.root.mediacard.background.MediaFlowBackgroundView
import com.juren233.hyperlyricsenhanced.root.mediacard.background.MediaFlowOverlayLayout
import com.juren233.hyperlyricsenhanced.root.mediacard.background.MediaFlowTimeline
import com.juren233.hyperlyricsenhanced.root.mediacard.background.MediaFlowTone
import com.juren233.hyperlyricsenhanced.root.mediacard.island.background.IslandExpandedBackgroundTarget
import com.juren233.hyperlyricsenhanced.root.mediacard.island.background.IslandExpandedMediaBackgroundApi
import com.juren233.hyperlyricsenhanced.root.mediacard.island.background.IslandExpandedMediaBackgroundController
import com.juren233.hyperlyricsenhanced.root.mediacard.island.background.IslandExpandedMediaBackgroundHost
import com.juren233.hyperlyricsenhanced.root.mediacard.notification.background.NotificationMediaColorConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.root.utils.MediaCardDiagnosticLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object IslandExpandedMediaAmbientFlowHooker {
    internal const val TAG = "IslandExpandedMediaAmbientFlowHooker"
    internal const val BINDER_CLASS =
        "com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaViewBinderImpl"
    internal const val MUSIC_BG_VIEW_CLASS = "com.mi.widget.view.MusicBgView"
    internal const val SEEK_BAR_HEAD_ALPHA_LISTENER_CLASS =
        "miuix.miuixbasewidget.widget.HyperProgressSeekBar\$1"
    internal const val BASE_CONTENT_VIEW_CLASS =
        "miui.systemui.dynamicisland.window.content.DynamicIslandBaseContentView"
    internal const val FAKE_CONTENT_VIEW_CLASS =
        "miui.systemui.dynamicisland.window.content.DynamicIslandContentFakeView"
    internal const val EXPANDED_VIEW_CLASS =
        "miui.systemui.dynamicisland.view.DynamicIslandExpandedView"
    internal const val MI_BLUR_COMPAT_CLASS = "miui.systemui.util.MiBlurCompat"
    internal const val ORIGINAL_ALPHA_TAG_KEY = 0x7e48594c
    internal const val CUSTOM_FLOW_VIEW_TAG = "hyperlyricsenhanced.island_expanded_media_custom_flow"
    internal const val CUSTOM_FAKE_FLOW_VIEW_TAG =
        "hyperlyricsenhanced.island_expanded_media_custom_fake_flow"

    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    internal val binderStates = Collections.synchronizedMap(WeakHashMap<Any, BinderState>())
    internal val activeBinders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    )
    private val firstArtworkCallbacks = Collections.synchronizedMap(
        WeakHashMap<Any, MutableSet<String>>()
    )
    internal val themeStates = Collections.synchronizedMap(WeakHashMap<View, ViewThemeState>())
    internal val lightBackgroundModes = Collections.synchronizedMap(
        WeakHashMap<View, IslandExpandedMediaLightBackgroundMode>()
    )
    internal val fakeFlowStates = Collections.synchronizedMap(
        WeakHashMap<ViewGroup, FakeFlowState>()
    )
    internal val seekBarThemeStates = Collections.synchronizedMap(
        WeakHashMap<View, SeekBarThemeState>()
    )
    internal val restoringNativeForeground = ThreadLocal<Boolean>()
    private val bindingBinder = ThreadLocal<Any?>()
    internal val colorExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "HyperLyrics Enhanced-IslandMediaColor").apply { isDaemon = true }
    }
    // 封面图标（setFixIcon）先于 binder 的 artWorkDrawable 刷新到达（真机日志：图标 T+0、
    // binder 滞后 0.6~0.7s，且滞后那跳并不保证出现）。流光封面色必须以图标更新为触发源，
    // 否则切歌后流光停留在旧色直到收起重展。
    internal val iconColorRequest = AtomicInteger()
    @Volatile
    internal var iconColorToken: String? = null
    @Volatile
    internal var iconColorPalette: MediaAmbientFlowPalette? = null

    @Volatile
    private var module: XposedModule? = null

    @Volatile
    internal var nativeApi: NativeApi? = null

    internal val prefs: SharedPreferences?
        get() = (module as? HookEntry)?.prefs

    fun initialize(xposedModule: XposedModule) {
        module = xposedModule
        IslandExpandedMediaBackgroundController.initialize(xposedModule)
    }

    fun hook(xposedModule: XposedModule, classLoader: ClassLoader) {
        initialize(xposedModule)
        if (!hookedClassLoaders.add(classLoader)) return

        val api = resolveApi(classLoader) ?: run {
            hookedClassLoaders.remove(classLoader)
            HookLogger.w(TAG, "跳过展开态媒体流光 Hook: reason=native_api_unavailable")
            return
        }

        val installedHandles = mutableListOf<HookHandle>()
        api.hookMethods.forEach { method ->
            runCatching {
                xposedModule.deoptimize(method)
                val hooker = hookerFor(method)
                    ?: error("No hooker for ${method.declaringClass.name}.${method.name}")
                installedHandles += xposedModule.hook(method).intercept(hooker)
            }.onFailure { error ->
                    HookLogger.e(
                        TAG,
                        "安装展开态媒体 Hook 失败: method=${method.declaringClass.simpleName}.${method.name}",
                        error
                    )
            }
        }

        if (installedHandles.size != api.hookMethods.size) {
            installedHandles.forEach(HookHandle::unhook)
            hookedClassLoaders.remove(classLoader)
            HookLogger.w(TAG, "展开态媒体流光 Hook 不完整，已移除全部 Hook")
        } else {
            HookLogger.i(TAG, "展开态媒体流光 Hook 已初始化: methods=${installedHandles.size}")
        }
    }

    fun isTargetMethod(method: Method): Boolean {
        return when (method.declaringClass.name) {
            BINDER_CLASS -> when (method.name) {
                "attach" -> method.parameterCount == 2
                "bindMediaData" -> method.parameterCount == 1
                "detach" -> method.parameterCount == 0
                IslandExpandedMediaBinderMethodProfile.LEGACY_ARTWORK_METHOD,
                IslandExpandedMediaBinderMethodProfile.OS4_ARTWORK_METHOD ->
                    IslandExpandedMediaBinderMethodProfile.isArtworkUpdate(method)
                "setSeamless" -> method.parameterCount == 2
                "updateForegroundColors" -> method.parameterCount == 1
                else -> false
            }

            MUSIC_BG_VIEW_CLASS ->
                (method.name == "start" || method.name == "resume") &&
                    method.parameterCount == 0

            SEEK_BAR_HEAD_ALPHA_LISTENER_CLASS ->
                method.name == "onUpdate" && method.parameterCount == 2

            else -> false
        }
    }

    fun hookerFor(executable: Executable): Hooker? {
        val method = executable as? Method ?: return null
        if (!isTargetMethod(method)) return null
        resolveApi(method.declaringClass.classLoader) ?: return null
        return when (method.declaringClass.name) {
            BINDER_CLASS -> when (method.name) {
                "attach" -> BinderHook(Action.ATTACH)
                "bindMediaData" -> BinderHook(Action.BIND)
                "detach" -> BinderHook(Action.DETACH)
                IslandExpandedMediaBinderMethodProfile.LEGACY_ARTWORK_METHOD,
                IslandExpandedMediaBinderMethodProfile.OS4_ARTWORK_METHOD ->
                    BinderHook(Action.ALBUM, method.name)
                "setSeamless" -> BinderHook(Action.SEAMLESS)
                "updateForegroundColors" -> ForegroundColorsHook()
                else -> null
            }

            MUSIC_BG_VIEW_CLASS -> PlaybackStartHook()
            SEEK_BAR_HEAD_ALPHA_LISTENER_CLASS -> HeadGlowUpdateHook()
            else -> null
        }
    }

    fun releaseAll() {
        IslandExpandedMediaBackgroundController.releaseAll()
        val binders = synchronized(activeBinders) { activeBinders.toList() }
        val cleanup = Runnable {
            binders.forEach { binder ->
                restoreCardTheme(binder)
                restoreMediaElements(binder)
                removeCustomFlow(binder)
            }
            val api = nativeApi
            if (api != null) {
                val trackedViews = synchronized(themeStates) { themeStates.keys.toList() }
                trackedViews.forEach { view -> restoreTrackedTheme(view, api) }
            }
            IslandExpandedMediaElementController.cleanup()
            synchronized(fakeFlowStates) { fakeFlowStates.keys.toList() }
                .forEach(::removeCustomFakeFlow)
            activeBinders.clear()
            themeStates.clear()
            lightBackgroundModes.clear()
            fakeFlowStates.clear()
            seekBarThemeStates.clear()
            firstArtworkCallbacks.clear()
        }
        releaseAmbientFlowResources(
            dispatchCleanup = { task ->
                if (Looper.myLooper() == Looper.getMainLooper()) task.run()
                else Handler(Looper.getMainLooper()).post(task)
            },
            invalidateRequests = {
                synchronized(binderStates) {
                    binderStates.values.forEach { it.request.incrementAndGet() }
                }
            },
            cleanupViews = { cleanup.run() },
            clearStates = { binderStates.clear() },
            shutdownWorker = { colorExecutor.shutdown() },
        )
    }

    private enum class Action { ATTACH, BIND, DETACH, ALBUM, SEAMLESS }

    private class BinderHook(
        private val action: Action,
        private val methodName: String? = null,
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val binder = chain.thisObject ?: return chain.proceed()
            MediaCardDiagnosticLogger.log(
                stage = "island_expanded_media",
                event = "binder_callback_begin",
                details = "action=${action.name.lowercase()},method=${MediaCardDiagnosticLogger.sanitize(methodName)},binder=${MediaCardDiagnosticLogger.identity(binder)},nested=${bindingBinder.get() === binder},enabled=${SystemUiEnhancementGate.isEnabled()}",
            )
            if (!SystemUiEnhancementGate.isEnabled()) {
                if (action == Action.DETACH) cleanupBinder(binder)
                val result = chain.proceed()
                if (action == Action.ATTACH || action == Action.BIND) {
                    activeBinders.add(binder)
                }
                MediaCardDiagnosticLogger.log(
                    stage = "island_expanded_media",
                    event = "binder_callback_complete",
                    details = "action=${action.name.lowercase()},binder=${MediaCardDiagnosticLogger.identity(binder)},enabled=false",
                )
                return result
            }
            if (action == Action.DETACH) cleanupBinder(binder)
            val nestedInBind = action != Action.BIND && bindingBinder.get() === binder
            val previousBinding = if (action == Action.BIND) bindingBinder.get() else null
            if (action == Action.BIND) bindingBinder.set(binder)
            val result = try {
                chain.proceed()
            } finally {
                if (action == Action.BIND) {
                    if (previousBinding == null) bindingBinder.remove()
                    else bindingBinder.set(previousBinding)
                }
            }
            logFirstArtworkCallback(binder)
            if (BuildConfig.DEBUG && action != Action.SEAMLESS && action != Action.DETACH) {
                HookLogger.d(
                    TAG,
                    "流光事件: action=${action.name} method=${methodName ?: "-"} nested=$nestedInBind " +
                        "binder=${System.identityHashCode(binder)}"
                )
            }
            if (nestedInBind && (action == Action.ALBUM || action == Action.SEAMLESS)) {
                return result
            }
            runCatching {
                when (action) {
                    Action.ATTACH -> {
                        activeBinders.add(binder)
                        applyAppearance(binder, allowCoverColor = false)
                        applyMediaElements(binder)
                    }
                    Action.BIND -> {
                        activeBinders.add(binder)
                        applyAppearance(binder, allowCoverColor = true)
                        applyMediaElements(binder)
                    }
                    Action.ALBUM -> {
                        applyAppearance(binder, allowCoverColor = true)
                        applyMediaElements(binder)
                    }
                    Action.SEAMLESS -> applyMediaElements(binder)
                    Action.DETACH -> Unit
                }
                if (action != Action.DETACH) {
                    IslandAlbumCoverStyleHooker.onPlaybackStateChanged(
                        requireNotNull(nativeApi).isPlaying(binder)
                    )
                }
            }.onFailure { error ->
                MediaCardDiagnosticLogger.log(
                    stage = "island_expanded_media",
                    event = "binder_apply_failed",
                    reason = "exception",
                    details = "action=${action.name.lowercase()},binder=${MediaCardDiagnosticLogger.identity(binder)},error=${MediaCardDiagnosticLogger.sanitize(error.message)}",
                )
                HookLogger.e(TAG, "应用展开态媒体流光模式失败", error)
            }
            MediaCardDiagnosticLogger.log(
                stage = "island_expanded_media",
                event = "binder_callback_complete",
                details = "action=${action.name.lowercase()},binder=${MediaCardDiagnosticLogger.identity(binder)},coverStyle=${currentCoverStyle()},hideCover=${hideCoverSource()},hideDevice=${hideDeviceSwitch()},playing=${runCatching { nativeApi?.isPlaying(binder) }.getOrNull()},activeBinders=${synchronized(activeBinders) { activeBinders.size }}",
            )
            return result
        }

        private fun logFirstArtworkCallback(binder: Any) {
            if (BuildConfig.DEBUG && action == Action.ALBUM) {
                val callbackName = methodName ?: "artwork"
                val shouldLog = synchronized(firstArtworkCallbacks) {
                    val methods = firstArtworkCallbacks.getOrPut(binder) { mutableSetOf() }
                    methods.add(callbackName)
                }
                if (shouldLog) {
                    HookLogger.i(
                        TAG,
                        "展开态媒体封面刷新首次回调: method=$callbackName " +
                            "binder=${binder.javaClass.name}@${System.identityHashCode(binder)}",
                    )
                }
            }
        }
    }

    private class PlaybackStartHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (!SystemUiEnhancementGate.isEnabled()) return chain.proceed()
            val view = chain.thisObject as? View ?: return chain.proceed()
            if ((IslandExpandedMediaBackgroundController.isActive() ||
                    currentMode() == RootConstants.ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE_DISABLED ||
                    isCustomMode(currentMode())) &&
                isExpandedIslandView(view)
            ) {
                return null
            }
            return chain.proceed()
        }
    }

    private class ForegroundColorsHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (!SystemUiEnhancementGate.isEnabled()) return chain.proceed()
            if (restoringNativeForeground.get() == true) return chain.proceed()
            val binder = chain.thisObject ?: return chain.proceed()
            val holder = chain.args.firstOrNull() ?: return chain.proceed()
            if (IslandExpandedMediaBackgroundController.isActive()) {
                val api = nativeApi ?: return chain.proceed()
                return runCatching {
                    if (!IslandExpandedMediaBackgroundController.applyForeground(
                            binder,
                            holder,
                            api
                        )
                    ) {
                        IslandExpandedMediaBackgroundController.apply(binder, api)
                    }
                    null
                }.getOrElse { error ->
                    HookLogger.e(TAG, "保持展开态媒体前景色失败", error)
                    chain.proceed()
                }
            }
            if (!shouldUseLightTheme(binder)) return chain.proceed()

            val api = nativeApi ?: return chain.proceed()
            return try {
                val lightContext = api.getContext(binder)
                    .withNightMode(Configuration.UI_MODE_NIGHT_NO)
                applyLightForeground(api, holder, CardColors.from(lightContext))
                null
            } catch (error: Throwable) {
                HookLogger.e(TAG, "应用原生浅色前景失败", error)
                chain.proceed()
            }
        }
    }

    private class HeadGlowUpdateHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            if (!SystemUiEnhancementGate.isEnabled()) return result
            val api = nativeApi ?: return result
            val listener = chain.thisObject ?: return result
            val seekBar = api.getHeadAlphaListenerSeekBar(listener)
            if (seekBarThemeStates[seekBar]?.suppressHeadGlow == true) {
                api.setSeekBarHeadGlowAlpha(seekBar, 0f)
            }
            return result
        }
    }

    internal class BackgroundUpdateHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (!SystemUiEnhancementGate.isEnabled()) return chain.proceed()
            val view = chain.args.firstOrNull() as? View
            if (
                view != null &&
                IslandExpandedMediaBackgroundController.shouldSkipNativeBackgroundUpdate(view)
            ) {
                return null
            }
            val result = chain.proceed()
            if (view != null) {
                runCatching { reapplyTrackedLightTheme(view) }.onFailure { error ->
                    HookLogger.e(TAG, "重放展开态浅色背景失败", error)
                }
            }
            return result
        }
    }

    internal class ExpandedVisibilityHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            if (!SystemUiEnhancementGate.isEnabled()) return result
            val visibility = (chain.args.getOrNull(1) as? Number)?.toInt()
            val view = chain.thisObject as? View
            if (visibility == View.VISIBLE && view?.isShown == true) {
                IslandExpandedMediaBackgroundController.onExpandedViewShown(view)
            }
            return result
        }
    }

    internal class ClosingToExpandedHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            if (!SystemUiEnhancementGate.isEnabled()) return result
            if (chain.args.getOrNull(1) == true) {
                (chain.thisObject as? ViewGroup)?.let(::restoreFakeTransitionTheme)
            }
            return result
        }
    }

    internal class MiniBarUpdateHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            if (!SystemUiEnhancementGate.isEnabled()) return result
            runCatching {
                val contentView = chain.thisObject as? View ?: return@runCatching
                applyContentViewTheme(contentView)
            }.onFailure { error ->
                HookLogger.e(TAG, "恢复展开态 MiniBar 主题失败", error)
            }
            return result
        }
    }


    private fun resolveApi(classLoader: ClassLoader?): NativeApi? {
        nativeApi?.let { return it }
        classLoader ?: return null
        return runCatching { NativeApi.create(classLoader) }
            .onSuccess { nativeApi = it }
            .onFailure { HookLogger.w(TAG, "展开态媒体原生接口不可用: reason=${it.message}") }
            .getOrNull()
    }

}
