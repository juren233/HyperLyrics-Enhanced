/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.createBitmap
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.IslandAlbumCoverWhitelist
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.media.MediaMetadataHelper
import com.juren233.hyperlyricsenhanced.root.HookEntry
import com.juren233.hyperlyricsenhanced.root.mediacard.island.IslandExpandedMediaAmbientFlowHooker
import com.juren233.hyperlyricsenhanced.root.mediacard.island.onIslandAlbumIconUpdated
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.SystemUiEnhancementGate
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.lyric.view.line.LyricTextPaintOwner
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.ArrayDeque
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun IslandAlbumCoverStyleHooker.isSmallIslandModule(module: View): Boolean {
    if (module.id == View.NO_ID) return false
    return runCatching {
        module.resources.getResourceEntryName(module.id) == "small_container"
    }.getOrDefault(false)
}

internal fun IslandAlbumCoverStyleHooker.setGravity(lp: ViewGroup.LayoutParams, gravity: Int) {
    runCatching { lp.javaClass.getField("gravity").setInt(lp, gravity) }
}

internal fun IslandAlbumCoverStyleHooker.getGravity(lp: ViewGroup.LayoutParams?): Int? {
    return lp?.let {
        runCatching { it.javaClass.getField("gravity").getInt(it) }.getOrNull()
    }
}

