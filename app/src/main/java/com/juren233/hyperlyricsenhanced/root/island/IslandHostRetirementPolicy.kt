/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

/**
 * 失效岛宿主的退役判定。
 *
 * 宿主 contentView 可能仍附着但媒体模块子树已被原生重排：注入锚点（父容器 +
 * 文本容器）不可达，重注入必败，岛内容永久消失（2026-09-28 酷我真机：
 * injected_view_recovery_failed 每句重试，切歌重建才恢复）。渲染层对同一宿主
 * 连续注入失败达到阈值后注销它并触发重挂扫描；重挂候选同样按锚点有效性过滤，
 * 避免"岛数据包名仍匹配"的失效宿主被立即注册回来形成注销↔重挂抖动。
 */
internal object IslandHostRetirementPolicy {
    /**
     * 连续失败达到该次数才注销：fake/real 过渡与原生重排窗口只有几百毫秒，
     * 最多撞上 1-2 句换句；阈值以下视为暂态，恢复成功即清零。
     */
    const val RETIRE_THRESHOLD = 5

    fun shouldRetire(consecutiveFailures: Int): Boolean = consecutiveFailures >= RETIRE_THRESHOLD

    /** 需要注入的每一侧锚点都必须可达；不需要注入的侧不参与判定。 */
    fun isAnchorStateInjectable(
        state: IslandAnchorState,
        shouldInjectLeft: Boolean,
        shouldInjectRight: Boolean,
    ): Boolean = (!shouldInjectLeft || (state.leftParent && state.leftContainer)) &&
        (!shouldInjectRight || (state.rightParent && state.rightContainer))
}

/** 注入锚点可达性快照；container 为 false 时 parent 单独保留，供诊断区分缺失层级。 */
internal data class IslandAnchorState(
    val leftParent: Boolean,
    val leftContainer: Boolean,
    val rightParent: Boolean,
    val rightContainer: Boolean,
)
