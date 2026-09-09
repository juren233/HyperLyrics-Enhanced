/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

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

internal data class ViewSizeSnapshot(
    val view: View,
    val originalLayoutHeight: Int,
    val originalMinimumHeight: Int,
    var baseHeight: Int
)

internal class BackgroundConstraints private constructor(
    private val view: View,
    private val originalTopMargin: Int,
    private val originalBottomMargin: Int,
    private val verticalBiasField: Field?,
    private val originalVerticalBias: Float?,
) {
    var isPinned: Boolean = false
        private set
    fun pinToParentTop(player: View): Boolean {
        if (isPinned) return true
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return false
        val parent = view.parent as? View ?: return false
        if (parent !== player) return false
        verticalBiasField?.setFloat(params, 0f)
        params.topMargin = 0
        params.bottomMargin = 0
        view.layoutParams = params
        isPinned = true
        if (BuildConfig.DEBUG) {
            HookLogger.i(
                NotificationMediaAodLyricHooker.TAG,
                "full AOD mediaBg 已锚定父容器顶部: " +
                    "backgroundTop=${view.top}, backgroundBottom=${view.bottom}, " +
                    "backgroundTransY=${view.translationY}",
            )
        }
        return true
    }

    fun restore() {
        if (!isPinned) return
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        verticalBiasField?.let { field ->
            originalVerticalBias?.let { value -> field.setFloat(params, value) }
        }
        params.topMargin = originalTopMargin
        params.bottomMargin = originalBottomMargin
        view.layoutParams = params
        isPinned = false
    }

    fun snapshot(): String = runCatching {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams
            ?: return@runCatching "bgConstraintsUnavailable"
        "bgPinned=$isPinned, " +
            "bgTopMargin=${params.topMargin}, bgBottomMargin=${params.bottomMargin}, " +
            "bgVerticalBias=${verticalBiasField?.getFloat(params)}"
    }.getOrDefault("bgConstraintsUnavailable")

    companion object {
        fun create(view: View): BackgroundConstraints? {
            if (view.parent == null) return null
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return null
            val biasField = runCatching {
                params.javaClass.getField("verticalBias").apply { isAccessible = true }
            }.getOrNull() ?: return null
            val bias = runCatching { biasField.getFloat(params) }.getOrNull() ?: return null
            return BackgroundConstraints(
                view = view,
                originalTopMargin = params.topMargin,
                originalBottomMargin = params.bottomMargin,
                verticalBiasField = biasField,
                originalVerticalBias = bias,
            )
        }
    }
}

internal class LyricOverlay(
    val root: LinearLayout,
    val main: TextView,
    val translation: TextView,
    val backing: TextView,
    val backingTranslation: TextView,
    val overlappingMain: TextView,
    val overlappingTranslation: TextView,
    val overlappingBacking: TextView,
    val overlappingBackingTranslation: TextView,
    val next: TextView,
    val artist: View,
    val album: View,
    val player: ViewGroup,
    val playerSize: ViewSizeSnapshot,
    val backgroundSize: ViewSizeSnapshot,
    val backgroundConstraints: BackgroundConstraints?,
    val headerHeightController: MediaHeaderHeightController?,
    val drawWakeLock: PowerManager.WakeLock,
    var appliedCardHeight: Int? = null,
    var appliedTextStyle: AodTextStyleConfig? = null,
    var appliedMainAlignment: AodLyricAlignment? = null,
    var appliedBackingAlignment: AodLyricAlignment? = null,
    var appliedOverlappingAlignment: AodLyricAlignment? = null,
    var appliedOverlappingBackingAlignment: AodLyricAlignment? = null,
    var appliedNextAlignment: AodLyricAlignment? = null,
    var lastHeightDriftKey: String? = null,
    var fullAodActive: Boolean = false,
    var heightAnimator: ValueAnimator? = null,
) {
    val lifetime = AodOverlayLifetime()
}

