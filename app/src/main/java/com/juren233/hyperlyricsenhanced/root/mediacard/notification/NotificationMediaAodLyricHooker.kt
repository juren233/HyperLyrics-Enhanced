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

internal data class AodLyricContent(
    val main: String,
    val translation: String,
    val backing: String,
    val backingTranslation: String,
    val overlappingMain: String,
    val overlappingTranslation: String,
    val overlappingBacking: String,
    val overlappingBackingTranslation: String,
    val next: String,
    val mainAlignment: AodLyricAlignment,
    val backingAlignment: AodLyricAlignment,
    val overlappingAlignment: AodLyricAlignment,
    val overlappingBackingAlignment: AodLyricAlignment,
    val nextAlignment: AodLyricAlignment,
)

internal enum class AodLyricAlignment {
    LEFT,
    CENTER,
    RIGHT,
}

internal enum class AodLyricRow {
    MAIN,
    TRANSLATION,
    BACKING,
    BACKING_TRANSLATION,
    OVERLAPPING_MAIN,
    OVERLAPPING_TRANSLATION,
    OVERLAPPING_BACKING,
    OVERLAPPING_BACKING_TRANSLATION,
    NEXT,
}

internal data class AodTextStyleConfig(
    val mainTextSize: Int,
    val backingTextSize: Int,
    val translationTextSize: Int,
    val showNextLyric: Boolean,
    val nextLyricStyle: Int,
    val duetLyrics: Boolean,
    val centerNonDuetSong: Boolean,
    val centerGroupVocals: Boolean,
    val pauseStyle: Int,
    val translationDisplay: Boolean,
    val swapTranslation: Boolean,
    val nextSongPreview: Boolean,
    val nextSongPreviewPosition: Int,
)

internal data class AodHorizontalMargins(
    val left: Int,
    val right: Int,
)

internal object AodMediaLyricPolicy {
    private const val NO_LYRIC_PREVIEW_DURATION_MS = 5_000L

    fun embeddedSongInfoGravity(position: Int): Int = when (position) {
        RootConstants.AOD_SONG_INFO_POSITION_LEFT ->
            Gravity.LEFT or Gravity.CENTER_VERTICAL
        RootConstants.AOD_SONG_INFO_POSITION_RIGHT ->
            Gravity.RIGHT or Gravity.CENTER_VERTICAL
        else -> Gravity.CENTER
    }

    fun shouldShow(
        enabled: Boolean,
        fullAod: Boolean,
        playing: Boolean,
        hasLyric: Boolean,
        packageMatches: Boolean,
        pauseStyle: Int = RootConstants.DEFAULT_HOOK_AOD_PAUSE_STYLE,
    ): Boolean = enabled &&
        fullAod &&
        (playing || pauseStyle == RootConstants.AOD_PAUSE_STYLE_KEEP_LYRICS) &&
        hasLyric &&
        packageMatches

    fun shouldShowNextSongPreview(
        enabled: Boolean,
        positionMs: Long,
        durationMs: Long,
        hasActualLyrics: Boolean,
        lastLyricStartMs: Long,
    ): Boolean {
        if (!enabled || positionMs < 0L || durationMs <= 0L) return false
        val previewStartMs = if (hasActualLyrics) {
            if (lastLyricStartMs < 0L) return false
            lastLyricStartMs
        } else {
            (durationMs - NO_LYRIC_PREVIEW_DURATION_MS).coerceAtLeast(0L)
        }
        return positionMs >= previewStartMs && positionMs < durationMs
    }

    fun formatNextSongPreview(title: String, artist: String): String {
        val songInfo = listOf(title.trim(), artist.trim())
            .filter { it.isNotBlank() }
            .joinToString("-")
        return songInfo.takeIf { it.isNotBlank() }?.let { "下一首：$it" }.orEmpty()
    }

    fun sanitizeNextSongPreviewPosition(value: Int): Int =
        value.takeIf {
            it in RootConstants.AOD_NEXT_SONG_PREVIEW_POSITION_LEFT..
                RootConstants.AOD_NEXT_SONG_PREVIEW_POSITION_RIGHT
        } ?: RootConstants.DEFAULT_HOOK_AOD_NEXT_SONG_PREVIEW_POSITION

    fun nextSongPreviewAlignment(position: Int): AodLyricAlignment =
        when (sanitizeNextSongPreviewPosition(position)) {
            RootConstants.AOD_NEXT_SONG_PREVIEW_POSITION_LEFT -> AodLyricAlignment.LEFT
            RootConstants.AOD_NEXT_SONG_PREVIEW_POSITION_RIGHT -> AodLyricAlignment.RIGHT
            else -> AodLyricAlignment.CENTER
        }

    fun shouldSuppressNoLyricPlaceholder(
        isTextMode: Boolean,
        hasActualLyrics: Boolean,
    ): Boolean = !isTextMode && !hasActualLyrics

    fun shouldCompactClassicMain(lineCount: Int): Boolean = lineCount <= 1

    fun requiredCardHeight(
        nativeCardHeight: Int,
        lyricBottom: Int,
        bottomPadding: Int
    ): Int = maxOf(
        nativeCardHeight.coerceAtLeast(0),
        lyricBottom.coerceAtLeast(0) + bottomPadding.coerceAtLeast(0)
    )

    fun lockScreenTargetCardHeight(
        nativeCardHeight: Int,
        lyricBottom: Int,
        bottomPadding: Int,
    ): Int = requiredCardHeight(
        nativeCardHeight = nativeCardHeight,
        lyricBottom = lyricBottom,
        bottomPadding = bottomPadding,
    )

    fun lockScreenBackgroundTargetHeight(
        targetCardHeight: Int,
    ): Int = targetCardHeight.coerceAtLeast(0)

    fun lockScreenNativeCardHeight(
        fullAod: Boolean,
        fullAodBaseHeight: Int,
        playerBaseHeight: Int
    ): Int = if (fullAod) {
        fullAodBaseHeight.coerceAtLeast(0)
    } else {
        playerBaseHeight.coerceAtLeast(0)
    }

    fun lockScreenHeightNeedsReassert(
        appliedHeight: Int?,
        currentLayoutHeight: Int?
    ): Boolean = appliedHeight != null &&
        appliedHeight > 0 &&
        currentLayoutHeight != null &&
        currentLayoutHeight != appliedHeight

    fun lockScreenHeightNeedsRestore(
        appliedHeight: Int?,
        heightAnimationActive: Boolean,
    ): Boolean = appliedHeight != null || heightAnimationActive

    fun lockScreenLyricTop(
        anchorBottom: Int,
        topGap: Int
    ): Int = anchorBottom.coerceAtLeast(0) + topGap.coerceAtLeast(0)

    fun contentAnchorBottom(albumBottom: Int, artistBottom: Int): Int =
        maxOf(albumBottom.coerceAtLeast(0), artistBottom.coerceAtLeast(0))

    fun lockScreenHorizontalMargins(
        playerWidth: Int,
        cardLeft: Int,
        cardRight: Int,
        albumLeft: Int,
        extraInset: Int = 0,
    ): AodHorizontalMargins {
        val safePlayerWidth = playerWidth.coerceAtLeast(0)
        val safeCardLeft = cardLeft.coerceIn(0, safePlayerWidth)
        val safeCardRight = cardRight.coerceIn(safeCardLeft, safePlayerWidth)
        val cardWidth = safeCardRight - safeCardLeft
        val inset = (albumLeft - safeCardLeft + extraInset.coerceAtLeast(0))
            .coerceIn(0, cardWidth / 2)
        return AodHorizontalMargins(
            left = safeCardLeft + inset,
            right = safePlayerWidth - safeCardRight + inset,
        )
    }

    fun classicOverlayHeight(contentHeight: Int, availableHeight: Int): Int =
        minOf(
            contentHeight.coerceAtLeast(1),
            availableHeight.coerceAtLeast(1)
        )

    fun isLockScreenAodActive(
        fullAod: Boolean,
        interactive: Boolean,
        playerShown: Boolean
    ): Boolean = fullAod || (!interactive && playerShown)

    fun assembleContent(
        main: String?,
        translation: String?,
        backing: String?,
        backingTranslation: String?,
        roma: String?,
        overlappingMain: String? = null,
        overlappingTranslation: String? = null,
        overlappingBacking: String? = null,
        overlappingBackingTranslation: String? = null,
        next: String? = null,
        showNext: Boolean = false,
        mainAlignedRight: Boolean = false,
        backingAlignedRight: Boolean = mainAlignedRight,
        overlappingAlignedRight: Boolean = mainAlignedRight,
        overlappingBackingAlignedRight: Boolean = overlappingAlignedRight,
        mainGroupVocals: Boolean = false,
        nextAlignedRight: Boolean = false,
        nextGroupVocals: Boolean = false,
        duetLyrics: Boolean = RootConstants.DEFAULT_HOOK_AOD_DUET_LYRICS,
        centerNonDuetSong: Boolean = RootConstants.DEFAULT_HOOK_AOD_CENTER_NON_DUET_SONG,
        centerGroupVocals: Boolean = RootConstants.DEFAULT_HOOK_AOD_CENTER_GROUP_VOCALS,
        translationDisplay: Boolean = RootConstants.DEFAULT_HOOK_AOD_TRANSLATION_DISPLAY,
    ): AodLyricContent {
        val normalizedMain = main.normalized()
        val normalizedTranslation = translation.normalized()
            .takeIf { translationDisplay }
            .takeUnless { it == normalizedMain }
            .orEmpty()
        val normalizedBacking = backing.normalized()
            .takeUnless { it == normalizedMain }
            .orEmpty()
        val normalizedBackingTranslation = backingTranslation.normalized()
            .takeIf { translationDisplay }
            .takeIf { normalizedBacking.isNotBlank() && it != normalizedBacking }
            .orEmpty()
        val normalizedOverlappingMain = overlappingMain.normalized()
            .takeUnless { it == normalizedMain }
            .orEmpty()
        val normalizedOverlappingTranslation = overlappingTranslation.normalized()
            .takeIf { translationDisplay }
            .takeIf {
                normalizedOverlappingMain.isNotBlank() &&
                    it != normalizedOverlappingMain
            }
            .orEmpty()
        val normalizedOverlappingBacking = overlappingBacking.normalized()
            .takeIf {
                normalizedOverlappingMain.isNotBlank() &&
                    it != normalizedOverlappingMain
            }
            .orEmpty()
        val normalizedOverlappingBackingTranslation = overlappingBackingTranslation.normalized()
            .takeIf { translationDisplay }
            .takeIf {
                normalizedOverlappingBacking.isNotBlank() &&
                    it != normalizedOverlappingBacking
            }
            .orEmpty()
        val romaFallback = roma.normalized()
            .takeIf {
                normalizedTranslation.isBlank() &&
                    normalizedBacking.isBlank() &&
                    it != normalizedMain
            }
            .orEmpty()
        val hasDisplayedTranslation = normalizedTranslation.isNotBlank() ||
            normalizedBackingTranslation.isNotBlank() ||
            romaFallback.isNotBlank()
        val normalizedNext = next.normalized()
            .takeIf {
                showNext &&
                    !hasDisplayedTranslation &&
                    it != normalizedMain
            }
            .orEmpty()
        return AodLyricContent(
            main = normalizedMain,
            translation = normalizedTranslation.ifBlank { romaFallback },
            backing = normalizedBacking,
            backingTranslation = normalizedBackingTranslation,
            overlappingMain = normalizedOverlappingMain,
            overlappingTranslation = normalizedOverlappingTranslation,
            overlappingBacking = normalizedOverlappingBacking,
            overlappingBackingTranslation = normalizedOverlappingBackingTranslation,
            next = normalizedNext,
            mainAlignment = lyricAlignment(
                duetLyrics = duetLyrics,
                centerNonDuetSong = centerNonDuetSong,
                alignedRight = mainAlignedRight,
                groupVocals = mainGroupVocals,
                centerGroupVocals = centerGroupVocals,
            ),
            backingAlignment = lyricAlignment(
                duetLyrics = duetLyrics,
                centerNonDuetSong = centerNonDuetSong,
                alignedRight = backingAlignedRight,
                groupVocals = mainGroupVocals,
                centerGroupVocals = centerGroupVocals,
            ),
            overlappingAlignment = lyricAlignment(
                duetLyrics = duetLyrics,
                centerNonDuetSong = centerNonDuetSong,
                alignedRight = overlappingAlignedRight,
                groupVocals = false,
                centerGroupVocals = centerGroupVocals,
            ),
            overlappingBackingAlignment = lyricAlignment(
                duetLyrics = duetLyrics,
                centerNonDuetSong = centerNonDuetSong,
                alignedRight = overlappingBackingAlignedRight,
                groupVocals = false,
                centerGroupVocals = centerGroupVocals,
            ),
            nextAlignment = lyricAlignment(
                duetLyrics = duetLyrics,
                centerNonDuetSong = centerNonDuetSong,
                alignedRight = nextAlignedRight,
                groupVocals = nextGroupVocals,
                centerGroupVocals = centerGroupVocals,
            ),
        )
    }