internal class RightEdgeGradientDrawable(
    bandFraction: Float,
    private val color: Int,
) : Drawable() {
    private val bandFraction = bandFraction.coerceIn(0.01f, 1f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun matches(snapshot: CoverVisualSnapshot): Boolean {
        return !snapshot.smallIsland &&
            bandFraction == snapshot.gradientBandFraction.coerceIn(0.01f, 1f) &&
            color == snapshot.islandColor
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        if (bounds.isEmpty) {
            paint.shader = null
            return
        }
        val bandWidth = (bounds.width() * bandFraction).coerceAtLeast(1f)
        val startX = bounds.right - bandWidth
        val transparent = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))
        val opaque = Color.argb(255, Color.red(color), Color.green(color), Color.blue(color))
        paint.shader = LinearGradient(
            startX,
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.top.toFloat(),
            transparent,
            opaque,
            Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty || paint.shader == null) return
        val bandWidth = (bounds.width() * bandFraction).coerceAtLeast(1f)
        canvas.drawRect(
            bounds.right - bandWidth,
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.bottom.toFloat(),
            paint,
        )
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

internal class GradientCoverState(
    val fixIcon: ImageView,
    val iconContainer: View,
    var packageName: String?,
    val module: View?,
    val moduleParent: View?,
    val originalFixIconWidth: Int,
    val originalFixIconHeight: Int,
    val originalScaleType: ImageView.ScaleType,
    val originalFixIconScaleX: Float,
    val originalFixIconScaleY: Float,
    val originalFixIconTranslationX: Float,
    val originalFixIconTranslationY: Float,
    val originalFixIconPivotX: Float,
    val originalFixIconPivotY: Float,
    val originalFixIconPivotSet: Boolean,
    val originalFixIconVisibility: Int,
    val originalForeground: Drawable?,
    val originalOutlineProvider: ViewOutlineProvider?,
    val originalClipToOutline: Boolean,
    val originalIconContainerWidth: Int,
    val originalIconContainerHeight: Int,
    val originalIconContainerGravity: Int?,
    val originalIconContainerMarginStart: Int,
    val originalIconContainerMarginEnd: Int,
    val originalIconContainerMarginTop: Int,
    val originalIconContainerMarginBottom: Int,
    val originalIconContainerPaddingLeft: Int,
    val originalIconContainerPaddingTop: Int,
    val originalIconContainerPaddingRight: Int,
    val originalIconContainerPaddingBottom: Int,
    val originalIconContainerTranslationX: Float,
    val originalIconContainerTranslationY: Float,
    val originalFixIconMarginStart: Int,
    val originalFixIconMarginEnd: Int,
    val originalFixIconMarginTop: Int,
    val originalFixIconMarginBottom: Int,
    val originalModulePaddingLeft: Int,
    val originalModulePaddingTop: Int,
    val originalModulePaddingRight: Int,
    val originalModulePaddingBottom: Int,
    val originalIconContainerClipChildren: Boolean,
    val originalModuleClipChildren: Boolean,
    val originalModuleClipToPadding: Boolean,
    val originalModuleParentClipChildren: Boolean,
    val originalModuleParentClipToPadding: Boolean,
    val originalLeftContentTextShadows: MutableList<TextShadowTargetSnapshot>,
) {
    var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    var observedRoot: View? = null
    var observationFrames: Int = 0
    var stableGeometry: IslandGradientGeometryCandidate? = null
    var stableGeometryFrames: Int = 0
    var appliedSmall: Boolean? = null
    var appliedCoverWidth: Int = -1
    var appliedCoverHeight: Int = -1
    private var lastDiagnosticSignature: String? = null
    private var lastGeometryDiagnostic: String? = null
    private var lastTextShadowDiagnostic: String? = null
    private var lastTextShadowPostDiagnostic: String? = null
    private val loggedGeometryDiagnosticCategories = HashSet<String>()

    fun scheduleLayout() {
        if (preDrawListener != null) return
        removePreDrawObserver()
        observationFrames = 0
        stableGeometry = null
        stableGeometryFrames = 0
        loggedGeometryDiagnosticCategories.clear()

        val root = fixIcon.rootView
        val listener = ViewTreeObserver.OnPreDrawListener {
            val stillTracked = synchronized(IslandAlbumCoverStyleHooker.gradientStates) {
                IslandAlbumCoverStyleHooker.gradientStates[fixIcon] === this
            }
            if (!stillTracked || IslandAlbumCoverStyleHooker.currentStyleForPackage(packageName) != RootConstants.ISLAND_ALBUM_COVER_STYLE_GRADIENT) {
                removePreDrawObserver()
                return@OnPreDrawListener true
            }

            observationFrames += 1
            val geometry = resolveStableIslandGeometry()
            if (geometry != null && geometry == stableGeometry) {
                stableGeometryFrames += 1
            } else {
                stableGeometry = geometry
                stableGeometryFrames = if (geometry == null) 0 else 1
            }

            if (geometry != null) {
                val finalPlacement = stableGeometryFrames >= IslandAlbumCoverStyleHooker.REQUIRED_STABLE_FRAMES
                if (finalPlacement) {
                    applyLayout(geometry, logFinalPlacement = true)
                    removePreDrawObserver()
                } else if (observationFrames >= IslandAlbumCoverStyleHooker.MAX_OBSERVATION_FRAMES) {
                    if (BuildConfig.DEBUG) {
                        HookLogger.w(IslandAlbumCoverStyleHooker.TAG, "渐变封面坐标持续变化，不显示中间位移")
                    }
                    removePreDrawObserver()
                }
            } else if (observationFrames >= IslandAlbumCoverStyleHooker.MAX_OBSERVATION_FRAMES) {
                if (BuildConfig.DEBUG) {
                    HookLogger.w(IslandAlbumCoverStyleHooker.TAG, "渐变封面等待稳定岛坐标超时，保留原生封面")
                }
                removePreDrawObserver()
            }
            true
        }
        observedRoot = root
        preDrawListener = listener
        root.viewTreeObserver.takeIf { it.isAlive }?.addOnPreDrawListener(listener)
    }

    fun removePreDrawObserver() {
        val root = observedRoot
        val listener = preDrawListener
        if (root != null && listener != null) {
            root.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
        }
        observedRoot = null
        preDrawListener = null
    }

    fun applyLeftContentTextShadow(source: String = "cover") {
        val density = fixIcon.resources.displayMetrics.density
        val targets = IslandAlbumCoverStyleHooker.captureLeftContentTextTargets(module)
        val before = if (BuildConfig.DEBUG) {
            targets.joinToString(" | ") {
                IslandAlbumCoverStyleHooker.textShadowTargetDiagnostic(it)
            }
        } else {
            ""
        }
        targets.forEach { current ->
            if (originalLeftContentTextShadows.none { it.paint === current.paint }) {
                originalLeftContentTextShadows += current
            }
            current.paint.setShadowLayer(
                IslandAlbumCoverStyleHooker.LEFT_CONTENT_SHADOW_RADIUS_DP * density,
                0f,
                IslandAlbumCoverStyleHooker.LEFT_CONTENT_SHADOW_DY_DP * density,
                Color.argb(IslandAlbumCoverStyleHooker.LEFT_CONTENT_SHADOW_ALPHA, 0, 0, 0),
            )
            current.view.invalidate()
        }
        if (BuildConfig.DEBUG) {
            val after = targets.joinToString(" | ") {
                IslandAlbumCoverStyleHooker.textShadowTargetDiagnostic(it)
            }
            val diagnostic = "source=$source,state=${System.identityHashCode(this).toString(16)}," +
                "targetCount=${targets.size},before=[$before],after=[$after]," +
                IslandAlbumCoverStyleHooker.textShadowTreeDiagnostic(module)
            if (diagnostic != lastTextShadowDiagnostic) {
                lastTextShadowDiagnostic = diagnostic
                HookLogger.i(IslandAlbumCoverStyleHooker.TAG, "[GradientShadowDiag] apply $diagnostic")
            }
            fixIcon.post {
                val posted = targets.joinToString(" | ") {
                    IslandAlbumCoverStyleHooker.textShadowTargetDiagnostic(it)
                }
                val postDiagnostic = "state=${System.identityHashCode(this).toString(16)}," +
                    "targets=[$posted]," +
                    IslandAlbumCoverStyleHooker.textShadowTreeDiagnostic(module)
                if (postDiagnostic != lastTextShadowPostDiagnostic) {
                    lastTextShadowPostDiagnostic = postDiagnostic
                    HookLogger.i(IslandAlbumCoverStyleHooker.TAG, "[GradientShadowDiag] post $postDiagnostic")
                }
            }
        }
    }

    fun restoreLeftContentTextShadow() {
        originalLeftContentTextShadows.forEach { original ->
            if (original.radius > 0f) {
                original.paint.setShadowLayer(
                    original.radius,
                    original.dx,
                    original.dy,
                    original.color,
                )
            } else {
                original.paint.clearShadowLayer()
            }
            original.view.invalidate()
        }
    }

    fun restoreCoverVisuals() {
        fixIcon.scaleType = originalScaleType
        fixIcon.scaleX = originalFixIconScaleX
        fixIcon.scaleY = originalFixIconScaleY
        fixIcon.translationX = originalFixIconTranslationX
        fixIcon.translationY = originalFixIconTranslationY
        if (originalFixIconPivotSet) {
            fixIcon.pivotX = originalFixIconPivotX
            fixIcon.pivotY = originalFixIconPivotY
        } else {
            fixIcon.resetPivot()
        }
        fixIcon.foreground = originalForeground
        fixIcon.outlineProvider = originalOutlineProvider
        fixIcon.clipToOutline = originalClipToOutline
        fixIcon.invalidateOutline()
    }

    fun applySnapshot(snapshot: CoverVisualSnapshot): Boolean {
        (module as? ViewGroup)?.clipChildren = false
        (module as? ViewGroup)?.clipToPadding = false
        (moduleParent as? ViewGroup)?.clipChildren = false
        (moduleParent as? ViewGroup)?.clipToPadding = false
        (iconContainer as? ViewGroup)?.clipChildren = false

        val changed = IslandAlbumCoverStyleHooker.applySnapshotToImage(fixIcon, snapshot)
        appliedSmall = snapshot.smallIsland
        appliedCoverWidth = snapshot.coverWidth
        appliedCoverHeight = snapshot.coverHeight
        return changed
    }


    fun isSmallIslandState(): Boolean {
        val moduleView = module ?: return false
        return IslandAlbumCoverStyleHooker.isSmallIslandModule(moduleView)
    }

    fun cacheCurrentVisual(smallIsland: Boolean, placement: IslandGradientPlacement) {
        val snapshot = CoverVisualSnapshot(
            scaleX = placement.iconScaleX,
            scaleY = placement.iconScaleY,
            translationX = placement.iconTranslationX,
            translationY = placement.iconTranslationY,
            coverWidth = placement.coverWidth,
            coverHeight = placement.coverHeight,
            smallIsland = smallIsland,
            gradientBandFraction = placement.gradientBandFraction,
            islandColor = resolveIslandBackgroundColor(fixIcon),
        )
        if (smallIsland) IslandAlbumCoverStyleHooker.cachedSmallVisual = snapshot else IslandAlbumCoverStyleHooker.cachedBigVisual = snapshot
    }

    fun resolveStableIslandGeometry(): IslandGradientGeometryCandidate? {
        val moduleView = module ?: return null
        val moduleWidth = moduleView.width.takeIf { it > 0 } ?: moduleView.measuredWidth
        val moduleHeight = moduleView.height.takeIf { it > 0 } ?: moduleView.measuredHeight
        val iconWidth = fixIcon.expectedLayoutWidth()
        val iconHeight = fixIcon.expectedLayoutHeight()
        if (moduleWidth <= 0 || moduleHeight <= 0 || iconWidth <= 0 || iconHeight <= 0) {
            logGeometryDiagnostic("invalid_sizes module=${moduleWidth}x${moduleHeight} icon=${iconWidth}x${iconHeight}")
            return null
        }

        val density = fixIcon.resources.displayMetrics.density
        val tolerance = maxOf((4f * density).roundToInt(), moduleHeight / 10)
        val smallIsland = IslandAlbumCoverStyleHooker.isSmallIslandModule(moduleView)
        val associated = findIslandBackgroundView(fixIcon)
        if (associated == null) {
            if (smallIsland && moduleView.isShown) {
                val moduleLocation = moduleView.baseLocationInWindow()
                val geometry = IslandGradientGeometryCandidate(
                    left = moduleLocation.first.roundToInt(),
                    top = moduleLocation.second.roundToInt(),
                    width = moduleWidth,
                    height = moduleHeight,
                )
                logGeometryDiagnostic("small_module_fallback geometry=$geometry")
                return geometry
            }
            logGeometryDiagnostic("no_associated_background parents=${parentChain(fixIcon)}")
            return null
        }
        val background = associated.view
        if (!background.isAttachedToWindow || background.visibility != View.VISIBLE) {
            logGeometryDiagnostic("background_not_visible owner=${associated.ownerClass} attached=${background.isAttachedToWindow} visibility=${background.visibility}")
            return null
        }
        val actualLeft = IslandAlbumCoverStyleHooker.getViewInt(background, "getActualLeft")
        val actualTop = IslandAlbumCoverStyleHooker.getViewInt(background, "getActualTop")
        val actualWidth = IslandAlbumCoverStyleHooker.getViewInt(background, "getActualWidth")
        val actualHeight = IslandAlbumCoverStyleHooker.getViewInt(background, "getActualHeight")
        if (actualLeft == null || actualTop == null || actualWidth == null || actualHeight == null) {
            logGeometryDiagnostic("missing_actual owner=${associated.ownerClass} methods=${background.javaClass.methods.filter { it.parameterTypes.isEmpty() && it.name.contains("Actual") }.joinToString { it.name }}")
            return null
        }
        val geometry = IslandGradientCoverLayout.fromActualEdges(
            actualLeft = actualLeft,
            actualTop = actualTop,
            actualRight = actualWidth,
            actualBottom = actualHeight,
        )
        if (geometry.width <= 0 || abs(geometry.height - moduleHeight) > tolerance) {
            logGeometryDiagnostic("unstable_actual owner=${associated.ownerClass} edges=${actualLeft},${actualTop},${actualWidth},${actualHeight} geometry=$geometry module=${moduleWidth}x${moduleHeight} tolerance=$tolerance")
            return null
        }
        if (smallIsland) {
            if (abs(geometry.width - moduleWidth) > tolerance) {
                val moduleLocation = moduleView.baseLocationInWindow()
                val fallback = IslandGradientGeometryCandidate(
                    left = moduleLocation.first.roundToInt(),
                    top = moduleLocation.second.roundToInt(),
                    width = moduleWidth,
                    height = moduleHeight,
                )
                logGeometryDiagnostic("small_module_fallback backgroundWidth=${geometry.width} moduleWidth=$moduleWidth geometry=$fallback")
                return fallback
            }
        } else if (geometry.width <= moduleHeight * 1.5f) {
            logGeometryDiagnostic("big_width_too_small geometryWidth=${geometry.width} moduleHeight=${moduleHeight}")
            return null
        }
        logGeometryDiagnostic("accepted owner=${associated.ownerClass} geometry=$geometry module=${moduleWidth}x${moduleHeight} small=$smallIsland")
        return geometry
    }

    fun logGeometryDiagnostic(message: String) {
        if (!BuildConfig.DEBUG) return
        val category = message.substringBefore(' ')
        if (message == lastGeometryDiagnostic) return
        if (!loggedGeometryDiagnosticCategories.add(category)) return
        lastGeometryDiagnostic = message
        HookLogger.d(IslandAlbumCoverStyleHooker.TAG, "渐变封面几何诊断: $message")
    }

    fun parentChain(view: View): String {
        val names = ArrayList<String>()
        var current: View? = view
        while (current != null && names.size < 12) {
            names += current.javaClass.name
            current = current.parent as? View
        }
        return names.joinToString(" -> ")
    }

    fun logPlacement(
        module: View,
        islandWindowX: Float,
        smallIsland: Boolean,
        placement: IslandGradientPlacement,
    ) {
        if (!BuildConfig.DEBUG) return
        val signature = "${module.width},${module.height},$islandWindowX|" +
            "$smallIsland,${placement.coverWidth},${placement.coverHeight}|" +
            "${placement.iconScaleX},${placement.iconScaleY}," +
            "${placement.iconTranslationX},${placement.iconTranslationY},${placement.gradientBandFraction}"
        if (signature == lastDiagnosticSignature) return
        lastDiagnosticSignature = signature
        HookLogger.d(
            IslandAlbumCoverStyleHooker.TAG,
            "渐变封面布局: module=${module.width}x${module.height}, islandLeft=$islandWindowX, " +
                "small=$smallIsland, cover=${placement.coverWidth}x${placement.coverHeight}, " +
                "iconScale=${placement.iconScaleX},${placement.iconScaleY}, " +
                "iconTranslation=${placement.iconTranslationX},${placement.iconTranslationY}, " +
                "bandFraction=${placement.gradientBandFraction}",
        )
    }

    companion object {
        fun capture(
            fixIcon: ImageView,
            iconContainer: View,
            packageName: String?,
        ): GradientCoverState {
            val fixLp = fixIcon.layoutParams
            val iconLp = iconContainer.layoutParams
            val module = (fixIcon.parent as? View)?.parent as? View
            val moduleParent = module?.parent as? View
            return GradientCoverState(
                fixIcon = fixIcon,
                iconContainer = iconContainer,
                packageName = packageName,
                module = module,
                moduleParent = moduleParent,
                originalFixIconWidth = fixLp?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                originalFixIconHeight = fixLp?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                originalScaleType = fixIcon.scaleType,
                originalFixIconScaleX = fixIcon.scaleX,
                originalFixIconScaleY = fixIcon.scaleY,
                originalFixIconTranslationX = fixIcon.translationX,
                originalFixIconTranslationY = fixIcon.translationY,
                originalFixIconPivotX = fixIcon.pivotX,
                originalFixIconPivotY = fixIcon.pivotY,
                originalFixIconPivotSet = fixIcon.isPivotSet,
                originalFixIconVisibility = fixIcon.visibility,
                originalForeground = fixIcon.foreground,
                originalOutlineProvider = fixIcon.outlineProvider,
                originalClipToOutline = fixIcon.clipToOutline,
                originalIconContainerWidth = iconLp?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                originalIconContainerHeight = iconLp?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                originalIconContainerGravity = IslandAlbumCoverStyleHooker.getGravity(iconLp),
                originalIconContainerMarginStart = (iconLp as? ViewGroup.MarginLayoutParams)?.marginStart ?: 0,
                originalIconContainerMarginEnd = (iconLp as? ViewGroup.MarginLayoutParams)?.marginEnd ?: 0,
                originalIconContainerMarginTop = (iconLp as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0,
                originalIconContainerMarginBottom = (iconLp as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0,
                originalIconContainerPaddingLeft = iconContainer.paddingLeft,
                originalIconContainerPaddingTop = iconContainer.paddingTop,
                originalIconContainerPaddingRight = iconContainer.paddingRight,
                originalIconContainerPaddingBottom = iconContainer.paddingBottom,
                originalIconContainerTranslationX = iconContainer.translationX,
                originalIconContainerTranslationY = iconContainer.translationY,
                originalFixIconMarginStart = (fixLp as? ViewGroup.MarginLayoutParams)?.marginStart ?: 0,
                originalFixIconMarginEnd = (fixLp as? ViewGroup.MarginLayoutParams)?.marginEnd ?: 0,
                originalFixIconMarginTop = (fixLp as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0,
                originalFixIconMarginBottom = (fixLp as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0,
                originalModulePaddingLeft = module?.paddingLeft ?: 0,
                originalModulePaddingTop = module?.paddingTop ?: 0,
                originalModulePaddingRight = module?.paddingRight ?: 0,
                originalModulePaddingBottom = module?.paddingBottom ?: 0,
                originalIconContainerClipChildren = (iconContainer as? ViewGroup)?.clipChildren ?: true,
                originalModuleClipChildren = (module as? ViewGroup)?.clipChildren ?: true,
                originalModuleClipToPadding = (module as? ViewGroup)?.clipToPadding ?: true,
                originalModuleParentClipChildren = (moduleParent as? ViewGroup)?.clipChildren ?: true,
                originalModuleParentClipToPadding = (moduleParent as? ViewGroup)?.clipToPadding ?: true,
                originalLeftContentTextShadows =
                    IslandAlbumCoverStyleHooker.captureLeftContentTextTargets(module)
                        .toMutableList(),
            )
        }
    }
}
