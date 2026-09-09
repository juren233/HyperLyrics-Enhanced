@file:Suppress("PrivateApi")

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.animation.DecelerateInterpolator
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.ClassicAodSongInfoConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.CjkLyricWhitespacePolicy
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.common.media.MediaMetadataHelper
import com.juren233.hyperlyricsenhanced.lyric.view.SongPreprocessor
import com.juren233.hyperlyricsenhanced.root.ClassicAodFocusNotificationRecovery
import com.juren233.hyperlyricsenhanced.root.HookEntry
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.utils.DisplayDiagnosticLogger
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.root.utils.MediaCardDiagnosticLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

object NotificationMediaAodLyricHooker {
    internal const val TAG = "NotificationMediaAodLyricHooker"
    internal const val VIEW_CONTROLLER_CLASS =
        "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewControllerImpl"
    internal const val HOLDER_CLASS =
        "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewHolder"
    internal const val MEDIA_HEADER_VIEW_CLASS =
        "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaHeaderView"
    internal const val MEDIA_DATA_CLASS =
        "com.android.systemui.media.controls.shared.model.MediaData"
    internal const val DOZE_SERVICE_HOST_CLASS =
        "com.android.systemui.statusbar.phone.DozeServiceHost"
    // 原始 DEX 锁定的双 $ 脱糖类名（DozeUi$$ExternalSyntheticLambda0）；
    // 单 $ 的 jadx 风格别名是错误标识，回归测试锁定全名。
    internal const val DOZE_TICK_RUNNABLE_CLASS =
        "com.android.systemui.doze.DozeUi\$\$ExternalSyntheticLambda0"
    internal const val AOD_PLUGIN_VIEW_CLASS = "com.miui.aod.AODView"
    internal const val OVERLAY_TAG = "hyperlyrics_aod_media_lyrics"
    internal const val AOD_PLUGIN_OVERLAY_TAG = "hyperlyrics_aod_notification_lyrics"
    internal const val POSITION_POLL_INTERVAL_MS = 100L
    internal const val NO_LYRIC_PREVIEW_REFRESH_INTERVAL_MS = 500L
    internal const val DRAW_WAKE_LOCK_TIMEOUT_MS = 1_000L
    internal const val AOD_PLUGIN_GAP_DP = 14f
    internal const val AOD_PLUGIN_SIDE_MARGIN_DP = 24f
    internal const val AOD_PLUGIN_MAX_WIDTH_DP = 360f
    internal const val AOD_PLUGIN_BOTTOM_SAFE_DP = 24f
    internal const val AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP = 2f
    internal const val AOD_PLUGIN_MULTI_LINE_LYRIC_GAP_DP = 10f
    internal const val AOD_PLUGIN_SONG_INFO_ICON_DP = 18f
    internal const val AOD_PLUGIN_SONG_INFO_ICON_GAP_DP = 6f
    internal val AOD_PLUGIN_INITIAL_REFRESH_DELAYS_MS = longArrayOf(0L, 100L, 300L, 700L, 1_500L)
    internal const val LOCK_SCREEN_AOD_LINE_GAP_DP = 4f
    private const val LOCK_SCREEN_AOD_GROUP_GAP_DP = 8f
    internal const val LOCK_SCREEN_AOD_TOP_GAP_DP = 17f
    internal const val LOCK_SCREEN_AOD_BOTTOM_GAP_DP = 21f
    internal const val LOCK_SCREEN_AOD_SIDE_MARGIN_EXTRA_DP = 1f
    internal const val LOCK_SCREEN_AOD_HEIGHT_ANIMATION_MS = 160L
    // Hidden PowerManager level that permits frame submission while the display is dozing.
    private const val DRAW_WAKE_LOCK_LEVEL = 0x80