    fun orderedLyricRows(swapTranslation: Boolean): List<AodLyricRow> =
        if (swapTranslation) {
            listOf(
                AodLyricRow.TRANSLATION,
                AodLyricRow.MAIN,
                AodLyricRow.BACKING_TRANSLATION,
                AodLyricRow.BACKING,
                AodLyricRow.OVERLAPPING_TRANSLATION,
                AodLyricRow.OVERLAPPING_MAIN,
                AodLyricRow.OVERLAPPING_BACKING_TRANSLATION,
                AodLyricRow.OVERLAPPING_BACKING,
                AodLyricRow.NEXT,
            )
        } else {
            listOf(
                AodLyricRow.MAIN,
                AodLyricRow.TRANSLATION,
                AodLyricRow.BACKING,
                AodLyricRow.BACKING_TRANSLATION,
                AodLyricRow.OVERLAPPING_MAIN,
                AodLyricRow.OVERLAPPING_TRANSLATION,
                AodLyricRow.OVERLAPPING_BACKING,
                AodLyricRow.OVERLAPPING_BACKING_TRANSLATION,
                AodLyricRow.NEXT,
            )
        }

    fun lyricAlignment(
        duetLyrics: Boolean,
        centerNonDuetSong: Boolean = RootConstants.DEFAULT_HOOK_AOD_CENTER_NON_DUET_SONG,
        alignedRight: Boolean,
        groupVocals: Boolean,
        centerGroupVocals: Boolean,
    ): AodLyricAlignment = when {
        !duetLyrics -> AodLyricAlignment.CENTER
        centerNonDuetSong -> AodLyricAlignment.CENTER
        groupVocals && centerGroupVocals -> AodLyricAlignment.CENTER
        alignedRight -> AodLyricAlignment.RIGHT
        else -> AodLyricAlignment.LEFT
    }

    fun sanitizeTextSize(value: Int, defaultValue: Int, min: Int, max: Int): Int =
        value.takeIf { it in min..max } ?: defaultValue

    fun sanitizeNextLyricStyle(value: Int): Int = value.takeIf {
        it == RootConstants.AOD_NEXT_LYRIC_STYLE_BACKING ||
            it == RootConstants.AOD_NEXT_LYRIC_STYLE_TRANSLATION
    } ?: RootConstants.DEFAULT_HOOK_AOD_NEXT_LYRIC_STYLE

    private fun String?.normalized(): String = this?.trim().orEmpty()
}

object NotificationMediaAodLyricHooker {
    private const val TAG = "NotificationMediaAodLyricHooker"
    private const val VIEW_CONTROLLER_CLASS =
        "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewControllerImpl"
    private const val HOLDER_CLASS =
        "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewHolder"
    private const val MEDIA_HEADER_VIEW_CLASS =
        "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaHeaderView"
    private const val MEDIA_DATA_CLASS =
        "com.android.systemui.media.controls.shared.model.MediaData"
    private const val DOZE_SERVICE_HOST_CLASS =
        "com.android.systemui.statusbar.phone.DozeServiceHost"
    private const val DOZE_TICK_RUNNABLE_CLASS =
        "com.android.systemui.doze.DozeUi\$\$ExternalSyntheticLambda0"
    private const val AOD_PLUGIN_VIEW_CLASS = "com.miui.aod.AODView"
    private const val OVERLAY_TAG = "hyperlyrics_aod_media_lyrics"
    private const val AOD_PLUGIN_OVERLAY_TAG = "hyperlyrics_aod_notification_lyrics"
    private const val POSITION_POLL_INTERVAL_MS = 100L
    private const val NO_LYRIC_PREVIEW_REFRESH_INTERVAL_MS = 500L
    private const val DRAW_WAKE_LOCK_TIMEOUT_MS = 1_000L
    private const val AOD_PLUGIN_GAP_DP = 14f
    private const val AOD_PLUGIN_SIDE_MARGIN_DP = 24f
    private const val AOD_PLUGIN_MAX_WIDTH_DP = 360f
    private const val AOD_PLUGIN_BOTTOM_SAFE_DP = 24f
    private const val AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP = 2f
    private const val AOD_PLUGIN_MULTI_LINE_LYRIC_GAP_DP = 10f
    private const val AOD_PLUGIN_SONG_INFO_ICON_DP = 18f
    private const val AOD_PLUGIN_SONG_INFO_ICON_GAP_DP = 6f
    private val AOD_PLUGIN_INITIAL_REFRESH_DELAYS_MS = longArrayOf(0L, 100L, 300L, 700L, 1_500L)
    private const val LOCK_SCREEN_AOD_LINE_GAP_DP = 4f
    private const val LOCK_SCREEN_AOD_GROUP_GAP_DP = 8f
    private const val LOCK_SCREEN_AOD_TOP_GAP_DP = 17f
    private const val LOCK_SCREEN_AOD_BOTTOM_GAP_DP = 21f
    private const val LOCK_SCREEN_AOD_SIDE_MARGIN_EXTRA_DP = 1f
    private const val LOCK_SCREEN_AOD_HEIGHT_ANIMATION_MS = 160L
    // Hidden PowerManager level that permits frame submission while the display is dozing.
    private const val DRAW_WAKE_LOCK_LEVEL = 0x80

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private val hookedAodPluginClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private val states = Collections.synchronizedMap(WeakHashMap<Any, ControllerState>())
    private val aodPluginStates = Collections.synchronizedMap(
        WeakHashMap<Any, AodPluginState>()
    )
    private val nativeApis = Collections.synchronizedMap(WeakHashMap<ClassLoader, NativeApi>())
    private val aodPluginApis = Collections.synchronizedMap(
        WeakHashMap<ClassLoader, AodPluginApi>()
    )
    private val dozeRefreshApis = Collections.synchronizedMap(
        WeakHashMap<ClassLoader, DozeRefreshApi>()
    )
    private var positionPollScheduled = false
    private var lastNoLyricPreviewRefreshAt = 0L
    private val positionPollRunnable = object : Runnable {
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

    private val prefs: SharedPreferences?
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
        synchronized(states) { states.entries.toList() }.forEach { (controller, state) ->
            runCatching { applyState(controller, state) }
                .onFailure { HookLogger.e(TAG, "刷新息屏歌词失败", it) }
        }
        refreshAodPluginStates()
        updatePositionPolling()
    }

    fun onLyricChanged() = refresh()

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

    @SuppressLint("UseKtx")
    private fun applyState(controller: Any, state: ControllerState) {
        val diagnosticKey = "AOD_LOCK/${System.identityHashCode(controller)}"
        val api = resolveApi(controller.javaClass.classLoader) ?: run {
            DisplayDiagnosticLogger.log(
                "AOD_LOCK",
                "skipped",
                "hook_unavailable",
                dedupeKey = diagnosticKey,
            )
            return
        }
        val holder = state.holder ?: api.getHolder(controller) ?: run {
            DisplayDiagnosticLogger.log(
                "AOD_LOCK",
                "skipped",
                "holder_unavailable",
                dedupeKey = diagnosticKey,
            )
            return
        }
        state.holder = holder
        state.playing = resolvePlaying(
            api,
            controller,
            state.mediaData ?: api.getMediaData(controller)
        )
        synchronizeLyricPosition(api, controller)
        val player = api.getPlayer(holder)
        val interactive = player.context.getSystemService(PowerManager::class.java).isInteractive
        state.aodActive = AodMediaLyricPolicy.isLockScreenAodActive(
            fullAod = state.fullAod,
            interactive = interactive,
            playerShown = player.isShown
        )
        val textStyle = lockScreenAodTextStyle()
        val lyricPackage = LyriconDataBridge.currentLyricPackageName
        val mediaPackage = api.packageName(state.mediaData ?: api.getMediaData(controller))
        val content = appendNextSongPreview(
            content = currentContent(textStyle),
            style = textStyle,
            context = player.context,
            packageName = mediaPackage,
        )
        val packageMatches = lyricPackage.isNullOrBlank() ||
            mediaPackage.isNullOrBlank() || lyricPackage == mediaPackage
        val hasLyric = content.main.isNotBlank() || content.next.isNotBlank()
        val enabled = isEnabled()
        val show = AodMediaLyricPolicy.shouldShow(
            enabled = enabled,
            fullAod = state.fullAod,
            playing = state.playing,
            hasLyric = hasLyric,
            packageMatches = packageMatches,
            pauseStyle = textStyle.pauseStyle,
        )
        updatePositionPolling()

        if (!show) {
            val reason = when {
                !enabled -> "feature_disabled"
                !state.fullAod && !interactive -> "waiting_full_aod"
                !state.aodActive && interactive -> "screen_interactive"
                !state.aodActive -> "player_hidden"
                !state.playing &&
                    textStyle.pauseStyle != RootConstants.AOD_PAUSE_STYLE_KEEP_LYRICS ->
                    "pause_policy"
                !hasLyric -> "no_lyrics"
                !packageMatches -> "package_mismatch"
                else -> "policy_rejected"
            }
            DisplayDiagnosticLogger.log(
                channel = "AOD_LOCK",
                result = "hidden",
                reason = reason,
                extra = "interactive=$interactive, playerShown=${player.isShown}, " +
                    "fullAod=${state.fullAod}, aodActive=${state.aodActive}, " +
                    "mediaPackage=${mediaPackage.orEmpty()}, overlay=${state.overlay != null}",
                dedupeKey = diagnosticKey,
            )
            restoreActions(state)
            state.overlay?.let { overlay ->
                overlay.root.visibility = View.GONE
                restorePlayerHeight(overlay, state.fullAod)
            }
            return
        }

        val actions = api.getActions(holder)
        if (state.actionVisibilities.isEmpty()) {
            actions.forEach { state.actionVisibilities[it] = it.visibility }
        }
        actions.forEach { action ->
            if (action.visibility == View.VISIBLE) action.visibility = View.INVISIBLE
        }

        val overlay = state.overlay ?: createOverlay(api, holder, actions).also {
            state.overlay = it
        }
        if (overlay == null) {
            DisplayDiagnosticLogger.log(
                channel = "AOD_LOCK",
                result = "skipped",
                reason = "overlay_unavailable",
                extra = "actions=${actions.size}",
                dedupeKey = diagnosticKey,
            )
            restoreActions(state)
            return
        }
        val contentChanged = overlay.main.text.toString() != content.main ||
            overlay.translation.text.toString() != content.translation ||
            overlay.backing.text.toString() != content.backing ||
            overlay.backingTranslation.text.toString() != content.backingTranslation ||
            overlay.overlappingMain.text.toString() != content.overlappingMain ||
            overlay.overlappingTranslation.text.toString() != content.overlappingTranslation ||
            overlay.overlappingBacking.text.toString() != content.overlappingBacking ||
            overlay.overlappingBackingTranslation.text.toString() !=
                content.overlappingBackingTranslation ||
            overlay.next.text.toString() != content.next
        val styleChanged = overlay.appliedTextStyle != textStyle
        val alignmentChanged = overlay.appliedMainAlignment != content.mainAlignment ||
            overlay.appliedBackingAlignment != content.backingAlignment ||
            overlay.appliedOverlappingAlignment != content.overlappingAlignment ||
            overlay.appliedOverlappingBackingAlignment != content.overlappingBackingAlignment ||
            overlay.appliedNextAlignment != content.nextAlignment
        if (contentChanged || styleChanged || alignmentChanged) {
            overlay.drawWakeLock.acquire(DRAW_WAKE_LOCK_TIMEOUT_MS)
        }
        overlay.main.text = content.main
        setOptionalText(overlay.translation, content.translation)
        setOptionalText(overlay.backing, content.backing)
        setOptionalText(overlay.backingTranslation, content.backingTranslation)
        setOptionalText(overlay.overlappingMain, content.overlappingMain)
        setOptionalText(overlay.overlappingTranslation, content.overlappingTranslation)
        setOptionalText(overlay.overlappingBacking, content.overlappingBacking)
        setOptionalText(
            overlay.overlappingBackingTranslation,
            content.overlappingBackingTranslation
        )
        setOptionalText(overlay.next, content.next)
        applyContentAlignment(overlay, content)
        applyLockScreenTextStyle(
            overlay = overlay,
            style = textStyle,
            mainTypefaceView = api.getTitleText(holder),
            translationTypefaceView = api.getArtistText(holder),
        )
        applyLyricRowOrder(overlay, textStyle.swapTranslation)
        updateLockScreenLineSpacing(overlay)
        overlay.main.setTextColor(api.getTitleText(holder).currentTextColor)
        overlay.backing.setTextColor(api.getTitleText(holder).currentTextColor)
        overlay.overlappingMain.setTextColor(api.getTitleText(holder).currentTextColor)
        overlay.overlappingBacking.setTextColor(api.getTitleText(holder).currentTextColor)
        val translationColor = api.getArtistText(holder).currentTextColor
        overlay.translation.setTextColor(translationColor)
        overlay.backingTranslation.setTextColor(translationColor)
        overlay.overlappingTranslation.setTextColor(translationColor)
        overlay.overlappingBackingTranslation.setTextColor(translationColor)
        overlay.next.setTextColor(
            if (textStyle.nextLyricStyle == RootConstants.AOD_NEXT_LYRIC_STYLE_BACKING) {
                api.getTitleText(holder).currentTextColor
            } else {
                translationColor
            }
        )
        overlay.fullAodActive = state.fullAod
        if (overlay.root.visibility == View.GONE) {
            overlay.root.visibility = View.INVISIBLE
        }
        overlay.root.post {
            if (overlay.root.visibility == View.GONE) return@post
            if (overlay.root.visibility == View.INVISIBLE) {
                overlay.root.visibility = View.VISIBLE
                overlay.root.bringToFront()
                HookLogger.i(TAG, "锁屏 AOD 歌词已在原生控件隐藏完成后显示")
            }
            updateLockScreenCardHeight(
                overlay,
                forceRemeasure = contentChanged || styleChanged,
            )
            DisplayDiagnosticLogger.log(
                channel = "AOD_LOCK",
                result = "shown",
                reason = "overlay_visible",
                extra = "interactive=$interactive, playerShown=${player.isShown}, " +
                    "fullAod=${state.fullAod}, mediaPackage=${mediaPackage.orEmpty()}, " +
                    "contentChanged=$contentChanged, styleChanged=$styleChanged",
                dedupeKey = diagnosticKey,
            )
        }
        if (contentChanged || styleChanged) {
            overlay.root.invalidate()
            (overlay.root.parent as? View)?.invalidate()
            requestAodFrameRefresh(controller.javaClass.classLoader)
        }
    }

