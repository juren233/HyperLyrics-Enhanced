/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

/**
 * 冻结的状态栏障碍锚点快照，动态上限的稳定锚点来源。
 *
 * 两个作用：
 * 1. 回桌面/上岛恢复的过渡窗口里，状态栏根视图会短暂不满足 attached + shown +
 *    同屏宽 的匹配条件，用快照先行裁剪，避免第一帧按无上限原生宽度入场、
 *    锚点实测后再硬切（时钟几何在恢复瞬间与上一次有效观测几乎一致）。
 * 2. 手机端时钟右缘是岛宽的影子（岛变宽 → 时钟被压窄），追逐实时值会让上限
 *    自激振荡；因此实时观测只用于探测结构性变化（条目数量、文本属性、左缘
 *    移动），锚点本体保持冻结，只有结构变化才替换快照。
 */
internal class IslandAnchorSnapshot {

    private var spans: List<IslandDynamicLimitPolicy.Span>? = null
    private var screenWidth: Int = 0

    /** Boot-classloader-only state; preserve frozen anchors across module generations. */
    fun save(): IntArray = intArrayOf(screenWidth) + spans.orEmpty().flatMap {
        listOf(it.left, it.right, if (it.text) 1 else 0)
    }.toIntArray()

    fun restore(values: IntArray) {
        if (values.isEmpty() || (values.size - 1) % 3 != 0) return
        screenWidth = values[0]
        spans = (1 until values.size step 3).map {
            IslandDynamicLimitPolicy.Span(values[it], values[it + 1], values[it + 2] != 0)
        }
    }

    /** 结构性变化（或首次观测）时替换快照。 */
    fun update(live: List<IslandDynamicLimitPolicy.Span>, observedScreenWidth: Int) {
        spans = live
        screenWidth = observedScreenWidth
    }

    /** 仅在请求屏宽与快照屏宽一致且快照非空时复用，避免旋转/分屏后拿到错几何。 */
    fun fallbackFor(screenWidth: Int): List<IslandDynamicLimitPolicy.Span>? =
        spans?.takeIf { it.isNotEmpty() && screenWidth == this.screenWidth }
}