    internal val mainHandler = Handler(Looper.getMainLooper())
    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private val hookedAodPluginClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    internal val states = Collections.synchronizedMap(WeakHashMap<Any, ControllerState>())
    internal val aodPluginStates = Collections.synchronizedMap(
        WeakHashMap<Any, AodPluginState>()
    )
    private val nativeApis = Collections.synchronizedMap(WeakHashMap<ClassLoader, NativeApi>())
    private val aodPluginApis = Collections.synchronizedMap(
        WeakHashMap<ClassLoader, AodPluginApi>()
    )
    private val dozeRefreshApis = Collections.synchronizedMap(
        WeakHashMap<ClassLoader, DozeRefreshApi>()
    )
    internal var positionPollScheduled = false
    internal var lastNoLyricPreviewRefreshAt = 0L
    internal val positionPollRunnable = object : Runnable {
        override fun run() {
            positionPollScheduled = false
            val controllerEntries = synchronized(states) { states.entries.toList() }
            val keepPolling = controllerEntries.any { shouldPollPosition(it.value) } ||
                hasActiveAodPluginState()
            if (keepPolling) {
                val mediaPosition = controllerEntries.firstNotNullOfOrNull { (controller, state) ->
                    if (!state.playing) return@firstNotNullOfOrNull null
                    resolveApi(controller.javaClass.classLoader)
                        ?.currentPlaybackPosition(controller)
                }
                val position = LyriconDataBridge.estimatedPosition() ?: mediaPosition
                if (position != null) {
                    val lyricChanged = LyriconDataBridge.updateEstimatedPosition(position)
                    val refreshNoLyricPreview = shouldRefreshNoLyricPreview(position)
                    if (lyricChanged || refreshNoLyricPreview) {
                        controllerEntries.forEach { (controller, state) ->
                            if (state.fullAod) safeApply(controller, state)
                        }
                        refreshAodPluginStates()
                    }
                }
            }
            if (keepPolling) schedulePositionPoll()
        }
    }

    @Volatile
    private var module: XposedModule? = null

    internal val prefs: SharedPreferences?
        get() = (module as? HookEntry)?.prefs

    fun initialize(xposedModule: XposedModule) {
        module = xposedModule
    }

    fun hook(xposedModule: XposedModule, classLoader: ClassLoader) {
        initialize(xposedModule)
        if (!hookedClassLoaders.add(classLoader)) return

        val api = resolveApi(classLoader) ?: run {
            hookedClassLoaders.remove(classLoader)
            HookLogger.w(TAG, "跳过息屏歌词 Hook: reason=native_api_unavailable")
            return
        }
        val handles = mutableListOf<HookHandle>()
        var controllerHookCount = 0
        api.hookMethods.forEach { method ->
            runCatching {
                xposedModule.deoptimize(method)
                val hooker = hookerFor(method)
                    ?: error("No hooker for ${method.declaringClass.name}.${method.name}")
                handles += xposedModule.hook(method).intercept(hooker)
                controllerHookCount++
            }.onFailure {
                HookLogger.e(TAG, "安装息屏歌词 Hook 失败: method=${method.name}", it)
            }
        }
        var dozeConstructorHookCount = 0
        val dozeRefreshApi = runCatching { DozeRefreshApi.create(classLoader) }
            .onFailure { HookLogger.e(TAG, "初始化 AOD 原生刷新接口失败", it) }
            .getOrNull()
        dozeRefreshApi?.let { refreshApi ->
            refreshApi.hostConstructors.forEach { constructor ->
                runCatching {
                    xposedModule.deoptimize(constructor)
                    handles += xposedModule.hook(constructor)
                        .intercept(DozeHostConstructorHook(refreshApi))
                    dozeConstructorHookCount++
                }.onFailure {
                    HookLogger.e(TAG, "安装 DozeServiceHost 捕获 Hook 失败", it)
                }
            }
            dozeRefreshApis[classLoader] = refreshApi
        }
        var headerVisibilityHookCount = 0
        runCatching { classLoader.loadClass(MEDIA_HEADER_VIEW_CLASS) }
            .onSuccess { headerClass ->
                runCatching {
                    val setVisibility = headerClass.getDeclaredMethod(
                        "setVisibility",
                        Int::class.javaPrimitiveType,
                    ).apply { isAccessible = true }
                    xposedModule.deoptimize(setVisibility)
                    handles += xposedModule.hook(setVisibility)
                        .intercept(HeaderVisibilityHook())
                    headerVisibilityHookCount++
                }.onFailure {
                    HookLogger.w(TAG, "安装锁屏媒体头可见性 Hook 失败", it)
                }
            }
        if (controllerHookCount != api.hookMethods.size) {
            handles.forEach(HookHandle::unhook)
            hookedClassLoaders.remove(classLoader)
            dozeRefreshApis.remove(classLoader)
            HookLogger.w(TAG, "息屏歌词 Hook 安装不完整")
        } else {
            HookLogger.i(
                TAG,
                "息屏歌词 Hook 已初始化: methods=$controllerHookCount, " +
                    "dozeConstructors=$dozeConstructorHookCount, " +
                    "headerVisibility=$headerVisibilityHookCount"
            )
        }
    }

