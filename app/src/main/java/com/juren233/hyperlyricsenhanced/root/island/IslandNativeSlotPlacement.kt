/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.util.WeakHashMap
import java.lang.ref.WeakReference

/** Placement of the native module in the area, not text alignment inside our wrapper. */
internal object IslandNativeSlotPlacement {
    private val originalGravities = WeakHashMap<View, Int>()
    private val rhythmAnchors = WeakHashMap<ViewGroup, RhythmAnchor>()

    fun apply(root: ViewGroup, config: IslandSlotRuntimeConfig): Boolean {
        val left = applySide(root, IslandProbeUtils.LEFT_PARENT_NAME,
            config.dynamicWidthEnabled && config.shouldInjectLeft, config.wrapperHorizontalGravity(true))
        val right = applySide(root, IslandProbeUtils.RIGHT_PARENT_NAME,
            config.dynamicWidthEnabled && config.shouldInjectRight, config.wrapperHorizontalGravity(false))
        val rhythm = preserveRhythmAnchor(root,
            config.dynamicWidthEnabled && config.shouldInjectRight && config.showRhythm)
        return left || right || rhythm
    }

    fun restore(root: ViewGroup) {
        preserveRhythmAnchor(root, false)
        applySide(root, IslandProbeUtils.LEFT_PARENT_NAME, false, Gravity.START)
        applySide(root, IslandProbeUtils.RIGHT_PARENT_NAME, false, Gravity.START)
    }

    private fun preserveRhythmAnchor(root: ViewGroup, enabled: Boolean): Boolean {
        val module = IslandViewHelper.findViewByName(root, IslandProbeUtils.RIGHT_PARENT_NAME)
            as? ViewGroup ?: return false
        val previous = rhythmAnchors[module]
        if (!enabled) {
            previous ?: return false
            previous.restore(module)
            rhythmAnchors.remove(module)
            return true
        }
        val area = module.parent as? ViewGroup ?: return false
        // Current device's original resource table: image_text_2 -> res/e7S.xml,
        // icon_1 -> res/dCn.xml. Text and icon share a WRAP_CONTENT FrameLayout.
        // Move only the icon's drawing, never its measured width or the lyric anchor.
        val icon = IslandViewHelper.findViewByName(module, "island_container_module_icon")
            ?: return false
        if (previous != null && previous.icon.get() === icon && previous.area.get() === area) {
            previous.update(module)
            return false
        }
        previous?.restore(module)
        val state = RhythmAnchor(module, area, icon)
        rhythmAnchors[module] = state
        module.clipChildren = false
        module.clipToPadding = false
        module.addOnLayoutChangeListener(state.listener)
        area.addOnLayoutChangeListener(state.listener)
        state.update(module)
        return true
    }

    private class RhythmAnchor(module: ViewGroup, areaView: ViewGroup, iconView: View) {
        val area = WeakReference(areaView)
        val icon = WeakReference(iconView)
        private val originalTranslation = iconView.translationX
        private val originalClipChildren = module.clipChildren
        private val originalClipToPadding = module.clipToPadding
        private val moduleRef = WeakReference(module)
        private var logged = false
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            moduleRef.get()?.let(::update)
        }

        fun update(module: ViewGroup) {
            val areaView = area.get() ?: return
            val iconView = icon.get() ?: return
            if (module.width <= 0 || areaView.width <= 0) return
            val params = module.layoutParams as? FrameLayout.LayoutParams ?: return
            val offset = rhythmOffset(areaView.width, areaView.paddingLeft, areaView.paddingRight,
                params.leftMargin, params.rightMargin, module.left, module.width,
                module.layoutDirection == View.LAYOUT_DIRECTION_RTL)
            iconView.translationX = originalTranslation + offset
            if (BuildConfig.DEBUG && !logged && offset != 0f) {
                logged = true
                HookLogger.d("IslandNativeSlotPlacement",
                    "rhythm_anchor area=${areaView.width} module=${module.left},${module.right} " +
                        "icon=${iconView.left},${iconView.right} offset=$offset")
            }
        }

        fun restore(module: ViewGroup) {
            module.removeOnLayoutChangeListener(listener)
            area.get()?.removeOnLayoutChangeListener(listener)
            icon.get()?.translationX = originalTranslation
            module.clipChildren = originalClipChildren
            module.clipToPadding = originalClipToPadding
        }
    }

    internal fun rhythmOffset(areaWidth: Int, paddingLeft: Int, paddingRight: Int,
        leftMargin: Int, rightMargin: Int, moduleLeft: Int, moduleWidth: Int, rtl: Boolean): Float {
        val nativeLeft = if (rtl) paddingLeft + leftMargin
            else areaWidth - paddingRight - rightMargin - moduleWidth
        return (nativeLeft - moduleLeft).toFloat()
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
