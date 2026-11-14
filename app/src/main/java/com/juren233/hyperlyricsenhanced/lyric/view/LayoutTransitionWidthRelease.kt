/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view

import android.view.ViewGroup

/**
 * 外层宽度在布局转场途中变化时，让歌词视图立即按新宽度布局。
 *
 * AOSP `ViewGroup.layout()` 在自身 `LayoutTransition.isChangingLayout()` 为真时直接跳过布局，
 * 等转场结束才补做。副行出现/消失会启动 [LayoutTransitionX] 的变化动画（约 280ms），若此时
 * 岛宽也在伸展（如极简显示结束、前奏转入第一句），歌词视图会一直停在转场开始时的旧宽度，
 * 文字被裁成碎片，转场结束才整句跳出。
 *
 * 在 `onMeasure` 末尾调用 [onMeasured]：测出的宽度与当前布局宽度不同且转场正在改变布局时，
 * 下一帧重设同一转场（框架会对旧转场 `cancel()`，结束进行中的变化动画）并重新布局。
 */
internal class LayoutTransitionWidthRelease(private val view: ViewGroup) {
    private var posted = false

    private val release = Runnable {
        posted = false
        val transition = view.layoutTransition ?: return@Runnable
        if (!transition.isChangingLayout || view.measuredWidth == view.width) return@Runnable
        view.layoutTransition = null
        view.layoutTransition = transition
        view.requestLayout()
    }

    fun onMeasured() {
        if (posted) return
        val transition = view.layoutTransition ?: return
        if (!transition.isChangingLayout || view.measuredWidth == view.width) return
        posted = true
        view.post(release)
    }

    fun clear() {
        if (!posted) return
        posted = false
        view.removeCallbacks(release)
    }
}