    fun hookAodPlugin(xposedModule: XposedModule, classLoader: ClassLoader) {
        initialize(xposedModule)
        val api = runCatching { AodPluginApi.create(classLoader) }.getOrNull() ?: return
        if (!hookedAodPluginClassLoaders.add(classLoader)) return

        var installedCount = 0
        api.hookMethods.forEach { method ->
            runCatching {
                xposedModule.deoptimize(method)
                val hooker = hookerFor(method)
                    ?: error("No AOD plugin hooker for ${method.name}")
                xposedModule.hook(method).intercept(hooker)
                installedCount++
            }.onFailure {
                HookLogger.e(TAG, "安装通知图标式息屏歌词 Hook 失败: method=${method.name}", it)
            }
        }
        if (installedCount == api.hookMethods.size) {
            aodPluginApis[classLoader] = api
            HookLogger.i(TAG, "通知图标式息屏歌词 Hook 已初始化: methods=$installedCount")
        } else {
            hookedAodPluginClassLoaders.remove(classLoader)
            HookLogger.w(TAG, "通知图标式息屏歌词 Hook 安装不完整")
        }
    }

    fun refresh() = runOnMain {
        MediaCardDiagnosticLogger.log(
            stage = "aod",
            event = "refresh_begin",
            details = "controllers=${synchronized(states) { states.size }},aodViews=${synchronized(aodPluginStates) { aodPluginStates.size }}",
        )
        synchronized(states) { states.entries.toList() }.forEach { (controller, state) ->
            runCatching { applyState(controller, state) }
                .onFailure { HookLogger.e(TAG, "刷新息屏歌词失败", it) }
        }
        refreshAodPluginStates()
        updatePositionPolling()
        MediaCardDiagnosticLogger.log(
            stage = "aod",
            event = "refresh_complete",
        )
    }

    fun onLyricChanged() {
        MediaCardDiagnosticLogger.log(
            stage = "aod",
            event = "lyric_changed_refresh_requested",
            details = "controllers=${synchronized(states) { states.size }},aodViews=${synchronized(aodPluginStates) { aodPluginStates.size }}",
        )
        refresh()
    }

    fun isTargetMethod(method: Method): Boolean {
        return when (method.declaringClass.name) {
            VIEW_CONTROLLER_CLASS -> when (method.name) {
                "attach", "bindMediaData" -> method.parameterCount == 1
                "detach" -> method.parameterCount == 0
                "onFullAodStateChanged" -> method.parameterCount == 1
                else -> false
            }
            AOD_PLUGIN_VIEW_CLASS -> when (method.name) {
                "makeNormalPanel", "onAttachedToWindow", "onDetachedFromWindow",
                "onUpdatePositionTimer" -> method.parameterCount == 0
                "onAodContentLayoutChange" -> method.parameterCount == 3
                else -> false
            }
            else -> false
        }
    }

    fun hookerFor(executable: Executable): Hooker? {
        val method = executable as? Method ?: return null
        if (!isTargetMethod(method)) return null
        return when (method.declaringClass.name) {
            VIEW_CONTROLLER_CLASS -> ControllerHook(method.name)
            AOD_PLUGIN_VIEW_CLASS -> AodPluginHook(method.name)
            else -> null
        }
    }

    fun onPlaybackStateChanged(isPlaying: Boolean) = runOnMain {
        MediaCardDiagnosticLogger.log(
            stage = "aod",
            event = "playback_state_refresh_begin",
            details = "isPlaying=$isPlaying,controllers=${synchronized(states) { states.size }},aodViews=${synchronized(aodPluginStates) { aodPluginStates.size }}",
        )
        val lyricPackage = LyriconDataBridge.currentLyricPackageName
        synchronized(states) { states.entries.toList() }.forEach { (controller, state) ->
            val api = resolveApi(controller.javaClass.classLoader) ?: return@forEach
            val mediaPackage = api.packageName(state.mediaData ?: api.getMediaData(controller))
            if (lyricPackage.isNullOrBlank() || mediaPackage.isNullOrBlank() || mediaPackage == lyricPackage) {
                state.playing = isPlaying
                safeApply(controller, state)
            }
        }
        synchronized(aodPluginStates) { aodPluginStates.entries.toList() }
            .forEach { (aodView, state) ->
                state.playing = isPlaying
                safeApplyAodPlugin(aodView, state)
            }
        updatePositionPolling()
        MediaCardDiagnosticLogger.log(
            stage = "aod",
            event = "playback_state_refresh_complete",
            details = "isPlaying=$isPlaying",
        )
    }

