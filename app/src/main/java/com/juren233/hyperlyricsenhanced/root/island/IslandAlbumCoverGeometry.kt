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

internal fun IslandAlbumCoverStyleHooker.applyInitializedTemplateGeometry(holder: Any) {
    val dynamicIslandData = synchronized(trackedHolders) {
        trackedHolders[holder]?.dataRef?.get()
    }
    if (IslandAlbumCoverStyleHooker.currentStyle(dynamicIslandData) != RootConstants.ISLAND_ALBUM_COVER_STYLE_GRADIENT) return
    val bigContainer = IslandAlbumCoverStyleHooker.callViewGetter(holder, "getBigContainer") as? ViewGroup
    val smallContainer = IslandAlbumCoverStyleHooker.callViewGetter(holder, "getSmallContainer") as? ViewGroup
    val bigApplied = applyInitializedContainer(bigContainer, smallIsland = false)
    val smallApplied = applyInitializedContainer(smallContainer, smallIsland = true)
    if (BuildConfig.DEBUG && (bigApplied > 0 || smallApplied > 0)) {
        HookLogger.d(
            IslandAlbumCoverStyleHooker.TAG,
            "首帧渐变已按最终容器同步: big=$bigApplied, small=$smallApplied, " +
                "bigSize=${bigContainer?.width}x${bigContainer?.height}, " +
                "smallSize=${smallContainer?.width}x${smallContainer?.height}",
        )
    }
}

internal fun IslandAlbumCoverStyleHooker.applyInitializedContainer(
    container: ViewGroup?,
    smallIsland: Boolean,
): Int {
    val target = container ?: return 0
    val width = target.width.takeIf { it > 0 } ?: target.measuredWidth
    val height = target.height.takeIf { it > 0 } ?: target.measuredHeight
    if (!target.isAttachedToWindow || width <= 0 || height <= 0) return 0

    val trackedTargets = IslandAlbumCoverStyleHooker.collectCoverImageViews(target).mapNotNull { (imageView, _) ->
        val state = synchronized(IslandAlbumCoverStyleHooker.gradientStates) {
            IslandAlbumCoverStyleHooker.gradientStates[imageView]
        } ?: return@mapNotNull null
        imageView to state
    }
    if (trackedTargets.isEmpty()) return 0

    var applied = 0
    val embeddedViews = HashSet<ImageView>()
    trackedTargets.forEach { (imageView, state) ->
        state.applyLeftContentTextShadow()
        if (EmbeddedIslandAlbumCoverController.apply(target, imageView, smallIsland)) {
            state.removePreDrawObserver()
            state.restoreCoverVisuals()
            embeddedViews += imageView
            applied += 1
        }
    }
    if (embeddedViews.size == trackedTargets.size) return applied
    if (smallIsland) {
        trackedTargets.forEach { (imageView, state) ->
            if (imageView !in embeddedViews) {
                state.removePreDrawObserver()
                state.restoreCoverVisuals()
            }
        }
        return applied
    }

    val geometry = resolveInitializedContainerGeometry(target, width, height, smallIsland)
        ?: return applied
    trackedTargets.forEach { (imageView, state) ->
        if (imageView in embeddedViews) return@forEach
        IslandAlbumCoverStyleHooker.logArtworkBindingState(
            imageView,
            if (smallIsland) "final_small_container_before" else "final_big_container_before",
            IslandAlbumCoverStyleHooker.currentStyle(),
        )
        state.applyLayout(
            geometry = geometry,
            logFinalPlacement = true,
            smallIslandOverride = smallIsland,
        )
        state.removePreDrawObserver()
        IslandAlbumCoverStyleHooker.logArtworkBindingState(
            imageView,
            if (smallIsland) "final_small_container_after" else "final_big_container_after",
            IslandAlbumCoverStyleHooker.currentStyle(),
        )
        applied += 1
    }
    return applied
}

