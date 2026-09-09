/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

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

internal data class BinderState(
    var colorToken: String? = null,
    var palette: MediaAmbientFlowPalette? = null,
    var customColorToken: String? = null,
    var customArtwork: MediaFlowArtwork? = null,
    val customTimeline: MediaFlowTimeline = MediaFlowTimeline(),
    val customViews: MutableMap<View, MediaFlowBackgroundView> = mutableMapOf(),
    val request: AtomicInteger = AtomicInteger()
)

internal data class FakeFlowState(
    val binder: Any,
    var target: IslandExpandedBackgroundTarget,
    var api: NativeApi,
    val flowView: MediaFlowBackgroundView,
    var originalTransitionBackground: Drawable? = null,
    var originalOccludingBackgrounds: List<Pair<View, Drawable?>> = emptyList(),
    var hiddenHolderFlows: List<MediaFlowBackgroundView> = emptyList(),
    var active: Boolean = false
)

internal data class ViewThemeState(
    val target: IslandExpandedBackgroundTarget,
    val miniBar: View?,
    val originalMiniBarTint: ColorStateList?,
    var notificationLightContext: Context
)

internal data class SeekBarThemeState(
    val originalColorFilter: ColorFilter?,
    val originalHeadGlowAlpha: Float,
    var suppressHeadGlow: Boolean = false
)

internal data class CardColors(
    val primaryText: Int,
    val secondaryText: Int,
    val durationText: Int,
    val action: Int,
    val seekBarForeground: Int,
    val seekBarBackground: Int
) {
    companion object {
        fun from(context: Context): CardColors {
            // 文字、图标、进度条统一保持深色卡片的白色层级（夜间值）：
            // 歌名/按钮 90% 白、歌手 50% 白、时长 40% 白、轨道 10% 白；
            // 进度条前景无媒体专用夜间资源，用与歌名同级的 90% 白。
            val whiteContext = context.withNightMode(Configuration.UI_MODE_NIGHT_YES)
            fun nightColor(name: String): Int {
                val id = context.resources.getIdentifier(name, "color", context.packageName)
                require(id != 0) { "Missing color resource: $name" }
                return whiteContext.getColor(id)
            }
            return CardColors(
                primaryText = nightColor("media_primary_text"),
                secondaryText = nightColor("media_secondary_text"),
                durationText = nightColor("media_duration_time_font_color"),
                action = nightColor("notification_media_action_button_color"),
                seekBarForeground = android.graphics.Color.argb(0xE5, 0xFF, 0xFF, 0xFF),
                seekBarBackground = nightColor("media_seekbar_background_color")
            )
        }
    }
}