    private fun refreshAodPluginStates() {
        synchronized(aodPluginStates) { aodPluginStates.entries.toList() }
            .forEach { (aodView, state) -> safeApplyAodPlugin(aodView, state) }
    }

    private fun safeApplyAodPlugin(aodView: Any, state: AodPluginState) {
        runCatching { applyAodPluginState(aodView, state) }
            .onFailure {
                state.overlay?.root?.visibility = View.GONE
                HookLogger.e(TAG, "应用通知图标式息屏歌词失败", it)
            }
    }

    private fun applyAodPluginState(aodView: Any, state: AodPluginState) {
        val diagnosticKey = "AOD_CLASSIC/${System.identityHashCode(aodView)}"
        val api = resolveAodPluginApi(aodView.javaClass.classLoader) ?: run {
            DisplayDiagnosticLogger.log(
                "AOD_CLASSIC",
                "skipped",
                "hook_unavailable",
                dedupeKey = diagnosticKey,
            )
            return
        }
        val view = aodView as? View ?: run {
            DisplayDiagnosticLogger.log(
                "AOD_CLASSIC",
                "skipped",
                "view_unavailable",
                dedupeKey = diagnosticKey,
            )
            return
        }
        state.attached = view.isAttachedToWindow
        state.playing = LyriconDataBridge.currentPlaybackState ?: state.playing
        synchronizeLyricPosition()
        val textStyle = classicAodTextStyle()
        val mediaPackage = LyriconDataBridge.currentLyricPackageName
            ?: LyriconDataBridge.activePackageName
        val content = appendNextSongPreview(
            content = currentContent(textStyle),
            style = textStyle,
            context = view.context,
            packageName = mediaPackage,
        )
        val songInfo = currentClassicAodEmbeddedSongInfo()
        val fullAodActive = synchronized(states) {
            states.values.any { controllerState ->
                controllerState.overlay?.root?.isShown == true &&
                    controllerState.aodActive
            }
        }
        val enabled = isEnabled()
        val viewShown = view.isShown
        val aodShown = api.isAodShown(aodView)
        val pauseAllowed = state.playing ||
            textStyle.pauseStyle == RootConstants.AOD_PAUSE_STYLE_KEEP_LYRICS
        val hasContent = content.main.isNotBlank() ||
            content.next.isNotBlank() || songInfo.text.isNotBlank()
        val show = enabled &&
            state.attached &&
            viewShown &&
            aodShown &&
            pauseAllowed &&
            !fullAodActive &&
            hasContent

        if (!show) {
            val reason = when {
                !enabled -> "feature_disabled"
                !state.attached -> "view_detached"
                !viewShown -> "view_hidden"
                !aodShown -> "aod_panel_hidden"
                !pauseAllowed -> "pause_policy"
                fullAodActive -> "lockscreen_aod_active"
                !hasContent -> "no_lyrics_or_song_info"
                else -> "policy_rejected"
            }
            DisplayDiagnosticLogger.log(
                channel = "AOD_CLASSIC",
                result = "hidden",
                reason = reason,
                extra = "attached=${state.attached}, viewShown=$viewShown, " +
                    "aodShown=$aodShown, fullAodActive=$fullAodActive, " +
                    "overlay=${state.overlay != null}",
                dedupeKey = diagnosticKey,
            )
            state.overlay?.root?.visibility = View.GONE
            return
        }

        val overlay = state.overlay ?: createAodPluginOverlay(api, aodView)?.also {
            state.overlay = it
        } ?: return
        val contentChanged = overlay.main.text.toString() != content.main ||
            overlay.translation.text.toString() != content.translation ||
            overlay.backing.text.toString() != content.backing ||
            overlay.backingTranslation.text.toString() != content.backingTranslation ||
            overlay.overlappingMain.text.toString() != content.overlappingMain ||
            overlay.overlappingTranslation.text.toString() != content.overlappingTranslation ||
            overlay.overlappingBacking.text.toString() != content.overlappingBacking ||
            overlay.overlappingBackingTranslation.text.toString() !=
                content.overlappingBackingTranslation ||
            overlay.next.text.toString() != content.next ||
            overlay.songInfo.text.toString() != songInfo.text ||
            overlay.appliedSongInfoPackage != songInfo.sourcePackage ||
            overlay.appliedSongInfoTextSize != songInfo.textSize ||
            overlay.appliedSongInfoShowsIcon != songInfo.showIcon
        val styleChanged = overlay.appliedTextStyle != textStyle
        val alignmentChanged = overlay.appliedMainAlignment != content.mainAlignment ||
            overlay.appliedBackingAlignment != content.backingAlignment ||
            overlay.appliedOverlappingAlignment != content.overlappingAlignment ||
            overlay.appliedOverlappingBackingAlignment != content.overlappingBackingAlignment ||
            overlay.appliedNextAlignment != content.nextAlignment
        if (contentChanged || styleChanged || alignmentChanged) {
            overlay.drawWakeLock.acquire(DRAW_WAKE_LOCK_TIMEOUT_MS)
        }
        overlay.main.text = content.main
        setOptionalText(overlay.translation, content.translation)
        setOptionalText(overlay.backing, content.backing)
        setOptionalText(overlay.backingTranslation, content.backingTranslation)
        setOptionalText(overlay.overlappingMain, content.overlappingMain)
        setOptionalText(overlay.overlappingTranslation, content.overlappingTranslation)
        setOptionalText(overlay.overlappingBacking, content.overlappingBacking)
        setOptionalText(
            overlay.overlappingBackingTranslation,
            content.overlappingBackingTranslation
        )
        setOptionalText(overlay.next, content.next)
        applyContentAlignment(overlay, content)
        applyClassicTextStyle(overlay, textStyle)
        applyLyricRowOrder(overlay, textStyle.swapTranslation)
        updateClassicEmbeddedSongInfo(overlay, songInfo)
        updateClassicLineSpacing(overlay)
        overlay.root.visibility = View.VISIBLE
        overlay.root.bringToFront()
        positionAodPluginOverlay(overlay)
        DisplayDiagnosticLogger.log(
            channel = "AOD_CLASSIC",
            result = "shown",
            reason = "overlay_visible",
            extra = "attached=${state.attached}, viewShown=$viewShown, aodShown=$aodShown, " +
                "contentChanged=$contentChanged, styleChanged=$styleChanged, " +
                "songInfo=${songInfo.text.isNotBlank()}",
            dedupeKey = diagnosticKey,
        )
        if (contentChanged || styleChanged || alignmentChanged) {
            overlay.root.invalidate()
            overlay.parent.invalidate()
            requestAodFrameRefresh(aodView.javaClass.classLoader)
        }
    }

