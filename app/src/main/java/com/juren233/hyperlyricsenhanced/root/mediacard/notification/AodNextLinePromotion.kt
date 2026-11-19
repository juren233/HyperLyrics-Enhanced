/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import android.widget.TextView
import com.juren233.hyperlyricsenhanced.lyric.view.NextLinePromotionMotion
import com.juren233.hyperlyricsenhanced.lyric.view.canAnimateNextLinePromotion

/**
 * 锁屏 AOD 的「歌词滚动」：与超级岛同一套「下一句上浮」——下一句从第二行飞到主行位置并放大，
 * 旧主行上移淡出，落地后新的下一句淡入。动画参数全部取自 [NextLinePromotionMotion]。
 *
 * 只在下一句紧跟主行时才有意义：主行与下一句之间没有翻译/发音、伴唱、重叠分组等行。
 */
internal object AodNextLinePromotion {

    /** 换句落地后给动画收尾的兜底余量，防止结束回调丢失时界面卡在旧句。 */
    private const val WATCHDOG_MARGIN_MS = 250L

    fun shouldPromote(
        enabled: Boolean,
        showNextLyric: Boolean,
        rowsBetweenBlank: Boolean,
        wasPreview: Boolean,
        currentMainText: String?,
        previewText: String?,
        nextMainText: String?,
        lineAdvanced: Boolean,
        attached: Boolean,
        mainHeight: Int,
        nextHeight: Int,
    ): Boolean = enabled && showNextLyric && rowsBetweenBlank && canAnimateNextLinePromotion(
        wasPreview = wasPreview,
        currentMainText = currentMainText,
        previewText = previewText,
        nextMainText = nextMainText,
        lineAdvanced = lineAdvanced,
        attached = attached,
        mainHeight = mainHeight,
        secondaryHeight = nextHeight,
    )

    /** 主行与下一句之间的行（翻译、发音、伴唱、重叠分组）旧界面与新内容里都为空。 */
    fun rowsBetweenBlank(overlay: LyricOverlay, content: AodLyricContent): Boolean =
        listOf(
            overlay.translation.text, overlay.backing.text, overlay.backingTranslation.text,
            overlay.overlappingMain.text, overlay.overlappingTranslation.text,
            overlay.overlappingBacking.text, overlay.overlappingBackingTranslation.text,
        ).all { it.isBlank() } && listOf(
            content.translation, content.backing, content.backingTranslation,
            content.overlappingMain, content.overlappingTranslation,
            content.overlappingBacking, content.overlappingBackingTranslation,
        ).all { it.isBlank() }

    /**
     * 开始上浮。旧内容保持不动，动画结束（或兜底超时）后调用 [onLanded]，由调用方
     * 重新应用最新内容；落地帧起下一句淡入。
     */
    fun start(
        overlay: LyricOverlay,
        newMainText: String,
        newMainAlignment: AodLyricAlignment,
        onLanded: () -> Unit,
    ) {
        val main = overlay.main
        val next = overlay.next
        val generation = ++overlay.promotionGeneration
        overlay.promotionRunning = true
        overlay.drawWakeLock.acquire(NotificationMediaAodLyricHooker.DRAW_WAKE_LOCK_TIMEOUT_MS)

        val scale = NextLinePromotionMotion.targetScale(main.textSize, next.textSize)
        val anchorFactor = when (newMainAlignment) {
            AodLyricAlignment.LEFT -> 0f
            AodLyricAlignment.CENTER -> 0.5f
            AodLyricAlignment.RIGHT -> 1f
        }
        // 水平锚点：下一句当前首行文字的锚点，落到新主行首行文字的同一锚点（左/中/右由对齐决定）。
        val nextLayout = next.layout
        val sourceLeft = next.compoundPaddingLeft + (nextLayout?.getLineLeft(0) ?: 0f)
        val sourceWidth = nextLayout?.getLineWidth(0) ?: next.paint.measureText(next.text.toString())
        val available = (main.width - main.compoundPaddingLeft - main.compoundPaddingRight)
            .coerceAtLeast(0).toFloat()
        val targetWidth = minOf(main.paint.measureText(newMainText), available)
        val targetLeft = main.compoundPaddingLeft + when (newMainAlignment) {
            AodLyricAlignment.LEFT -> 0f
            AodLyricAlignment.CENTER -> (available - targetWidth) / 2f
            AodLyricAlignment.RIGHT -> available - targetWidth
        }
        val sourceAnchor = next.left + sourceLeft + sourceWidth * anchorFactor
        val targetAnchor = main.left + targetLeft + targetWidth * anchorFactor

        main.animate().cancel()
        next.animate().cancel()
        next.pivotX = sourceLeft + sourceWidth * anchorFactor
        next.pivotY = 0f
        main.animate()
            .alpha(0f)
            .translationY(-main.height * NextLinePromotionMotion.MAIN_EXIT_TRAVEL_FACTOR)
            .setDuration(NextLinePromotionMotion.DURATION_MS)
            .withLayer()
            .start()
        next.animate()
            .translationX(targetAnchor - sourceAnchor)
            .translationY((main.top - next.top).toFloat())
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(NextLinePromotionMotion.DURATION_MS)
            .withLayer()
            .withEndAction { land(overlay, generation, onLanded) }
            .start()
        overlay.root.postDelayed(
            { land(overlay, generation, onLanded) },
            NextLinePromotionMotion.DURATION_MS + WATCHDOG_MARGIN_MS,
        )
    }

    private fun land(overlay: LyricOverlay, generation: Int, onLanded: () -> Unit) {
        if (generation != overlay.promotionGeneration || !overlay.promotionRunning) return
        if (!overlay.lifetime.allowsUpdates) return
        overlay.promotionRunning = false
        reset(overlay.main, overlay.next)
        overlay.skipNextPromotion = true
        onLanded()
        overlay.skipNextPromotion = false
        // 新的下一句淡入，与超级岛落地后的预览淡入一致。
        overlay.next.alpha = 0f
        overlay.next.animate()
            .alpha(1f)
            .setDuration(NextLinePromotionMotion.PREVIEW_FADE_DURATION_MS)
            .withLayer()
            .start()
    }

    /** 复位的唯一入口：先停动画再写回静止值，避免残余帧把终点值写回。 */
    private fun reset(main: TextView, next: TextView) {
        main.animate().setListener(null)
        next.animate().setListener(null)
        main.animate().cancel()
        next.animate().cancel()
        main.alpha = 1f
        main.translationY = 0f
        next.alpha = 1f
        next.translationX = 0f
        next.translationY = 0f
        next.scaleX = 1f
        next.scaleY = 1f
    }
}
