/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import android.view.ViewGroup
import android.view.View
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.lyric.view.SpaceGateRichLyricLineView
import com.juren233.hyperlyricsenhanced.root.HookEntry
import com.juren233.hyperlyricsenhanced.root.island.IslandDynamicLimitPolicy.Geometry
import com.juren233.hyperlyricsenhanced.root.island.renderer.BaseIslandRenderer
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * The two full-island viewports still measure at their maximum capacity. After native
 * limits, the island shrinks to its bound content. Independent slots size each side to
 * its own content. A continuous lyric band is one line drawn across both slots, so it is
 * measured once and split symmetrically (IslandFullIslandWidthPolicy.continuousSide).
 */
internal object IslandFullIslandDynamicWidth {
    private data class ResultApi(val getters: List<Method>, val constructor: Constructor<*>)
    private val resultApis = WeakHashMap<Class<*>, ResultApi>()
    private var loggedHit = false

    fun apply(result: Any?, params: Any?, helper: Any?, pad: Boolean): Any? {
        val root = IslandDynamicWidthLimiter.calculatingContent ?: return result
        if (!IslandWidthHooker.lyricWidthCalculationActive) return result
        val prefs = HookEntry.instance?.prefs ?: return result
        val config = IslandSlotRuntimeConfig.from(prefs)
        if (!config.dynamicWidthEnabled || !config.isFullIslandMode) return result
        if (result == null || params == null || helper == null) return result
        val left = root.findViewWithTag<View>(IslandProbeUtils.LEFT_TEST_VIEW_TAG)
            as? SpaceGateRichLyricLineView ?: return result
        val right = root.findViewWithTag<View>(IslandProbeUtils.RIGHT_TEST_VIEW_TAG)
            as? SpaceGateRichLyricLineView ?: return result
        if (BaseIslandRenderer.isSlotReservedByNextSongPreview(root, IslandProbeUtils.LEFT_TEST_VIEW_TAG) ||
            BaseIslandRenderer.isSlotReservedByNextSongPreview(root, IslandProbeUtils.RIGHT_TEST_VIEW_TAG)
        ) return result
        return runCatching {
            val leftArea = IslandViewHelper.findViewByName(root, "area_left") as? ViewGroup ?: return result
            val rightArea = IslandViewHelper.findViewByName(root, "area_right") as? ViewGroup ?: return result
            if (left.measuredWidth <= 0 || right.measuredWidth <= 0) return result
            // All runtime descriptors are the existing, original-DEX-verified result profile.
            val api = resultApis.getOrPut(result.javaClass) {
                ResultApi(IslandDynamicLimitProfile.RESULT_GETTERS.map { result.javaClass.getMethod(it) },
                    result.javaClass.getConstructor(*Array(9) { Int::class.javaPrimitiveType!! }))
            }
            val values = api.getters.map { it.invoke(result) as Int }.toMutableList()
            // A preview promotion may temporarily reserve an incoming width. It is not
            // a new maximum capacity; preserve the last native limit until landing.
            if (!left.isNextLinePromotionRunning && !right.isNextLinePromotionRunning) {
                IslandShortLyricLayout.updateCapacity(right, config,
                    IslandFullIslandWidthPolicy.contentCapacity(values[2], rightArea.measuredWidth, right.measuredWidth))
            }
            if (BuildConfig.DEBUG && !loggedHit) {
                loggedHit = true
                HookLogger.d("IslandFullWidth", "first calculation pad=$pad full=${left.continuousSpaceGate}/${right.continuousSpaceGate}")
            }
            // Consume the roles actually bound to both views, including the old pair during fade-out.
            val continuous = left.continuousSpaceGate
            if (continuous != right.continuousSpaceGate) return result
            // The next line previewed on the right half occupies its own width; keep the band.
            if (continuous && config.nextLineOnRight) return result
            val leftDemand: Int
            val rightDemand: Int
            if (continuous) {
                // 换句淡出期间按即将落地的行计算，岛宽与换句动画同时变化。
                val line = right.widthDemandLine ?: return result
                val side = IslandFullIslandWidthPolicy.continuousSide(
                    content = right.measureIndependentContentWidth(line),
                    leftOverhead = IslandFullIslandWidthPolicy.overhead(leftArea.measuredWidth, left.measuredWidth),
                    rightOverhead = IslandFullIslandWidthPolicy.overhead(rightArea.measuredWidth, right.measuredWidth),
                ) { seam -> right.staticSeamClearance(seam.toFloat()) }
                leftDemand = side
                rightDemand = side
            } else {
                val leftLine = left.rawLine ?: return result
                val rightLine = right.rawLine ?: return result
                leftDemand = IslandFullIslandWidthPolicy.requiredArea(leftArea.measuredWidth,
                    left.measuredWidth, left.measureIndependentContentWidth(leftLine))
                rightDemand = IslandFullIslandWidthPolicy.requiredArea(rightArea.measuredWidth,
                    right.measuredWidth, right.measureIndependentContentWidth(rightLine))
            }
            // Gapless seam: the cutout spacer is GONE, so no middle width is reserved.
            val fixedOverride = if (continuous && !left.seamOcclusion) 0 else null
            val screenWidth = IslandReflect.noArgMethod(
                params.javaClass,
                IslandDynamicLimitProfile.SCREEN_WIDTH_GETTER,
            )?.invoke(params) as Int
            val floor = IslandDynamicLimitProfile.readIslandHeight(helper) +
                (2 * root.resources.displayMetrics.density).toInt()
            val original = Geometry(values[0], values[1], values[2], values[3])
            val target = IslandFullIslandWidthPolicy.shrink(original, leftDemand, rightDemand,
                screenWidth, floor, pad, fixedOverride)
            if (target == original) return result
            values[0] = target.width
            values[1] = target.left
            values[2] = target.right
            values[3] = target.x
            // Retain the native HasSmallIsland alternative, as the existing unlock hooks do.
            if (BuildConfig.DEBUG) HookLogger.d("IslandFullWidth",
                "${if (continuous) "continuous" else "independent"} pad=$pad gapless=${fixedOverride != null} " +
                    "demand=$leftDemand/$rightDemand capacity=$original final=$target")
            api.constructor.newInstance(*values.toTypedArray())
        }.onFailure {
            if (BuildConfig.DEBUG) HookLogger.w("IslandFullWidth", "measure failed: ${it.javaClass.simpleName}")
        }.getOrDefault(result)
    }
}
