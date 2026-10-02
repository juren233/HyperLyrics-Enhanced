/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view.line

import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import com.juren233.hyperlyricsenhanced.lyric.view.line.model.LyricModel

internal interface LineRenderer {
    val isPlaying: Boolean
    val isFinished: Boolean
    val isStarted: Boolean
    var centerIfPossible: Boolean
    var alignRight: Boolean

    fun step(deltaNanos: Long, model: LyricModel, state: LineState, viewWidth: Int): Boolean
    fun draw(canvas: Canvas, model: LyricModel, paint: TextPaint, state: LineState, viewWidth: Int, viewHeight: Int)
    fun seek(model: LyricModel, state: LineState, posMs: Long, viewWidth: Int, viewHeight: Int)
    fun update(model: LyricModel, state: LineState, posMs: Long, viewWidth: Int, viewHeight: Int)
    fun reset(state: LineState)

    /** Capacity after seam avoidance, shared by the view, scrolling and shadows. */
    fun layoutWidthFor(model: LyricModel, viewWidth: Int): Float = model.width

    /**
     * 当前帧的接缝绘制方案（ISLAND-GATE-OCCLUSION-001 第 6 轮，用户拍板）：
     * 静止＝绕孔分段（锚定边贴缘、跨缝段整段平移过缝），滚动＝边缘滑过＋
     * 渐隐，起滚/停驻间隙随滚动连续合拢/张开。null＝非拼接模式。
     * 仅 SpaceGate 渲染器覆写；实现必须是纯函数（无时钟/动画状态），与
     * draw 内部同输入同结果（主从一致，阴影同源）。
     */
    fun seamPlanFor(model: LyricModel, state: LineState, viewWidth: Int): SeamStripPlan? = null
}

internal fun resolvePlainTextOffset(
    textWidth: Float,
    viewWidth: Float,
    scrollOffset: Float,
    isAlignedRight: Boolean,
    centerIfPossible: Boolean,
    alignRight: Boolean = false
): Float = when {
    textWidth > viewWidth -> scrollOffset
    alignRight -> viewWidth - textWidth
    centerIfPossible -> (viewWidth - textWidth) / 2f
    isAlignedRight -> viewWidth - textWidth
    else -> scrollOffset
}

/** 跨缝渐隐裁剪的矩形半径（足够覆盖任何绘制区域）。 */
private const val SEAM_CLIP_EXTENT = 100000f

/**
 * 跨缝单元的公共绘制包装（第 6 轮拍板「边缘滑过＋渐隐」）：裁剪到多数
 * 侧（切点＝接缝＝摄像头边缘），alpha 按遮盖深度渐隐。无跨缝时直通。
 * 在已含段带平移的坐标系内调用，[SeamStripPlan.SeamFade.seamLocalX] 为
 * 缝在该坐标系中的 x。
 */
internal inline fun Canvas.withSeamFade(
    fade: SeamStripPlan.SeamFade?,
    paint: Paint,
    block: () -> Unit,
) {
    if (fade == null) {
        block()
        return
    }
    val saved = save()
    if (fade.majorityLeft) {
        clipRect(-SEAM_CLIP_EXTENT, -SEAM_CLIP_EXTENT, fade.seamLocalX, SEAM_CLIP_EXTENT)
    } else {
        clipRect(fade.seamLocalX, -SEAM_CLIP_EXTENT, SEAM_CLIP_EXTENT, SEAM_CLIP_EXTENT)
    }
    val oldAlpha = paint.alpha
    paint.alpha = (oldAlpha * fade.alpha).toInt().coerceIn(0, 255)
    try {
        block()
    } finally {
        paint.alpha = oldAlpha
        restoreToCount(saved)
    }
}