internal class NativeApi private constructor(
    val hookMethods: List<Method>,
    private val hostClassLoader: ClassLoader,
    private val holderField: Field,
    private val dummyHolderField: Field,
    private val artworkField: Field,
    private val mediaBgViewField: Field,
    private val playerField: Field,
    private val contextField: Field,
    private val titleTextField: Field,
    private val artistTextField: Field,
    private val elapsedTimeViewField: Field,
    private val totalTimeViewField: Field,
    private val seamlessIconField: Field,
    private val albumViewField: Field,
    private val albumImageField: Field,
    private val appIconField: Field,
    private val seamlessField: Field,
    private val mediaDataField: Field,
    private val mediaDataIsPlayingField: Field,
    private val mediaDataPackageNameField: Field,
    private val seekBarField: Field,
    private val seekBarPaintField: Field,
    private val seekBarRuntimeShaderField: Field,
    private val seekBarHeadGlowAlphaField: Field,
    private val headAlphaListenerSeekBarField: Field,
    private val getActionListMethod: Method,
    private val updateForegroundColorsMethod: Method,
    private val setSeekBarForegroundMethod: Method,
    private val setSeekBarBackgroundMethod: Method,
    private val pauseMethod: Method,
    private val setGradientColorMethod: Method,
    private val getPaletteColorMethod: Method
) : IslandExpandedMediaBackgroundApi {
    private val expandedBackgroundMethods = Collections.synchronizedMap(
        WeakHashMap<ClassLoader, ExpandedBackgroundMethods>()
    )

    fun getHolders(binder: Any): List<Any> {
        return listOfNotNull(holderField.get(binder), dummyHolderField.get(binder)).distinct()
    }

    fun getMusicBgViews(binder: Any): List<View> {
        return getHolders(binder).mapNotNull { holder -> getMusicBgView(holder) }
            .distinct()
    }

    fun getMusicBgView(holder: Any): View = mediaBgViewField.get(holder) as View

    fun getPlayer(holder: Any): View = playerField.get(holder) as View

    fun getMediaElements(holder: Any): IslandExpandedMediaElements {
        val player = getPlayer(holder)
        @Suppress("UNCHECKED_CAST")
        val actions = getActionListMethod.invoke(holder) as List<View>
        val actionsId = player.resources.getIdentifier(
            "actions",
            "id",
            player.context.packageName
        )
        require(actionsId != 0) { "Missing SystemUI id resource: actions" }
        return IslandExpandedMediaElements(
            albumView = albumViewField.get(holder) as View,
            albumImage = albumImageField.get(holder) as ImageView,
            coverSource = appIconField.get(holder) as ImageView,
            deviceSwitch = seamlessField.get(holder) as View,
            title = titleTextField.get(holder) as View,
            artist = artistTextField.get(holder) as View,
            actionsAnchor = requireNotNull(player.findViewById(actionsId)),
            firstAction = actions.first(),
            player = player
        )
    }

    fun isPlaying(binder: Any): Boolean {
        val mediaData = mediaDataField.get(binder) ?: return false
        return mediaDataIsPlayingField.get(mediaData) == true
    }

    fun getSeekBar(holder: Any): View = seekBarField.get(holder) as View

    fun getHeadAlphaListenerSeekBar(listener: Any): View {
        return headAlphaListenerSeekBarField.get(listener) as View
    }

    override fun getContext(binder: Any): Context = contextField.get(binder) as Context

    override fun getPackageName(binder: Any): String? {
        val mediaData = mediaDataField.get(binder) ?: return null
        return mediaDataPackageNameField.get(mediaData) as? String
    }

    override fun getBackgroundHosts(binder: Any): List<IslandExpandedMediaBackgroundHost> {
        return getHolders(binder).mapNotNull { holder ->
            val target = findExpandedBackgroundTarget(getPlayer(holder)) ?: return@mapNotNull null
            IslandExpandedMediaBackgroundHost(target, holder, getMiniBar(target))
        }
    }

    fun applyNativeForeground(binder: Any, holder: Any) {
        updateForegroundColorsMethod.invoke(binder, holder)
    }

    fun applyLightForeground(holder: Any, colors: CardColors) {
        (titleTextField.get(holder) as TextView).setTextColor(colors.primaryText)
        (artistTextField.get(holder) as TextView).setTextColor(colors.secondaryText)
        (elapsedTimeViewField.get(holder) as TextView).setTextColor(colors.durationText)
        (totalTimeViewField.get(holder) as TextView).setTextColor(colors.durationText)
        val tint = ColorStateList.valueOf(colors.action)
        (seamlessIconField.get(holder) as ImageView).imageTintList =
            ColorStateList.valueOf(colors.seekBarForeground)
        @Suppress("UNCHECKED_CAST")
        (getActionListMethod.invoke(holder) as List<Any>).forEach { action ->
            (action as ImageView).apply {
                imageTintBlendMode = BlendMode.SRC_IN
                imageTintList = tint
            }
        }
        val seekBar = getSeekBar(holder)
        setSeekBarForegroundMethod.invoke(seekBar, colors.seekBarForeground)
        setSeekBarBackgroundMethod.invoke(seekBar, colors.seekBarBackground)
        setSeekBarShaderColorFilter(
            seekBar,
            BlendModeColorFilter(colors.seekBarForeground, BlendMode.SRC_IN)
        )
        setSeekBarHeadGlowAlpha(seekBar, 0f)
    }

    override fun applyCustomForeground(
        holder: Any,
        colors: NotificationMediaColorConfig
    ) {
        val seekBar = getSeekBar(holder)
        val state = IslandExpandedMediaAmbientFlowHooker.seekBarThemeStates.getOrPut(seekBar) {
            SeekBarThemeState(
                originalColorFilter = getSeekBarShaderColorFilter(seekBar),
                originalHeadGlowAlpha = getSeekBarHeadGlowAlpha(seekBar)
            )
        }
        state.suppressHeadGlow = false
        (titleTextField.get(holder) as TextView).setTextColor(colors.textPrimary)
        (artistTextField.get(holder) as TextView).setTextColor(colors.textSecondary)
        (elapsedTimeViewField.get(holder) as TextView).setTextColor(colors.textSecondary)
        (totalTimeViewField.get(holder) as TextView).setTextColor(colors.textSecondary)
        val tint = ColorStateList.valueOf(colors.textPrimary)
        (seamlessIconField.get(holder) as ImageView).imageTintList = tint
        @Suppress("UNCHECKED_CAST")
        (getActionListMethod.invoke(holder) as List<Any>).forEach { action ->
            (action as ImageView).apply {
                imageTintBlendMode = BlendMode.SRC_IN
                imageTintList = tint
            }
        }
        setSeekBarForegroundMethod.invoke(seekBar, colors.textPrimary)
        setSeekBarBackgroundMethod.invoke(
            seekBar,
            colors.textPrimary and 0x00ffffff or (0x33 shl 24)
        )
        setSeekBarShaderColorFilter(
            seekBar,
            BlendModeColorFilter(colors.textPrimary, BlendMode.SRC_IN)
        )
        setSeekBarHeadGlowAlpha(seekBar, state.originalHeadGlowAlpha)
    }

    fun getSeekBarShaderColorFilter(seekBar: View): ColorFilter? {
        return (seekBarPaintField.get(seekBar) as Paint).colorFilter
    }

    fun setSeekBarShaderColorFilter(seekBar: View, colorFilter: ColorFilter?) {
        (seekBarPaintField.get(seekBar) as Paint).colorFilter = colorFilter
        seekBar.invalidate()
    }

    fun getSeekBarHeadGlowAlpha(seekBar: View): Float {
        return seekBarHeadGlowAlphaField.getFloat(seekBar)
    }

    fun setSeekBarHeadGlowAlpha(seekBar: View, alpha: Float) {
        seekBarHeadGlowAlphaField.setFloat(seekBar, alpha)
        (seekBarRuntimeShaderField.get(seekBar) as? RuntimeShader)?.setFloatUniform(
            "uHeadGlowAlpha",
            alpha
        )
        seekBar.invalidate()
    }

    fun findExpandedBackgroundTarget(player: View): IslandExpandedBackgroundTarget? {
        var current: View? = player
        var expandedView: View? = null
        repeat(16) {
            current = current?.parent as? View ?: return null
            if (current.javaClass.name == IslandExpandedMediaAmbientFlowHooker.EXPANDED_VIEW_CLASS) expandedView = current
            if (expandedView != null && current.javaClass.isOrExtends(IslandExpandedMediaAmbientFlowHooker.BASE_CONTENT_VIEW_CLASS)) {
                val owner = current
                val expanded = expandedView
                fun dimension(name: String): Int {
                    return (owner.javaClass.methods.single {
                        it.name == name && it.parameterTypes.isEmpty()
                    }.invoke(owner) as Number).toInt()
                }
                return IslandExpandedBackgroundTarget(
                    owner = owner,
                    expandedView = expanded,
                    viewportWidth = dimension("getExpandedViewWidth"),
                    viewportHeight = dimension("getExpandedViewHeight")
                )
            }
        }
        return null
    }

    fun findContentBackgroundTarget(contentView: View): IslandExpandedBackgroundTarget? {
        val isFakeView = contentView.javaClass.name == IslandExpandedMediaAmbientFlowHooker.FAKE_CONTENT_VIEW_CLASS
        val getterName = if (isFakeView) "getFakeExpandedView" else "getExpandedView"
        val expandedView = contentView.javaClass.methods.firstOrNull {
            it.name == getterName && it.parameterTypes.isEmpty()
        }?.invoke(contentView) as? View ?: return null
        if (isFakeView) {
            val realView = contentView.javaClass.methods.single {
                it.name == "getRealView" && it.parameterTypes.isEmpty()
            }.invoke(contentView) as View
            fun realDimension(name: String): Int {
                return (realView.javaClass.methods.single {
                    it.name == name && it.parameterTypes.isEmpty()
                }.invoke(realView) as Number).toInt()
            }
            val left = realDimension("getExpandedViewMarginHorizontal")
            val top = realDimension("getIslandViewMarginTop")
            val width = realDimension("getExpandedViewWidth")
            val height = realDimension("getExpandedViewHeight")
            val fakeContainer = contentView.javaClass.methods.single {
                it.name == "getFakeContainer" && it.parameterTypes.isEmpty()
            }.invoke(contentView) as View
            return IslandExpandedBackgroundTarget(
                owner = contentView,
                expandedView = expandedView,
                customBackgroundView = expandedView,
                extensionBackgroundView = contentView,
                viewportWidth = width,
                viewportHeight = height,
                transitionContentBounds = Rect(left, top, left + width, top + height),
                transitionOccludingViews = listOf(fakeContainer),
                nativeBackgroundViews = listOf(expandedView)
            )
        }
        return IslandExpandedBackgroundTarget(contentView, expandedView)
    }

    fun getMiniBar(target: IslandExpandedBackgroundTarget): View? {
        return expandedBackgroundMethods(target).getMiniBar.invoke(target.owner) as? View
    }

    fun applyLiveUpdateBackground(
        target: IslandExpandedBackgroundTarget,
        islandLightContext: Context,
        notificationLightContext: Context
    ) {
        val view = target.expandedView
        val methods = expandedBackgroundMethods(target)
        val blurOpened = methods.isBackgroundOpened.invoke(null, view.context) as Boolean
        val requestedMode = IslandExpandedMediaLightBackgroundPolicy.select(
            hasBionicsMaterialApi = methods.hasBionicsMaterialApi,
            hasNotificationGlassApi = methods.hasNotificationGlassApi,
            blurOpened = blurOpened,
            attached = view.parent != null,
        )
        var appliedMode = requestedMode
        var glassDiagnostics: NotificationGlassDiagnostics? = null
        when (requestedMode) {
            IslandExpandedMediaLightBackgroundMode.NOTIFICATION_GLASS -> {
                glassDiagnostics = runCatching {
                    methods.applyNotificationGlass(view, notificationLightContext)
                }.onFailure { error ->
                    HookLogger.e(
                        IslandExpandedMediaAmbientFlowHooker.TAG,
                        "通知中心玻璃背景应用失败，使用 Drawable 降级",
                        error
                    )
                }.getOrNull()
                if (glassDiagnostics == null) {
                    appliedMode = IslandExpandedMediaLightBackgroundMode.DRAWABLE_FALLBACK
                    methods.applyDrawableFallback(view, islandLightContext)
                }
            }

            IslandExpandedMediaLightBackgroundMode.LEGACY_BLUR -> {
                methods.clearBionicsMaterial(view)
                val blendColors = intArrayOf(
                    islandLightContext.getColor(methods.liveUpdateBlendColor1Id),
                    islandLightContext.resources.getInteger(methods.blurModeLinearLightId),
                    islandLightContext.getColor(methods.liveUpdateBlendColor2Id),
                    islandLightContext.resources.getInteger(methods.blurModeLabId),
                    islandLightContext.getColor(methods.liveUpdateBlendColor3Id),
                    islandLightContext.resources.getInteger(methods.blurModePureId)
                )
                methods.setMiViewBlurMode.invoke(null, view, 1)
                methods.clearMiBackgroundBlendColor.invoke(null, view)
                methods.setMiBackgroundBlendColors.invoke(
                    null,
                    view,
                    blendColors,
                    0.0f,
                    2,
                    null
                )
                view.background = null
            }

            IslandExpandedMediaLightBackgroundMode.DRAWABLE_FALLBACK -> {
                methods.applyDrawableFallback(view, islandLightContext)
            }
        }

        if (BuildConfig.DEBUG) {
            val previousMode = IslandExpandedMediaAmbientFlowHooker.lightBackgroundModes.put(view, appliedMode)
            if (previousMode != appliedMode) {
                val glassDetails = glassDiagnostics?.let { diagnostics ->
                    ", resourcePackage=${diagnostics.resourcePackage}, " +
                        "shadeColors=${diagnostics.colors.joinToString(
                            prefix = "[",
                            postfix = "]"
                        ) { color -> "#%08X".format(color) }}, " +
                        "shadeModes=${diagnostics.modes.contentToString()}, " +
                        "glassParams=${diagnostics.glassParamCount}, glassApplied=true"
                } ?: ", glassApplied=false"
                HookLogger.d(
                    IslandExpandedMediaAmbientFlowHooker.TAG,
                    "展开态浅色背景路径: mode=$appliedMode, requested=$requestedMode, " +
                        "bionicsApi=${methods.hasBionicsMaterialApi}, " +
                        "notificationGlassApi=${methods.hasNotificationGlassApi}, " +
                        "blurOpened=$blurOpened, attached=${view.parent != null}, " +
                        "target=${view.javaClass.name}@${System.identityHashCode(view)}, " +
                        "hostContext=${notificationLightContext.packageName}" +
                        glassDetails
                )
            }
        }
    }

    fun restoreNativeExpandedBackground(target: IslandExpandedBackgroundTarget) {
        expandedBackgroundMethods(target).updateBackgroundBg.invoke(
            target.owner,
            target.expandedView,
            false
        )
    }

    override fun prepareCustomBackground(target: IslandExpandedBackgroundTarget) {
        val methods = expandedBackgroundMethods(target)
        target.nativeBackgroundViews.forEach { view ->
            methods.clearBionicsMaterial(view)
            methods.setMiViewBlurMode.invoke(null, view, 0)
            methods.clearMiBackgroundBlendColor.invoke(null, view)
            if (view !== target.customBackgroundView) view.background = null
        }
    }

    override fun restoreNativeBackground(target: IslandExpandedBackgroundTarget) {
        target.nativeBackgroundViews.forEach { view ->
            expandedBackgroundMethods(target).updateBackgroundBg.invoke(
                target.owner,
                view,
                false
            )
        }
    }

    private fun expandedBackgroundMethods(
        target: IslandExpandedBackgroundTarget
    ): ExpandedBackgroundMethods {
        val ownerClass = target.owner.javaClass
        val classLoader = requireNotNull(ownerClass.classLoader) {
            "Expanded island view has no ClassLoader"
        }
        return synchronized(expandedBackgroundMethods) {
            expandedBackgroundMethods.getOrPut(classLoader) {
                ExpandedBackgroundMethods.create(ownerClass, classLoader, hostClassLoader)
            }
        }
    }

    private fun Class<*>.isOrExtends(className: String): Boolean {
        var current: Class<*>? = this
        while (current != null) {
            if (current.name == className) return true
            current = current.superclass
        }
        return false
    }

    override fun getArtwork(binder: Any): Drawable? = artworkField.get(binder) as? Drawable

    fun pause(view: View) {
        pauseMethod.invoke(view)
    }

    fun setGradientColor(view: View, mainColor: Int, colors: IntArray) {
        setGradientColorMethod.invoke(view, mainColor, colors)
    }

    fun createPalette(mainColor: Int): MediaAmbientFlowPalette {
        val colors = intArrayOf(
            getPaletteColor(mainColor, "primary", 12),
            getPaletteColor(mainColor, "primary", 10),
            getPaletteColor(mainColor, "tertiary", 12)
        )
        return MediaAmbientFlowPalette(mainColor, colors)
    }

    private fun getPaletteColor(mainColor: Int, role: String, tone: Int): Int {
        return getPaletteColorMethod.invoke(null, mainColor, role, tone) as Int
    }

    companion object {
        fun create(classLoader: ClassLoader): NativeApi {
            val binderClass = classLoader.loadClass(IslandExpandedMediaAmbientFlowHooker.BINDER_CLASS)
            val holderClass = classLoader.loadClass(
                "com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaViewHolder"
            )
            val musicBgViewClass = classLoader.loadClass(IslandExpandedMediaAmbientFlowHooker.MUSIC_BG_VIEW_CLASS)
            val mediaDataClass = classLoader.loadClass(
                "com.android.systemui.media.controls.shared.model.MediaData"
            )
            val miPaletteClass = classLoader.loadClass("miuix.mipalette.MiPalette")
            miPaletteClass.declaredMethods.firstOrNull { method ->
                method.name == "init" && method.parameterCount == 0
            }?.apply { isAccessible = true }?.invoke(null)
            val getPaletteColor = miPaletteClass.getDeclaredMethod(
                "getPaletteColor",
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
            val attach = binderClass.declaredMethods.single {
                it.name == "attach" && it.parameterCount == 2
            }.apply { isAccessible = true }
            val bind = binderClass.declaredMethods.single {
                it.name == "bindMediaData" && it.parameterCount == 1
            }.apply { isAccessible = true }
            val detach = binderClass.declaredMethods.single {
                it.name == "detach" && it.parameterCount == 0
            }.apply { isAccessible = true }
            val artworkUpdate = binderClass.declaredMethods
                .firstOrNull(IslandExpandedMediaBinderMethodProfile::isArtworkUpdate)
                ?.apply { isAccessible = true }
                ?.also { method ->
                    HookLogger.d(IslandExpandedMediaAmbientFlowHooker.TAG, "展开态媒体封面刷新 Hook 目标: $method")
                }
                ?: run {
                    HookLogger.w(IslandExpandedMediaAmbientFlowHooker.TAG, "展开态媒体封面刷新 Hook 目标不存在，继续安装其余媒体 Hook")
                    null
                }
            val setSeamless = binderClass.declaredMethods.single {
                it.name == "setSeamless" && it.parameterCount == 2
            }.apply { isAccessible = true }
            val start = musicBgViewClass.getDeclaredMethod("start").apply {
                isAccessible = true
            }
            val resume = musicBgViewClass.getDeclaredMethod("resume").apply {
                isAccessible = true
            }
            val seekBarClass = holderClass.getDeclaredField("seekBar").type
            val headAlphaListenerClass = classLoader.loadClass(
                IslandExpandedMediaAmbientFlowHooker.SEEK_BAR_HEAD_ALPHA_LISTENER_CLASS
            )
            val headAlphaUpdate = headAlphaListenerClass.declaredMethods.single {
                it.name == "onUpdate" && it.parameterCount == 2
            }.apply { isAccessible = true }

            return NativeApi(
                hookMethods = listOfNotNull(
                    attach,
                    bind,
                    detach,
                    artworkUpdate,
                    setSeamless,
                    binderClass.declaredMethods.single {
                        it.name == "updateForegroundColors" && it.parameterCount == 1
                    }.apply { isAccessible = true },
                    start,
                    resume,
                    headAlphaUpdate
                ),
                hostClassLoader = classLoader,
                holderField = binderClass.getDeclaredField("holder").apply {
                    isAccessible = true
                },
                dummyHolderField = binderClass.getDeclaredField("dummyHolder").apply {
                    isAccessible = true
                },
                artworkField = binderClass.getDeclaredField("artWorkDrawable").apply {
                    isAccessible = true
                },
                mediaBgViewField = holderClass.getDeclaredField("mediaBgView").apply {
                    isAccessible = true
                },
                playerField = holderClass.getDeclaredField("player").apply {
                    isAccessible = true
                },
                contextField = binderClass.getDeclaredField("context").apply {
                    isAccessible = true
                },
                titleTextField = holderClass.getDeclaredField("titleText").apply {
                    isAccessible = true
                },
                artistTextField = holderClass.getDeclaredField("artistText").apply {
                    isAccessible = true
                },
                elapsedTimeViewField = holderClass.getDeclaredField("elapsedTimeView").apply {
                    isAccessible = true
                },
                totalTimeViewField = holderClass.getDeclaredField("totalTimeView").apply {
                    isAccessible = true
                },
                seamlessIconField = holderClass.getDeclaredField("seamlessIcon").apply {
                    isAccessible = true
                },
                albumViewField = holderClass.getDeclaredField("albumView").apply {
                    isAccessible = true
                },
                albumImageField = holderClass.getDeclaredField("albumImageView").apply {
                    isAccessible = true
                },
                appIconField = holderClass.getDeclaredField("appIcon").apply {
                    isAccessible = true
                },
                seamlessField = holderClass.getDeclaredField("seamless").apply {
                    isAccessible = true
                },
                mediaDataField = binderClass.getDeclaredField("mediaData").apply {
                    isAccessible = true
                },
                mediaDataIsPlayingField = mediaDataClass.getDeclaredField("isPlaying").apply {
                    isAccessible = true
                },
                mediaDataPackageNameField = mediaDataClass.getDeclaredField("packageName").apply {
                    isAccessible = true
                },
                seekBarField = holderClass.getDeclaredField("seekBar").apply {
                    isAccessible = true
                },
                seekBarPaintField = seekBarClass.getDeclaredField("mPaint").apply {
                    isAccessible = true
                },
                seekBarRuntimeShaderField = seekBarClass.getDeclaredField("runtimeShader").apply {
                    isAccessible = true
                },
                seekBarHeadGlowAlphaField = seekBarClass.getDeclaredField("uHeadGlowAlpha").apply {
                    isAccessible = true
                },
                headAlphaListenerSeekBarField = headAlphaListenerClass.getDeclaredField(
                    "this\$0"
                ).apply { isAccessible = true },
                getActionListMethod = holderClass.getDeclaredMethod("getActionList").apply {
                    isAccessible = true
                },
                updateForegroundColorsMethod = binderClass.declaredMethods.single {
                    it.name == "updateForegroundColors" && it.parameterCount == 1
                }.apply { isAccessible = true },
                setSeekBarForegroundMethod = seekBarClass.getDeclaredMethod(
                    "setForegroundPrimaryColor",
                    Int::class.javaPrimitiveType
                ).apply { isAccessible = true },
                setSeekBarBackgroundMethod = seekBarClass.getDeclaredMethod(
                    "setBackgroundPrimaryColor",
                    Int::class.javaPrimitiveType
                ).apply { isAccessible = true },
                pauseMethod = musicBgViewClass.getDeclaredMethod("pause").apply {
                    isAccessible = true
                },
                setGradientColorMethod = musicBgViewClass.getDeclaredMethod(
                    "setGradientColor",
                    Int::class.javaPrimitiveType,
                    IntArray::class.java
                ).apply { isAccessible = true },
                getPaletteColorMethod = getPaletteColor
            )
        }
    }
}

