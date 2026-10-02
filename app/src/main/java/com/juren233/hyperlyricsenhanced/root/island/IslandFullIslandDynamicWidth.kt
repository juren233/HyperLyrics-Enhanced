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
 * limits, independent slots may shrink to their bound content. Continuous lyrics keep
 * their shared canvas: measuring the complete lyric in each slot would count it twice.
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
            if (left.continuousSpaceGate || right.continuousSpaceGate) return result
            val leftLine = left.rawLine ?: return result
            val rightLine = right.rawLine ?: return result
            val leftDemand = IslandFullIslandWidthPolicy.requiredArea(leftArea.measuredWidth,
                left.measuredWidth, left.measureIndependentContentWidth(leftLine))
            val rightDemand = IslandFullIslandWidthPolicy.requiredArea(rightArea.measuredWidth,
                right.measuredWidth, right.measureIndependentContentWidth(rightLine))
            val screenWidth = params.javaClass.getMethod(IslandDynamicLimitProfile.SCREEN_WIDTH_GETTER)
                .invoke(params) as Int
            val floor = IslandDynamicLimitProfile.readIslandHeight(helper) +
                (2 * root.resources.displayMetrics.density).toInt()
            val original = Geometry(values[0], values[1], values[2], values[3])
            val target = IslandFullIslandWidthPolicy.shrink(original, leftDemand, rightDemand,
                screenWidth, floor, pad)
            if (target == original) return result
            values[0] = target.width
            values[1] = target.left
            values[2] = target.right
            values[3] = target.x
            // Retain the native HasSmallIsland alternative, as the existing unlock hooks do.
            if (BuildConfig.DEBUG) HookLogger.d("IslandFullWidth",
                "independent pad=$pad demand=$leftDemand/$rightDemand capacity=$original final=$target")
            api.constructor.newInstance(*values.toTypedArray())
        }.onFailure {
            if (BuildConfig.DEBUG) HookLogger.w("IslandFullWidth", "measure failed: ${it.javaClass.simpleName}")
        }.getOrDefault(result)
    }
}