internal fun IslandAlbumCoverStyleHooker.resolveInitializedContainerGeometry(
    target: ViewGroup,
    width: Int,
    height: Int,
    smallIsland: Boolean,
): IslandGradientGeometryCandidate? {
    val associated = findIslandBackgroundView(target) ?: return null
    val background = associated.view
    if (!background.isAttachedToWindow ||
        background.visibility != View.VISIBLE ||
        !background.isShown
    ) {
        if (BuildConfig.DEBUG) {
            HookLogger.d(
                IslandAlbumCoverStyleHooker.TAG,
                "跳过非法封面几何: small=$smallIsland, backgroundVisibility=${background.visibility}, " +
                    "attached=${background.isAttachedToWindow}, shown=${background.isShown}",
            )
        }
        return null
    }

    val actualLeft = IslandAlbumCoverStyleHooker.getViewInt(background, "getActualLeft") ?: return null
    val actualTop = IslandAlbumCoverStyleHooker.getViewInt(background, "getActualTop") ?: return null
    val actualRight = IslandAlbumCoverStyleHooker.getViewInt(background, "getActualWidth") ?: return null
    val actualBottom = IslandAlbumCoverStyleHooker.getViewInt(background, "getActualHeight") ?: return null
    if (actualLeft == 0 && actualTop == 0 && actualRight == 0 && actualBottom == 0) {
        if (BuildConfig.DEBUG) HookLogger.d(IslandAlbumCoverStyleHooker.TAG, "跳过非法封面几何: actual=0,0,0,0")
        return null
    }
    val actual = IslandGradientCoverLayout.fromActualEdges(
        actualLeft,
        actualTop,
        actualRight,
        actualBottom,
    )
    val tolerance = maxOf(12, height / 10)
    if (actual.height <= 0 || kotlin.math.abs(actual.height - height) > tolerance) {
        if (BuildConfig.DEBUG) HookLogger.d(IslandAlbumCoverStyleHooker.TAG, "跳过非法封面几何: actual=$actual module=${width}x$height")
        return null
    }
    if (smallIsland) {
        if (kotlin.math.abs(actual.width - width) <= tolerance) return actual
        val location = target.baseLocationInWindow()
        return IslandGradientGeometryCandidate(
            left = location.first.roundToInt(),
            top = location.second.roundToInt(),
            width = width,
            height = height,
        )
    }
    if (actual.width <= height * 1.5f) {
        if (BuildConfig.DEBUG) HookLogger.d(IslandAlbumCoverStyleHooker.TAG, "跳过非法封面几何: 大岛宽度不足 actual=$actual")
        return null
    }
    return actual
}

internal fun IslandAlbumCoverStyleHooker.callViewGetter(receiver: Any, methodName: String): View? {
    return runCatching {
        receiver.javaClass.methods.firstOrNull {
            it.name == methodName && it.parameterTypes.isEmpty()
        }?.invoke(receiver) as? View
    }.getOrNull()
}


internal fun IslandAlbumCoverStyleHooker.textShadowTargetDiagnostic(target: TextShadowTargetSnapshot): String {
    val paint = target.paint
    return "${viewDiagnostic(target.view)},paint@" +
        "${System.identityHashCode(paint).toString(16)}," +
        "shadow=${paint.getShadowLayerRadius()}/${paint.getShadowLayerDx()}/" +
        "${paint.getShadowLayerDy()}/0x${paint.getShadowLayerColor().toUInt().toString(16)}"
}

internal fun IslandAlbumCoverStyleHooker.textShadowTreeDiagnostic(module: View?): String {
    val moduleRoot = module as? ViewGroup ?: return "module=${viewDiagnostic(module)}"
    val textRoot = IslandViewHelper.findViewByName(
        moduleRoot,
        IslandProbeUtils.TEXT_CONTAINER_NAME,
    ) ?: return "module=${viewDiagnostic(moduleRoot)},textRoot=missing"
    val entries = ArrayList<String>()
    val queue = ArrayDeque<View>()
    queue.add(textRoot)
    while (queue.isNotEmpty() && entries.size < 24) {
        val current = queue.removeFirst()
        entries += viewDiagnostic(current)
        if (current is ViewGroup) {
            for (index in 0 until current.childCount) {
                queue.addLast(current.getChildAt(index))
            }
        }
    }
    return "module=${viewDiagnostic(moduleRoot)},tree=${entries.joinToString(";")}"
}