internal data class ExpandedBackgroundMethods(
    val updateBackgroundBg: Method,
    val clearBionicsMaterialMethod: Method?,
    val notificationGlassMethods: NotificationGlassMethods?,
    val getMiniBar: Method,
    val isBackgroundOpened: Method,
    val setMiViewBlurMode: Method,
    val clearMiBackgroundBlendColor: Method,
    val setMiBackgroundBlendColors: Method,
    val liveUpdateBlendColor1Id: Int,
    val liveUpdateBlendColor2Id: Int,
    val liveUpdateBlendColor3Id: Int,
    val blurModeLinearLightId: Int,
    val blurModeLabId: Int,
    val blurModePureId: Int,
    val liveUpdateBackgroundDrawableId: Int
) {
    val hasBionicsMaterialApi: Boolean
        get() = clearBionicsMaterialMethod != null

    val hasNotificationGlassApi: Boolean
        get() = notificationGlassMethods != null

    /**
     * 把展开视图材质从 OS4 液态玻璃（bionics）重置回经典模式；
     * 经典混色背景在液态玻璃材质上不生效，系统自身的经典分支
     * 也总是先执行这个重置。旧系统没有该 API 时保持原行为。
     */
    fun clearBionicsMaterial(view: View) {
        val method = clearBionicsMaterialMethod ?: return
        runCatching { method.invoke(null, view) }.onFailure { error ->
            HookLogger.e(IslandExpandedMediaAmbientFlowHooker.TAG, "重置展开态液态玻璃材质失败", error)
        }
    }

    fun applyNotificationGlass(
        view: View,
        notificationLightContext: Context
    ): NotificationGlassDiagnostics {
        return requireNotNull(notificationGlassMethods) {
            "OS4 notification glass methods are unavailable"
        }.apply(view, notificationLightContext)
    }

    fun applyDrawableFallback(view: View, islandLightContext: Context) {
        clearBionicsMaterial(view)
        setMiViewBlurMode.invoke(null, view, 0)
        clearMiBackgroundBlendColor.invoke(null, view)
        view.background = requireNotNull(
            islandLightContext.getDrawable(liveUpdateBackgroundDrawableId)
        )
    }

    companion object {
        fun create(
            ownerClass: Class<*>,
            classLoader: ClassLoader,
            hostClassLoader: ClassLoader
        ): ExpandedBackgroundMethods {
            val baseContentViewClass = generateSequence(ownerClass as Class<*>?) {
                it.superclass
            }.firstOrNull { it.name == IslandExpandedMediaAmbientFlowHooker.BASE_CONTENT_VIEW_CLASS }
                ?: error("Missing superclass: ${IslandExpandedMediaAmbientFlowHooker.BASE_CONTENT_VIEW_CLASS}")
            val miBlurCompatClass = classLoader.loadClass(IslandExpandedMediaAmbientFlowHooker.MI_BLUR_COMPAT_CLASS)
            val colorClass = classLoader.loadClass("miui.systemui.dynamicisland.R\$color")
            val integerClass = classLoader.loadClass("miui.systemui.dynamicisland.R\$integer")
            val drawableClass = classLoader.loadClass("miui.systemui.dynamicisland.R\$drawable")
            return ExpandedBackgroundMethods(
                updateBackgroundBg = baseContentViewClass.getDeclaredMethod(
                    "updateBackgroundBg",
                    View::class.java,
                    Boolean::class.javaPrimitiveType
                ).apply { isAccessible = true },
                clearBionicsMaterialMethod = runCatching {
                    classLoader.loadClass(IslandExpandedMediaBionicsProfile.MI_BACKGROUND_STYLE_CLASS)
                        .declaredMethods.firstOrNull(
                            IslandExpandedMediaBionicsProfile::isClearBionicsMaterial
                        )
                }.getOrNull()?.apply { isAccessible = true },
                notificationGlassMethods = runCatching {
                    // NotificationUtil/MiGlassCompat 只在主 MiuiSystemUI APK 中定义，
                    // 必须用宿主加载器解析；插件加载器会 ClassNotFoundException。
                    NotificationGlassMethods.create(hostClassLoader)
                }.onFailure { error ->
                    HookLogger.w(IslandExpandedMediaAmbientFlowHooker.TAG, "通知中心媒体玻璃接口不可用", error)
                }.getOrNull(),
                getMiniBar = baseContentViewClass.getDeclaredMethod("getMiniBar").apply {
                    isAccessible = true
                },
                isBackgroundOpened = listOf(
                    "getBackgroundMaterialOpened",
                    "getBackgroundBlurOpened"
                ).firstNotNullOfOrNull { name ->
                    runCatching {
                        miBlurCompatClass.getDeclaredMethod(name, Context::class.java)
                    }.getOrNull()?.takeIf { it.returnType == Boolean::class.javaPrimitiveType }
                }?.apply {
                    isAccessible = true
                    HookLogger.d(IslandExpandedMediaAmbientFlowHooker.TAG, "动态岛背景开关方法已匹配: $name")
                } ?: error("MiBlurCompat background-open method is unavailable"),
                setMiViewBlurMode = miBlurCompatClass.getDeclaredMethod(
                    "setMiViewBlurModeCompat",
                    View::class.java,
                    Int::class.javaPrimitiveType
                ).apply { isAccessible = true },
                clearMiBackgroundBlendColor = miBlurCompatClass.getDeclaredMethod(
                    "clearMiBackgroundBlendColorCompat",
                    View::class.java
                ).apply { isAccessible = true },
                setMiBackgroundBlendColors = miBlurCompatClass.getDeclaredMethod(
                    "setMiBackgroundBlendColors\$default",
                    View::class.java,
                    IntArray::class.java,
                    Float::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Any::class.java
                ).apply { isAccessible = true },
                liveUpdateBlendColor1Id = colorClass.resourceId(
                    "liveupdate_island_element_blend_shade_color_1"
                ),
                liveUpdateBlendColor2Id = colorClass.resourceId(
                    "liveupdate_island_element_blend_shade_color_2"
                ),
                liveUpdateBlendColor3Id = colorClass.resourceId(
                    "liveupdate_island_element_blend_shade_color_3"
                ),
                blurModeLinearLightId = integerClass.resourceId("blur_mode_linear_light"),
                blurModeLabId = integerClass.resourceId("blur_mode_lab"),
                blurModePureId = integerClass.resourceId("blur_mode_pure"),
                liveUpdateBackgroundDrawableId = drawableClass.resourceId(
                    "dynamic_island_liveupdate_background"
                )
            )
        }

        private fun Class<*>.resourceId(name: String): Int {
            return getDeclaredField(name).apply { isAccessible = true }.getInt(null)
        }
    }
}