    @SuppressLint("UseKtx")
    private fun createAodPluginOverlay(
        api: AodPluginApi,
        aodView: Any
    ): AodPluginOverlay? {
        val diagnosticKey = "AOD_CLASSIC/${System.identityHashCode(aodView)}/overlay"
        val aodRoot = aodView as? FrameLayout ?: run {
            DisplayDiagnosticLogger.log(
                channel = "AOD_CLASSIC",
                result = "skipped",
                reason = "root_not_frame_layout",
                extra = "rootClass=${aodView.javaClass.name}",
                dedupeKey = "$diagnosticKey/root",
            )
            return null
        }
        val anchor = api.getNotificationIcons(aodView) ?: run {
            DisplayDiagnosticLogger.log(
                channel = "AOD_CLASSIC",
                result = "skipped",
                reason = "notification_icons_null",
                extra = "rootClass=${aodRoot.javaClass.name}",
                dedupeKey = "$diagnosticKey/anchor_presence",
            )
            return null
        }
        if (!anchor.isAttachedToWindow) {
            DisplayDiagnosticLogger.log(
                channel = "AOD_CLASSIC",
                result = "pending",
                reason = "anchor_detached",
                extra = "anchorClass=${anchor.javaClass.name}, " +
                    "width=${anchor.width}, height=${anchor.height}",
                dedupeKey = "$diagnosticKey/anchor_attachment",
            )
        }
        if (anchor.width <= 0 || anchor.height <= 0) {
            DisplayDiagnosticLogger.log(
                channel = "AOD_CLASSIC",
                result = "pending",
                reason = "anchor_zero_size",
                extra = "attached=${anchor.isAttachedToWindow}, " +
                    "width=${anchor.width}, height=${anchor.height}",
                dedupeKey = "$diagnosticKey/anchor_size",
            )
        }
        val parentCandidate = api.getTableModeContainer(aodView)
        val movingContainer = parentCandidate as? FrameLayout
        if (movingContainer == null) {
            DisplayDiagnosticLogger.log(
                channel = "AOD_CLASSIC",
                result = "pending",
                reason = "parent_unavailable",
                extra = "candidateClass=${parentCandidate?.javaClass?.name ?: "null"}, " +
                    "usingRootFallback=true",
                dedupeKey = "$diagnosticKey/parent",
            )
        }
        val parent = movingContainer ?: aodRoot
        val context = aodRoot.context
        val main = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_CLASSIC_AOD_MAIN_TEXT_SIZE.toFloat(),
            )
        }
        val translation = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_CLASSIC_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
            )
        }
        val backing = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = main.typeface
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_CLASSIC_AOD_BACKING_TEXT_SIZE.toFloat(),
            )
        }
        val backingTranslation = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = translation.typeface
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_CLASSIC_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
            )
        }
        val overlappingMain = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = main.typeface
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_CLASSIC_AOD_MAIN_TEXT_SIZE.toFloat(),
            )
        }
        val overlappingTranslation = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = translation.typeface
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_CLASSIC_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
            )
        }
        val overlappingBacking = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = main.typeface
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_CLASSIC_AOD_BACKING_TEXT_SIZE.toFloat(),
            )
        }
        val overlappingBackingTranslation = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = translation.typeface
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_CLASSIC_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
            )
        }
        val next = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = translation.typeface
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_CLASSIC_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
            )
        }
        val sourceIcon = ImageView(context).apply {
            visibility = View.GONE
        }
        val songInfo = TextView(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_CLASSIC_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
            )
        }
        val songInfoRow = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            addView(
                sourceIcon,
                LinearLayout.LayoutParams(
                    (AOD_PLUGIN_SONG_INFO_ICON_DP * resources.displayMetrics.density).toInt(),
                    (AOD_PLUGIN_SONG_INFO_ICON_DP * resources.displayMetrics.density).toInt()
                ).apply {
                    marginEnd = (
                        AOD_PLUGIN_SONG_INFO_ICON_GAP_DP * resources.displayMetrics.density
                    ).toInt()
                }
            )
            addView(
                songInfo,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val root = LinearLayout(context).apply {
            id = View.generateViewId()
            tag = AOD_PLUGIN_OVERLAY_TAG
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            val horizontalPadding = (8f * resources.displayMetrics.density).toInt()
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
            addView(
                songInfoRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (
                        AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP *
                            resources.displayMetrics.density
                    ).toInt()
                }
            )
            addView(
                main,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                translation,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (
                        AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP *
                            resources.displayMetrics.density
                    ).toInt()
                }
            )
            addView(
                backing,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (
                        AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP *
                            resources.displayMetrics.density
                    ).toInt()
                }
            )
            addView(
                backingTranslation,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (
                        AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP *
                            resources.displayMetrics.density
                    ).toInt()
                }
            )
            addView(
                overlappingMain,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (
                        AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP *
                            resources.displayMetrics.density
                    ).toInt()
                }
            )
            addView(
                overlappingTranslation,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (
                        AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP *
                            resources.displayMetrics.density
                    ).toInt()
                }
            )
            addView(
                overlappingBacking,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (
                        AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP *
                            resources.displayMetrics.density
                    ).toInt()
                }
            )
            addView(
                overlappingBackingTranslation,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (
                        AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP *
                            resources.displayMetrics.density
                    ).toInt()
                }
            )
            addView(
                next,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (
                        AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP *
                            resources.displayMetrics.density
                    ).toInt()
                }
            )
        }
        parent.addView(
            root,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            )
        )
        val drawWakeLock = context.getSystemService(PowerManager::class.java).newWakeLock(
            DRAW_WAKE_LOCK_LEVEL,
            "${context.packageName}:HyperLyricsAodDraw"
        ).apply {
            setReferenceCounted(false)
        }
        val overlay = AodPluginOverlay(
            root = root,
            songInfoRow = songInfoRow,
            sourceIcon = sourceIcon,
            songInfo = songInfo,
            main = main,
            translation = translation,
            backing = backing,
            backingTranslation = backingTranslation,
            overlappingMain = overlappingMain,
            overlappingTranslation = overlappingTranslation,
            overlappingBacking = overlappingBacking,
            overlappingBackingTranslation = overlappingBackingTranslation,
            next = next,
            parent = parent,
            aodRoot = aodRoot,
            anchor = anchor,
            drawWakeLock = drawWakeLock
        )
        main.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateClassicLineSpacing(overlay)
        }
        val preDrawListener = ViewTreeObserver.OnPreDrawListener {
            positionAodPluginOverlay(overlay)
            true
        }
        overlay.preDrawListener = preDrawListener
        aodRoot.viewTreeObserver.addOnPreDrawListener(preDrawListener)
        positionAodPluginOverlay(overlay)
        HookLogger.i(
            TAG,
            "通知图标式息屏歌词已挂载: parent=${parent.javaClass.name}, " +
                "anchor=${anchor.javaClass.name}"
        )
        return overlay
    }

    private fun updateClassicLineSpacing(overlay: AodPluginOverlay) {
        val mainParams = overlay.main.layoutParams as? LinearLayout.LayoutParams ?: return
        if (
            mainParams.height != ViewGroup.LayoutParams.WRAP_CONTENT ||
            mainParams.weight != 0f
        ) {
            mainParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            mainParams.weight = 0f
            overlay.main.layoutParams = mainParams
        }

        val compact = AodMediaLyricPolicy.shouldCompactClassicMain(overlay.main.lineCount)
        val gapDp = if (compact) {
            AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP
        } else {
            AOD_PLUGIN_MULTI_LINE_LYRIC_GAP_DP
        }
        val density = overlay.main.resources.displayMetrics.density
        val contentRows = orderedLyricViews(
            overlay,
            overlay.appliedTextStyle?.swapTranslation == true,
        )
        val visibleRows = contentRows.filter { it.visibility == View.VISIBLE }
        val firstRow = visibleRows.firstOrNull() ?: return
        val secondRow = visibleRows.getOrNull(1)
        val firstBackingRow = contentRows
            .filter { it === overlay.backing || it === overlay.backingTranslation }
            .firstOrNull { it.visibility == View.VISIBLE }
        val primaryVisibleCount = listOf(overlay.main, overlay.translation)
            .count { it.visibility == View.VISIBLE }
        val normalMargin = (AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP * density).toInt()
        val groupMargin = (8f * density).toInt()
        val firstMargin = (gapDp * density).toInt()
        contentRows.forEach { view ->
            val params = view.layoutParams as? LinearLayout.LayoutParams ?: return@forEach
            val targetMargin = when {
                view === firstRow -> 0
                view === secondRow && primaryVisibleCount > 0 -> firstMargin
                view === firstBackingRow && primaryVisibleCount > 1 -> groupMargin
                else -> normalMargin
            }
            if (params.topMargin != targetMargin) {
                params.topMargin = targetMargin
                view.layoutParams = params
            }
        }
    }

    private fun positionAodPluginOverlay(overlay: AodPluginOverlay) {
        if (!overlay.aodRoot.isAttachedToWindow || overlay.aodRoot.width <= 0) return
        val density = overlay.aodRoot.resources.displayMetrics.density
        val rootLocation = IntArray(2)
        val parentLocation = IntArray(2)
        val anchorLocation = IntArray(2)
        overlay.aodRoot.getLocationOnScreen(rootLocation)
        overlay.parent.getLocationOnScreen(parentLocation)
        overlay.anchor.getLocationOnScreen(anchorLocation)

        val sideMargin = (AOD_PLUGIN_SIDE_MARGIN_DP * density).toInt()
        val gap = (AOD_PLUGIN_GAP_DP * density).toInt()
        val bottomSafe = (AOD_PLUGIN_BOTTOM_SAFE_DP * density).toInt()
        val maxWidth = (AOD_PLUGIN_MAX_WIDTH_DP * density).toInt()
        val availableWidth = (overlay.aodRoot.width - sideMargin * 2).coerceAtLeast(1)
        val width = minOf(maxWidth, availableWidth)
        val anchorCenter = anchorLocation[0] + overlay.anchor.width / 2
        val rootLeft = rootLocation[0] + sideMargin
        val rootRight = rootLocation[0] + overlay.aodRoot.width - sideMargin
        val leftOnScreen = (anchorCenter - width / 2).coerceIn(
            rootLeft,
            (rootRight - width).coerceAtLeast(rootLeft)
        )
        val topOnScreen = anchorLocation[1] + overlay.anchor.height + gap
        val bottomLimit = rootLocation[1] + overlay.aodRoot.height - bottomSafe
        val availableHeight = (bottomLimit - topOnScreen).coerceAtLeast(1)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        updateClassicSongInfoMaxWidth(overlay, width)
        overlay.root.measure(widthSpec, heightSpec)
        updateClassicLineSpacing(overlay)
        overlay.root.measure(widthSpec, heightSpec)
        val contentHeight = overlay.root.measuredHeight
        val height = AodMediaLyricPolicy.classicOverlayHeight(
            contentHeight = contentHeight,
            availableHeight = availableHeight
        )

        val params = overlay.root.layoutParams as FrameLayout.LayoutParams
        val leftMargin = leftOnScreen - parentLocation[0]
        val topMargin = topOnScreen - parentLocation[1]
        if (
            params.width != width ||
            params.height != height ||
            params.leftMargin != leftMargin ||
            params.topMargin != topMargin
        ) {
            params.width = width
            params.height = height
            params.leftMargin = leftMargin
            params.topMargin = topMargin
            overlay.root.layoutParams = params
        }
        if (overlay.appliedHeight != height) {
            overlay.appliedHeight = height
            HookLogger.i(
                TAG,
                "经典 AOD 歌词覆盖层已按内容实测高度调整: " +
                    "contentHeight=$contentHeight, availableHeight=$availableHeight, " +
                    "targetHeight=$height"
            )
        }
    }

    private fun createOverlay(
        api: NativeApi,
        holder: Any,
        actions: List<View>
    ): LyricOverlay? {
        val player = api.getPlayer(holder)
        if (actions.isEmpty()) return null
        val title = api.getTitleText(holder)
        val artist = api.getArtistText(holder)
        val album = api.getAlbumView(holder) ?: artist
        val context = player.context

        val main = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = title.typeface
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_MAIN_TEXT_SIZE.toFloat(),
            )
            setTextColor(title.currentTextColor)
        }
        val translation = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = artist.typeface
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
            )
            setTextColor(artist.currentTextColor)
        }
        val backing = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = title.typeface
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_BACKING_TEXT_SIZE.toFloat(),
            )
            setTextColor(title.currentTextColor)
        }
        val backingTranslation = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = artist.typeface
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
            )
            setTextColor(artist.currentTextColor)
        }
        val overlappingMain = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = title.typeface
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_MAIN_TEXT_SIZE.toFloat(),
            )
            setTextColor(title.currentTextColor)
        }
        val overlappingTranslation = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = artist.typeface
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
            )
            setTextColor(artist.currentTextColor)
        }
        val overlappingBacking = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = title.typeface
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_BACKING_TEXT_SIZE.toFloat(),
            )
            setTextColor(title.currentTextColor)
        }
        val overlappingBackingTranslation = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = artist.typeface
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
            )
            setTextColor(artist.currentTextColor)
        }
        val next = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = artist.typeface
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
            )
            setTextColor(artist.currentTextColor)
        }
        val root = LinearLayout(context).apply {
            id = View.generateViewId()
            tag = OVERLAY_TAG
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            val density = resources.displayMetrics.density
            addView(main, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(translation, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (LOCK_SCREEN_AOD_LINE_GAP_DP * density).toInt()
            })
            addView(backing, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (LOCK_SCREEN_AOD_LINE_GAP_DP * density).toInt()
            })
            addView(backingTranslation, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (LOCK_SCREEN_AOD_LINE_GAP_DP * density).toInt()
            })
            addView(overlappingMain, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (LOCK_SCREEN_AOD_LINE_GAP_DP * density).toInt()
            })
            addView(overlappingTranslation, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (LOCK_SCREEN_AOD_LINE_GAP_DP * density).toInt()
            })
            addView(overlappingBacking, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (LOCK_SCREEN_AOD_LINE_GAP_DP * density).toInt()
            })
            addView(overlappingBackingTranslation, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (LOCK_SCREEN_AOD_LINE_GAP_DP * density).toInt()
            })
            addView(next, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (LOCK_SCREEN_AOD_LINE_GAP_DP * density).toInt()
            })
        }

        val params = createConstraintLayoutParams(player, album) ?: return null
        val playerSize = captureViewSize(player)
        val backgroundView = api.getMediaBackground(holder) ?: player
        val backgroundSize = captureViewSize(backgroundView)
        val backgroundConstraints = BackgroundConstraints.create(backgroundView)
        val headerHeightController = MediaHeaderHeightController.create(player.parent as? View)
        val fullAodHeightId = context.resources.getIdentifier(
            "qs_media_session_height_expanded_fullAod",
            "dimen",
            context.packageName
        )
        if (fullAodHeightId != 0) {
            backgroundSize.baseHeight = context.resources.getDimensionPixelSize(fullAodHeightId)
        }
        player.addView(root, params)
        val powerManager = context.getSystemService(PowerManager::class.java)
        val drawWakeLock = powerManager.newWakeLock(
            DRAW_WAKE_LOCK_LEVEL,
            "${context.packageName}:HyperLyricsAodDraw"
        ).apply {
            setReferenceCounted(false)
        }
        val overlay = LyricOverlay(
            root = root,
            main = main,
            translation = translation,
            backing = backing,
            backingTranslation = backingTranslation,
            overlappingMain = overlappingMain,
            overlappingTranslation = overlappingTranslation,
            overlappingBacking = overlappingBacking,
            overlappingBackingTranslation = overlappingBackingTranslation,
            next = next,
            artist = artist,
            album = album,
            player = player,
            playerSize = playerSize,
            backgroundSize = backgroundSize,
            backgroundConstraints = backgroundConstraints,
            headerHeightController = headerHeightController,
            drawWakeLock = drawWakeLock
        )
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateLockScreenCardHeight(overlay)
        }
        HookLogger.i(
            TAG,
            "锁屏 AOD 媒体卡片动态高度已挂载: player=${player.javaClass.name}, " +
                "playerBase=${playerSize.baseHeight}, " +
                "playerLayout=${playerSize.originalLayoutHeight}, " +
                "background=${backgroundSize.view.javaClass.name}, " +
                "backgroundBase=${backgroundSize.baseHeight}, " +
                "backgroundLayout=${backgroundSize.originalLayoutHeight}, " +
                "header=${headerHeightController?.view?.javaClass?.name.orEmpty()}, " +
                "headerBase=${headerHeightController?.originalHeight ?: 0}"
        )
        return overlay
    }

    private fun createConstraintLayoutParams(
        player: ViewGroup,
        metadataAnchor: View
    ): ViewGroup.LayoutParams? = runCatching {
        val loader = requireNotNull(player.javaClass.classLoader) {
            "ConstraintLayout class loader unavailable"
        }
        val paramsClass = loader.loadClass(
            "androidx.constraintlayout.widget.ConstraintLayout\$LayoutParams"
        )
        val params = paramsClass.getConstructor(
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        ).newInstance(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ) as ViewGroup.LayoutParams
        paramsClass.getField("startToStart").setInt(params, 0)
        paramsClass.getField("endToEnd").setInt(params, 0)
        paramsClass.getField("topToBottom").setInt(params, metadataAnchor.id)
        (params as ViewGroup.MarginLayoutParams).topMargin = (
            LOCK_SCREEN_AOD_TOP_GAP_DP * player.resources.displayMetrics.density
            ).toInt()
        params
    }.onFailure {
        HookLogger.e(TAG, "创建息屏歌词布局参数失败", it)
    }.getOrNull()

    private fun restoreActions(state: ControllerState) {
        if (state.actionVisibilities.isEmpty()) return
        state.actionVisibilities.forEach { (view, visibility) -> view.visibility = visibility }
        state.actionVisibilities.clear()
    }

    private fun safeApply(controller: Any, state: ControllerState) {
        runCatching { applyState(controller, state) }
            .onFailure {
                restoreActions(state)
                state.overlay?.let { overlay ->
                    overlay.root.visibility = View.GONE
                    restorePlayerHeight(overlay, state.fullAod)
                }
                HookLogger.e(TAG, "应用息屏歌词失败", it)
            }
    }

    private fun updateLockScreenCardHeight(
        overlay: LyricOverlay,
        forceRemeasure: Boolean = false
    ) {
        if (!overlay.root.isShown) return
        if (updateLockScreenHorizontalMargins(overlay)) {
            overlay.root.post {
                updateLockScreenCardHeight(overlay, forceRemeasure = true)
            }
            return
        }
        if (forceRemeasure) {
            val measuredWidth = overlay.root.width.takeIf { it > 0 }
                ?: overlay.root.measuredWidth
            if (measuredWidth > 0) {
                overlay.root.measure(
                    View.MeasureSpec.makeMeasureSpec(
                        measuredWidth,
                        View.MeasureSpec.EXACTLY
                    ),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
            }
        }
        if (overlay.root.measuredHeight <= 0) return
        val anchorBottom = AodMediaLyricPolicy.contentAnchorBottom(
            albumBottom = overlay.album.bottom,
            artistBottom = overlay.artist.bottom
        )
        if (anchorBottom <= 0) return
        if (overlay.playerSize.baseHeight <= 0) {
            overlay.playerSize.baseHeight = overlay.player.height.takeIf { it > 0 }
                ?: overlay.player.measuredHeight
        }
        if (overlay.playerSize.baseHeight <= 0) return
        if (overlay.backgroundSize.baseHeight <= 0) {
            overlay.backgroundSize.baseHeight = overlay.backgroundSize.view.height.takeIf { it > 0 }
                ?: overlay.backgroundSize.view.measuredHeight
        }
        if (overlay.backgroundSize.baseHeight <= 0) return

        val density = overlay.root.resources.displayMetrics.density
        val topGap = (LOCK_SCREEN_AOD_TOP_GAP_DP * density).toInt()
        val bottomGap = (LOCK_SCREEN_AOD_BOTTOM_GAP_DP * density).toInt()
        val nativeCardHeight = AodMediaLyricPolicy.lockScreenNativeCardHeight(
            fullAod = overlay.fullAodActive,
            fullAodBaseHeight = overlay.backgroundSize.baseHeight,
            playerBaseHeight = overlay.playerSize.baseHeight,
        )
        if (overlay.fullAodActive) {
            overlay.backgroundConstraints?.pinToParentTop(overlay.player)
        } else {
            overlay.backgroundConstraints?.restore()
        }
        val lyricTop = AodMediaLyricPolicy.lockScreenLyricTop(
            anchorBottom = anchorBottom,
            topGap = topGap
        )
        val params = overlay.root.layoutParams as? ViewGroup.MarginLayoutParams
        val targetTopMargin = lyricTop - overlay.album.bottom
        if (params != null && params.topMargin != targetTopMargin) {
            params.topMargin = targetTopMargin
            overlay.root.layoutParams = params
        }
        val lyricBottom = lyricTop + overlay.root.measuredHeight
        val targetHeight = AodMediaLyricPolicy.lockScreenTargetCardHeight(
            nativeCardHeight = nativeCardHeight,
            lyricBottom = lyricBottom,
            bottomPadding = bottomGap,
        )
        val backgroundTargetHeight = AodMediaLyricPolicy.lockScreenBackgroundTargetHeight(
            targetCardHeight = targetHeight,
        )

        if (overlay.appliedCardHeight != targetHeight) {
            animateLockScreenCardHeight(
                overlay = overlay,
                targetHeight = targetHeight,
                backgroundTargetHeight = backgroundTargetHeight,
            )
            overlay.appliedCardHeight = targetHeight
            HookLogger.i(
                TAG,
                "锁屏 AOD 媒体卡片高度动画开始: " +
                    "lyricTop=$lyricTop, lyricBottom=$lyricBottom, " +
                    "albumBottom=${overlay.album.bottom}, artistBottom=${overlay.artist.bottom}, " +
                    "topGap=$topGap, bottomGap=$bottomGap, " +
                    "nativeBackgroundHeight=$nativeCardHeight, " +
                    "targetHeight=$targetHeight, " +
                    "backgroundTargetHeight=$backgroundTargetHeight"
            )
        }
        val appliedHeight = overlay.appliedCardHeight
        if (
            overlay.heightAnimator?.isRunning != true &&
            (
                AodMediaLyricPolicy.lockScreenHeightNeedsReassert(
                    if (overlay.backgroundConstraints?.isPinned == true) {
                        backgroundTargetHeight
                    } else {
                        appliedHeight
                    },
                    overlay.backgroundSize.view.layoutParams?.height,
                ) ||
                    AodMediaLyricPolicy.lockScreenHeightNeedsReassert(
                        appliedHeight,
                        overlay.player.layoutParams?.height,
                    )
            )
        ) {
            appliedHeight?.let { height ->
                animateLockScreenCardHeight(
                    overlay = overlay,
                    targetHeight = height,
                    backgroundTargetHeight = AodMediaLyricPolicy
                        .lockScreenBackgroundTargetHeight(height),
                )
            }
        }
        if (BuildConfig.DEBUG) {
            val background = overlay.backgroundSize.view
            val driftKey = buildString {
                append("applied=${overlay.appliedCardHeight}, target=$targetHeight")
                append(", bgTarget=$backgroundTargetHeight")
                append(", bg=${background.height}/${background.measuredHeight}/${background.layoutParams?.height}")
                append(", bgTop=${background.top}, bgBottom=${background.bottom}")
                append(", bgTransY=${background.translationY}")
                append(", ").append(overlay.backgroundConstraints?.snapshot().orEmpty())
                append(", player=${overlay.player.height}/${overlay.player.measuredHeight}/${overlay.player.layoutParams?.height}")
                append(", ").append(overlay.headerHeightController?.actualHeightSnapshot().orEmpty())
            }
            if (driftKey != overlay.lastHeightDriftKey) {
                overlay.lastHeightDriftKey = driftKey
                HookLogger.i(TAG, "AOD_HEIGHT_VERIFY $driftKey shown=${overlay.root.isShown}")
            }
        }
    }

    private fun updateLockScreenHorizontalMargins(overlay: LyricOverlay): Boolean {
        val playerWidth = overlay.player.width.takeIf { it > 0 }
            ?: overlay.player.measuredWidth
        val backgroundWidth = overlay.backgroundSize.view.width.takeIf { it > 0 }
            ?: overlay.backgroundSize.view.measuredWidth
        val albumWidth = overlay.album.width.takeIf { it > 0 }
            ?: overlay.album.measuredWidth
        if (playerWidth <= 0 || backgroundWidth <= 0 || albumWidth <= 0) return false

        val playerLocation = IntArray(2)
        val backgroundLocation = IntArray(2)
        val albumLocation = IntArray(2)
        overlay.player.getLocationOnScreen(playerLocation)
        overlay.backgroundSize.view.getLocationOnScreen(backgroundLocation)
        overlay.album.getLocationOnScreen(albumLocation)

        val cardLeft = backgroundLocation[0] - playerLocation[0]
        val cardRight = cardLeft + backgroundWidth
        val albumLeft = albumLocation[0] - playerLocation[0]
        val margins = AodMediaLyricPolicy.lockScreenHorizontalMargins(
            playerWidth = playerWidth,
            cardLeft = cardLeft,
            cardRight = cardRight,
            albumLeft = albumLeft,
            extraInset = (
                LOCK_SCREEN_AOD_SIDE_MARGIN_EXTRA_DP *
                    overlay.root.resources.displayMetrics.density
                ).toInt(),
        )
        val params = overlay.root.layoutParams as? ViewGroup.MarginLayoutParams ?: return false
        if (
            params.leftMargin == margins.left &&
            params.rightMargin == margins.right &&
            params.marginStart == margins.left &&
            params.marginEnd == margins.right
        ) {
            return false
        }

        params.leftMargin = margins.left
        params.rightMargin = margins.right
        params.marginStart = margins.left
        params.marginEnd = margins.right
        overlay.root.layoutParams = params
        HookLogger.i(
            TAG,
            "锁屏 AOD 歌词左右边距已按封面位置调整: " +
                "cardLeft=$cardLeft, albumLeft=$albumLeft, " +
                "leftMargin=${margins.left}, rightMargin=${margins.right}",
        )
        return true
    }

    private fun restorePlayerHeight(overlay: LyricOverlay, fullAod: Boolean = false) {
        if (
            !AodMediaLyricPolicy.lockScreenHeightNeedsRestore(
                appliedHeight = overlay.appliedCardHeight,
                heightAnimationActive = overlay.heightAnimator != null,
            ) && overlay.backgroundConstraints?.isPinned != true
        ) {
            return
        }
        val animator = overlay.heightAnimator
        overlay.heightAnimator = null
        animator?.cancel()
        overlay.backgroundConstraints?.restore()
        restoreViewSize(overlay.playerSize)
        resizeViewToHeight(
            overlay.backgroundSize.view,
            if (fullAod) {
                overlay.backgroundSize.baseHeight
            } else {
                overlay.playerSize.baseHeight
            }
        )
        overlay.headerHeightController?.restoreHeight()
        overlay.appliedCardHeight = null
        overlay.backgroundSize.view.requestLayout()
        overlay.player.requestLayout()
        (overlay.player.parent as? View)?.requestLayout()
        if (BuildConfig.DEBUG) {
            HookLogger.i(
                TAG,
                "AOD_HEIGHT_RESTORE fullAod=$fullAod, " +
                    "bgH=${overlay.backgroundSize.view.height}, " +
                    "playerH=${overlay.player.height}, " +
                    overlay.headerHeightController?.actualHeightSnapshot().orEmpty()
            )
        }
    }

    private fun resizeViewToHeight(view: View, targetHeight: Int) {
        val params = view.layoutParams ?: return
        if (params.height != targetHeight) {
            params.height = targetHeight
            view.layoutParams = params
        }
    }

    private fun animateLockScreenCardHeight(
        overlay: LyricOverlay,
        targetHeight: Int,
        backgroundTargetHeight: Int = targetHeight,
    ) {
        val previousAnimator = overlay.heightAnimator
        overlay.heightAnimator = null
        previousAnimator?.cancel()
        val background = overlay.backgroundSize.view
        val player = overlay.player
        val headerController = overlay.headerHeightController

        val startBackgroundHeight = background.height.takeIf { it > 0 }
            ?: (background.layoutParams?.height)?.takeIf { it >= 0 }
            ?: overlay.backgroundSize.baseHeight
        val startPlayerHeight = player.height.takeIf { it > 0 }
            ?: (player.layoutParams?.height)?.takeIf { it >= 0 }
            ?: overlay.playerSize.baseHeight
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = LOCK_SCREEN_AOD_HEIGHT_ANIMATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                resizeViewToHeight(
                    background,
                    lerp(startBackgroundHeight, backgroundTargetHeight, fraction),
                )
                resizeViewToHeight(
                    player,
                    lerp(startPlayerHeight, targetHeight, fraction),
                )
                background.requestLayout()
                player.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (overlay.heightAnimator !== animation) return
                    overlay.heightAnimator = null
                    resizeViewToHeight(background, backgroundTargetHeight)
                    resizeViewToHeight(player, targetHeight)
                    headerController?.applyFinalHeight(targetHeight)
                    background.requestLayout()
                    player.requestLayout()
                    (player.parent as? View)?.requestLayout()
                    HookLogger.i(
                        TAG,
                        "锁屏 AOD 媒体卡片高度动画完成: target=$targetHeight, " +
                            "backgroundTarget=$backgroundTargetHeight",
                    )
                }
            })
        }
        overlay.heightAnimator = animator
        animator.start()
    }

    private fun lerp(start: Int, end: Int, fraction: Float): Int =
        (start + (end - start) * fraction).roundToInt()

    private fun restoreViewSize(size: ViewSizeSnapshot) {
        val params = size.view.layoutParams
        if (params.height != size.originalLayoutHeight) {
            params.height = size.originalLayoutHeight
            size.view.layoutParams = params
        }
        if (size.view.minimumHeight != size.originalMinimumHeight) {
            size.view.minimumHeight = size.originalMinimumHeight
        }
    }

    private fun captureViewSize(view: View): ViewSizeSnapshot {
        val layoutHeight = view.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
        return ViewSizeSnapshot(
            view = view,
            originalLayoutHeight = layoutHeight,
            originalMinimumHeight = view.minimumHeight,
            baseHeight = layoutHeight.takeIf { it >= 0 }
                ?: view.height.takeIf { it > 0 }
                ?: view.measuredHeight.takeIf { it > 0 }
                ?: layoutHeight.coerceAtLeast(0)
        )
    }

    private fun shouldPollPosition(state: ControllerState): Boolean {
        return isEnabled() && state.aodActive && state.playing &&
            LyriconDataBridge.currentSong != null
    }

    private fun hasActiveAodPluginState(): Boolean {
        return isEnabled() &&
            LyriconDataBridge.currentSong != null &&
            synchronized(aodPluginStates) {
                aodPluginStates.values.any { it.attached && it.playing }
            }
    }

    private fun shouldRefreshNoLyricPreview(position: Long): Boolean {
        if (currentActualLyrics().isNotEmpty()) return false
        val currentPrefs = prefs ?: return false
        val previewEnabled = currentPrefs.getBoolean(
            RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_NEXT_SONG_PREVIEW,
            RootConstants.DEFAULT_HOOK_AOD_NEXT_SONG_PREVIEW,
        ) || currentPrefs.getBoolean(
            RootConstants.KEY_HOOK_CLASSIC_AOD_NEXT_SONG_PREVIEW,
            RootConstants.DEFAULT_HOOK_AOD_NEXT_SONG_PREVIEW,
        )
        if (!previewEnabled) return false
        val duration = LyriconDataBridge.currentSong?.duration?.takeIf { it > 0L }
            ?: return false
        if (position < (duration - 5_000L).coerceAtLeast(0L) || position >= duration) {
            return false
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastNoLyricPreviewRefreshAt < NO_LYRIC_PREVIEW_REFRESH_INTERVAL_MS) {
            return false
        }
        lastNoLyricPreviewRefreshAt = now
        return true
    }

    private fun updatePositionPolling() {
        val hasTarget = synchronized(states) { states.values.any(::shouldPollPosition) } ||
            hasActiveAodPluginState()
        if (hasTarget) {
            schedulePositionPoll()
        } else {
        mainHandler.removeCallbacks(positionPollRunnable)
        positionPollScheduled = false
        DisplayDiagnosticLogger.clear("AOD_LOCK")
        DisplayDiagnosticLogger.clear("AOD_CLASSIC")
    }
    }

    private fun schedulePositionPoll() {
        if (positionPollScheduled) return
        positionPollScheduled = true
        mainHandler.postDelayed(positionPollRunnable, POSITION_POLL_INTERVAL_MS)
    }

    private fun scheduleAodPluginInitialRefresh(aodView: Any, state: AodPluginState) {
        state.initialRefreshGeneration++
        val generation = state.initialRefreshGeneration
        val viewReference = WeakReference(aodView)
        DisplayDiagnosticLogger.log(
            channel = "AOD_CLASSIC",
            result = "pending",
            reason = "initial_refresh_scheduled",
            extra = "attempts=${AOD_PLUGIN_INITIAL_REFRESH_DELAYS_MS.size}, generation=$generation",
            dedupeKey = "AOD_CLASSIC/${System.identityHashCode(aodView)}/initial",
        )
        AOD_PLUGIN_INITIAL_REFRESH_DELAYS_MS.forEach { delay ->
            mainHandler.postDelayed(
                {
                    val target = viewReference.get() ?: return@postDelayed
                    val currentState = synchronized(aodPluginStates) {
                        aodPluginStates[target]
                    }
                    if (currentState !== state || state.initialRefreshGeneration != generation) {
                        return@postDelayed
                    }
                    safeApplyAodPlugin(target, state)
                    updatePositionPolling()
                },
                delay
            )
        }
    }

    private fun synchronizeLyricPosition(
        preferredApi: NativeApi? = null,
        preferredController: Any? = null
    ) {
        val mediaPosition = if (preferredApi != null && preferredController != null) {
            preferredApi.currentPlaybackPosition(preferredController)
        } else {
            synchronized(states) { states.entries.toList() }
                .firstNotNullOfOrNull { (controller, state) ->
                    if (!state.playing) return@firstNotNullOfOrNull null
                    resolveApi(controller.javaClass.classLoader)
                        ?.currentPlaybackPosition(controller)
                }
        }
        val position = LyriconDataBridge.estimatedPosition() ?: mediaPosition ?: return
        LyriconDataBridge.updateEstimatedPosition(position)
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

    private fun currentContent(style: AodTextStyleConfig): AodLyricContent {
        val currentLine = LyriconDataBridge.currentLyricLine
        val removeCjkLyricSpaces = currentLine != null && prefs?.getBoolean(
            RootConstants.KEY_HOOK_REMOVE_CJK_LYRIC_SPACES,
            RootConstants.DEFAULT_HOOK_REMOVE_CJK_LYRIC_SPACES,
        ) == true && currentLine.metadata?.getBoolean(
            SongPreprocessor.KEY_TITLE_LINE
        ) != true
        /** 只处理 AOD 展示文本，发音 roma 不进入此处理。 */
        fun displayText(text: String?): String? = if (removeCjkLyricSpaces) {
            CjkLyricWhitespacePolicy.transformText(text)
        } else {
            text
        }
        val suppressNoLyricPlaceholder =
            AodMediaLyricPolicy.shouldSuppressNoLyricPlaceholder(
                isTextMode = LyriconDataBridge.isTextMode,
                hasActualLyrics = currentActualLyrics().isNotEmpty(),
            )
        val line = LyriconDataBridge.currentLyricLine.takeUnless {
            suppressNoLyricPlaceholder
        }
        val nextLine = LyriconDataBridge.currentNextLyricLine.takeUnless {
            suppressNoLyricPlaceholder
        }
        val metadata = line?.metadata
        val isOverlappingGroup = metadata?.getBoolean(
            LyricMetadataKeys.OVERLAPPING_LYRICS_GROUP
        ) == true
        val main = if (suppressNoLyricPlaceholder) {
            ""
        } else {
            line?.text?.trim().orEmpty().ifBlank {
                LyriconDataBridge.currentLyric?.trim().orEmpty()
            }
        }
        val mainAlignedRight = line?.isAlignedRight == true
        val backingAlignedRight = metadata?.getBoolean(
            LyricMetadataKeys.CONCURRENT_SECONDARY_ALIGNED_RIGHT,
            mainAlignedRight
        ) ?: mainAlignedRight
        val primaryBacking = if (isOverlappingGroup) {
            metadata.getString(LyricMetadataKeys.OVERLAPPING_PRIMARY_BACKING)
        } else {
            line?.secondary
        }
        val primaryBackingTranslation = if (isOverlappingGroup) {
            metadata.getString(
                LyricMetadataKeys.OVERLAPPING_PRIMARY_BACKING_TRANSLATION
            )
        } else {
            metadata?.getString(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION)
        }
        val overlappingMain = if (isOverlappingGroup) line.secondary else null
        val overlappingTranslation = if (isOverlappingGroup) {
            metadata.getString(LyricMetadataKeys.OVERLAPPING_SECONDARY_TRANSLATION)
        } else {
            null
        }
        val overlappingBacking = if (isOverlappingGroup) {
            metadata.getString(LyricMetadataKeys.OVERLAPPING_SECONDARY_BACKING)
        } else {
            null
        }
        val overlappingBackingTranslation = if (isOverlappingGroup) {
            metadata.getString(
                LyricMetadataKeys.OVERLAPPING_SECONDARY_BACKING_TRANSLATION
            )
        } else {
            null
        }
        return AodMediaLyricPolicy.assembleContent(
            main = displayText(main),
            translation = displayText(line?.translation),
            backing = displayText(primaryBacking),
            backingTranslation = displayText(primaryBackingTranslation),
            roma = line?.roma,
            overlappingMain = displayText(overlappingMain),
            overlappingTranslation = displayText(overlappingTranslation),
            overlappingBacking = displayText(overlappingBacking),
            overlappingBackingTranslation = displayText(overlappingBackingTranslation),
            next = displayText(nextLine?.text),
            showNext = style.showNextLyric,
            mainAlignedRight = mainAlignedRight,
            backingAlignedRight = mainAlignedRight,
            overlappingAlignedRight = backingAlignedRight,
            overlappingBackingAlignedRight = backingAlignedRight,
            mainGroupVocals =
                line?.metadata?.getBoolean(LyricMetadataKeys.GROUP_VOCALS) == true,
            nextAlignedRight = nextLine?.isAlignedRight == true,
            nextGroupVocals =
                nextLine?.metadata?.getBoolean(LyricMetadataKeys.GROUP_VOCALS) == true,
            duetLyrics = style.duetLyrics,
            centerNonDuetSong = style.centerNonDuetSong &&
                LyriconDataBridge.currentSong?.lyrics.orEmpty().none { it.isAlignedRight },
            centerGroupVocals = style.centerGroupVocals,
            translationDisplay = style.translationDisplay,
        )
    }

    private fun appendNextSongPreview(
        content: AodLyricContent,
        style: AodTextStyleConfig,
        context: Context,
        packageName: String?,
    ): AodLyricContent {
        if (!style.nextSongPreview || packageName.isNullOrBlank()) return content
        val actualLyrics = currentActualLyrics()
        val mediaInfo = MediaMetadataHelper.getMediaInfo(context, packageName, HookLogger)
        val duration = LyriconDataBridge.currentSong?.duration?.takeIf { it > 0L }
            ?: mediaInfo.duration
        val position = LyriconDataBridge.estimatedPosition()
            ?: LyriconDataBridge.currentPosition
        if (
            !AodMediaLyricPolicy.shouldShowNextSongPreview(
                enabled = true,
                positionMs = position,
                durationMs = duration,
                hasActualLyrics = actualLyrics.isNotEmpty(),
                lastLyricStartMs = actualLyrics.maxOfOrNull { it.begin } ?: -1L,
            )
        ) {
            return content
        }
        val nextSong = MediaMetadataHelper.getNextMediaInfo(
            context = context,
            packageName = packageName,
            current = mediaInfo,
        )
        val preview = AodMediaLyricPolicy.formatNextSongPreview(
            title = nextSong.title,
            artist = nextSong.artist,
        )
        return if (preview.isBlank()) {
            content
        } else {
            content.copy(
                next = preview,
                nextAlignment = AodMediaLyricPolicy.nextSongPreviewAlignment(
                    style.nextSongPreviewPosition
                ),
            )
        }
    }

    private fun currentActualLyrics() =
        LyriconDataBridge.currentSong?.lyrics.orEmpty().filterNot { line ->
            line.metadata?.getBoolean(SongPreprocessor.KEY_TITLE_LINE) == true
        }

    private fun lockScreenAodTextStyle(): AodTextStyleConfig = AodTextStyleConfig(
        mainTextSize = readAodTextSize(
            key = RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_MAIN_TEXT_SIZE,
            defaultValue = RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_MAIN_TEXT_SIZE,
            min = RootConstants.MIN_HOOK_AOD_MAIN_TEXT_SIZE,
            max = RootConstants.MAX_HOOK_AOD_MAIN_TEXT_SIZE,
        ),
        backingTextSize = readAodTextSize(
            key = RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_BACKING_TEXT_SIZE,
            defaultValue = RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_BACKING_TEXT_SIZE,
            min = RootConstants.MIN_HOOK_AOD_BACKING_TEXT_SIZE,
            max = RootConstants.MAX_HOOK_AOD_BACKING_TEXT_SIZE,
        ),
        translationTextSize = readAodTextSize(
            key = RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_TRANSLATION_TEXT_SIZE,
            defaultValue = RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_TRANSLATION_TEXT_SIZE,
            min = RootConstants.MIN_HOOK_AOD_TRANSLATION_TEXT_SIZE,
            max = RootConstants.MAX_HOOK_AOD_TRANSLATION_TEXT_SIZE,
        ),
        showNextLyric = prefs?.getBoolean(
            RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_SHOW_NEXT_LYRIC,
            RootConstants.DEFAULT_HOOK_AOD_SHOW_NEXT_LYRIC,
        ) ?: RootConstants.DEFAULT_HOOK_AOD_SHOW_NEXT_LYRIC,
        nextLyricStyle = readAodNextLyricStyle(
            RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_NEXT_LYRIC_STYLE
        ),
        duetLyrics = prefs?.getBoolean(
            RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_DUET_LYRICS,
            RootConstants.DEFAULT_HOOK_AOD_DUET_LYRICS,
        ) ?: RootConstants.DEFAULT_HOOK_AOD_DUET_LYRICS,
        centerNonDuetSong = prefs?.getBoolean(
            RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_CENTER_NON_DUET_SONG,
            RootConstants.DEFAULT_HOOK_AOD_CENTER_NON_DUET_SONG,
        ) ?: RootConstants.DEFAULT_HOOK_AOD_CENTER_NON_DUET_SONG,
        centerGroupVocals = prefs?.getBoolean(
            RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_CENTER_GROUP_VOCALS,
            RootConstants.DEFAULT_HOOK_AOD_CENTER_GROUP_VOCALS,
        ) ?: RootConstants.DEFAULT_HOOK_AOD_CENTER_GROUP_VOCALS,
        pauseStyle = readAodPauseStyle(
            RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_PAUSE_STYLE
        ),
        translationDisplay = prefs?.getBoolean(
            RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_TRANSLATION_DISPLAY,
            RootConstants.DEFAULT_HOOK_AOD_TRANSLATION_DISPLAY,
        ) ?: RootConstants.DEFAULT_HOOK_AOD_TRANSLATION_DISPLAY,
        swapTranslation = prefs?.getBoolean(
            RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_SWAP_TRANSLATION,
            RootConstants.DEFAULT_HOOK_AOD_SWAP_TRANSLATION,
        ) ?: RootConstants.DEFAULT_HOOK_AOD_SWAP_TRANSLATION,
        nextSongPreview = prefs?.getBoolean(
            RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_NEXT_SONG_PREVIEW,
            RootConstants.DEFAULT_HOOK_AOD_NEXT_SONG_PREVIEW,
        ) ?: RootConstants.DEFAULT_HOOK_AOD_NEXT_SONG_PREVIEW,
        nextSongPreviewPosition = readAodNextSongPreviewPosition(
            RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_NEXT_SONG_PREVIEW_POSITION
        ),
    )

    private fun classicAodTextStyle(): AodTextStyleConfig = AodTextStyleConfig(
        mainTextSize = readAodTextSize(
            key = RootConstants.KEY_HOOK_CLASSIC_AOD_MAIN_TEXT_SIZE,
            defaultValue = RootConstants.DEFAULT_HOOK_CLASSIC_AOD_MAIN_TEXT_SIZE,
            min = RootConstants.MIN_HOOK_AOD_MAIN_TEXT_SIZE,
            max = RootConstants.MAX_HOOK_AOD_MAIN_TEXT_SIZE,
        ),
        backingTextSize = readAodTextSize(
            key = RootConstants.KEY_HOOK_CLASSIC_AOD_BACKING_TEXT_SIZE,
            defaultValue = RootConstants.DEFAULT_HOOK_CLASSIC_AOD_BACKING_TEXT_SIZE,
            min = RootConstants.MIN_HOOK_AOD_BACKING_TEXT_SIZE,
            max = RootConstants.MAX_HOOK_AOD_BACKING_TEXT_SIZE,
        ),
        translationTextSize = readAodTextSize(
            key = RootConstants.KEY_HOOK_CLASSIC_AOD_TRANSLATION_TEXT_SIZE,
            defaultValue = RootConstants.DEFAULT_HOOK_CLASSIC_AOD_TRANSLATION_TEXT_SIZE,
            min = RootConstants.MIN_HOOK_AOD_TRANSLATION_TEXT_SIZE,
            max = RootConstants.MAX_HOOK_AOD_TRANSLATION_TEXT_SIZE,
        ),
        showNextLyric = prefs?.getBoolean(
            RootConstants.KEY_HOOK_CLASSIC_AOD_SHOW_NEXT_LYRIC,
            RootConstants.DEFAULT_HOOK_AOD_SHOW_NEXT_LYRIC,
        ) ?: RootConstants.DEFAULT_HOOK_AOD_SHOW_NEXT_LYRIC,
        nextLyricStyle = readAodNextLyricStyle(
            RootConstants.KEY_HOOK_CLASSIC_AOD_NEXT_LYRIC_STYLE
        ),
        duetLyrics = prefs?.getBoolean(
            RootConstants.KEY_HOOK_CLASSIC_AOD_DUET_LYRICS,
            RootConstants.DEFAULT_HOOK_AOD_DUET_LYRICS,
        ) ?: RootConstants.DEFAULT_HOOK_AOD_DUET_LYRICS,
        centerNonDuetSong = prefs?.getBoolean(
            RootConstants.KEY_HOOK_CLASSIC_AOD_CENTER_NON_DUET_SONG,
            RootConstants.DEFAULT_HOOK_AOD_CENTER_NON_DUET_SONG,
        ) ?: RootConstants.DEFAULT_HOOK_AOD_CENTER_NON_DUET_SONG,
        centerGroupVocals = prefs?.getBoolean(
            RootConstants.KEY_HOOK_CLASSIC_AOD_CENTER_GROUP_VOCALS,
            RootConstants.DEFAULT_HOOK_AOD_CENTER_GROUP_VOCALS,
        ) ?: RootConstants.DEFAULT_HOOK_AOD_CENTER_GROUP_VOCALS,
        pauseStyle = readAodPauseStyle(
            RootConstants.KEY_HOOK_CLASSIC_AOD_PAUSE_STYLE
        ),
        translationDisplay = prefs?.getBoolean(
            RootConstants.KEY_HOOK_CLASSIC_AOD_TRANSLATION_DISPLAY,
            RootConstants.DEFAULT_HOOK_AOD_TRANSLATION_DISPLAY,
        ) ?: RootConstants.DEFAULT_HOOK_AOD_TRANSLATION_DISPLAY,
        swapTranslation = prefs?.getBoolean(
            RootConstants.KEY_HOOK_CLASSIC_AOD_SWAP_TRANSLATION,
            RootConstants.DEFAULT_HOOK_AOD_SWAP_TRANSLATION,
        ) ?: RootConstants.DEFAULT_HOOK_AOD_SWAP_TRANSLATION,
        nextSongPreview = prefs?.getBoolean(
            RootConstants.KEY_HOOK_CLASSIC_AOD_NEXT_SONG_PREVIEW,
            RootConstants.DEFAULT_HOOK_AOD_NEXT_SONG_PREVIEW,
        ) ?: RootConstants.DEFAULT_HOOK_AOD_NEXT_SONG_PREVIEW,
        nextSongPreviewPosition = readAodNextSongPreviewPosition(
            RootConstants.KEY_HOOK_CLASSIC_AOD_NEXT_SONG_PREVIEW_POSITION
        ),
    )

    private fun readAodTextSize(
        key: String,
        defaultValue: Int,
        min: Int,
        max: Int,
    ): Int = AodMediaLyricPolicy.sanitizeTextSize(
        value = prefs?.getInt(key, defaultValue) ?: defaultValue,
        defaultValue = defaultValue,
        min = min,
        max = max,
    )

    private fun readAodNextLyricStyle(key: String): Int =
        AodMediaLyricPolicy.sanitizeNextLyricStyle(
            prefs?.getInt(
                key,
                RootConstants.DEFAULT_HOOK_AOD_NEXT_LYRIC_STYLE,
            ) ?: RootConstants.DEFAULT_HOOK_AOD_NEXT_LYRIC_STYLE
        )

    private fun readAodNextSongPreviewPosition(key: String): Int =
        AodMediaLyricPolicy.sanitizeNextSongPreviewPosition(
            prefs?.getInt(
                key,
                RootConstants.DEFAULT_HOOK_AOD_NEXT_SONG_PREVIEW_POSITION,
            ) ?: RootConstants.DEFAULT_HOOK_AOD_NEXT_SONG_PREVIEW_POSITION
        )

    private fun readAodPauseStyle(key: String): Int =
        (prefs?.getInt(
            key,
            RootConstants.DEFAULT_HOOK_AOD_PAUSE_STYLE,
        ) ?: RootConstants.DEFAULT_HOOK_AOD_PAUSE_STYLE).coerceIn(
            RootConstants.AOD_PAUSE_STYLE_RESTORE,
            RootConstants.AOD_PAUSE_STYLE_KEEP_LYRICS,
        )

    private fun applyContentAlignment(
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

    private fun applyContentAlignment(
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

    private fun applyLockScreenTextStyle(
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

    private fun applyClassicTextStyle(
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

    private fun resolvePlaying(api: NativeApi, controller: Any, mediaData: Any?): Boolean {
        return LyriconDataBridge.currentPlaybackState
            ?: api.isControllerPlaying(controller)
            ?: api.isPlaying(mediaData)
    }

    private fun isEnabled(): Boolean = prefs?.getBoolean(
        RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS,
        RootConstants.DEFAULT_HOOK_ENABLE_AOD_LYRICS
    ) ?: RootConstants.DEFAULT_HOOK_ENABLE_AOD_LYRICS

    private fun resolveApi(classLoader: ClassLoader?): NativeApi? {
        classLoader ?: return null
        nativeApis[classLoader]?.let { return it }
        return runCatching { NativeApi.create(classLoader) }
            .onSuccess { nativeApis[classLoader] = it }
            .onFailure { HookLogger.w(TAG, "息屏歌词接口不可用: reason=${it.message}") }
            .getOrNull()
    }

    private fun resolveAodPluginApi(classLoader: ClassLoader?): AodPluginApi? {
        classLoader ?: return null
        aodPluginApis[classLoader]?.let { return it }
        return runCatching { AodPluginApi.create(classLoader) }
            .onSuccess { aodPluginApis[classLoader] = it }
            .onFailure {
                HookLogger.w(TAG, "通知图标式息屏歌词接口不可用: reason=${it.message}")
            }
            .getOrNull()
    }

    private fun requestAodFrameRefresh(classLoader: ClassLoader?) {
        val refreshApi = classLoader?.let(dozeRefreshApis::get)
            ?: synchronized(dozeRefreshApis) { dozeRefreshApis.values.firstOrNull() }
        if (refreshApi == null) {
            HookLogger.w(TAG, "跳过 AOD 原生帧刷新: reason=api_unavailable")
            return
        }
        refreshApi.requestTick()
    }

    private inline fun runOnMain(crossinline action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else mainHandler.post { action() }
    }

    private fun setOptionalText(view: TextView, text: String) {
        view.text = text
        view.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    private data class ClassicAodEmbeddedSongInfo(
        val text: String,
        val sourcePackage: String?,
        val textSize: Int = RootConstants.DEFAULT_HOOK_CLASSIC_AOD_SONG_INFO_TEXT_SIZE,
        val showIcon: Boolean = RootConstants.DEFAULT_HOOK_CLASSIC_AOD_SONG_INFO_SHOW_ICON,
    )

    private fun currentClassicAodEmbeddedSongInfo(): ClassicAodEmbeddedSongInfo {
        val currentPrefs = prefs ?: return ClassicAodEmbeddedSongInfo("", null)
        if (
            ClassicAodSongInfoConfig.displayStyle(currentPrefs) !=
                RootConstants.AOD_SONG_INFO_DISPLAY_STYLE_TEXT_EMBEDDED
        ) {
            return ClassicAodEmbeddedSongInfo("", null)
        }
        val song = LyriconDataBridge.currentSong
        val text = ClassicAodSongInfoConfig.formatSongInfo(
            title = song?.name.orEmpty(),
            artist = song?.artist.orEmpty(),
            format = ClassicAodSongInfoConfig.format(currentPrefs)
        )
        return ClassicAodEmbeddedSongInfo(
            text = text,
            sourcePackage = LyriconDataBridge.currentLyricPackageName
                ?: LyriconDataBridge.activePackageName,
            textSize = ClassicAodSongInfoConfig.embeddedTextSize(currentPrefs),
            showIcon = ClassicAodSongInfoConfig.showsEmbeddedIcon(currentPrefs),
        )
    }

    private fun updateClassicEmbeddedSongInfo(
        overlay: AodPluginOverlay,
        songInfo: ClassicAodEmbeddedSongInfo,
    ) {
        if (songInfo.text.isBlank()) {
            overlay.songInfoRow.visibility = View.GONE
            overlay.songInfo.text = ""
            overlay.sourceIcon.setImageDrawable(null)
            overlay.appliedSongInfoPackage = null
            overlay.appliedSongInfoTextSize = songInfo.textSize
            overlay.appliedSongInfoShowsIcon = songInfo.showIcon
            return
        }

        overlay.songInfo.text = songInfo.text
        overlay.songInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, songInfo.textSize.toFloat())
        updateClassicSongInfoIconLayout(overlay, songInfo.textSize)
        if (overlay.appliedSongInfoPackage != songInfo.sourcePackage) {
            overlay.appliedSongInfoPackage = songInfo.sourcePackage
            val icon = songInfo.sourcePackage?.let { packageName ->
                runCatching {
                    overlay.root.context.packageManager.getApplicationIcon(packageName)
                }.getOrNull()
            }
            overlay.sourceIcon.setImageDrawable(icon)
        }
        overlay.sourceIcon.visibility = if (
            songInfo.showIcon && overlay.sourceIcon.drawable != null
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        overlay.appliedSongInfoTextSize = songInfo.textSize
        overlay.appliedSongInfoShowsIcon = songInfo.showIcon
        overlay.songInfoRow.visibility = View.VISIBLE
    }

    private fun updateClassicSongInfoIconLayout(
        overlay: AodPluginOverlay,
        textSize: Int,
    ) {
        val params = overlay.sourceIcon.layoutParams as? LinearLayout.LayoutParams ?: return
        val scale = textSize.toFloat() /
            RootConstants.DEFAULT_HOOK_CLASSIC_AOD_SONG_INFO_TEXT_SIZE
        val density = overlay.root.resources.displayMetrics.density
        val iconSize = (AOD_PLUGIN_SONG_INFO_ICON_DP * density * scale)
            .roundToInt()
            .coerceAtLeast(1)
        val iconGap = (AOD_PLUGIN_SONG_INFO_ICON_GAP_DP * density * scale)
            .roundToInt()
            .coerceAtLeast(0)
        if (
            params.width != iconSize ||
            params.height != iconSize ||
            params.marginEnd != iconGap
        ) {
            params.width = iconSize
            params.height = iconSize
            params.marginEnd = iconGap
            overlay.sourceIcon.layoutParams = params
        }
    }

    private fun updateClassicSongInfoMaxWidth(
        overlay: AodPluginOverlay,
        overlayWidth: Int,
    ) {
        if (overlay.songInfoRow.visibility != View.VISIBLE) return
        val rowWidth = overlayWidth -
            overlay.root.paddingLeft -
            overlay.root.paddingRight
        val iconWidth = if (overlay.sourceIcon.visibility == View.VISIBLE) {
            val iconParams = overlay.sourceIcon.layoutParams as? LinearLayout.LayoutParams
            (iconParams?.width ?: 0) + (iconParams?.marginEnd ?: 0)
        } else {
            0
        }
        val maxTextWidth = (rowWidth - iconWidth).coerceAtLeast(0)
        if (overlay.songInfo.maxWidth != maxTextWidth) {
            overlay.songInfo.maxWidth = maxTextWidth
        }
    }

    private fun updateLockScreenLineSpacing(overlay: LyricOverlay) {
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

    private fun applyLyricRowOrder(
        overlay: LyricOverlay,
        swapTranslation: Boolean,
    ) {
        reorderLyricViews(
            root = overlay.root,
            orderedViews = orderedLyricViews(overlay, swapTranslation),
        )
    }

    private fun applyLyricRowOrder(
        overlay: AodPluginOverlay,
        swapTranslation: Boolean,
    ) {
        reorderLyricViews(
            root = overlay.root,
            orderedViews = orderedLyricViews(overlay, swapTranslation),
        )
    }

    private fun orderedLyricViews(
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

    private fun orderedLyricViews(
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

    private data class ViewSizeSnapshot(
        val view: View,
        val originalLayoutHeight: Int,
        val originalMinimumHeight: Int,
        var baseHeight: Int
    )

    private class BackgroundConstraints private constructor(
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
                    TAG,
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

    private class LyricOverlay(
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
    )

    private class MediaHeaderHeightController private constructor(
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
                if (view?.javaClass?.name != MEDIA_HEADER_VIEW_CLASS) return null
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
                    HookLogger.e(TAG, "初始化锁屏媒体通知外层动态高度接口失败", it)
                }.getOrNull()
            }
        }
    }

    private class AodPluginOverlay(
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

    private data class AodPluginState(
        var attached: Boolean = false,
        var playing: Boolean = false,
        var overlay: AodPluginOverlay? = null,
        var initialRefreshGeneration: Int = 0
    )

    private data class ControllerState(
        var holder: Any? = null,
        var mediaData: Any? = null,
        var fullAod: Boolean = false,
        var aodActive: Boolean = false,
        var playing: Boolean = false,
        var overlay: LyricOverlay? = null,
        val actionVisibilities: MutableMap<View, Int> = LinkedHashMap()
    )

    private class AodPluginApi private constructor(
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
                val aodViewClass = classLoader.loadClass(AOD_PLUGIN_VIEW_CLASS)
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

    private class NativeApi private constructor(
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
                val controllerClass = classLoader.loadClass(VIEW_CONTROLLER_CLASS)
                val holderClass = classLoader.loadClass(HOLDER_CLASS)
                val mediaDataClass = classLoader.loadClass(MEDIA_DATA_CLASS)
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

    private class DozeRefreshApi private constructor(
        val hostConstructors: List<Constructor<*>>,
        private val tickRunnableConstructor: Constructor<*>
    ) {
        private var hostReference = WeakReference<Any>(null)
        private var didLogFirstTick = false

        fun captureHost(host: Any) {
            hostReference = WeakReference(host)
            HookLogger.i(TAG, "已捕获 DozeServiceHost，AOD 原生刷新可用")
        }

        fun requestTick() {
            val host = hostReference.get()
            if (host == null) {
                HookLogger.w(TAG, "跳过 AOD 原生帧刷新: reason=host_unavailable")
                return
            }
            runCatching {
                (tickRunnableConstructor.newInstance(host) as Runnable).run()
            }
                .onSuccess {
                    if (!didLogFirstTick) {
                        didLogFirstTick = true
                        HookLogger.i(TAG, "已通过 SystemUI dozeTimeTick 提交 AOD 帧")
                    }
                }
                .onFailure { HookLogger.e(TAG, "SystemUI dozeTimeTick 执行失败", it) }
        }

        companion object {
            fun create(classLoader: ClassLoader): DozeRefreshApi {
                val hostClass = classLoader.loadClass(DOZE_SERVICE_HOST_CLASS)
                val tickRunnableClass = classLoader.loadClass(DOZE_TICK_RUNNABLE_CLASS)
                return DozeRefreshApi(
                    hostConstructors = hostClass.declaredConstructors
                        .onEach { it.isAccessible = true }
                        .toList(),
                    tickRunnableConstructor = tickRunnableClass
                        .getDeclaredConstructor(hostClass)
                        .apply { isAccessible = true }
                )
            }
        }
    }
}