internal fun IslandAlbumCoverStyleHooker.viewDiagnostic(view: View?): String {
    if (view == null) return "null"
    val idName = if (view.id == View.NO_ID) {
        "no-id"
    } else {
        runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
            ?: view.id.toString()
    }
    return "${view.javaClass.name}@${System.identityHashCode(view).toString(16)}" +
        "(id=$idName,tag=${view.tag},attached=${view.isAttachedToWindow}," +
        "visibility=${view.visibility},alpha=${view.alpha},layer=${view.layerType}," +
        "hardware=${view.isHardwareAccelerated})"
}

internal fun IslandAlbumCoverStyleHooker.captureLeftContentTextTargets(module: View?): List<TextShadowTargetSnapshot> {
    val moduleRoot = module as? ViewGroup ?: return emptyList()
    val textRoot = IslandViewHelper.findViewByName(
        moduleRoot,
        "island_container_module_text",
    ) ?: return emptyList()
    val result = ArrayList<TextShadowTargetSnapshot>()
    val queue = ArrayDeque<View>()
    queue.add(textRoot)
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        when (current) {
            is TextView -> result += TextShadowTargetSnapshot(
                view = current,
                paint = current.paint,
                radius = current.paint.getShadowLayerRadius(),
                dx = current.paint.getShadowLayerDx(),
                dy = current.paint.getShadowLayerDy(),
                color = current.paint.getShadowLayerColor(),
            )
            is LyricTextPaintOwner -> current.forEachDrawingTextPaint { paint ->
                result += TextShadowTargetSnapshot(
                    view = current,
                    paint = paint,
                    radius = paint.getShadowLayerRadius(),
                    dx = paint.getShadowLayerDx(),
                    dy = paint.getShadowLayerDy(),
                    color = paint.getShadowLayerColor(),
                )
            }
        }
        if (current is ViewGroup) {
            for (index in 0 until current.childCount) {
                queue.addLast(current.getChildAt(index))
            }
        }
    }
    return result.distinctBy { System.identityHashCode(it.paint) }
}

internal fun IslandAlbumCoverStyleHooker.collectCoverImageViews(root: ViewGroup): List<Pair<ImageView, String>> {
    val result = ArrayList<Pair<ImageView, String>>()
    val queue = ArrayDeque<View>()
    queue.add(root)
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (current is ImageView && current.id != View.NO_ID) {
            val name = runCatching { current.resources.getResourceEntryName(current.id) }.getOrNull()
            if (name == "island_fix_icon" || name == "island_small_icon") {
                result += current to name
            }
        }
        if (current is ViewGroup) {
            for (index in 0 until current.childCount) {
                queue.addLast(current.getChildAt(index))
            }
        }
    }
    return result
}

internal fun IslandAlbumCoverStyleHooker.hasNamedAncestor(view: View, stop: View, resourceName: String): Boolean {
    var current = view.parent as? View
    while (current != null) {
        if (current.id != View.NO_ID) {
            val name = runCatching { current.resources.getResourceEntryName(current.id) }.getOrNull()
            if (name == resourceName) return true
        }
        if (current === stop) break
        current = current.parent as? View
    }
    return false
}

internal fun IslandAlbumCoverStyleHooker.findFakeContentView(view: View): ViewGroup? {
    var current: View? = view
    while (current != null) {
        if (current.javaClass.name == IslandGradientCoverRuntimeIdentifiers.FAKE_CONTENT_VIEW_CLASS) {
            return current as? ViewGroup
        }
        current = current.parent as? View
    }
    return null
}

