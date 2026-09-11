/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 平板解除长度限制后，把"岛内容左缘"钉在胶囊左缘（statusBarDatePosX）上。
 *
 * 2026-09-11 真机逐帧证据（`[岛背景追踪] 背景几何`）：
 * - 胶囊 `DynamicIslandBackgroundView` 左缘恒为 156/160（= statusBarDatePosX），只有右边缘随宽度动画生长；
 * - 原生 `DynamicIslandBigIslandView`（内含 big_container / area_left / area_right 与注入歌词）由父容器
 *   **居中布局**，其左缘 = `(screenWidth - W(t)) / 2 + translationX`，而 `translationX = padIslandTransX
 *   = statusBarDatePosX - x`。
 * 稳态下 `x = (screenWidth - W) / 2`（[IslandWidthHooker] 已恢复该不变量）两者重合；但宽度更新时原生只把
 * `W` 与 `padIslandTransX` 动画到目标值：居中布局用的是**动画中的** `W(t)`，于是内容先跳
 * `(W_target - W(t)) / 2` 再滑回（实测 W 1057→695 时内容 243→56→239），表现为"每次长度更新两侧内容闪一下"。
 *
 * 本对象在宽度改写后的窗口内每帧把 pill 的 `translationX` 修正为
 * `padIslandTransX + (W_target - pill.width) / 2`，使内容左缘恒等于胶囊左缘、只向右伸展。
 * 只在"已可见且停在旧宽度的大岛过渡到新宽度"时开启；宽度跳出 prev..target 区间、pill 不可见或窗口超时
 * 立即停手，避免干扰原生进出场动画。所有入口都在开关关闭或缺少视图时静默返回。
 */
internal object IslandPadContentAnchor {
    private const val TAG = "平板岛内容锚定"
    private const val PILL_VIEW_NAME = "big_island_view"
    private const val FAKE_VIEW_NAME = "DynamicIslandContentFakeView"
    private const val MAX_SEARCH_DEPTH = 10
    private const val WINDOW_MS = 1200L
    private const val SETTLED_TOLERANCE_PX = 4
    private const val DONE_TOLERANCE_PX = 1

    private val intGetters = ConcurrentHashMap<String, java.lang.reflect.Method>()

    private var pillRef: WeakReference<View>? = null
    private var contentRef: WeakReference<View>? = null
    private var listener: ViewTreeObserver.OnPreDrawListener? = null
    private var observedTree: ViewTreeObserver? = null
    private var previousWidth = 0
    private var deadlineAt = 0L
    private var lastReportedWidth = -1

    /** 读取原生当前生效的岛宽（`DynamicIslandBaseContentView.bigIslandViewWidth`）。 */
    internal fun currentIslandWidth(content: View): Int = intGetter(content, "getBigIslandViewWidth")

    /**
     * 宽度改写落地后调用：只有在大岛已可见、且没有正在进行的进出场过渡（fake view 不可见）时开启锚定窗口。
     * 参考宽度取 pill 当前实际宽度，因此连续多次改写（同一行歌词触发多次重算）也能接续锚定。
     */
    fun onWidthRewritten(contentView: ViewGroup, rewrittenWidth: Int) {
        val pill = IslandViewHelper.findViewByName(contentView, PILL_VIEW_NAME) ?: return
        if (rewrittenWidth <= 0) return
        if (!pill.isShown || pill.width <= 0) return
        if (abs(rewrittenWidth - pill.width) <= DONE_TOLERANCE_PX) return
        if (isFakeViewVisible(pill)) return
        startWindow(contentView, pill, pill.width, rewrittenWidth)
    }

    private fun startWindow(contentView: ViewGroup, pill: View, previous: Int, target: Int) {
        stopListener()
        contentRef = WeakReference(contentView)
        pillRef = WeakReference(pill)
        previousWidth = previous
        deadlineAt = SystemClock.uptimeMillis() + WINDOW_MS
        lastReportedWidth = -1
        val created = ViewTreeObserver.OnPreDrawListener { onFrame() }
        val tree = pill.viewTreeObserver
        if (!tree.isAlive) {
            reset()
            return
        }
        tree.addOnPreDrawListener(created)
        listener = created
        observedTree = tree
    }

