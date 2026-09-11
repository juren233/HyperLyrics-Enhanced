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

    /**
     * 当前进程是否存在平板岛宽路径（`DynamicIslandContentViewPadHelper` 安装成功）。
     * 手机插件没有该类，内容左缘锚定（平板专用的居中布局补偿）必须只在该路径生效。
     */
    @Volatile
    var padIslandPathActive: Boolean = false

    class CalculateWidthHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            var hookedContentView: ViewGroup? = null
            var previousIslandWidth = -1
            var lyricIslandCalculation = false
            runCatching {
                if (!IslandProbeUtils.isSuperIslandEnabled()) return@runCatching
                val contentView = chain.thisObject as? ViewGroup ?: return@runCatching
                hookedContentView = contentView
                previousIslandWidth = IslandPadContentAnchor.currentIslandWidth(contentView)
                val currentData = IslandProbeUtils.getCurrentIslandData(contentView)
                val mediaInfo = IslandProbeUtils.extractMediaIslandInfo(currentData) ?: return@runCatching
                if (!IslandTextHookerSupport.isCurrentLyricIsland(mediaInfo)) return@runCatching
                lyricIslandCalculation = true
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
            if (lyricIslandCalculation && padIslandPathActive) hookedContentView?.let { view ->
                // 歌词岛宽度变化（原生 Rule 1/2 或本模块改写 Rule 3）都会让平板居中布局的
                // 内容先跳 (W_target - W(t)) / 2 再滑回，这里统一开启内容左缘锚定窗口。
                runCatching {
                    val currentWidth = IslandPadContentAnchor.currentIslandWidth(view)
                    if (IslandViewHelper.isUnlockIslandLengthEnabled() &&
                        previousIslandWidth > 0 &&
                        currentWidth > 0 &&
                        currentWidth != previousIslandWidth
                    ) {
                        IslandPadContentAnchor.onWidthRewritten(view, currentWidth)
                    }
                }.onFailure { HookLogger.e(TAG, "开启平板岛内容锚定失败", it) }
            }
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
                    // 先输出胶囊 vs 内容的对齐快照：判断错位在原生结果改写后的哪一环。
                    IslandBackgroundTraceDiagnostics.logAlignmentSnapshot(
                        reason = "宽度计算落地",
                        contentView = view,
                        detail = "unlock=${IslandViewHelper.isUnlockIslandLengthEnabled()}",
                    )
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
     * 解除平板超级岛长度限制（v3）：Hook PadHelper.calculateBigIslandWidth，按内容宽改写 Rule 3 结果。
     *
     * 160042 曾直接 Hook getter getBigIslandMaxWidth()，真机判别日志证明安装成功、
     * 开关可读，但拦截器从未命中——该 getter 被 ART AOT（插件自带 baseline profile）
     * 内联进调用方，AOT 后的调用点不再经过被 Hook 方法入口。因此改为 Hook 调用方
     * 本身，在其结果上把 Rule 3（big_island_max_width_pad=180dp 截断且压缩长边）
     * 改写为随内容伸展、左右不压缩。
     *
     * 结果字段语义（平板插件原始 DEX `DynamicIslandContentViewPadHelper.calculateBigIslandWidth`）：
     * `x = (params.screenWidth - islandWidth) / 2`，是"居中左边缘"，不是固定左锚点。
     * 平板绘制时胶囊与内容用的是同一套 x 派生量：
     *   `padIslandTransX = statusBarDatePosX - x`（`DynamicIslandBaseContentView.updateBigIslandLayoutWithAnim` 平板分支）
     *   内容左缘 = (screenWidth - W) / 2 + padIslandTransX
     *   胶囊左缘 = x + padIslandTransX = statusBarDatePosX（与 x 无关，恒锚定在状态栏日期位）
     * 因此只有保持原生不变量 `x = (screenWidth - W) / 2` 时两者才重合。160044 保留被
     * 180dp 上限截断后的原生 x，会让内容相对胶囊左移 `(W - 465px) / 2`：左侧被胶囊左缘
     * 裁切、右侧留白（2026-09-11 真机：胶囊绘制矩形 163..872，注入内容 132..729,
     * islandWidth=701 时偏移 118px = (701-465)/2）。所以改写宽度时必须同步按原生公式重算 x。
     * 内容未超上限（Rule 1/2）时原样放行；上限边界处两式相等，x 曲线连续；开关实时读取。
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
                val screenWidth = params.intGetter("ScreenWidth") ?: return@runCatching result
                // 原生不变量：x 是居中左边缘。保留被截断宽度下的 x 会让内容与胶囊错位 (W - nativeW)/2。
                val centeredX = (screenWidth - content) / 2
                val intType = Int::class.javaPrimitiveType ?: return@runCatching result
                val constructor = resultClass.getConstructor(intType, intType, intType, intType, intType, intType, intType, intType, intType)
                val swapped = constructor.newInstance(
                    content,
                    areaLeft,
                    areaRight,
                    centeredX,
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
                        "平板岛宽上限已解除: $width -> $content " +
                            "(x=$x -> $centeredX 居中重算 left=$areaLeft right=$areaRight screen=$screenWidth)",
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

    /**
     * 解除手机超级岛长度限制：Hook PhoneHelper.calculateBigIslandWidth，在上限截断时恢复未截断宽度。
     *
     * 手机插件 18.3.2.5.0 原始 DEX（Xiaomi 15 / OS4.0.0.8）核实：
     * `DynamicIslandContentViewPhoneHelper.calculateBigIslandWidth(IslandContentViewCalculationParams)`，
     * 宽度公式 `W = max(min(cutout + 2*max(左区, 右区), params.maxWidth), getBigIslandMinWidth())`。
     * 手机上限 params.maxWidth 由宿主经 `extra_island_max_width` 下发（插件内没有手机端 dimen，
     * 只有 big_island_max_width_pad）。截断时结果左右区被对称压缩为 `(W - cutout) / 2`，内容随之被裁。
     *
     * 手机岛必须围绕挖孔对称：x=(screenWidth-W)/2 时只有左右区等宽，挖孔才能对中，
     * 因此改写保持原生未截断形状：`W' = cutout + 2*max(左, 右)`、left=right=max(左, 右)、
     * x 按同一不变量重算；不加 margin（原生未截断公式亦无 margin）。
     * 仅在 `W < W'`（上限实际截断）时改写：未截断结果与最小下限抬高的结果都满足 `W >= W'`，原样放行，
     * 与动态长度下限（缩小方向）互不干扰。HasSmallIsland 字段与平板解锁同样保留原生值。
     * Flip 外屏（FlipUtils.isFlipTiny，标识符已核对原始 DEX）的 810dp 上限是小屏物理约束，不改写。
     */
    class PhoneMaxWidthUnlockHook(
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
                        HookLogger.i(TAG, "手机岛宽上限 Hook 已命中但开关读取为关闭，放行原生宽度")
                    }
                    return@runCatching result
                }
                if (isFlipTiny(chain.thisObject)) return@runCatching result
                val params = chain.args.getOrNull(0) ?: return@runCatching result
                val width = result.intGetter("BigIslandViewWidth") ?: return@runCatching result
                val margin = result.intGetter("BigIslandMarginWidth") ?: return@runCatching result
                val areaLeft = params.intGetter("BigIslandAreaLeftWidth") ?: return@runCatching result
                val areaRight = params.intGetter("BigIslandAreaRightWidth") ?: return@runCatching result
                val cutout = params.intGetter("CutoutWidth") ?: return@runCatching result
                val maxSide = maxOf(areaLeft, areaRight)
                val unlockedWidth = cutout + 2 * maxSide
                if (width >= unlockedWidth) return@runCatching result
                val screenWidth = params.intGetter("ScreenWidth") ?: return@runCatching result
                // 原生不变量：x 是居中左边缘，保持 (screenWidth - W)/2 才能让胶囊、内容与挖孔对齐。
                val centeredX = (screenWidth - unlockedWidth) / 2
                val intType = Int::class.javaPrimitiveType ?: return@runCatching result
                val constructor = resultClass.getConstructor(intType, intType, intType, intType, intType, intType, intType, intType, intType)
                val swapped = constructor.newInstance(
                    unlockedWidth,
                    maxSide,
                    maxSide,
                    centeredX,
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
                        "手机岛宽上限已解除: $width -> $unlockedWidth " +
                            "(x 居中重算 left=$maxSide right=$maxSide cutout=$cutout screen=$screenWidth)",
                    )
                }
                swapped
            }.getOrDefault(result)
        }

        private fun isFlipTiny(helper: Any?): Boolean = runCatching {
            helper?.javaClass?.classLoader
                ?.loadClass("miui.systemui.util.FlipUtils")
                ?.getMethod("isFlipTiny")
                ?.invoke(null) as? Boolean
        }.getOrNull() == true

        private fun Any.intGetter(suffix: String): Int? = runCatching {
            javaClass.methods.firstOrNull {
                it.name == "get$suffix" && it.parameterTypes.isEmpty()
            }?.invoke(this) as? Int
        }.getOrNull()
    }
}
