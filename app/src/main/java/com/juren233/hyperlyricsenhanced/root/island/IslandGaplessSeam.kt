/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import android.view.View
import android.view.ViewGroup
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.lyric.view.SpaceGateRichLyricLineView
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.util.Collections
import java.util.WeakHashMap

/**
 * 无顶部挖孔时（手机横屏）全岛连续歌词去掉中间间隙，左右两槽直接相接。
 *
 * 原生依据（miui.systemui.plugin 18.3.2.67.0 反编译，2026-11-19）：
 * - 大岛模板 `dynamic_big_island_template_standard` 的 `big_container` 是横向 LinearLayout：
 *   `area_left`（weight=1，paddingEnd=island_area_padding_cutout 1dp）、`area_cutout`、
 *   `area_right`（weight=1，paddingStart=1dp）。
 * - `area_cutout` 的宽度只由 `IslandTemplateBuilder.updateCutoutWidth(getFixedCutoutWidth())`
 *   在大岛动画开始时写 layoutParams.width；原生从不改它的可见性。
 * - `DynamicIslandWindowView.getCutoutWidth()`：显示屏顶部挖孔宽度 ≤ 0 时取 20dp 保底
 *   （横屏日志 `cutoutWidth 61`），即中间没有摄像头时原生仍留出 20dp。
 *
 * 生效时：`area_cutout` 设为 GONE（LinearLayout 不为 GONE 子视图留宽，左右区按 weight 吃掉原宽度）、
 * 两区朝向中间的 1dp 内边距与两槽朝向中间的内边距清零、连续文本带关闭接缝避让。
 * 是否生效只记在两槽视图的 [SpaceGateRichLyricLineView.seamOcclusion] 上（唯一来源）。
 */
internal object IslandGaplessSeam {
    private const val TAG = "IslandGaplessSeam"
    private const val AREA_LEFT = "area_left"
    private const val AREA_RIGHT = "area_right"
    private const val AREA_CUTOUT = "area_cutout"
    private const val BIG_CONTAINER = "big_container"

    private val hiddenCutouts = WeakHashMap<View, Int>()
    private val areaInnerPaddings = WeakHashMap<View, Int>()
    private val suppressedWrappers: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

    /** 全岛模式、两槽都是连续文本带、且显示屏顶部没有挖孔。挖孔状态未知时保持原生。 */
    fun decide(
        fullIsland: Boolean,
        leftContinuous: Boolean,
        rightContinuous: Boolean,
        hasTopCutout: Boolean?,
    ): Boolean = fullIsland && leftContinuous && rightContinuous && hasTopCutout == false

    /** 与原生 `getCutoutWidth()` 同一判据：当前方向下显示屏顶部挖孔区域是否非空。 */
    fun hasTopCutout(view: View): Boolean? {
        val display = view.display ?: return null
        val cutout = display.cutout ?: return false
        return !cutout.boundingRectTop.isEmpty
    }

    /** 槽位包装层朝向中间一侧的内边距是否被清零（IslandLyricTextInjector 写内边距时读取）。 */
    fun innerPaddingSuppressed(wrapper: View): Boolean = wrapper in suppressedWrappers

    /** 按当前内容同步；返回是否改动了任何布局属性。 */
    fun sync(root: ViewGroup, config: IslandSlotRuntimeConfig): Boolean {
        val left = root.findViewWithTag<View>(IslandProbeUtils.LEFT_TEST_VIEW_TAG) as? SpaceGateRichLyricLineView
        val right = root.findViewWithTag<View>(IslandProbeUtils.RIGHT_TEST_VIEW_TAG) as? SpaceGateRichLyricLineView
        val topCutout = hasTopCutout(root)
        val active = left != null && right != null && decide(
            config.isFullIslandMode, left.continuousSpaceGate, right.continuousSpaceGate, topCutout,
        )
        val changed = apply(root, left, right, config, active)
        if (BuildConfig.DEBUG && changed) {
            HookLogger.d(TAG, "去间隙状态变化: active=$active full=${config.isFullIslandMode} " +
                "continuous=${left?.continuousSpaceGate}/${right?.continuousSpaceGate} topCutout=$topCutout " +
                "root=${root.javaClass.simpleName}@${Integer.toHexString(System.identityHashCode(root))}")
        }
        return changed
    }