    private fun onFrame(): Boolean {
        val pill = pillRef?.get()
        val content = contentRef?.get()
        if (pill == null || content == null || !pill.isAttachedToWindow || !pill.isShown) {
            stopListener()
            return true
        }
        val target = islandWidth(content)
        if (target <= 0) {
            stopListener()
            return true
        }
        if (isFakeViewVisible(pill)) {
            // 进出场过渡由 fake view 承载内容，交回原生动画。
            HookLogger.d(TAG, "fake 过渡开始，停止锚定")
            stopListener()
            return true
        }
        val width = pill.width
        val low = min(previousWidth, target) - SETTLED_TOLERANCE_PX
        val high = max(previousWidth, target) + SETTLED_TOLERANCE_PX
        if (width < low || width > high) {
            // 宽度跳到区间外说明发生了进出场/形态切换，交回原生动画。
            HookLogger.d(TAG, "离开过渡区间，停止锚定: width=$width prev=$previousWidth target=$target")
            stopListener()
            return true
        }
        val base = translationBase(content)
        if (base == null) {
            stopListener()
            return true
        }
        // 目标：pill 左缘 = 胶囊左缘（statusBarDatePosX）。
        // pill 左缘 = (screen - W(t)) / 2 + translationX，而原生 base = statusBarDatePosX - x
        // 且 x = (screen - W_target) / 2，因此 translationX = base + (W(t) - W_target) / 2。
        val wanted = base + (width - target) / 2f
        if (pill.translationX != wanted) {
            pill.translationX = wanted
        }
        if (lastReportedWidth != width) {
            lastReportedWidth = width
            HookLogger.d(
                TAG,
                "内容锚定: width=$width target=$target base=$base translation=$wanted",
            )
        }
        val finished = abs(target - width) <= DONE_TOLERANCE_PX
        if (finished || SystemClock.uptimeMillis() > deadlineAt) {
            stopListener()
        }
        return true
    }

    /** 原生写入的等价目标位移：`statusBarDatePosX - bigIslandX`。 */
    private fun translationBase(content: View): Float? {
        val datePosX = intGetter(content, "getStatusBarDatePosX")
        val islandX = intGetter(content, "getBigIslandX")
        if (datePosX < 0 || islandX < 0) return null
        return (datePosX - islandX).toFloat()
    }

    private fun islandWidth(content: View): Int = intGetter(content, "getBigIslandViewWidth")

    /** 进出场过渡期间 `DynamicIslandContentFakeView` 可见；此时不得接管 pill 位移。 */
    private fun isFakeViewVisible(view: View): Boolean {
        val fake = findFakeView(view.rootView, 0) ?: return false
        return fake.visibility == View.VISIBLE
    }

    private fun findFakeView(view: View, depth: Int): View? {
        if (view.javaClass.simpleName == FAKE_VIEW_NAME) return view
        if (view !is ViewGroup || depth >= MAX_SEARCH_DEPTH) return null
        for (index in 0 until view.childCount) {
            findFakeView(view.getChildAt(index), depth + 1)?.let { return it }
        }
        return null
    }

    private fun stopListener() {
        listener?.let { created ->
            observedTree?.takeIf { it.isAlive }?.removeOnPreDrawListener(created)
        }
        listener = null
        observedTree = null
        pillRef = null
        contentRef = null
        lastReportedWidth = -1
    }

    private fun reset() {
        stopListener()
        previousWidth = 0
        deadlineAt = 0L
    }

    private fun intGetter(target: Any, name: String): Int = runCatching {
        val key = target.javaClass.name + '#' + name
        val method = intGetters.getOrPut(key) {
            target.javaClass.methods.first { it.name == name && it.parameterTypes.isEmpty() }
        }
        (method.invoke(target) as? Number)?.toInt() ?: -1
    }.getOrDefault(-1)
}
