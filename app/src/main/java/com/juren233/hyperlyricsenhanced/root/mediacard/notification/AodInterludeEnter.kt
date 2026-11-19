/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import com.juren233.hyperlyricsenhanced.lyric.view.NextLinePromotionMotion

/**
 * 「灵动圆点」：当前句换成前奏/间奏指示器时与超级岛一致——旧的主行按上浮的退场动作向上滑出并淡出
 * （时长、位移取自 [NextLinePromotionMotion]），换成圆点内容后主行渐显，接上圆点自己从暗到亮的
 * 点亮动画。只动主行，其余行（翻译、下一句等）不参与动画。渐显时长与上浮落地后的预览淡入同源。
 *
 * 与「歌词滚动」共用 [LyricOverlay] 上的「转场进行中」标记：转场期间保持旧内容，结束后由调用方
 * 重新应用最新内容。
 */
internal object AodInterludeEnter {

    /** 结束回调丢失时的兜底余量，防止歌词块卡在透明。 */
    private const val WATCHDOG_MARGIN_MS = 250L

    fun shouldStart(
        enabled: Boolean,
        wasInterlude: Boolean,
        nowInterlude: Boolean,
        hadMainText: Boolean,
        attached: Boolean,
    ): Boolean = enabled && !wasInterlude && nowInterlude && hadMainText && attached

    fun start(overlay: LyricOverlay, onFaded: () -> Unit) {
        val main = overlay.main
        val generation = ++overlay.promotionGeneration
        overlay.promotionRunning = true
        overlay.drawWakeLock.acquire(NotificationMediaAodLyricHooker.DRAW_WAKE_LOCK_TIMEOUT_MS)
        main.animate().cancel()
        main.animate()
            .alpha(0f)
            .translationY(-main.height * NextLinePromotionMotion.MAIN_EXIT_TRAVEL_FACTOR)
            .setDuration(NextLinePromotionMotion.DURATION_MS)
            .withLayer()
            .withEndAction { swap(overlay, generation, onFaded) }
            .start()
        overlay.root.postDelayed(
            { swap(overlay, generation, onFaded) },
            NextLinePromotionMotion.DURATION_MS + WATCHDOG_MARGIN_MS,
        )
    }

    private fun swap(overlay: LyricOverlay, generation: Int, onFaded: () -> Unit) {
        if (generation != overlay.promotionGeneration || !overlay.promotionRunning) return
        val main = overlay.main
        // 复位的唯一入口：先停动画再写回静止值，避免残余帧把终点值写回。
        main.animate().setListener(null)
        main.animate().cancel()
        main.translationY = 0f
        overlay.promotionRunning = false
        if (!overlay.lifetime.allowsUpdates) {
            main.alpha = 1f
            return
        }
        main.alpha = 0f
        overlay.skipNextPromotion = true
        onFaded()
        overlay.skipNextPromotion = false
        main.animate()
            .alpha(1f)
            .setDuration(NextLinePromotionMotion.PREVIEW_FADE_DURATION_MS)
            .withLayer()
            .start()
    }
}