    fun releaseAll() = runOnMain {
        synchronized(states) { states.entries.toList() }.forEach { (_, state) ->
            restoreActions(state)
            removeOverlay(state)
        }
        states.clear()
        synchronized(aodPluginStates) { aodPluginStates.values.toList() }.forEach {
            removeAodPluginOverlay(it)
        }
        aodPluginStates.clear()
        nativeApis.clear()
        aodPluginApis.clear()
        dozeRefreshApis.clear()
        mainHandler.removeCallbacks(positionPollRunnable)
        positionPollScheduled = false
    }

    fun hideLockScreenOverlays() = runOnMain {
        synchronized(states) { states.values.toList() }.forEach { state ->
            val overlay = state.overlay ?: return@forEach
            if (overlay.root.isShown) {
                overlay.root.visibility = View.GONE
                HookLogger.i(TAG, "亮屏事件已隐藏锁屏 AOD 歌词覆盖层")
            }
        }
    }

    private class ControllerHook(private val methodName: String) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val controller = chain.thisObject ?: return chain.proceed()
            val api = resolveApi(controller.javaClass.classLoader) ?: return chain.proceed()
            val state = states.getOrPut(controller) { ControllerState() }
            MediaCardDiagnosticLogger.log(
                stage = "aod_media_controller",
                event = "callback_begin",
                details = "method=$methodName,controller=${MediaCardDiagnosticLogger.identity(controller)},state=${MediaCardDiagnosticLogger.identity(state)},arg0=${MediaCardDiagnosticLogger.identity(chain.args.firstOrNull())},fullAod=${state.fullAod},playing=${state.playing}",
            )

            if (methodName == "bindMediaData" || methodName == "detach") {
                restoreActions(state)
                if (methodName == "detach") removeOverlay(state)
            }