internal data class NotificationGlassDiagnostics(
    val resourcePackage: String,
    val colors: IntArray,
    val modes: IntArray,
    val glassParamCount: Int
)

internal data class NotificationGlassMethods(
    val getBackgroundBlurOpened: Method,
    val setMiViewBlurMode: Method,
    val clearMiBackgroundBlendColor: Method,
    val setMiBackgroundBlendColors: Method,
    val setMiViewMaterialType: Method,
    val setMiGlass: Method
) {
    fun apply(view: View, context: Context): NotificationGlassDiagnostics {
        val backgroundId = context.requireSystemUiResource(
            IslandExpandedMediaNotificationGlassProfile.TRANSPARENT_BACKGROUND_DRAWABLE,
            "drawable"
        )
        val colors = intArrayOf(
            context.getColor(
                context.requireSystemUiResource(
                    IslandExpandedMediaNotificationGlassProfile.SHADE_COLOR_1,
                    "color"
                )
            ),
            context.getColor(
                context.requireSystemUiResource(
                    IslandExpandedMediaNotificationGlassProfile.SHADE_COLOR_2,
                    "color"
                )
            ),
            context.getColor(
                context.requireSystemUiResource(
                    IslandExpandedMediaNotificationGlassProfile.SHADE_COLOR_3,
                    "color"
                )
            )
        )
        val modes = intArrayOf(
            context.resources.getInteger(
                context.requireSystemUiResource(
                    IslandExpandedMediaNotificationGlassProfile.BLEND_MODE_LINEAR_LIGHT,
                    "integer"
                )
            ),
            context.resources.getInteger(
                context.requireSystemUiResource(
                    IslandExpandedMediaNotificationGlassProfile.BLEND_MODE_LAB,
                    "integer"
                )
            ),
            context.resources.getInteger(
                context.requireSystemUiResource(
                    IslandExpandedMediaNotificationGlassProfile.BLEND_MODE_PURE,
                    "integer"
                )
            )
        )
        val blend = intArrayOf(
            colors[0], modes[0],
            colors[1], modes[1],
            colors[2], modes[2]
        )
        val glassParams = IslandExpandedMediaNotificationGlassProfile.defaultGlassParams()
        check(getBackgroundBlurOpened.invoke(null, context) as Boolean) {
            "Notification blend gate closed (getBackgroundBlurOpened=false)"
        }

        // Mirror MediaViewBlurEffect.apply, then MediaViewGlassEffect.apply. The blend half
        // is inlined from NotificationUtil.applyElementViewBlend minus setRoundRect: its
        // outline provider reads main-package dimens through the plugin view's Resources
        // and crashed SystemUI repeatedly in 150207.
        view.background = requireNotNull(context.getDrawable(backgroundId))
        setMiViewMaterialType.invoke(null, 0, view)
        setMiViewBlurMode.invoke(null, 1, view)
        clearMiBackgroundBlendColor.invoke(null, view)
        setMiBackgroundBlendColors.invoke(null, view, blend)
        setMiViewMaterialType.invoke(null, 1, view)
        setMiGlass.invoke(null, view, glassParams)

        return NotificationGlassDiagnostics(
            resourcePackage = context.resources.getResourcePackageName(backgroundId),
            colors = colors,
            modes = modes,
            glassParamCount = glassParams.size
        )
    }

    companion object {
        fun create(classLoader: ClassLoader): NotificationGlassMethods {
            val miBlurCompatClass = classLoader.loadClass(
                IslandExpandedMediaNotificationGlassProfile.MI_BLUR_COMPAT_CLASS
            )
            val miGlassCompatClass = classLoader.loadClass(
                IslandExpandedMediaNotificationGlassProfile.MI_GLASS_COMPAT_CLASS
            )
            return NotificationGlassMethods(
                getBackgroundBlurOpened = miBlurCompatClass.declaredMethods.single(
                    IslandExpandedMediaNotificationGlassProfile::isGetBackgroundBlurOpened
                ).apply { isAccessible = true },
                setMiViewBlurMode = miBlurCompatClass.declaredMethods.single(
                    IslandExpandedMediaNotificationGlassProfile::isSetMiViewBlurMode
                ).apply { isAccessible = true },
                clearMiBackgroundBlendColor = miBlurCompatClass.declaredMethods.single(
                    IslandExpandedMediaNotificationGlassProfile::isClearMiBackgroundBlendColor
                ).apply { isAccessible = true },
                setMiBackgroundBlendColors = miBlurCompatClass.declaredMethods.single(
                    IslandExpandedMediaNotificationGlassProfile::isSetMiBackgroundBlendColors
                ).apply { isAccessible = true },
                setMiViewMaterialType = miGlassCompatClass.declaredMethods.single(
                    IslandExpandedMediaNotificationGlassProfile::isSetMiViewMaterialType
                ).apply { isAccessible = true },
                setMiGlass = miGlassCompatClass.declaredMethods.single(
                    IslandExpandedMediaNotificationGlassProfile::isSetMiGlass
                ).apply { isAccessible = true }
            )
        }
    }
}

internal fun Context.requireSystemUiResource(name: String, type: String): Int {
    val id = resources.getIdentifier(
        name,
        type,
        IslandExpandedMediaNotificationGlassProfile.SYSTEMUI_PACKAGE
    )
    require(id != 0) { "Missing SystemUI $type resource: $name" }
    return id
}
