package com.juren233.hyperlyricsenhanced.root.island

import android.view.ViewGroup
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.island.IslandTextHookerSupport.TAG
import com.juren233.hyperlyricsenhanced.root.island.IslandTextHookerSupport.findFieldValue
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import kotlin.math.min

internal object IslandWidthHooker {

    /**
     * calculateBigIslandWidth 的歌词岛计算窗口标记。
     * 仅在该窗口内降低系统最小岛宽下限，避免影响通知、充电等原生岛。
     */
    @Volatile
    var lyricWidthCalculationActive: Boolean = false

    class CalculateWidthHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            var hookedContentView: ViewGroup? = null
            runCatching {
                if (!IslandProbeUtils.isSuperIslandEnabled()) return@runCatching
                val contentView = chain.thisObject as? ViewGroup ?: return@runCatching
                hookedContentView = contentView
                val currentData = IslandProbeUtils.getCurrentIslandData(contentView)
                val mediaInfo = IslandProbeUtils.extractMediaIslandInfo(currentData) ?: return@runCatching
                if (!IslandTextHookerSupport.isCurrentLyricIsland(mediaInfo)) return@runCatching
                if (!IslandTextHookerSupport.shouldRenderInjectedIsland()) {
                    IslandTextHookerSupport.clearInjectedIsland(contentView, suppressRelayout = true)
                    return@runCatching
                }

                if (IslandLyricTextInjector.restoreExistingSlotsLightweight(contentView)) {
                    IslandLyricTextInjector.refreshCurrentContent(contentView)
                } else {
                    IslandLyricTextInjector.injectSlots(contentView, reconfigureExisting = false)
                    IslandLyricTextInjector.refreshCurrentContent(contentView)
                }
                // proceed 内的测量规格与上次相同且子树无 FORCE_LAYOUT 标志时会整体短路，
                // 动态长度开启时必须先标记区域子树，换行后的新文字宽度才能进入岛宽计算。
                IslandViewHelper.forceLayoutIslandAreasIfDynamicWidth(contentView)
                lyricWidthCalculationActive = true
                if (BuildConfig.DEBUG) {
                    IslandBackgroundTraceDiagnostics.event(
                        "宽度计算开始",
                        contentView,
                        "dynamic=${IslandViewHelper.isDynamicWidthEnabled()}",
                    )
                }
            }.onFailure { e ->
            HookLogger.e(TAG, "计算大岛宽度前准备歌词视图失败", e)
            }

