/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.util.WeakHashMap

/** Placement of the native module in the area, not text alignment inside our wrapper. */
internal object IslandNativeSlotPlacement {
    private val originalGravities = WeakHashMap<View, Int>()

    fun apply(root: ViewGroup, config: IslandSlotRuntimeConfig): Boolean {
        val left = applySide(root, IslandProbeUtils.LEFT_PARENT_NAME,
            config.dynamicWidthEnabled && config.shouldInjectLeft, config.wrapperHorizontalGravity(true))
        val right = applySide(root, IslandProbeUtils.RIGHT_PARENT_NAME,
            config.dynamicWidthEnabled && config.shouldInjectRight, config.wrapperHorizontalGravity(false))
        return left || right
    }

    fun restore(root: ViewGroup) {
        applySide(root, IslandProbeUtils.LEFT_PARENT_NAME, false, Gravity.START)
        applySide(root, IslandProbeUtils.RIGHT_PARENT_NAME, false, Gravity.START)
    }

    private fun applySide(root: ViewGroup, name: String, enabled: Boolean, horizontal: Int): Boolean {
        val module = IslandViewHelper.findViewByName(root, name) ?: return false
        val params = module.layoutParams as? FrameLayout.LayoutParams ?: return false
        val original = if (enabled) {
            originalGravities.getOrPut(module) { params.gravity }
        } else {
            originalGravities.remove(module) ?: return false
        }
        val expected = resolveGravity(original, enabled, horizontal)
        if (params.gravity == expected) return false
        val previous = params.gravity
        params.gravity = expected
        module.layoutParams = params
        if (BuildConfig.DEBUG) {
            HookLogger.d("IslandNativeSlotPlacement",
                "native_anchor name=$name enabled=$enabled gravity=$previous->$expected " +
                    "original=$original module=${module.left},${module.right}/${module.measuredWidth} " +
                    "areaWidth=${(module.parent as? View)?.width} translationX=${module.translationX}")
        }
        return true
    }

    /**
     * Binary resource evidence: OS4.0.0.6 MIUISystemUIPlugin.apk,
     * res/layout/dynamic_island_module_image_text_2.xml declares its root as
     * WRAP_CONTENT + END|CENTER_VERTICAL. Its text include is already START.
     * Shortening only descendants leaves that whole module anchored at END.
     * Keep measurement, margins and vertical placement intact; restore the exact
     * native gravity when dynamic sizing or the injected slot is disabled.
     */
    internal fun resolveGravity(original: Int, enabled: Boolean, horizontal: Int): Int {
        if (!enabled) return original
        // FrameLayout treats an unspecified gravity as TOP|START, not all bits set.
        val base = if (original == -1) Gravity.TOP or Gravity.START else original
        return (base and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK.inv()) or horizontal
    }
}