            val result = chain.proceed()
            when (methodName) {
                "attach" -> {
                    state.holder = chain.args.firstOrNull() ?: api.getHolder(controller)
                    state.mediaData = api.getMediaData(controller)
                    state.playing = resolvePlaying(api, controller, state.mediaData)
                    safeApply(controller, state)
                }
                "bindMediaData" -> {
                    state.holder = api.getHolder(controller)
                    state.mediaData = chain.args.firstOrNull() ?: api.getMediaData(controller)
                    state.playing = resolvePlaying(api, controller, state.mediaData)
                    safeApply(controller, state)
                    if (state.fullAod) {
                        (state.holder as? View)?.context?.let {
                            ClassicAodFocusNotificationRecovery.requestAppRefresh(
                                it,
                                "media_data_bound",
                            )
                        }
                    }
                }
                "onFullAodStateChanged" -> {
                    state.fullAod = chain.args.firstOrNull() == true
                    state.holder = api.getHolder(controller)
                    state.mediaData = api.getMediaData(controller)
                    state.playing = resolvePlaying(api, controller, state.mediaData)
                    HookLogger.i(
                        TAG,
                        "全屏息屏状态: active=${state.fullAod}, playing=${state.playing}, " +
                            "modulePlaying=${LyriconDataBridge.currentPlaybackState}"
                    )
                    safeApply(controller, state)
                    if (state.fullAod) {
                        (state.holder as? View)?.context?.let {
                            ClassicAodFocusNotificationRecovery.requestAppRefresh(
                                it,
                                "full_aod_started",
                            )
                        }
                    }
                }
                "detach" -> {
                    states.remove(controller)
                    DisplayDiagnosticLogger.clear("AOD_LOCK")
                }
            }
            updatePositionPolling()
            return result
        }
    }

    private class HeaderVisibilityHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val header = chain.thisObject as? View ?: return chain.proceed()
            val newVisibility = (chain.args.firstOrNull() as? Int) ?: View.GONE
            val becomesVisible = newVisibility == View.VISIBLE
            val result = chain.proceed()
            runOnMain {
                var hiddenCount = 0
                synchronized(states) { states.values.toList() }.forEach { state ->
                    val overlay = state.overlay ?: return@forEach
                    if (BuildConfig.DEBUG) {
                        HookLogger.i(
                            TAG,
                            "AOD_HEADER_VIS header=0x" +
                                System.identityHashCode(header).toString(16) +
                                ", new=$newVisibility, shown=${overlay.root.isShown}, " +
                                "matched=${
                                    overlay.headerHeightController?.view === header
                                }, overlayHeader=0x${
                                    overlay.headerHeightController?.view
                                        ?.let { System.identityHashCode(it).toString(16) }
                                        ?: "null"
                                }"
                        )
                    }
                    if (becomesVisible && overlay.root.isShown) {
                            overlay.root.visibility = View.GONE
                        hiddenCount++
                    }
                }
                if (becomesVisible && hiddenCount > 0) {
                    HookLogger.i(
                        TAG,
                        "锁屏媒体头恢复可见，已立即隐藏 $hiddenCount 个 AOD 歌词覆盖层",
                    )
                }
            }
            return result
        }
    }

    private class AodPluginHook(private val methodName: String) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val aodView = chain.thisObject ?: return chain.proceed()
            if (methodName == "onDetachedFromWindow") {
                aodPluginStates.remove(aodView)?.let(::removeAodPluginOverlay)
                DisplayDiagnosticLogger.clear("AOD_CLASSIC")
                updatePositionPolling()
                return chain.proceed()
            }

            val result = chain.proceed()
            val state = aodPluginStates.getOrPut(aodView) { AodPluginState() }
            when (methodName) {
                "makeNormalPanel", "onAttachedToWindow" -> {
                    state.attached = (aodView as? View)?.isAttachedToWindow == true
                    state.playing = LyriconDataBridge.currentPlaybackState == true
                    safeApplyAodPlugin(aodView, state)
                    scheduleAodPluginInitialRefresh(aodView, state)
                    (aodView as? View)?.context?.let {
                        ClassicAodFocusNotificationRecovery.requestAppRefresh(
                            it,
                            "aod_view_attached",
                        )
                    }
                }
                "onAodContentLayoutChange", "onUpdatePositionTimer" -> {
                    state.overlay?.let(::positionAodPluginOverlay)
                    safeApplyAodPlugin(aodView, state)
                }
            }
            updatePositionPolling()
            return result
        }
    }

    private class DozeHostConstructorHook(
        private val refreshApi: DozeRefreshApi
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            chain.thisObject?.let(refreshApi::captureHost)
            return result
        }
    }

    private fun removeOverlay(state: ControllerState) {
        val overlay = state.overlay ?: return
        restorePlayerHeight(overlay, state.fullAod)
        (overlay.root.parent as? ViewGroup)?.removeView(overlay.root)
        state.overlay = null
    }

    private fun removeAodPluginOverlay(state: AodPluginState) {
        val overlay = state.overlay ?: return
        overlay.preDrawListener?.let { listener ->
            if (overlay.aodRoot.viewTreeObserver.isAlive) {
                overlay.aodRoot.viewTreeObserver.removeOnPreDrawListener(listener)
            }
        }
        (overlay.root.parent as? ViewGroup)?.removeView(overlay.root)
        state.overlay = null
    }

    internal fun applyContentAlignment(
        overlay: LyricOverlay,
        content: AodLyricContent,
    ) {
        applyAlignment(
            views = listOf(overlay.main, overlay.translation),
            alignment = content.mainAlignment,
        )
        applyAlignment(
            views = listOf(overlay.backing, overlay.backingTranslation),
            alignment = content.backingAlignment,
        )
        applyAlignment(
            views = listOf(overlay.overlappingMain, overlay.overlappingTranslation),
            alignment = content.overlappingAlignment,
        )
        applyAlignment(
            views = listOf(
                overlay.overlappingBacking,
                overlay.overlappingBackingTranslation
            ),
            alignment = content.overlappingBackingAlignment,
        )
        applyAlignment(listOf(overlay.next), content.nextAlignment)
        overlay.appliedMainAlignment = content.mainAlignment
        overlay.appliedBackingAlignment = content.backingAlignment
        overlay.appliedOverlappingAlignment = content.overlappingAlignment
        overlay.appliedOverlappingBackingAlignment = content.overlappingBackingAlignment
        overlay.appliedNextAlignment = content.nextAlignment
    }

    internal fun applyContentAlignment(
        overlay: AodPluginOverlay,
        content: AodLyricContent,
    ) {
        applyAlignment(
            views = listOf(overlay.main, overlay.translation),
            alignment = content.mainAlignment,
        )
        val songInfoGravity = AodMediaLyricPolicy.embeddedSongInfoGravity(
            prefs?.let(ClassicAodSongInfoConfig::embeddedPosition)
                ?: RootConstants.DEFAULT_HOOK_CLASSIC_AOD_SONG_INFO_POSITION
        )
        overlay.songInfoRow.gravity = songInfoGravity
        overlay.songInfo.gravity = songInfoGravity
        applyAlignment(
            views = listOf(overlay.backing, overlay.backingTranslation),
            alignment = content.backingAlignment,
        )
        applyAlignment(
            views = listOf(overlay.overlappingMain, overlay.overlappingTranslation),
            alignment = content.overlappingAlignment,
        )
        applyAlignment(
            views = listOf(
                overlay.overlappingBacking,
                overlay.overlappingBackingTranslation
            ),
            alignment = content.overlappingBackingAlignment,
        )
        applyAlignment(listOf(overlay.next), content.nextAlignment)
        overlay.appliedMainAlignment = content.mainAlignment
        overlay.appliedBackingAlignment = content.backingAlignment
        overlay.appliedOverlappingAlignment = content.overlappingAlignment
        overlay.appliedOverlappingBackingAlignment = content.overlappingBackingAlignment
        overlay.appliedNextAlignment = content.nextAlignment
    }

    private fun applyAlignment(
        views: List<TextView>,
        alignment: AodLyricAlignment,
    ) {
        val gravity = when (alignment) {
            AodLyricAlignment.LEFT -> Gravity.LEFT
            AodLyricAlignment.CENTER -> Gravity.CENTER_HORIZONTAL
            AodLyricAlignment.RIGHT -> Gravity.RIGHT
        }
        views.forEach { view ->
            if (view.gravity != gravity) {
                view.gravity = gravity
            }
        }
    }

    internal fun applyLockScreenTextStyle(
        overlay: LyricOverlay,
        style: AodTextStyleConfig,
        mainTypefaceView: TextView,
        translationTypefaceView: TextView,
    ) {
        overlay.main.typeface = mainTypefaceView.typeface
        overlay.backing.typeface = mainTypefaceView.typeface
        overlay.overlappingMain.typeface = mainTypefaceView.typeface
        overlay.overlappingBacking.typeface = mainTypefaceView.typeface
        overlay.translation.typeface = translationTypefaceView.typeface
        overlay.backingTranslation.typeface = translationTypefaceView.typeface
        overlay.overlappingTranslation.typeface = translationTypefaceView.typeface
        overlay.overlappingBackingTranslation.typeface = translationTypefaceView.typeface
        overlay.main.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.mainTextSize.toFloat())
        overlay.backing.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.backingTextSize.toFloat())
        overlay.overlappingMain.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            style.mainTextSize.toFloat(),
        )
        overlay.overlappingBacking.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            style.backingTextSize.toFloat(),
        )
        overlay.translation.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            style.translationTextSize.toFloat(),
        )
        overlay.backingTranslation.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            style.translationTextSize.toFloat(),
        )
        overlay.overlappingTranslation.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            style.translationTextSize.toFloat(),
        )
        overlay.overlappingBackingTranslation.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            style.translationTextSize.toFloat(),
        )
        val nextUsesBackingStyle =
            style.nextLyricStyle == RootConstants.AOD_NEXT_LYRIC_STYLE_BACKING
        overlay.next.typeface = if (nextUsesBackingStyle) {
            mainTypefaceView.typeface
        } else {
            translationTypefaceView.typeface
        }
        overlay.next.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (nextUsesBackingStyle) {
                style.backingTextSize.toFloat()
            } else {
                style.translationTextSize.toFloat()
            },
        )
        overlay.appliedTextStyle = style
    }

    internal fun applyClassicTextStyle(
        overlay: AodPluginOverlay,
        style: AodTextStyleConfig,
    ) {
        overlay.songInfo.typeface = overlay.translation.typeface
        overlay.songInfo.setTextColor(0xCCFFFFFF.toInt())
        overlay.songInfo.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            style.translationTextSize.toFloat(),
        )
        overlay.main.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.mainTextSize.toFloat())
        overlay.backing.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.backingTextSize.toFloat())
        overlay.overlappingMain.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            style.mainTextSize.toFloat(),
        )
        overlay.overlappingBacking.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            style.backingTextSize.toFloat(),
        )
        overlay.translation.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            style.translationTextSize.toFloat(),
        )
        overlay.backingTranslation.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            style.translationTextSize.toFloat(),
        )
        overlay.overlappingTranslation.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            style.translationTextSize.toFloat(),
        )
        overlay.overlappingBackingTranslation.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            style.translationTextSize.toFloat(),
        )
        val nextUsesBackingStyle =
            style.nextLyricStyle == RootConstants.AOD_NEXT_LYRIC_STYLE_BACKING
        overlay.next.typeface = if (nextUsesBackingStyle) {
            overlay.main.typeface
        } else {
            overlay.translation.typeface
        }
        overlay.next.setTextColor(
            if (nextUsesBackingStyle) 0xFFFFFFFF.toInt() else 0xCCFFFFFF.toInt()
        )
        overlay.next.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (nextUsesBackingStyle) {
                style.backingTextSize.toFloat()
            } else {
                style.translationTextSize.toFloat()
            },
        )
        overlay.appliedTextStyle = style
    }

    internal fun resolvePlaying(api: NativeApi, controller: Any, mediaData: Any?): Boolean {
        return LyriconDataBridge.currentPlaybackState
            ?: api.isControllerPlaying(controller)
            ?: api.isPlaying(mediaData)
    }

    internal fun isEnabled(): Boolean = prefs?.getBoolean(
        RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS,
        RootConstants.DEFAULT_HOOK_ENABLE_AOD_LYRICS
    ) ?: RootConstants.DEFAULT_HOOK_ENABLE_AOD_LYRICS

    internal fun resolveApi(classLoader: ClassLoader?): NativeApi? {
        classLoader ?: return null
        nativeApis[classLoader]?.let { return it }
        return runCatching { NativeApi.create(classLoader) }
            .onSuccess { nativeApis[classLoader] = it }
            .onFailure { HookLogger.w(TAG, "息屏歌词接口不可用: reason=${it.message}") }
            .getOrNull()
    }

    internal fun resolveAodPluginApi(classLoader: ClassLoader?): AodPluginApi? {
        classLoader ?: return null
        aodPluginApis[classLoader]?.let { return it }
        return runCatching { AodPluginApi.create(classLoader) }
            .onSuccess { aodPluginApis[classLoader] = it }
            .onFailure {
                HookLogger.w(TAG, "通知图标式息屏歌词接口不可用: reason=${it.message}")
            }
            .getOrNull()
    }

    internal fun requestAodFrameRefresh(classLoader: ClassLoader?) {
        val refreshApi = classLoader?.let(dozeRefreshApis::get)
            ?: synchronized(dozeRefreshApis) { dozeRefreshApis.values.firstOrNull() }
        if (refreshApi == null) {
            MediaCardDiagnosticLogger.log(
                stage = "aod_refresh",
                event = "native_frame_refresh_skipped",
                reason = "api_unavailable",
                details = "classLoader=${MediaCardDiagnosticLogger.identity(classLoader)}",
            )
            HookLogger.w(TAG, "跳过 AOD 原生帧刷新: reason=api_unavailable")
            return
        }
        try {
            refreshApi.requestTick()
            MediaCardDiagnosticLogger.log(
                stage = "aod_refresh",
                event = "native_frame_refresh_requested",
                details = "api=${MediaCardDiagnosticLogger.identity(refreshApi)},classLoader=${MediaCardDiagnosticLogger.identity(classLoader)}",
            )
        } catch (error: Throwable) {
            MediaCardDiagnosticLogger.log(
                stage = "aod_refresh",
                event = "native_frame_refresh_failed",
                reason = "exception",
                details = "error=${MediaCardDiagnosticLogger.sanitize(error.message)}",
            )
            HookLogger.e(TAG, "请求 AOD 原生帧刷新失败", error)
            throw error
        }
    }

    private inline fun runOnMain(crossinline action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else mainHandler.post { action() }
    }


    internal fun updateLockScreenLineSpacing(overlay: LyricOverlay) {
        val density = overlay.root.resources.displayMetrics.density
        val normalMargin = (LOCK_SCREEN_AOD_LINE_GAP_DP * density).toInt()
        val groupMargin = (LOCK_SCREEN_AOD_GROUP_GAP_DP * density).toInt()
        val views = orderedLyricViews(
            overlay,
            overlay.appliedTextStyle?.swapTranslation == true,
        )
        val firstVisible = views.firstOrNull { it.visibility == View.VISIBLE }
        val firstBackingRow = views
            .filter { it === overlay.backing || it === overlay.backingTranslation }
            .firstOrNull { it.visibility == View.VISIBLE }
        val primaryVisibleCount = listOf(overlay.main, overlay.translation)
            .count { it.visibility == View.VISIBLE }
        views.forEach { view ->
            val params = view.layoutParams as? LinearLayout.LayoutParams ?: return@forEach
            val targetMargin = when {
                view === firstVisible -> 0
                view === firstBackingRow && primaryVisibleCount > 1 -> groupMargin
                else -> normalMargin
            }
            if (params.topMargin != targetMargin) {
                params.topMargin = targetMargin
                view.layoutParams = params
            }
        }
    }

    internal fun applyLyricRowOrder(
        overlay: LyricOverlay,
        swapTranslation: Boolean,
    ) {
        reorderLyricViews(
            root = overlay.root,
            orderedViews = orderedLyricViews(overlay, swapTranslation),
        )
    }

    internal fun applyLyricRowOrder(
        overlay: AodPluginOverlay,
        swapTranslation: Boolean,
    ) {
        reorderLyricViews(
            root = overlay.root,
            orderedViews = orderedLyricViews(overlay, swapTranslation),
        )
    }

    internal fun orderedLyricViews(
        overlay: LyricOverlay,
        swapTranslation: Boolean,
    ): List<TextView> = AodMediaLyricPolicy.orderedLyricRows(swapTranslation).map { row ->
        when (row) {
            AodLyricRow.MAIN -> overlay.main
            AodLyricRow.TRANSLATION -> overlay.translation
            AodLyricRow.BACKING -> overlay.backing
            AodLyricRow.BACKING_TRANSLATION -> overlay.backingTranslation
            AodLyricRow.OVERLAPPING_MAIN -> overlay.overlappingMain
            AodLyricRow.OVERLAPPING_TRANSLATION -> overlay.overlappingTranslation
            AodLyricRow.OVERLAPPING_BACKING -> overlay.overlappingBacking
            AodLyricRow.OVERLAPPING_BACKING_TRANSLATION ->
                overlay.overlappingBackingTranslation
            AodLyricRow.NEXT -> overlay.next
        }
    }

    internal fun orderedLyricViews(
        overlay: AodPluginOverlay,
        swapTranslation: Boolean,
    ): List<TextView> = AodMediaLyricPolicy.orderedLyricRows(swapTranslation).map { row ->
        when (row) {
            AodLyricRow.MAIN -> overlay.main
            AodLyricRow.TRANSLATION -> overlay.translation
            AodLyricRow.BACKING -> overlay.backing
            AodLyricRow.BACKING_TRANSLATION -> overlay.backingTranslation
            AodLyricRow.OVERLAPPING_MAIN -> overlay.overlappingMain
            AodLyricRow.OVERLAPPING_TRANSLATION -> overlay.overlappingTranslation
            AodLyricRow.OVERLAPPING_BACKING -> overlay.overlappingBacking
            AodLyricRow.OVERLAPPING_BACKING_TRANSLATION ->
                overlay.overlappingBackingTranslation
            AodLyricRow.NEXT -> overlay.next
        }
    }

    private fun reorderLyricViews(
        root: LinearLayout,
        orderedViews: List<TextView>,
    ) {
        val currentOrder = (0 until root.childCount)
            .map(root::getChildAt)
            .filter { it in orderedViews }
        if (currentOrder == orderedViews) return
        val firstLyricIndex = orderedViews
            .map(root::indexOfChild)
            .filter { it >= 0 }
            .minOrNull()
            ?: return
        orderedViews.forEach(root::removeView)
        orderedViews.forEachIndexed { index, view ->
            root.addView(view, firstLyricIndex + index)
        }
    }

}