internal fun IslandAlbumCoverStyleHooker.syncRealTransitionCover(
    realOwner: View?,
    smallIsland: Boolean,
    source: String,
): Boolean {
    val root = realOwner as? ViewGroup ?: return false
    val targets = IslandAlbumCoverStyleHooker.collectCoverImageViews(root)
    val selected = selectFakeCoverTarget(root, targets, smallIsland) ?: return false
    val imageView = selected.first
    val sharedIdentity = synchronized(artworkIdentityByView) {
        artworkIdentityByView[imageView]
            ?: targets.firstNotNullOfOrNull { (target, _) -> artworkIdentityByView[target] }
    }
    if (sharedIdentity != null) {
        synchronized(artworkIdentityByView) {
            artworkIdentityByView.putIfAbsent(imageView, sharedIdentity)
        }
    }
    IslandAlbumCoverStyleHooker.ensureArtworkContinuity(imageView, "real-state:$source")
    val host = IslandAlbumCoverStyleHooker.findNamedContainer(
        root,
        if (smallIsland) "small_container" else "big_container",
    ) ?: return false
    val embedded = EmbeddedIslandAlbumCoverController.apply(host, imageView, smallIsland)
    if (embedded) {
        synchronized(IslandAlbumCoverStyleHooker.gradientStates) { IslandAlbumCoverStyleHooker.gradientStates[imageView] }?.let { state ->
            state.applyLeftContentTextShadow()
            state.removePreDrawObserver()
            state.restoreCoverVisuals()
        }
    }
    return embedded
}

internal fun IslandAlbumCoverStyleHooker.resolveFakeEmbeddedHost(
    imageView: ImageView,
    fakeView: ViewGroup,
    smallIsland: Boolean,
): ViewGroup? {
    // The fake hierarchy is rebuilt through Expanded/Big/Small stages. Only exact role
    // containers are stable enough to host the cover; geometry-based ancestors were proven
    // to select the wrong stage intermittently.
    val names = if (smallIsland) {
        arrayOf("small_container", "fake_small_container")
    } else {
        arrayOf("big_container", "fake_big_container")
    }
    return names.firstNotNullOfOrNull { name -> IslandAlbumCoverStyleHooker.findNamedContainer(fakeView, name) }
}

internal fun IslandAlbumCoverStyleHooker.findNamedContainer(root: ViewGroup, name: String): ViewGroup? {
    val direct = if (root.id != View.NO_ID) {
        runCatching { root.resources.getResourceEntryName(root.id) == name }.getOrDefault(false)
    } else {
        false
    }
    if (direct) return root
    return (IslandViewHelper.findViewByName(root, name) as? ViewGroup)
}

internal fun IslandAlbumCoverStyleHooker.resolveFakeTransitionOwner(fakeView: ViewGroup, realView: View?): View? {
    return realView ?: runCatching {
        fakeView.javaClass.methods.firstOrNull {
            it.name == "getRealView" && it.parameterTypes.isEmpty()
        }?.invoke(fakeView) as? View
    }.getOrNull()
}

internal fun IslandAlbumCoverStyleHooker.isMediaFakeTransition(fakeView: ViewGroup, realView: View?): Boolean {
    val owner = resolveFakeTransitionOwner(fakeView, realView) ?: return false
    val data = IslandProbeUtils.getCurrentIslandData(owner) ?: return false
    return IslandProbeUtils.isMediaIsland(data)
}

internal fun IslandAlbumCoverStyleHooker.resolveFakeTransitionStateClass(fakeView: ViewGroup, realView: View?): String? {
    val owner = resolveFakeTransitionOwner(fakeView, realView)
    val state = owner?.let { target ->
        runCatching {
            target.javaClass.methods.firstOrNull {
                it.name == "getState" && it.parameterTypes.isEmpty()
            }?.invoke(target)
        }.getOrNull()
    }
    return state?.javaClass?.name
}