internal class MediaHeaderHeightController private constructor(
    val view: View,
    private val lockScreenHeightField: Field,
    private val setAnimateHeightMethod: Method,
    private val setActualHeightMethod: Method,
    val originalHeight: Int,
    private val originalMinimumHeight: Int
) {
    fun currentHeight(): Int = runCatching {
        view.height.takeIf { it > 0 }
            ?: lockScreenHeightField.getInt(view)
    }.getOrDefault(0)

    fun applyFinalHeight(height: Int) {
        if (height <= 0) return
        lockScreenHeightField.setInt(view, height)
        setActualHeightMethod.invoke(view, height, false)
        if (view.minimumHeight != height) {
            view.minimumHeight = height
        }
        view.requestLayout()
        (view.parent as? View)?.requestLayout()
    }

    fun restoreHeight() {
        lockScreenHeightField.setInt(view, originalHeight)
        setAnimateHeightMethod.invoke(view, 0)
        setActualHeightMethod.invoke(view, originalHeight, false)
        if (view.minimumHeight != originalMinimumHeight) {
            view.minimumHeight = originalMinimumHeight
        }
        view.requestLayout()
        (view.parent as? View)?.requestLayout()
    }

    fun actualHeightSnapshot(): String = runCatching {
        "headerH=${view.height}, headerM=${view.measuredHeight}, " +
            "headerField=${lockScreenHeightField.getInt(view)}, " +
            "headerLP=${view.layoutParams?.height}, headerMin=${view.minimumHeight}, " +
            "headerTop=${view.top}, headerTransY=${view.translationY}"
    }.getOrDefault("headerUnavailable")

    companion object {
        fun create(view: View?): MediaHeaderHeightController? {
            if (view?.javaClass?.name != NotificationMediaAodLyricHooker.MEDIA_HEADER_VIEW_CLASS) return null
            return runCatching {
                val viewClass = view.javaClass
                val lockScreenHeightField = viewClass
                    .getDeclaredField("mediaLockScreenHeight")
                    .apply { isAccessible = true }
                val setAnimateHeightMethod = viewClass
                    .getDeclaredMethod(
                        "setAnimateHeight",
                        Int::class.javaPrimitiveType
                    )
                    .apply { isAccessible = true }
                val setActualHeightMethod = viewClass
                    .getMethod(
                        "setActualHeight",
                        Int::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType
                    )
                    .apply { isAccessible = true }
                MediaHeaderHeightController(
                    view = view,
                    lockScreenHeightField = lockScreenHeightField,
                    setAnimateHeightMethod = setAnimateHeightMethod,
                    setActualHeightMethod = setActualHeightMethod,
                    originalHeight = lockScreenHeightField.getInt(view),
                    originalMinimumHeight = view.minimumHeight
                )
            }.onFailure {
                HookLogger.e(NotificationMediaAodLyricHooker.TAG, "初始化锁屏媒体通知外层动态高度接口失败", it)
            }.getOrNull()
        }
    }
}

internal class AodPluginOverlay(
    val root: LinearLayout,
    val songInfoRow: LinearLayout,
    val sourceIcon: ImageView,
    val songInfo: TextView,
    val main: TextView,
    val translation: TextView,
    val backing: TextView,
    val backingTranslation: TextView,
    val overlappingMain: TextView,
    val overlappingTranslation: TextView,
    val overlappingBacking: TextView,
    val overlappingBackingTranslation: TextView,
    val next: TextView,
    val parent: FrameLayout,
    val aodRoot: FrameLayout,
    val anchor: View,
    val drawWakeLock: PowerManager.WakeLock,
    var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null,
    var appliedHeight: Int? = null,
    var appliedTextStyle: AodTextStyleConfig? = null,
    var appliedSongInfoPackage: String? = null,
    var appliedSongInfoTextSize: Int? = null,
    var appliedSongInfoShowsIcon: Boolean? = null,
    var appliedMainAlignment: AodLyricAlignment? = null,
    var appliedBackingAlignment: AodLyricAlignment? = null,
    var appliedOverlappingAlignment: AodLyricAlignment? = null,
    var appliedOverlappingBackingAlignment: AodLyricAlignment? = null,
    var appliedNextAlignment: AodLyricAlignment? = null,
)

internal data class AodPluginState(
    var attached: Boolean = false,
    var playing: Boolean = false,
    var overlay: AodPluginOverlay? = null,
    var initialRefreshGeneration: Int = 0
)

internal data class ControllerState(
    var holder: Any? = null,
    var mediaData: Any? = null,
    var fullAod: Boolean = false,
    var aodActive: Boolean = false,
    var playing: Boolean = false,
    var overlay: LyricOverlay? = null,
    val actionVisibilities: MutableMap<View, Int> = LinkedHashMap()
)

