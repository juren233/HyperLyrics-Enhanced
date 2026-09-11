/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.View
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.lang.reflect.Field
import java.util.WeakHashMap

internal data class AssociatedBackground(
    val view: View,
    val ownerClass: String,
)

/**
 * 沿父链寻找超级岛胶囊的真实背景 View（宿主 `DynamicIslandBackgroundView`，或暴露
 * `getBackgroundView()` 的内容容器）。渐变封面的几何解析与底色解析共用这一结构约定。
 */
internal fun findIslandBackgroundView(view: View): AssociatedBackground? {
    var current: View? = view
    while (current != null) {
        if (current.javaClass.simpleName == "DynamicIslandBackgroundView") {
            return AssociatedBackground(current, current.javaClass.name)
        }
        val background = runCatching {
            current.javaClass.methods.firstOrNull {
                it.name == "getBackgroundView" && it.parameterTypes.isEmpty()
            }?.invoke(current) as? View
        }.getOrNull()
        if (background != null) {
            return AssociatedBackground(background, current.javaClass.name)
        }
        current = current.parent as? View
    }
    return null
}

/**
 * 解析超级岛的实时底色。手机 OLED 上胶囊为纯黑；平板/LCD 设备的宿主底色可能是深灰，
 * 渐变封面的渐变带与末端遮罩必须跟随该底色，否则会在灰底上留下一块纯黑。
 *
 * 解析顺序：关联背景 View（`DynamicIslandBackgroundView`）在 onDraw 里自绘的私有 `drawable`
 * 字段（真机证据 ISLAND-GRADIENT-COVER-COLOR-001：`view.background` 是透明 ColorDrawable，
 * 可见胶囊色在私有 drawable 里）→ 该 View 的 `background` → 沿用旧的候选启发式
 * （solidColor / ColorDrawable）→ 纯黑兜底。
 */
internal fun resolveIslandBackgroundColor(view: View): Int {
    val associated = findIslandBackgroundView(view)
    var source = "none"
    var resolved = Color.BLACK
    if (associated != null) {
        IslandBackgroundColorSampler.privateViewDrawable(associated.view)
            ?.let { IslandBackgroundColorSampler.drawableColor(it) }
            ?.also { resolved = it; source = "private-drawable" }
            ?: IslandBackgroundColorSampler.drawableColor(associated.view.background)
                ?.also { resolved = it; source = "background" }
    }
    if (source == "none") {
        val candidates = buildList {
            add(view)
            (view.parent as? View)?.let(::add)
            ((view.parent as? View)?.parent as? View)?.let(::add)
            add(view.rootView)
        }
        for (candidate in candidates) {
            runCatching {
                val solid = candidate.solidColor
                if (solid != 0 && Color.alpha(solid) == 255) {
                    resolved = solid
                    source = "solidColor"
                    return@runCatching
                }
            }
            if (source != "none") break
            IslandBackgroundColorSampler.drawableColor(candidate.background)?.let {
                resolved = it
                source = "candidate-background"
            }
            if (source != "none") break
        }
    }
    logResolvedIslandColor(source, associated != null, resolved)
    return resolved
}

/** Debug-only：按来源+颜色签名去重，验证取色链路是否命中真实胶囊色。 */
private fun logResolvedIslandColor(source: String, backgroundFound: Boolean, color: Int) {
    if (!BuildConfig.DEBUG) return
    val signature = "$source/$backgroundFound/$color"
    if (signature == lastLoggedColorSignature) return
    lastLoggedColorSignature = signature
    HookLogger.i(
        ISLAND_COLOR_RESOLVER_TAG,
        "岛底色解析: source=$source, backgroundFound=$backgroundFound, " +
            "color=${Integer.toHexString(color)}",
    )
}

private var lastLoggedColorSignature: String? = null

internal const val ISLAND_COLOR_RESOLVER_TAG = "IslandBackgroundColor"

internal object IslandBackgroundColorSampler {
    // 胶囊底色几乎不随时间变化；TTL 只用于主题/形态切换后的低频重采样。
    private const val SAMPLE_TTL_MS = 5_000L
    private const val SAMPLE_SIZE = 32

    // 仅绘制 background drawable 本身（而非整个 View），避免把歌词文本像素混进底色。
    private val sampleCache = WeakHashMap<Drawable, SampledColor>()

    private data class SampledColor(
        val boundsWidth: Int,
        val boundsHeight: Int,
        val color: Int,
        val sampledAtMs: Long,
    )