            val result = chain.proceed()
            lyricWidthCalculationActive = false
            if (BuildConfig.DEBUG) {
                IslandBackgroundTraceDiagnostics.event(
                    "宽度计算结束",
                    hookedContentView,
                    "nativeWidth=${(result as? Number)?.toInt() ?: -1}",
                )
            }
            hookedContentView?.let { scheduleLayoutDump(it) }
            return result
        }

        private var lastDumpAt: Long = 0

        /**
         * 平板解除长度后内部模块可能不跟随新宽度重排，在宽度计算落地后
         * dump 一次岛视图子树的实际尺寸用于定位（限流，仅在开关开启时）。
         */
        private fun scheduleLayoutDump(contentView: ViewGroup) {
            if (!IslandViewHelper.isUnlockIslandLengthEnabled()) return
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastDumpAt < 8000) return
            lastDumpAt = now
            val view = contentView
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching {
                    val sb = StringBuilder("超级岛布局树: ")
                    dumpView(view, sb, 0)
                    HookLogger.i(TAG, sb.toString())
                }.onFailure { HookLogger.e(TAG, "布局树 dump 失败", it) }
            }, 500)
        }

        private fun dumpView(view: android.view.View, sb: StringBuilder, depth: Int) {
            if (depth > 5 || sb.length > 3200) return
            val width = if (view.width != 0) view.width else view.measuredWidth
            sb.append("[")
                .append(view.javaClass.simpleName)
                .append(" tag=")
                .append(view.tag)
                .append(" ")
                .append(width)
                .append("x")
                .append(view.height)
                .append(" xy=")
                .append(view.x.toInt())
                .append(",")
                .append(view.y.toInt())
                .append(" v=")
                .append(view.visibility)
                .append("]")
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    dumpView(view.getChildAt(i), sb, depth + 1)
                }
            }
        }
    }

    /**
     * 动态长度开启时，把系统最小岛宽下限降低到"岛高 + 小余量"。
     *
     * 系统原生下限（本机实测 330px ≈ pill 宽度）会让两侧都短的内容无法继续收缩，
     * 导致"该短的时候不短"。下限取岛高保证收窄后仍保持圆角胶囊形状、并完全
     * 覆盖摄像头挖孔。仅在歌词岛宽度计算窗口内生效。
     */
    class BigIslandMinWidthHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            return runCatching {
                if (!lyricWidthCalculationActive) return@runCatching result
                if (!IslandViewHelper.isDynamicWidthEnabled()) return@runCatching result
                val original = (result as? Number)?.toInt() ?: return@runCatching result
                val helper = chain.thisObject ?: return@runCatching result
                val height = helper.javaClass.methods.firstOrNull {
                    it.name == "getIslandViewHeight" && it.parameterTypes.isEmpty()
                }?.invoke(helper) as? Int ?: return@runCatching result
                if (height <= 0) return@runCatching result
                val margin = findFieldValue(helper, "context")?.let { context ->
                    runCatching {
                        val metrics = (context as android.content.Context).resources.displayMetrics
                        (2 * metrics.density).toInt()
                    }.getOrNull()
                } ?: 4
                val floor = height + margin
                val adjusted = min(original, floor)
                if (adjusted != original) {
                    HookLogger.d(TAG, "动态长度下限已调整: original=$original floor=$floor")
                }
                adjusted
            }.getOrDefault(result)
        }
    }

    /**
     * 解除平板超级岛长度限制（v2）：Hook PadHelper.calculateBigIslandWidth，替换返回结果。
     *
     * 160042 曾直接 Hook getter getBigIslandMaxWidth()，真机判别日志证明安装成功、
     * 开关可读，但拦截器从未命中——该 getter 被 ART AOT（插件自带 baseline profile）
     * 内联进调用方，AOT 后的调用点不再经过被 Hook 方法入口。因此改为 Hook 调用方
     * 本身，在其结果上把 Rule 3（big_island_max_width_pad=180dp 截断且压缩长边）
     * 改写为随内容伸展、左右不压缩。
     *
     * 平板岛的 x 语义是左边缘坐标（ContentView: rect.set(x, top, x+width, bottom)），
     * 锚点在左侧：改写时保留系统原 x（原生 Rule 3 的固定左边缘），仅替换宽度，
     * 使长度变化只推动右边缘；在原生上限边界处与原生 x 曲线连续，无跳变。
     * 内容未超上限（Rule 1/2）时保持系统原结果；开关实时读取，关闭即恢复原生。
     */
    class PadMaxWidthUnlockHook(
        private val resultClass: Class<*>,
    ) : Hooker {

        internal companion object {
            // 0=命中时开关关闭，1=已执行改写；仅记首个状态与状态翻转，避免刷屏。
            @Volatile
            var lastLoggedState: Int? = null
        }

        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            return runCatching {
                if (!IslandViewHelper.isUnlockIslandLengthEnabled()) {
                    if (lastLoggedState != 0) {
                        lastLoggedState = 0
                        HookLogger.i(TAG, "平板岛宽上限 Hook 已命中但开关读取为关闭，放行原生宽度")
                    }
                    return@runCatching result
                }
                val params = chain.args.getOrNull(0) ?: return@runCatching result
                val width = result.intGetter("BigIslandViewWidth") ?: return@runCatching result
                val x = result.intGetter("BigIslandX") ?: return@runCatching result
                val margin = result.intGetter("BigIslandMarginWidth") ?: return@runCatching result
                val areaLeft = params.intGetter("BigIslandAreaLeftWidth") ?: return@runCatching result
                val areaRight = params.intGetter("BigIslandAreaRightWidth") ?: return@runCatching result
                val content = areaLeft + areaRight + margin
                if (content <= width) return@runCatching result
                val intType = Int::class.javaPrimitiveType ?: return@runCatching result
                val constructor = resultClass.getConstructor(intType, intType, intType, intType, intType, intType, intType, intType, intType)
                val swapped = constructor.newInstance(
                    content,
                    areaLeft,
                    areaRight,
                    x,
                    margin,
                    result.intGetter("BigIslandViewWidthHasSmallIsland") ?: 0,
                    result.intGetter("BigIslandLeftWidthHasSmallIsland") ?: 0,
                    result.intGetter("BigIslandRightWidthHasSmallIsland") ?: 0,
                    result.intGetter("BigIslandXHasSmallIsland") ?: 0,
                )
                if (lastLoggedState != 1) {
                    lastLoggedState = 1
                    HookLogger.i(
                        TAG,
                        "平板岛宽上限已解除: $width -> $content (x=$x 左锚点不变 left=$areaLeft right=$areaRight)",
                    )
                }
                swapped
            }.getOrDefault(result)
        }

        private fun Any.intGetter(suffix: String): Int? = runCatching {
            javaClass.methods.firstOrNull {
                it.name == "get$suffix" && it.parameterTypes.isEmpty()
            }?.invoke(this) as? Int
        }.getOrNull()
    }
}
