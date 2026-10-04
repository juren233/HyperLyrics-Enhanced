package com.juren233.hyperlyricsenhanced.root.island

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.HookEntry
import com.juren233.hyperlyricsenhanced.root.island.view.MaxWidthFrameLayout
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.util.Collections
import java.util.WeakHashMap

/**
 * 小米超级岛视图管理
 * 负责处理超级岛内部组件的查找、显隐切换及布局刷新
 */
object IslandViewHelper {

    private val SYSTEMUI_PKG_NAMES = arrayOf("miui.systemui.plugin", "com.android.systemui")
    private val originalMargins = WeakHashMap<View, MarginSnapshot>()
    private val isRelayouting = ThreadLocal.withInitial { false }
    private val premeasureScope = IslandPremeasureScope<ViewGroup, View>()
    private val loggedRelayoutClasses = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>()),
    )
    private val loggedMissingRelayoutClasses = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>()),
    )

    /**
     * 切换超级岛内部容器（如图标、文本容器）的可见性
     */
    @SuppressLint("DiscouragedApi")
    fun toggleContainer(root: ViewGroup, parentName: String, containerName: String, show: Boolean) {
        try {
            val res = root.resources
            val parent = findViewByName(root, parentName) as? ViewGroup
            
            if (parent != null) {
                for (pkg in SYSTEMUI_PKG_NAMES) {
                    val id = res.getIdentifier(containerName, "id", pkg)
                    if (id != 0) {
                        parent.findViewById<View>(id)?.visibility = if (show) View.VISIBLE else View.GONE
                    }
                }
            }
        } catch (e: Exception) {
            HookLogger.e("IslandViewHelper", "切换容器可见性失败: container=$containerName", e)
        }
    }

    /**
     * 清除超级岛文本容器的边距
     */
    @SuppressLint("DiscouragedApi")
    fun clearTextContainerMargin(root: ViewGroup, parentName: String, clearStart: Boolean, clearEnd: Boolean) {
        try {
            val res = root.resources
            val parent = findViewByName(root, parentName) as? ViewGroup
            
            if (parent != null) {
                for (pkg in SYSTEMUI_PKG_NAMES) {
                    val id = res.getIdentifier("island_container_module_text", "id", pkg)
                    if (id != 0) {
                        val textContainer = parent.findViewById<View>(id)
                        if (textContainer != null) {
                            val lp = textContainer.layoutParams as? ViewGroup.MarginLayoutParams
                            if (lp != null) {
                                originalMargins.getOrPut(textContainer) {
                                    MarginSnapshot(lp.marginStart, lp.marginEnd)
                                }
                                if (clearStart) lp.marginStart = 0
                                if (clearEnd) lp.marginEnd = 0
                                textContainer.layoutParams = lp
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            HookLogger.e("IslandViewHelper", "清除边距失败: parent=$parentName", e)
        }
    }

    /**
     * 清理所有注入的视图并恢复系统原生组件
     */
    fun clearInjectedViews(rootView: ViewGroup) {
        IslandNativeSlotPlacement.restore(rootView)
        hideInjectedView(rootView, IslandProbeUtils.LEFT_TEST_VIEW_TAG)
        hideInjectedView(rootView, IslandProbeUtils.LEFT_TEST_WRAPPER_TAG)
        hideInjectedView(rootView, IslandProbeUtils.RIGHT_TEST_VIEW_TAG)
        hideInjectedView(rootView, IslandProbeUtils.RIGHT_TEST_WRAPPER_TAG)
        hideInjectedView(rootView, "HYPERLYRIC_TEST_VIEW_WRAPPER_LEFT")
        hideInjectedView(rootView, "HYPERLYRIC_TEST_VIEW_WRAPPER_RIGHT")
 
        // 恢复系统原有组件的可见性
        toggleContainer(rootView, "island_container_module_image_text_1", "island_container_module_icon", true)
        toggleContainer(rootView, "island_container_module_image_text_2", "island_container_module_icon", true)

        restoreTextContainerMargins(rootView, "island_container_module_image_text_1")
        restoreTextContainerMargins(rootView, "island_container_module_image_text_2")
        showOriginalTexts(rootView, "island_container_module_image_text_1")
        showOriginalTexts(rootView, "island_container_module_image_text_2")
    }

    private fun hideInjectedView(rootView: ViewGroup, tag: String) {
        val view = rootView.findViewWithTag<View>(tag) ?: return
        val wrapper = view as? MaxWidthFrameLayout
        if (wrapper == null && view.javaClass.name == MaxWidthFrameLayout::class.java.name) {
            (view.parent as? ViewGroup)?.removeView(view)
            return
        }
        wrapper?.keepVisible = false
        view.visibility = View.GONE
    }

    /**
     * 显示原本被隐藏的原生文本视图
     */
    @SuppressLint("DiscouragedApi")
    fun showOriginalTexts(rootView: ViewGroup, parentName: String) {
        try {
            val res = rootView.resources
            val slotId = res.getIdentifier(parentName, "id", "miui.systemui.plugin")
            if (slotId == 0) return
            val parent = rootView.findViewById<ViewGroup>(slotId) ?: return
            
            val textSlotId = res.getIdentifier("island_container_module_text", "id", "miui.systemui.plugin")
            val container = if (textSlotId != 0) (parent.findViewById(textSlotId) ?: parent) else parent

            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                val tag = child.tag as? String ?: ""
                if (!tag.startsWith("HYPERLYRIC")) {
                    child.visibility = View.VISIBLE
                }
            }
        } catch (e: Exception) {
            HookLogger.e("IslandViewHelper", "恢复原生文本失败: parent=$parentName", e)
        }
    }

    /**
     * 递归标记子树在下次 measure 时强制重新执行 onMeasure。
     *
     * 系统的 calculateBigIslandWidth 仅当左右区域包含原生 TextView 时才会
     * forceLayoutRecursively（见 applyPreMeasureMode），注入的歌词子树全部是
     * 自定义 View，不会触发该分支；而区域测量规格每次相同，View.measure 会因
     * 规格未变且无 FORCE_LAYOUT 标志直接短路，导致岛宽锁死在注入时刻。
     * 动态长度开启时必须在宽度重算前手动标记。
     */
    fun forceLayoutIslandAreas(rootView: ViewGroup) {
        val areaLeft = findViewByName(rootView, "area_left")
        val areaRight = findViewByName(rootView, "area_right")
        forceLayoutIslandAreas(rootView, areaLeft, areaRight)
    }

    private fun forceLayoutIslandAreas(rootView: ViewGroup, areaLeft: View?, areaRight: View?) {
        if (areaLeft == null && areaRight == null) {
            // 兜底：不同版本区域容器缺失时，直接标记注入模块所在的父容器
            forceLayoutRecursively(findViewByName(rootView, IslandProbeUtils.LEFT_PARENT_NAME))
            forceLayoutRecursively(findViewByName(rootView, IslandProbeUtils.RIGHT_PARENT_NAME))
            return
        }
        forceLayoutRecursively(areaLeft)
        forceLayoutRecursively(areaRight)
    }

    private fun forceLayoutRecursively(view: View?) {
        if (view == null) return
        view.forceLayout()
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                forceLayoutRecursively(view.getChildAt(i))
            }
        }
    }

    /**
     * 岛宽计算（calculateBigIslandWidth）以左右区在计算那一刻的 getMeasuredWidth()
     * 为内容输入（手机插件 DEX：DynamicIslandContentView.calculateBigIslandWidth 直接
     * 读取 area.measuredWidth），而该值是上一次布局的产物：回桌面恢复时岛从胶囊宽度
     * 起步，区域被窄规格测量，计算读到窄值 → 岛按窄值定宽 → 窄状态自我维持，直到
     * 一次强制刷新才跳回真实内容宽（真机：恢复后胶囊 546 停 3.5s 再跳 652，
     * LyricHug 可见规格在 573/246 间交替）。
     *
     * 因此每次岛宽计算前把左右区以 AT_MOST(可用屏宽) 重新测量到内容自然宽度，
     * 让计算输入不再依赖上一次布局宽度。高度沿用当前实测值，不扰动纵向布局。
     */
    private fun measureIslandAreasToNaturalWidth(
        rootView: ViewGroup,
        areaLeft: View?,
        areaRight: View?,
    ): IslandNaturalWidthSnapshot<View>? =
        runCatching {
            val available = rootView.width.takeIf { it > 0 }
                ?: rootView.resources.displayMetrics.widthPixels
            val widthSpec = View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST)
            val leftHeight = areaLeft?.measuredHeight?.coerceAtLeast(1) ?: 1
            fun measure(area: View?, height: Int) {
                if (area == null) return
                val heightSpec = View.MeasureSpec.makeMeasureSpec(
                    height,
                    View.MeasureSpec.EXACTLY,
                )
                area.measure(widthSpec, heightSpec)
            }
            measure(areaLeft, leftHeight)
            val rightHeight = areaRight?.measuredHeight?.coerceAtLeast(1) ?: 1
            measure(areaRight, rightHeight)
            if (areaLeft == null || areaRight == null) null else {
                IslandNaturalWidthSnapshot(areaLeft, areaRight, available, leftHeight, rightHeight)
            }
        }.onFailure { e ->
            HookLogger.e("IslandViewHelper", "区域自然宽度预测量失败", e)
        }.getOrNull()

    internal fun isDynamicWidthEnabled(): Boolean {
        return HookEntry.instance?.prefs?.getBoolean(
            RootConstants.KEY_HOOK_ISLAND_DYNAMIC_WIDTH,
            RootConstants.DEFAULT_HOOK_ISLAND_DYNAMIC_WIDTH
        ) == true
    }

    internal fun isUnlockIslandLengthEnabled(): Boolean {
        return HookEntry.instance?.prefs?.getBoolean(
            RootConstants.KEY_HOOK_UNLOCK_ISLAND_LENGTH,
            RootConstants.DEFAULT_HOOK_UNLOCK_ISLAND_LENGTH
        ) == true
    }

    /**
     * 动态长度开启时，在宽度重算前标记左右区域子树强制重新测量，
     * 覆盖 triggerSystemRelayout 与系统自发 calculateBigIslandWidth 两条路径；
     * 并把区域预测量到内容自然宽度，保证宽度计算的输入不受上一次布局宽度污染。
     */
    internal fun forceLayoutIslandAreasIfDynamicWidth(rootView: ViewGroup): IslandNaturalWidthSnapshot<View>? {
        if (!isDynamicWidthEnabled()) return null
        val left = findViewByName(rootView, "area_left")
        val right = findViewByName(rootView, "area_right")
        forceLayoutIslandAreas(rootView, left, right)
        return measureIslandAreasToNaturalWidth(rootView, left, right)
    }

    /** Reuse only the immediately preceding successful measurement, before any native calculation. */
    internal fun reuseNaturalWidthPremeasure(rootView: ViewGroup, contentChanged: Boolean): Boolean {
        val prepared = premeasureScope.take(rootView) ?: return false
        if (!isDynamicWidthEnabled() || contentChanged) return false
        val left = findViewByName(rootView, "area_left")
        val right = findViewByName(rootView, "area_right")
        val available = rootView.width.takeIf { it > 0 }
            ?: rootView.resources.displayMetrics.widthPixels
        return prepared.isStillValid(
            left = left,
            right = right,
            availableWidth = available,
            leftHeight = left?.measuredHeight?.coerceAtLeast(1) ?: 1,
            rightHeight = right?.measuredHeight?.coerceAtLeast(1) ?: 1,
            contentChanged = contentChanged,
            layoutPending = hasPendingLayout(left) || hasPendingLayout(right),
        )
    }

    private fun hasPendingLayout(view: View?): Boolean {
        if (view == null) return true
        // A normal parent does not measure GONE children, so forceLayout() leaves their flag
        // set even after a successful area measurement. A visibility change dirties the parent.
        // Keep unknown custom parents and FrameLayouts that measure GONE children conservative.
        if (view.visibility == View.GONE) {
            val parent = view.parent
            if (parent?.javaClass == LinearLayout::class.java) return false
            if ((parent?.javaClass == FrameLayout::class.java || parent is MaxWidthFrameLayout) &&
                !(parent as FrameLayout).measureAllChildren
            ) return false
        }
        if (view.isLayoutRequested) return true
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (hasPendingLayout(view.getChildAt(index))) return true
            }
        }
        return false
    }

    /**
     * 从注入的槽位视图向上查找超级岛内容视图并触发布局刷新。
     * 用于换句/预览提升动画把内容更新延迟落地后的第二次岛宽重算——
     * 否则内容应用返回时的立即测量只能量到上一行宽度。
     * 开关状态在触发时实时读取，找不到宿主视图时静默返回。
     */
    fun triggerSystemRelayoutForDescendant(view: View) {
        if (!isDynamicWidthEnabled()) return
        var parent = view.parent
        while (parent is View) {
            if (parent is ViewGroup &&
                IslandRelayoutMethodResolver.resolve(parent.javaClass) != null
            ) {
                triggerLyricContentRelayout(parent)
                return
            }
            parent = parent.parent
        }
    }

    /**
     * 重算同一原生状态内的歌词内容宽度。
     *
     * 完整走 `updateBigIslandViewWidth()` 原生入口和 `UpdateDynamicIslandWidth`
     * 事件。Hook 只把该请求产生的精确 `BigIslandChanged` 转场与歌词宽度关联，
     * 让它不触发通用 Lottie 暂停/归零/恢复；宽度状态、布局与转场本身不变。
     * 预测量不在关联作用域内，Hook 不可用时安全退化为完整原生行为。
     */
    fun triggerLyricContentRelayout(islandView: ViewGroup) {
        traceIslandPerformance("HLE.relayout.lyric") {
            triggerRelayout(islandView, protectLyricLottie = true)
        }
    }

    /**
     * 触发超级岛系统的布局刷新
     *
     * 使用 ThreadLocal 防止重入：triggerSystemRelayout 调用的系统方法可能被
     * Hook 拦截后再次触发 triggerSystemRelayout，导致无限递归。
     *
     * `updateBigIslandViewWidth` 会先计算宽度，再用 `UpdateDynamicIslandWidth`
     * 驱动 `BigIslandChanged` 提交布局/状态。真机已确认两段都承重：不得绕过、
     * 吞掉或手工替代事件路径，否则会破坏可见伸缩或状态时序。
     */
    fun triggerSystemRelayout(islandView: ViewGroup) {
        traceIslandPerformance("HLE.relayout.system") {
            triggerRelayout(islandView, protectLyricLottie = false)
        }
    }

    private fun triggerRelayout(
        islandView: ViewGroup,
        protectLyricLottie: Boolean,
    ) {
        if (isRelayouting.get() == true) return
        if (IslandContentUpdateCoordinator.deferRelayout(islandView, protectLyricLottie)) return
        if (BuildConfig.DEBUG) {
            IslandBackgroundTraceDiagnostics.event(
                if (protectLyricLottie) "模块歌词宽度刷新" else "模块主动布局刷新",
                islandView,
            )
        }
        HookLogger.d("IslandViewHelper","正在触发布局刷新")
        isRelayouting.set(true)
        try {
            runCatching {
                val prepared = traceIslandPerformance("HLE.relayout.premeasure") {
                    forceLayoutIslandAreasIfDynamicWidth(islandView)
                }
                val viewClass = islandView.javaClass
                val resolved = IslandRelayoutMethodResolver.resolve(viewClass) ?: run {
                    if (loggedMissingRelayoutClasses.add(viewClass)) {
                        HookLogger.w(
                            "IslandViewHelper",
                            "跳过超级岛布局刷新: " +
                                "reason=no_compatible_zero_arg_entry, class=${viewClass.name}",
                        )
                    }
                    return@runCatching
                }
                if (BuildConfig.DEBUG && loggedRelayoutClasses.add(viewClass)) {
                    HookLogger.i(
                        "IslandViewHelper",
                        "超级岛布局刷新入口: ${resolved.diagnosticSummary}",
                    )
                }
                premeasureScope.run(islandView, prepared) {
                    if (resolved.entry == IslandRelayoutEntry.UPDATE_BIG_ISLAND_VIEW_WIDTH && protectLyricLottie) {
                        IslandWidthEventRebindGuard.aroundLyricWidthRelayout(islandView) {
                            resolved.method.invoke(islandView)
                        }
                    } else {
                        resolved.method.invoke(islandView)
                    }
                }
            }.onFailure { e ->
                HookLogger.e("IslandViewHelper", "超级岛布局刷新失败", e)
            }
        } finally {
            isRelayouting.set(false)
        }
    }

    /**
     * 根据名称寻找 View（支持多包名兜底）
     */
    @SuppressLint("DiscouragedApi")
    fun findViewByName(root: ViewGroup, name: String): View? {
        val res = root.resources
        for (pkg in SYSTEMUI_PKG_NAMES) {
            val id = res.getIdentifier(name, "id", pkg)
            if (id != 0) {
                val v = root.findViewById<View>(id)
                if (v != null) return v
            }
        }
        return null
    }

    private fun restoreTextContainerMargins(rootView: ViewGroup, parentName: String) {
        val parent = findViewByName(rootView, parentName) as? ViewGroup ?: return
        val container = findViewByName(parent, "island_container_module_text") ?: return
        val snapshot = originalMargins[container] ?: return
        val lp = container.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.marginStart != snapshot.marginStart || lp.marginEnd != snapshot.marginEnd) {
            lp.marginStart = snapshot.marginStart
            lp.marginEnd = snapshot.marginEnd
            container.layoutParams = lp
        }
    }

    private data class MarginSnapshot(
        val marginStart: Int,
        val marginEnd: Int
    )
}
