/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import com.juren233.hyperlyricsenhanced.root.reload.SystemUiHookLifetime
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.island.renderer.BaseIslandRenderer
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Tracks the SystemUI status-bar window, which is separate from the island window. */
internal object IslandStatusBarSpaceMonitor {
    private const val TAG = "IslandDynamicLimit"
    private val roots = WeakHashMap<ViewGroup, Runnable>()
    private var installed = false
    private var hit = false

    internal fun snapshotForReload(): Array<Any> = arrayOf(roots.keys.toTypedArray(), snapshot.save())

    internal fun restoreAfterReload(values: List<Any>) {
        (values.getOrNull(1) as? IntArray)?.let(snapshot::restore)
        (values.getOrNull(0) as? Array<*>)?.filterIsInstance<ViewGroup>()
            ?.filter { it.isAttachedToWindow }?.forEach(::attach)
    }

    internal fun releaseForReload() {
        roots.values.toList().forEach(Runnable::run)
        roots.clear()
    }

    fun install(module: XposedModule, loader: ClassLoader) {
        if (installed) return
        runCatching {
            val method = loader.loadClass(IslandDynamicLimitProfile.STATUS_BAR_CLASS)
                .getDeclaredMethod(IslandDynamicLimitProfile.ATTACH_METHOD)
            module.deoptimize(method)
            module.hook(method).intercept(object : Hooker {
                override fun intercept(chain: Chain): Any? {
                    if (SystemUiHookLifetime.retired) return chain.proceed()
                    val result = chain.proceed()
                    runCatching { (chain.thisObject as? ViewGroup)?.let(::attach) }
                    return result
                }
            })
            installed = true
            HookLogger.i(TAG, "已安装状态栏空间监听")
        }.onFailure { HookLogger.w(TAG, "状态栏空间监听不可用: ${it.javaClass.simpleName}") }
    }