internal fun IslandAlbumCoverStyleHooker.selectFakeCoverTarget(
    fakeView: ViewGroup,
    targets: List<Pair<ImageView, String>>,
    smallIsland: Boolean,
): Pair<ImageView, String>? {
    return if (smallIsland) {
        targets.firstOrNull { (imageView, resourceName) ->
            resourceName == "island_small_icon" ||
                hasNamedAncestor(imageView, fakeView, "small_container") ||
                hasNamedAncestor(imageView, fakeView, "fake_small_container")
        }
    } else {
        targets.firstOrNull { (imageView, resourceName) ->
            resourceName == "island_fix_icon" &&
                !hasNamedAncestor(imageView, fakeView, "small_container") &&
                !hasNamedAncestor(imageView, fakeView, "fake_small_container")
        }
    }
}

internal fun IslandAlbumCoverStyleHooker.resolveLocalSmallSnapshot(imageView: ImageView, stop: View): CoverVisualSnapshot? {
    var namedAncestor: View? = null
    var current = imageView.parent as? View
    while (current != null) {
        val name = if (current.id == View.NO_ID) null else {
            runCatching { current.resources.getResourceEntryName(current.id) }.getOrNull()
        }
        if (name == "small_container") {
            namedAncestor = current
            break
        }
        if (current === stop) break
        current = current.parent as? View
    }
    val globalContainer = (stop as? ViewGroup)?.let {
        IslandViewHelper.findViewByName(it, "small_container")
    }
    val directParent = imageView.parent as? View
    val container = namedAncestor
        ?: globalContainer
        ?: ((directParent?.parent as? View) ?: directParent)
        ?: return null
    val width = container.width.takeIf { it > 0 } ?: container.measuredWidth
    val height = container.height.takeIf { it > 0 } ?: container.measuredHeight
    if (width <= 0 || height <= 0) return null
    val dimensionTolerance = maxOf(4, height / 10)
    if (abs(width - height) > dimensionTolerance) return null
    val diameter = minOf(width, height)
    val iconWidth = imageView.expectedLayoutWidth()
    val iconHeight = imageView.expectedLayoutHeight()
    val containerLocation = container.baseLocationFor(stop)
    val imageLocation = imageView.baseLocationFor(stop)
    return CoverVisualSnapshot(
        scaleX = diameter.toFloat() / iconWidth,
        scaleY = diameter.toFloat() / iconHeight,
        translationX = containerLocation.first - imageLocation.first,
        translationY = containerLocation.second - imageLocation.second,
        coverWidth = diameter,
        coverHeight = diameter,
        smallIsland = true,
        gradientBandFraction = 0f,
        islandColor = Color.BLACK,
    )
}

