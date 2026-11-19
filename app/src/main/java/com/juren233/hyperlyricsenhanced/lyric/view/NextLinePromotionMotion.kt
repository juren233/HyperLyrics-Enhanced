/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view

/**
 * 「下一句上浮」的动画参数唯一来源：下一句预览飞到主行位置并放大，旧主行上移淡出，
 * 落地后新的下一句淡入。超级岛各视图与 AOD 共用，不在各处另写数值。
 */
internal object NextLinePromotionMotion {
    const val DURATION_MS = 220L

    const val PREVIEW_FADE_DURATION_MS = 140L

    /** 旧主行上移的距离，相对主行高度。 */
    const val MAIN_EXIT_TRAVEL_FACTOR = 0.65f

    private const val MIN_SCALE = 0.5f
    private const val MAX_SCALE = 2f

    /** 预览行放大到主行字号的缩放比。 */
    fun targetScale(mainTextSize: Float, previewTextSize: Float): Float =
        (mainTextSize / previewTextSize).coerceIn(MIN_SCALE, MAX_SCALE)
}