    private fun attach(root: ViewGroup) {
        if (roots.containsKey(root)) return
        if (BuildConfig.DEBUG && !hit) {
            hit = true
            HookLogger.i(TAG, "状态栏空间监听首次命中")
        }
        val reference = WeakReference(root)
        var baseline: List<IslandDynamicLimitPolicy.Span>? = null
        var settlePending = false
        var suppressedRefreshes = 0
        val observer = root.viewTreeObserver
        // Status-bar root can attach before the plugin width hooks set padIslandPathActive.
        // Use the same native-device predicate instead of freezing that transient install state.
        val pad = isPadView(root)
        val coordinateTolerancePx = if (pad) 0 else
            (3f * root.resources.displayMetrics.density).roundToInt()

        fun observation(view: ViewGroup): List<IslandDynamicLimitPolicy.Span> =
            IslandDynamicLimitPolicy.relevantObservation(
                obstacles = collect(view, pad),
                screenWidth = view.width,
                leftAnchor = if (pad) 0 else null,
            )

        val settleCheck = object : Runnable {
            override fun run() {
                settlePending = false
                val view = reference.get() ?: return
                if (!IslandDynamicWidthLimiter.isEnabled()) return
                val settled = observation(view)
                if (!IslandDynamicLimitRefreshPolicy.shouldRefresh(
                        settled = settled,
                        baseline = baseline,
                        islandWidthSettling = IslandDynamicWidthLimiter.isWidthSettling(),
                        coordinateTolerancePx = coordinateTolerancePx,
                        ignoreRightDrift = !pad,
                    )
                ) {
                    return
                }
                baseline = settled
                if (BuildConfig.DEBUG && suppressedRefreshes > 0) {
                    HookLogger.i(
                        TAG,
                        "动态上限自激刷新已抑制 $suppressedRefreshes 次(岛宽过渡/瞬态布局)",
                    )
                    suppressedRefreshes = 0
                }
                BaseIslandRenderer.refreshDynamicWidth()
            }
        }

        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val view = reference.get() ?: return@OnGlobalLayoutListener
            val current = observation(view)
            when (
                IslandDynamicLimitRefreshPolicy.decide(
                    enabled = IslandDynamicWidthLimiter.isEnabled(),
                    current = current,
                    baseline = baseline,
                    islandWidthSettling = IslandDynamicWidthLimiter.isWidthSettling(),
                    coordinateTolerancePx = coordinateTolerancePx,
                    ignoreRightDrift = !pad,
                )
            ) {
                IslandDynamicLimitRefreshPolicy.Decision.CANCEL -> {
                    // 取消待定刷新：瞬态布局或岛宽过渡不能被带进岛，否则会重启宽度动画。
                    if (settlePending) {
                        settlePending = false
                        suppressedRefreshes++
                    }
                    view.removeCallbacks(settleCheck)
                }

                IslandDynamicLimitRefreshPolicy.Decision.SCHEDULE -> {
                    // 每次新增变化都重置静默计时，只有状态栏停下来之后才真正刷新一次。
                    settlePending = true
                    view.removeCallbacks(settleCheck)
                    view.postDelayed(settleCheck, IslandDynamicLimitRefreshPolicy.SETTLE_DELAY_MS)
                }
            }
        }
        observer.addOnGlobalLayoutListener(listener)
        val observerReference = WeakReference(observer)
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                roots.remove(v)?.run()
            }
        }
        roots[root] = Runnable {
            reference.get()?.let { view ->
                view.removeCallbacks(settleCheck)
                view.removeOnAttachStateChangeListener(attachListener)
            }
            observerReference.get()?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(listener)
        }
        root.addOnAttachStateChangeListener(attachListener)
    }

    private val snapshot = IslandAnchorSnapshot()

    private fun isPadView(view: View): Boolean =
        view.javaClass.classLoader?.let(IslandTextHookerSupport::isTabletIslandDevice)
            ?: (view.resources.configuration.smallestScreenWidthDp >= 600)

    /**
     * 返回本次岛宽计算使用的障碍锚点。
     *
     * 手机端状态栏所有条目的坐标都是岛宽的影子：时钟右缘被岛挤压、紧邻时钟的
     * 图标随之时移（真机：左缘 66↔79、右缘 257↔280 随岛宽摆动）。因此锚点本体
     * 使用冻结快照，且重锚条件只看条目数量（通知图标增删）——任何坐标比较都会被
     * 岛诱导的位移击穿，让上限追逐自己的影子（160151 教训：坐标比较使快照反复
     * 重建，宽度在 604↔650 间持续漂移）。平板同样存在随岛几何布局的视图（岛内
     * 部布局在岛右缘外的过渡占位），靠 collect() 的岛视图过滤排除，快照保持实时。
     */
    fun obstacles(content: View, screenWidth: Int): List<IslandDynamicLimitPolicy.Span>? {
        val phone = !isPadView(content)
        val live = roots.keys.firstOrNull {
            it.isAttachedToWindow && it.isShown && it.display?.displayId == content.display?.displayId &&
                it.width == screenWidth
        }?.let { root -> collect(root, isPadView(root)).takeIf { it.isNotEmpty() } }
        val stable = snapshot.fallbackFor(screenWidth)
        if (live != null) {
            val structuralChange = if (phone) {
                stable == null || stable.size != live.size
            } else {
                stable == null || !IslandDynamicLimitPolicy.observationsEquivalent(live, stable, 0)
            }
            if (structuralChange) {
                snapshot.update(live, screenWidth)
            }
            return snapshot.fallbackFor(screenWidth)
        }
        // 恢复/过渡窗口：状态栏根暂时不满足匹配条件。用冻结快照先行裁剪，
        // 避免第一帧按无上限原生宽度入场、锚点实测后再硬切。
        return stable
    }

    private fun collect(root: ViewGroup, pad: Boolean): List<IslandDynamicLimitPolicy.Span> {
        val spans = ArrayList<IslandDynamicLimitPolicy.Span>()
        val identities = ArrayList<String>()
        val position = IntArray(2)
        val rootPosition = IntArray(2)
        root.getLocationOnScreen(rootPosition)
        fun visit(view: View) {
            // Reserve laid-out INVISIBLE / alpha-zero icons too: island-induced hiding must
            // not free space and create a grow/hide/shrink feedback loop. GONE has no layout.
            if (view.visibility == View.GONE || view.width <= 0 || view.height <= 0) return
            // 岛自身视图不是障碍：岛内部有些视图布局在岛右缘之外一点的位置并随岛宽移动，
            // 收进障碍集会让上限恒等于岛当前宽度，形成逐秒棘轮收缩
            // （2026-09-15 平板日志：障碍左缘恒=岛右缘+15px，宽度 310→274px 连续收缩）。
            if (view !== root &&
                view.javaClass.name.startsWith(IslandDynamicLimitProfile.ISLAND_VIEW_PACKAGE_PREFIX)
            ) {
                return
            }
            // 通知图标槽位（StatusBarIconView）被宿主排在岛几何之后随岛宽重排，
            // 属于随岛移动的影子内容，不是可避让的静态障碍（运行时身份日志实证）。
            if (view !== root &&
                view.javaClass.name == IslandDynamicLimitProfile.NOTIFICATION_ICON_CONTAINER_CLASS
            ) {
                return
            }
            // 平板额外跳过不可见/全透明视图：平板存在随岛几何布局的隐藏占位，收进障碍集
            // 同样形成影子追逐；手机端保留 INVISIBLE 预约（手机上限只以时间文本为锚，
            // 不可见图标不参与上限）。
            if (pad && view !== root && (view.visibility != View.VISIBLE || view.alpha == 0f)) return
            val draws = when (view) {
                is TextView -> view.text.isNotEmpty()
                is ImageView -> view.drawable != null
                else -> !view.willNotDraw()
            }
            if (view !== root && draws && view.width < root.width / 2) {
                view.getLocationOnScreen(position)
                if (position[1] < rootPosition[1] + root.height && position[1] + view.height > rootPosition[1]) {
                    spans += IslandDynamicLimitPolicy.Span(position[0], position[0] + view.width, text = view is TextView)
                    if (BuildConfig.DEBUG && pad) identities.add(describe(view, position[0]))
                }
                return
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(root)
        if (identities.isNotEmpty()) logIdentitiesOncePerSignature(identities)
        return spans.distinct().sortedBy { it.left }
    }

    /** 影子障碍身份判别（仅 Debug）：签名（不含坐标）变化时打印一次完整身份。 */
    @Volatile
    private var lastIdentitySignature: String? = null

    private fun describe(view: View, left: Int): String {
        val idName = runCatching {
            if (view.id == View.NO_ID) "no-id" else view.resources.getResourceEntryName(view.id)
        }.getOrDefault("id?")
        val parent = runCatching { view.parent?.javaClass?.name }.getOrNull()
        return "${view.javaClass.name} id=$idName vis=${view.visibility} alpha=${view.alpha} " +
            "w=${view.width}x${view.height} left=$left parent=$parent"
    }

    private fun logIdentitiesOncePerSignature(identities: List<String>) {
        val signature = identities.joinToString("\n").replace(Regex(" left=-?\\d+"), "")
        if (signature == lastIdentitySignature) return
        lastIdentitySignature = signature
        HookLogger.i(TAG, "状态栏障碍视图身份:\n" + identities.joinToString("\n"))
    }
}