internal class AodPluginApi private constructor(
    val hookMethods: List<Method>,
    private val tableModeContainerField: Field,
    private val notificationIconsField: Field,
    private val isAodShownMethod: Method
) {
    fun getTableModeContainer(aodView: Any): View? =
        tableModeContainerField.get(aodView) as? View

    fun getNotificationIcons(aodView: Any): View? =
        notificationIconsField.get(aodView) as? View

    fun isAodShown(aodView: Any): Boolean =
        isAodShownMethod.invoke(aodView) == true

    companion object {
        fun create(classLoader: ClassLoader): AodPluginApi {
            val aodViewClass = classLoader.loadClass(NotificationMediaAodLyricHooker.AOD_PLUGIN_VIEW_CLASS)
            val makeNormalPanel = aodViewClass.getDeclaredMethod("makeNormalPanel")
                .apply { isAccessible = true }
            val onAttached = aodViewClass.getDeclaredMethod("onAttachedToWindow")
                .apply { isAccessible = true }
            val onDetached = aodViewClass.getDeclaredMethod("onDetachedFromWindow")
                .apply { isAccessible = true }
            val onPositionTimer = aodViewClass.getDeclaredMethod("onUpdatePositionTimer")
                .apply { isAccessible = true }
            val onContentLayoutChange = aodViewClass.getDeclaredMethod(
                "onAodContentLayoutChange",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            ).apply { isAccessible = true }
            return AodPluginApi(
                hookMethods = listOf(
                    makeNormalPanel,
                    onAttached,
                    onDetached,
                    onPositionTimer,
                    onContentLayoutChange
                ),
                tableModeContainerField = aodViewClass
                    .getDeclaredField("mTableModeContainer")
                    .apply { isAccessible = true },
                notificationIconsField = aodViewClass
                    .getDeclaredField("mNotificationIcons")
                    .apply { isAccessible = true },
                isAodShownMethod = aodViewClass.getDeclaredMethod("isAodShown")
                    .apply { isAccessible = true }
            )
        }
    }
}