internal fun IslandAlbumCoverStyleHooker.resolveLocalBigSnapshot(imageView: ImageView, stop: View): CoverVisualSnapshot? {
    var namedAncestor: View? = null
    var nearestWideContainer: View? = null
    var current = imageView.parent as? View
    while (current != null) {
        val name = if (current.id == View.NO_ID) null else {
            runCatching { current.resources.getResourceEntryName(current.id) }.getOrNull()
        }
        if (name == "big_container") {
            namedAncestor = current
            break
        }
        if (nearestWideContainer == null && current is ViewGroup) {
            val width = current.width.takeIf { it > 0 } ?: current.measuredWidth
            val height = current.height.takeIf { it > 0 } ?: current.measuredHeight
            if (height in 60..200 && width > height * 1.5f) {
                nearestWideContainer = current
            }
        }
        if (current === stop) break
        current = current.parent as? View
    }
    val globalContainer = (stop as? ViewGroup)?.let {
        IslandViewHelper.findViewByName(it, "big_container")
    }
    val host = namedAncestor ?: globalContainer ?: nearestWideContainer
    if (host == null) {
        if (BuildConfig.DEBUG) {
            HookLogger.d(
                IslandAlbumCoverStyleHooker.TAG,
                "大岛本地快照无有效宿主: view=${System.identityHashCode(imageView)}",
            )
        }
        return null
    }
    val width = host.width.takeIf { it > 0 } ?: host.measuredWidth
    val height = host.height.takeIf { it > 0 } ?: host.measuredHeight
    if (width <= height * 1.5f || height <= 0) {
        if (BuildConfig.DEBUG) {
            HookLogger.d(
                IslandAlbumCoverStyleHooker.TAG,
                "大岛本地快照尺寸无效: host=${System.identityHashCode(host)}, size=${width}x$height",
            )
        }
        return null
    }
    val iconWidth = imageView.expectedLayoutWidth()
    val iconHeight = imageView.expectedLayoutHeight()
    val coverWidth = height
    val containerLocation = host.baseLocationFor(stop)
    val imageLocation = imageView.baseLocationFor(stop)
    return CoverVisualSnapshot(
        scaleX = coverWidth.toFloat() / iconWidth,
        scaleY = height.toFloat() / iconHeight,
        translationX = containerLocation.first - imageLocation.first,
        translationY = containerLocation.second - imageLocation.second,
        coverWidth = coverWidth,
        coverHeight = height,
        smallIsland = false,
        gradientBandFraction = IslandGradientCoverLayout.gradientBandFraction(
            coverWidth = coverWidth,
            density = imageView.resources.displayMetrics.density,
        ),
        islandColor = resolveIslandBackgroundColor(host),
    )
}

internal fun IslandAlbumCoverStyleHooker.applySnapshotToImage(
    imageView: ImageView,
    snapshot: CoverVisualSnapshot,
): Boolean {
    var changed = false
    if (!imageView.isPivotSet || imageView.pivotX != 0f || imageView.pivotY != 0f) {
        imageView.pivotX = 0f
        imageView.pivotY = 0f
        changed = true
    }
    if (imageView.scaleX != snapshot.scaleX) {
        imageView.scaleX = snapshot.scaleX
        changed = true
    }
    if (imageView.scaleY != snapshot.scaleY) {
        imageView.scaleY = snapshot.scaleY
        changed = true
    }
    if (imageView.translationX != snapshot.translationX) {
        imageView.translationX = snapshot.translationX
        changed = true
    }
    if (imageView.translationY != snapshot.translationY) {
        imageView.translationY = snapshot.translationY
        changed = true
    }
    if (imageView.scaleType != ImageView.ScaleType.CENTER_CROP) {
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        changed = true
    }
    val outlineProvider = if (snapshot.smallIsland) {
        IslandAlbumCoverStyleHooker.circleOutlineProvider
    } else {
        IslandAlbumCoverStyleHooker.leftRoundedCoverOutlineProvider
    }
    var outlineChanged = false
    if (imageView.outlineProvider !== outlineProvider) {
        imageView.outlineProvider = outlineProvider
        changed = true
        outlineChanged = true
    }
    if (!imageView.clipToOutline) {
        imageView.clipToOutline = true
        changed = true
        outlineChanged = true
    }
    if (snapshot.smallIsland) {
        if (imageView.foreground != null) {
            imageView.foreground = null
            changed = true
        }
    } else if ((imageView.foreground as? RightEdgeGradientDrawable)?.matches(snapshot) != true) {
        imageView.foreground = RightEdgeGradientDrawable(
            snapshot.gradientBandFraction,
            snapshot.islandColor,
        )
        changed = true
    }
    if (outlineChanged) {
        imageView.invalidateOutline()
    }
    return changed
}

internal fun IslandAlbumCoverStyleHooker.getViewInt(view: View, getterName: String): Int? {
    return runCatching {
        view.javaClass.methods.firstOrNull {
            it.name == getterName && it.parameterTypes.isEmpty()
        }?.invoke(view).let { it as? Number }?.toInt()
    }.getOrNull()
}
