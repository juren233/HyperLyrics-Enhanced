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
    private val iconAnchors = WeakHashMap<ViewGroup, NativeIconAnchor>()

    fun apply(
        root: ViewGroup,
        config: IslandSlotRuntimeConfig,
        leftDuetAlignedRight: Boolean? = null,
        rightDuetAlignedRight: Boolean? = null,
    ): Boolean {
        // Album art and rhythm share their native WRAP_CONTENT modules with the text slot.
        // Capture the native module gravity before applying the text placement so each icon
        // can stay at its original edge while only the content follows CENTER/END.
        val album = preserveNativeIconAnchor(
            root = root,
            moduleName = IslandProbeUtils.LEFT_PARENT_NAME,
            enabled = config.dynamicWidthEnabled && config.shouldInjectLeft &&
                IslandSlotContentAssembler.isAlbumPlaced(config),
            diagnosticName = "album",
        )
        val rhythm = preserveNativeIconAnchor(
            root = root,
            moduleName = IslandProbeUtils.RIGHT_PARENT_NAME,
            enabled = config.dynamicWidthEnabled && config.shouldInjectRight &&
                IslandSlotContentAssembler.isRhythmVisible(config),
            diagnosticName = "rhythm",
        )
        val left = applySide(root, IslandProbeUtils.LEFT_PARENT_NAME,
            config.dynamicWidthEnabled && config.shouldInjectLeft,
            config.wrapperHorizontalGravity(true, leftDuetAlignedRight))
        val right = applySide(root, IslandProbeUtils.RIGHT_PARENT_NAME,
            config.dynamicWidthEnabled && config.shouldInjectRight,
            config.wrapperHorizontalGravity(false, rightDuetAlignedRight))
        return left || right || album || rhythm
    }

    fun restore(root: ViewGroup) {
        preserveNativeIconAnchor(root, IslandProbeUtils.LEFT_PARENT_NAME, false, "album")
        preserveNativeIconAnchor(root, IslandProbeUtils.RIGHT_PARENT_NAME, false, "rhythm")
        applySide(root, IslandProbeUtils.LEFT_PARENT_NAME, false, Gravity.START)
        applySide(root, IslandProbeUtils.RIGHT_PARENT_NAME, false, Gravity.START)
    }

    private fun preserveNativeIconAnchor(
        root: ViewGroup,
        moduleName: String,
        enabled: Boolean,
        diagnosticName: String,
    ): Boolean {
        val module = IslandViewHelper.findViewByName(root, moduleName)
            as? ViewGroup ?: return false
        val previous = iconAnchors[module]
        if (!enabled) {
            previous ?: return false
            previous.restore(module)
            iconAnchors.remove(module)
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
        val currentGravity = (module.layoutParams as? FrameLayout.LayoutParams)?.gravity
            ?: return false
        // A native rebind can replace the icon View while keeping the already re-anchored
        // module. In that case the gravity map still owns the true pre-injection value.
        val originalGravity = originalGravities[module] ?: currentGravity
        val state = NativeIconAnchor(module, area, icon, originalGravity, diagnosticName)
        iconAnchors[module] = state
        module.clipChildren = false
        module.clipToPadding = false
        module.addOnLayoutChangeListener(state.listener)
        area.addOnLayoutChangeListener(state.listener)
        state.update(module)
        return true
    }

    private class NativeIconAnchor(
        module: ViewGroup,
        areaView: ViewGroup,
        iconView: View,
        private val originalGravity: Int,
        private val diagnosticName: String,
    ) {
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
            val offset = nativeIconOffset(areaView.width, areaView.paddingLeft, areaView.paddingRight,
                params.leftMargin, params.rightMargin, module.left, module.width,
                originalGravity, module.layoutDirection == View.LAYOUT_DIRECTION_RTL)
            iconView.translationX = originalTranslation + offset
            if (BuildConfig.DEBUG && !logged && offset != 0f) {
                logged = true
                HookLogger.d("IslandNativeSlotPlacement",
                    "${diagnosticName}_anchor area=${areaView.width} " +
                        "module=${module.left},${module.right} icon=${iconView.left},${iconView.right} " +
                        "originalGravity=$originalGravity offset=$offset")
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
        return nativeIconOffset(areaWidth, paddingLeft, paddingRight, leftMargin, rightMargin,
            moduleLeft, moduleWidth, Gravity.END, rtl)
    }

    internal fun nativeIconOffset(
        areaWidth: Int,
        paddingLeft: Int,
        paddingRight: Int,
        leftMargin: Int,
        rightMargin: Int,
        moduleLeft: Int,
        moduleWidth: Int,
        originalGravity: Int,
        rtl: Boolean,
    ): Float {
        val startLeft = paddingLeft + leftMargin
        val endLeft = areaWidth - paddingRight - rightMargin - moduleWidth
        val horizontalGravity = originalGravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK
        val nativeLeft = when (horizontalGravity) {
            Gravity.START -> if (rtl) endLeft else startLeft
            Gravity.END -> if (rtl) startLeft else endLeft
            Gravity.RIGHT -> endLeft
            Gravity.CENTER_HORIZONTAL -> startLeft + (endLeft - startLeft) / 2
            else -> startLeft
        }
        return (nativeLeft - moduleLeft).toFloat()
    }

    private fun applySide(root: ViewGroup, name: String, enabled: Boolean, horizontal: Int): Boolean {
        val module = IslandViewHelper.findViewByName(root, name) ?: return false
        return applyModuleGravity(module, enabled, horizontal)
    }

    /**
     * 对单个原生模块应用锚点；除 [applySide] 的按名查找外，也供内容落地时的
     * 对唱方向同步直接使用（持模块引用，避免向上遍历找 root 再按名查一遍）。
     */
    internal fun applyModuleGravity(module: View, enabled: Boolean, horizontal: Int): Boolean {
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
                "native_anchor enabled=$enabled gravity=$previous->$expected " +
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