internal class NativeApi private constructor(
    val hookMethods: List<Method>,
    private val holderField: Field,
    private val mediaDataField: Field,
    private val playerField: Field,
    private val mediaBackgroundField: Field?,
    private val albumViewField: Field?,
    private val titleTextField: Field,
    private val artistTextField: Field,
    private val actionFields: List<Field>,
    private val mediaControllerField: Field,
    private val mediaDataIsPlayingField: Field,
    private val mediaDataPackageNameField: Field
) {
    fun getHolder(controller: Any): Any? = holderField.get(controller)
    fun getMediaData(controller: Any): Any? = mediaDataField.get(controller)
    fun getPlayer(holder: Any): ViewGroup = playerField.get(holder) as ViewGroup
    fun getMediaBackground(holder: Any): View? =
        runCatching { mediaBackgroundField?.get(holder) as? View }.getOrNull()
    fun getAlbumView(holder: Any): View? =
        runCatching { albumViewField?.get(holder) as? View }.getOrNull()
    fun getTitleText(holder: Any): TextView = titleTextField.get(holder) as TextView
    fun getArtistText(holder: Any): TextView = artistTextField.get(holder) as TextView
    fun getActions(holder: Any): List<View> = actionFields.map { it.get(holder) as View }
    fun isPlaying(mediaData: Any?): Boolean =
        mediaData?.let { mediaDataIsPlayingField.get(it) == true } ?: false
    fun isControllerPlaying(controller: Any): Boolean? {
        val playbackState = (mediaControllerField.get(controller) as? MediaController)
            ?.playbackState ?: return null
        return playbackState.state == PlaybackState.STATE_PLAYING
    }
    fun packageName(mediaData: Any?): String? =
        mediaData?.let { mediaDataPackageNameField.get(it) as? String }
    fun currentPlaybackPosition(controller: Any): Long? {
        val playbackState = (mediaControllerField.get(controller) as? MediaController)
            ?.playbackState ?: return null
        var position = playbackState.position.coerceAtLeast(0L)
        if (
            playbackState.state == PlaybackState.STATE_PLAYING &&
            playbackState.lastPositionUpdateTime > 0L
        ) {
            val elapsed = (SystemClock.elapsedRealtime() - playbackState.lastPositionUpdateTime)
                .coerceAtLeast(0L)
            position += (elapsed * playbackState.playbackSpeed).toLong()
        }
        return position.coerceAtLeast(0L)
    }

    companion object {
        fun create(classLoader: ClassLoader): NativeApi {
            val controllerClass = classLoader.loadClass(NotificationMediaAodLyricHooker.VIEW_CONTROLLER_CLASS)
            val holderClass = classLoader.loadClass(NotificationMediaAodLyricHooker.HOLDER_CLASS)
            val mediaDataClass = classLoader.loadClass(NotificationMediaAodLyricHooker.MEDIA_DATA_CLASS)
            val attach = controllerClass.getDeclaredMethod("attach", holderClass).accessible()
            val bind = controllerClass.getDeclaredMethod("bindMediaData", mediaDataClass).accessible()
            val detach = controllerClass.getDeclaredMethod("detach").accessible()
            val fullAod = controllerClass.getDeclaredMethod(
                "onFullAodStateChanged",
                Boolean::class.javaPrimitiveType
            ).accessible()
            return NativeApi(
                hookMethods = listOf(attach, bind, detach, fullAod),
                holderField = controllerClass.getDeclaredField("holder").accessible(),
                mediaDataField = controllerClass.getDeclaredField("mediaData").accessible(),
                playerField = holderClass.getDeclaredField("player").accessible(),
                mediaBackgroundField = holderClass.declaredFields
                    .firstOrNull { it.name == "mediaBg" }
                    ?.accessible(),
                albumViewField = holderClass.declaredFields
                    .firstOrNull { it.name == "albumView" }
                    ?.accessible(),
                titleTextField = holderClass.getDeclaredField("titleText").accessible(),
                artistTextField = holderClass.getDeclaredField("artistText").accessible(),
                actionFields = (0..4).map { index ->
                    holderClass.getDeclaredField("action$index").accessible()
                },
                mediaControllerField = controllerClass.getDeclaredField(
                    "mediaController"
                ).accessible(),
                mediaDataIsPlayingField = mediaDataClass.getDeclaredField("isPlaying").accessible(),
                mediaDataPackageNameField = mediaDataClass.getDeclaredField("packageName").accessible()
            )
        }

        private fun <T : java.lang.reflect.AccessibleObject> T.accessible(): T = apply {
            isAccessible = true
        }
    }
}

internal class DozeRefreshApi private constructor(
    val hostConstructors: List<Constructor<*>>,
    private val tickRunnableFactory: DozeTickRunnableFactory
) {
    private var hostReference = WeakReference<Any>(null)
    private var didLogFirstTick = false

    fun captureHost(host: Any) {
        hostReference = WeakReference(host)
        HookLogger.i(NotificationMediaAodLyricHooker.TAG, "已捕获 DozeServiceHost，AOD 原生刷新可用")
    }

    fun requestTick() {
        val host = hostReference.get()
        if (host == null) {
            HookLogger.w(NotificationMediaAodLyricHooker.TAG, "跳过 AOD 原生帧刷新: reason=host_unavailable")
            return
        }
        runCatching {
            tickRunnableFactory.create(host).run()
        }
            .onSuccess {
                if (!didLogFirstTick) {
                    didLogFirstTick = true
                    HookLogger.i(NotificationMediaAodLyricHooker.TAG, "已通过 SystemUI dozeTimeTick 提交 AOD 帧")
                }
            }
            .onFailure { HookLogger.e(NotificationMediaAodLyricHooker.TAG, "SystemUI dozeTimeTick 执行失败", it) }
    }

    companion object {
        fun create(classLoader: ClassLoader): DozeRefreshApi {
            val hostClass = classLoader.loadClass(NotificationMediaAodLyricHooker.DOZE_SERVICE_HOST_CLASS)
            val tickRunnableClass = classLoader.loadClass(NotificationMediaAodLyricHooker.DOZE_TICK_RUNNABLE_CLASS)
            return DozeRefreshApi(
                hostConstructors = hostClass.declaredConstructors
                    .onEach { it.isAccessible = true }
                    .toList(),
                tickRunnableFactory = DozeTickRunnableFactory.resolve(
                    tickRunnableClass,
                    hostClass,
                )
            )
        }
    }
}
