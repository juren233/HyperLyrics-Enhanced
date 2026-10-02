/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import android.os.SystemClock
import android.view.ViewGroup
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.root.HookEntry
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Final safety cap, applied after the native calculation and optional length unlock. */
internal object IslandDynamicWidthLimiter {
    var calculatingContent: ViewGroup? = null
    private data class ResultApi(val getters: List<Method>, val constructor: Constructor<*>)
    private val resultApis = WeakHashMap<Class<*>, ResultApi>()
    private var loggedHit = false
    private var lastDiagnosticAt = 0L

    /**
     * 原生岛宽过渡是动画：写入新宽度后的一小段时间内，状态栏会围绕动画中的岛宽重新排布。
     * 这些布局变化是本次宽度写入的下游结果，不是新的状态栏内容；用它们再去触发一次
     * 宽度刷新会形成"刷新→布局变化→刷新"的自激循环，并让岛两侧内容反复重建。
     */
    private const val WIDTH_SETTLE_MS = 900L

    @Volatile
    private var lastWidthChangeAt = 0L

    /** 岛宽（原生 Rule 1/2 或本模块改写结果）发生实际变化时调用。 */
    fun onIslandWidthChanged() {
        lastWidthChangeAt = SystemClock.uptimeMillis()
    }

    /** 岛自身是否仍在宽度过渡窗口内。 */
    fun isWidthSettling(now: Long = SystemClock.uptimeMillis()): Boolean =
        now - lastWidthChangeAt < WIDTH_SETTLE_MS

    fun isEnabled(): Boolean {
        val prefs = HookEntry.instance?.prefs ?: return false
        return IslandRuntimePreferenceReader.getBoolean(prefs,
            RootConstants.KEY_HOOK_ISLAND_DYNAMIC_LIMIT,
            RootConstants.DEFAULT_HOOK_ISLAND_DYNAMIC_LIMIT)
    }

    fun apply(native: Any?, candidate: Any?, params: Any?, pad: Boolean, helper: Any?): Any? {
        if (!isEnabled()) return candidate
        val content = calculatingContent ?: return candidate
        if (native == null || candidate == null || params == null || helper == null) return native
        return runCatching {
            val screenWidth = params.javaClass.getMethod(IslandDynamicLimitProfile.SCREEN_WIDTH_GETTER)
                .invoke(params) as Int
            // No reliable matching status-bar window: retain native protection, never the
            // unrestricted candidate. A later status-bar layout triggers another calculation.
            val obstacles = IslandStatusBarSpaceMonitor.obstacles(content, screenWidth) ?: return native
            val api = resultApis.getOrPut(candidate.javaClass) {
                ResultApi(IslandDynamicLimitProfile.RESULT_GETTERS.map { candidate.javaClass.getMethod(it) },
                    candidate.javaClass.getConstructor(*Array(9) { Int::class.javaPrimitiveType!! }))
            }
            val original = api.getters.map { it.invoke(candidate) as Int }
            val values = original.toMutableList()
            val anchor = if (pad) content.javaClass.getMethod(IslandDynamicLimitProfile.DATE_POSITION_GETTER)
                .invoke(content) as? Int else null
            val gap = (6 * content.resources.displayMetrics.density).toInt()
            // 与动态长度下限（BigIslandMinWidthHook）一致：上限收缩不得低于岛高胶囊，
            // 否则空间不足时岛会被压缩到趋近于零。
            val pillHeight = IslandDynamicLimitProfile.readIslandHeight(helper)
            val pillFloor = pillHeight + (2 * content.resources.displayMetrics.density).toInt()
            fun constrain(width: Int, left: Int, right: Int, x: Int) {
                val geometry = IslandDynamicLimitPolicy.limit(
                    IslandDynamicLimitPolicy.Geometry(values[width], values[left], values[right], values[x]),
                    screenWidth, obstacles, gap, anchor, minWidth = pillFloor)
                values[width] = geometry.width
                values[left] = geometry.left
                values[right] = geometry.right
                values[x] = geometry.x
            }
            constrain(0, 1, 2, 3)
            constrain(5, 6, 7, 8)
            if (BuildConfig.DEBUG && (!loggedHit || SystemClock.uptimeMillis() - lastDiagnosticAt >= 250L)) {
                val firstHit = !loggedHit
                loggedHit = true
                lastDiagnosticAt = SystemClock.uptimeMillis()
                HookLogger.i("IslandDynamicLimit",
                    "动态上限${if (firstHit) "首次" else ""}计算: pad=$pad anchor=$anchor " +
                        "contentHeight=${content.height} pillHeight=$pillHeight floor=$pillFloor " +
                        "obstacles=$obstacles candidate=${original.take(4)} " +
                        "final=${values.take(4)} smallCandidate=${original.slice(5..8)} " +
                        "smallFinal=${values.slice(5..8)} " +
                        "native=${runCatching { api.getters.map { it.invoke(native) as Int }.take(4) }.getOrNull()}")
            }
            if (values == original) candidate else api.constructor.newInstance(*values.toTypedArray())
        }.onFailure {
            if (BuildConfig.DEBUG) HookLogger.w("IslandDynamicLimit", "动态上限读取失败: ${it.javaClass.simpleName}")
        }.getOrDefault(native)
    }
}