    fun drawableColor(drawable: Drawable?): Int? {
        if (drawable == null) return null
        when (drawable) {
            is ColorDrawable -> return opaqueOrNull(drawable.color)
            is GradientDrawable -> {
                val solid = runCatching { drawable.color?.defaultColor }.getOrNull()
                if (solid != null) return opaqueOrNull(solid)
            }
        }
        return sampledColor(drawable)
    }

    // DynamicIslandBackgroundView 的可见胶囊色在 onDraw 自绘的私有 `drawable` 字段里
    // （真机证据 ISLAND-GRADIENT-COVER-COLOR-001：view.background 是透明 ColorDrawable）。
    private val privateDrawableFields = HashMap<String, Field?>()

    fun privateViewDrawable(view: View): Drawable? = runCatching {
        val field = synchronized(privateDrawableFields) {
            privateDrawableFields.getOrPut(view.javaClass.name) {
                var current: Class<*>? = view.javaClass
                while (current != null && current != View::class.java) {
                    val found = runCatching {
                        current.getDeclaredField("drawable").apply { isAccessible = true }
                    }.getOrNull()
                    if (found != null) return@getOrPut found
                    current = current.superclass
                }
                null
            }
        }
        field?.get(view) as? Drawable
    }.getOrNull()

    private fun sampledColor(drawable: Drawable): Int? {
        val bounds = drawable.bounds
        if (bounds.isEmpty) return null
        synchronized(sampleCache) {
            sampleCache[drawable]?.let { cached ->
                val fresh = SystemClock.uptimeMillis() - cached.sampledAtMs < SAMPLE_TTL_MS
                if (fresh &&
                    cached.boundsWidth == bounds.width() &&
                    cached.boundsHeight == bounds.height()
                ) {
                    return cached.color
                }
            }
        }
        val color = sampleDrawableNow(drawable, bounds) ?: return null
        synchronized(sampleCache) {
            sampleCache[drawable] = SampledColor(
                boundsWidth = bounds.width(),
                boundsHeight = bounds.height(),
                color = color,
                sampledAtMs = SystemClock.uptimeMillis(),
            )
        }
        return color
    }

    private fun sampleDrawableNow(drawable: Drawable, bounds: Rect): Int? {
        val bitmap = Bitmap.createBitmap(SAMPLE_SIZE, SAMPLE_SIZE, Bitmap.Config.ARGB_8888)
        val oldBounds = Rect(bounds)
        try {
            drawable.setBounds(0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
            drawable.draw(Canvas(bitmap))
            val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
            bitmap.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
            return averageOpaqueSampledColor(pixels, SAMPLE_SIZE, SAMPLE_SIZE)
        } catch (_: Throwable) {
            return null
        } finally {
            drawable.bounds = oldBounds
            bitmap.recycle()
        }
    }

    private fun opaqueOrNull(color: Int): Int? {
        return if (Color.alpha(color) == 255) color else null
    }
}

/**
 * 对采样像素中心区域（四周各收缩 [insetFraction]，避开胶囊圆角）的不透明像素求平均。
 * 出现半透明像素说明底色是叠加在壁纸上的遮罩，其显示效果依赖壁纸，无法用单一颜色匹配，
 * 此时返回 null 让调用方退回下一优先级的来源。
 */
internal fun averageOpaqueSampledColor(
    pixels: IntArray,
    width: Int,
    height: Int,
    insetFraction: Float = 0.25f,
): Int? {
    if (width <= 0 || height <= 0 || pixels.size < width * height) return null
    val insetX = (width * insetFraction).toInt().coerceIn(0, width / 2)
    val insetY = (height * insetFraction).toInt().coerceIn(0, height / 2)
    var count = 0
    var red = 0L
    var green = 0L
    var blue = 0L
    for (y in insetY until height - insetY) {
        for (x in insetX until width - insetX) {
            val color = pixels[y * width + x]
            if (color ushr 24 != 255) continue
            red += (color shr 16) and 0xFF
            green += (color shr 8) and 0xFF
            blue += color and 0xFF
            count++
        }
    }
    if (count == 0) return null
    // 纯位运算组装而非 Color.argb：该函数在 JVM 单元测试中验证，android.graphics 未被 mock。
    return (255 shl 24) or
        ((red / count).toInt() shl 16) or
        ((green / count).toInt() shl 8) or
        (blue / count).toInt()
}
