package com.juren233.hyperlyricsenhanced.root.island

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.root.HookEntry
import com.juren233.hyperlyricsenhanced.root.island.view.MaxWidthFrameLayout
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.util.WeakHashMap

/**
 * 小米超级岛视图管理
 * 负责处理超级岛内部组件的查找、显隐切换及布局刷新
 */
object IslandViewHelper {

    private val SYSTEMUI_PKG_NAMES = arrayOf("miui.systemui.plugin", "com.android.systemui")
    private val originalMargins = WeakHashMap<View, MarginSnapshot>()
    private val isRelayouting = ThreadLocal.withInitial { false }

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

    internal fun isDynamicWidthEnabled(): Boolean {
        return HookEntry.instance?.prefs?.getBoolean(
            RootConstants.KEY_HOOK_ISLAND_DYNAMIC_WIDTH,
            RootConstants.DEFAULT_HOOK_ISLAND_DYNAMIC_WIDTH
        ) == true
    }

    /**
     * 动态长度开启时，在宽度重算前标记左右区域子树强制重新测量，
     * 覆盖 triggerSystemRelayout 与系统自发 calculateBigIslandWidth 两条路径。
     */
    fun forceLayoutIslandAreasIfDynamicWidth(rootView: ViewGroup) {
        if (isDynamicWidthEnabled()) {
            forceLayoutIslandAreas(rootView)
        }
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
            if (parent is ViewGroup && parent.javaClass.methods.any {
                    it.name == "updateBigIslandViewWidth" || it.name == "calculateBigIslandWidth"
                }
            ) {
                triggerSystemRelayout(parent)
                return
            }
            parent = parent.parent
        }
    }

    /**
     * 触发超级岛系统的布局刷新
     *
     * 使用 ThreadLocal 防止重入：triggerSystemRelayout 调用的系统方法可能被
     * Hook 拦截后再次触发 triggerSystemRelayout，导致无限递归。
     */
    fun triggerSystemRelayout(islandView: ViewGroup) {
        if (isRelayouting.get() == true) return
        HookLogger.d("IslandViewHelper","正在触发布局刷新")
        isRelayouting.set(true)
        try {
            runCatching {
                forceLayoutIslandAreasIfDynamicWidth(islandView)
                val viewClass = islandView.javaClass
                // 优先尝试 updateBigIslandViewWidth
                val updateWidthMethod = viewClass.methods.find { it.name == "updateBigIslandViewWidth" }
                if (updateWidthMethod != null) {
                    updateWidthMethod.invoke(islandView)
                } else {
                    // 兜底尝试 calculateBigIslandWidth
                    viewClass.methods.find { it.name == "calculateBigIslandWidth" }?.invoke(islandView)
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