    /** 内容写入单个槽位后，从槽位向上找到大岛容器再同步。 */
    fun syncFromSlot(slot: View, config: IslandSlotRuntimeConfig): Boolean {
        var parent = slot.parent
        while (parent is ViewGroup) {
            if (entryName(parent) == BIG_CONTAINER) return sync(parent, config)
            parent = parent.parent
        }
        return false
    }

    /** 清理注入内容时恢复原生间隙。 */
    fun restore(root: ViewGroup): Boolean {
        val left = root.findViewWithTag<View>(IslandProbeUtils.LEFT_TEST_VIEW_TAG) as? SpaceGateRichLyricLineView
        val right = root.findViewWithTag<View>(IslandProbeUtils.RIGHT_TEST_VIEW_TAG) as? SpaceGateRichLyricLineView
        return apply(root, left, right, config = null, active = false).also { changed ->
            if (BuildConfig.DEBUG && changed) HookLogger.d(TAG, "去间隙已恢复原生间隙（清理注入内容）")
        }
    }

    private fun apply(
        root: ViewGroup,
        left: SpaceGateRichLyricLineView?,
        right: SpaceGateRichLyricLineView?,
        config: IslandSlotRuntimeConfig?,
        active: Boolean,
    ): Boolean {
        var changed = false
        for (slot in listOfNotNull(left, right)) {
            if (slot.seamOcclusion == active) {
                slot.seamOcclusion = !active
                changed = true
            }
        }
        IslandViewHelper.findViewByName(root, AREA_CUTOUT)?.let { cutout ->
            if (active && cutout.visibility != View.GONE) {
                hiddenCutouts[cutout] = cutout.visibility
                cutout.visibility = View.GONE
                changed = true
            } else if (!active) {
                hiddenCutouts.remove(cutout)?.let {
                    cutout.visibility = it
                    changed = true
                }
            }
        }
        changed = setAreaInnerPadding(IslandViewHelper.findViewByName(root, AREA_LEFT), isLeft = true, active) || changed
        changed = setAreaInnerPadding(IslandViewHelper.findViewByName(root, AREA_RIGHT), isLeft = false, active) || changed
        changed = setWrapperInnerPadding(
            root.findViewWithTag(IslandProbeUtils.LEFT_TEST_WRAPPER_TAG), isLeft = true, active, config,
        ) || changed
        changed = setWrapperInnerPadding(
            root.findViewWithTag(IslandProbeUtils.RIGHT_TEST_WRAPPER_TAG), isLeft = false, active, config,
        ) || changed
        return changed
    }

    private fun setAreaInnerPadding(area: View?, isLeft: Boolean, active: Boolean): Boolean {
        area ?: return false
        val current = if (isLeft) area.paddingEnd else area.paddingStart
        val target = if (active) {
            if (area !in areaInnerPaddings) areaInnerPaddings[area] = current
            0
        } else {
            areaInnerPaddings.remove(area) ?: return false
        }
        if (current == target) return false
        if (isLeft) {
            area.setPaddingRelative(area.paddingStart, area.paddingTop, target, area.paddingBottom)
        } else {
            area.setPaddingRelative(target, area.paddingTop, area.paddingEnd, area.paddingBottom)
        }
        return true
    }

    private fun setWrapperInnerPadding(
        wrapper: View?,
        isLeft: Boolean,
        active: Boolean,
        config: IslandSlotRuntimeConfig?,
    ): Boolean {
        wrapper ?: return false
        val target = if (active) {
            suppressedWrappers.add(wrapper)
            0
        } else {
            if (!suppressedWrappers.remove(wrapper)) return false
            // 无配置（清理路径）时留给下次注入按配置重写。
            config ?: return false
            if (isLeft) config.paddingRightPx(wrapper, IslandProbeUtils.LEFT_PARENT_NAME)
            else config.paddingLeftPx(wrapper, IslandProbeUtils.RIGHT_PARENT_NAME)
        }
        val current = if (isLeft) wrapper.paddingRight else wrapper.paddingLeft
        if (current == target) return false
        if (isLeft) {
            wrapper.setPadding(wrapper.paddingLeft, wrapper.paddingTop, target, wrapper.paddingBottom)
        } else {
            wrapper.setPadding(target, wrapper.paddingTop, wrapper.paddingRight, wrapper.paddingBottom)
        }
        return true
    }

    private fun entryName(view: View): String? {
        if (view.id == View.NO_ID) return null
        return runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
    }
}
