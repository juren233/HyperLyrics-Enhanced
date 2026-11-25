/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.LinearInterpolator
import android.widget.TextView
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger

/**
 * 锁屏 AOD 歌词的切换转场（「歌词滚动」与「灵动圆点」共用）。
 *
 * 做法是「先排版、再倒推」：新内容立即应用到真实控件并完成排版，再让每一行从旧位置/旧字号
 * 动到新位置；旧内容先拍成快照放在媒体卡片的 overlay 上，由快照完成上浮或淡出。因此：
 * - 落地帧就是真实排版，长句换行不会在落地时跳一下；
 * - 新主行一开始就绑定逐字进度，上浮途中就在扫；
 * - 转场进行中又来新内容时，当前转场直接跳到终点，再从终点状态开始下一次，不排队等。
 *
 * 所有行同时开始、同时结束，时长与位移见 [AodLyricTransitionPolicy]。
 */
internal object AodLyricTransition {

    private const val TAG = "AodLyricTransition"

    /** 结束回调丢失或一直没出帧时的兜底余量。 */
    private const val WATCHDOG_MARGIN_MS = 250L

    /** 旧内容在应用新内容之前拍下的状态。 */
    class Pending internal constructor(
        val kind: AodTransitionKind,
        internal val plans: Map<AodLyricRow, AodRowPlan>,
        internal val oldTops: Map<AodLyricRow, Int>,
        internal val ghosts: Map<AodLyricRow, Ghost>,
        internal val exitTravel: Float,
        internal val oldNextTop: Int,
        internal val oldNextSourceLeft: Float,
        internal val oldNextSourceWidth: Float,
        internal val oldNextTextSize: Float,
    )

    /** 正在进行的转场。 */
    class Running internal constructor(
        internal val generation: Int,
        internal val layer: GhostLayer,
        internal val host: ViewGroup,
    ) {
        internal var animator: ValueAnimator? = null
        internal var preDraw: ViewTreeObserver.OnPreDrawListener? = null
        internal var observer: ViewTreeObserver? = null
        internal val layerTypes = HashMap<View, Int>()
    }

    fun viewOf(overlay: LyricOverlay, row: AodLyricRow): TextView = when (row) {
        AodLyricRow.MAIN -> overlay.main
        AodLyricRow.TRANSLATION -> overlay.translation
        AodLyricRow.BACKING -> overlay.backing
        AodLyricRow.BACKING_TRANSLATION -> overlay.backingTranslation
        AodLyricRow.OVERLAPPING_MAIN -> overlay.overlappingMain
        AodLyricRow.OVERLAPPING_TRANSLATION -> overlay.overlappingTranslation
        AodLyricRow.OVERLAPPING_BACKING -> overlay.overlappingBacking
        AodLyricRow.OVERLAPPING_BACKING_TRANSLATION -> overlay.overlappingBackingTranslation
        AodLyricRow.NEXT -> overlay.next
    }

    /** 当前界面上各行显示的文本（隐藏的行为空）。 */
    fun shownRows(overlay: LyricOverlay): Map<AodLyricRow, String> =
        AodLyricRow.entries.associateWith { row ->
            val view = viewOf(overlay, row)
            if (view.visibility == View.VISIBLE) view.text.toString() else ""
        }

    /** 新内容各行文本（空白的行会被隐藏）。 */
    fun contentRows(content: AodLyricContent): Map<AodLyricRow, String> = mapOf(
        AodLyricRow.MAIN to content.main,
        AodLyricRow.TRANSLATION to content.translation,
        AodLyricRow.BACKING to content.backing,
        AodLyricRow.BACKING_TRANSLATION to content.backingTranslation,
        AodLyricRow.OVERLAPPING_MAIN to content.overlappingMain,
        AodLyricRow.OVERLAPPING_TRANSLATION to content.overlappingTranslation,
        AodLyricRow.OVERLAPPING_BACKING to content.overlappingBacking,
        AodLyricRow.OVERLAPPING_BACKING_TRANSLATION to content.overlappingBackingTranslation,
        AodLyricRow.NEXT to content.next,
    ).mapValues { (_, text) -> if (text.isBlank()) "" else text }

    fun canAnimate(overlay: LyricOverlay): Boolean =
        overlay.lifetime.allowsUpdates && overlay.root.isAttachedToWindow &&
            overlay.root.visibility == View.VISIBLE && overlay.root.isShown &&
            overlay.root.width > 0 && overlay.root.height > 0

    /** 在应用新内容之前调用：给要离开的行拍快照、记下旧位置。[kind] 为 NONE 时返回 null。 */
    fun capture(
        overlay: LyricOverlay,
        kind: AodTransitionKind,
        oldRows: Map<AodLyricRow, String>,
        newRows: Map<AodLyricRow, String>,
    ): Pending? {
        if (kind == AodTransitionKind.NONE) return null
        return runCatching {
            val plans = AodLyricRow.entries.associateWith { row ->
                AodLyricTransitionPolicy.plan(
                    kind, row, oldRows[row].orEmpty(), newRows[row].orEmpty(),
                )
            }
            val oldTops = HashMap<AodLyricRow, Int>()
            val ghosts = HashMap<AodLyricRow, Ghost>()
            AodLyricRow.entries.forEach { row ->
                val view = viewOf(overlay, row)
                if (view.visibility != View.VISIBLE) return@forEach
                oldTops[row] = view.top
                val plan = plans.getValue(row)
                if (plan.exit != AodRowExit.NONE && view.width > 0 && view.height > 0) {
                    ghosts[row] = Ghost(
                        bitmap = snapshot(view),
                        left = view.left.toFloat(),
                        top = view.top.toFloat(),
                        exit = plan.exit,
                    )
                }
            }
            val referenceHeight = overlay.main.takeIf { it.visibility == View.VISIBLE }?.height
                ?: AodLyricRow.entries.map { viewOf(overlay, it) }
                    .firstOrNull { it.visibility == View.VISIBLE }?.height
                ?: 0
            val next = overlay.next
            val nextLayout = next.layout
            Pending(
                kind = kind,
                plans = plans,
                oldTops = oldTops,
                ghosts = ghosts,
                exitTravel = referenceHeight * AodLyricTransitionPolicy.EXIT_TRAVEL_FACTOR,
                oldNextTop = next.top,
                oldNextSourceLeft = next.compoundPaddingLeft + (nextLayout?.getLineLeft(0) ?: 0f),
                oldNextSourceWidth = nextLayout?.getLineWidth(0)
                    ?: next.paint.measureText(next.text.toString()),
                oldNextTextSize = next.textSize,
            )
        }.onFailure {
            HookLogger.e(TAG, "锁屏 AOD 转场拍快照失败，改为直接替换", it)
        }.getOrNull()
    }

    /** 在新内容应用之后调用：等下一次排版完成后从旧状态倒推开始动画。 */
    fun begin(overlay: LyricOverlay, pending: Pending, mainAlignment: AodLyricAlignment) {
        finish(overlay)
        val host = overlay.player
        val generation = ++overlay.transitionGeneration
        val layer = GhostLayer(pending.ghosts.values.toList())
        val running = Running(generation, layer, host)
        overlay.transition = running
        overlay.drawWakeLock.acquire(NotificationMediaAodLyricHooker.DRAW_WAKE_LOCK_TIMEOUT_MS)

        val (rootLeft, rootTop) = offsetIn(overlay.root, host)
        layer.originX = rootLeft.toFloat()
        layer.originY = rootTop.toFloat()
        layer.clipTop = rootTop.toFloat()
        layer.setBounds(0, 0, host.width, maxOf(host.height, rootTop + overlay.root.height * 2))
        host.overlay.add(layer)
        overlay.root.clipChildren = false

        // 新内容在排版完成前不能露出：先把要「进入」的行藏起来。
        pending.plans.forEach { (row, plan) ->
            val view = viewOf(overlay, row)
            when (plan.enter) {
                AodRowEnter.ENTER, AodRowEnter.RIDE, AodRowEnter.FADE_IN, AodRowEnter.PROMOTED -> {
                    running.layerTypes[view] = view.layerType
                    view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    view.alpha = 0f
                }
                else -> Unit
            }
        }

        val observer = overlay.root.viewTreeObserver
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                running.preDraw = null
                if (overlay.transition !== running) return true
                runCatching { startAnimation(overlay, running, pending, mainAlignment) }
                    .onFailure {
                        HookLogger.e(TAG, "锁屏 AOD 转场启动失败，直接落地", it)
                        finish(overlay)
                    }
                return true
            }
        }
        running.preDraw = listener
        running.observer = observer
        observer.addOnPreDrawListener(listener)
        overlay.root.invalidate()
        overlay.root.postDelayed(
            { if (overlay.transition === running) finish(overlay) },
            AodLyricTransitionPolicy.MOVE_DURATION_MS + WATCHDOG_MARGIN_MS,
        )
    }

    private fun startAnimation(
        overlay: LyricOverlay,
        running: Running,
        pending: Pending,
        mainAlignment: AodLyricAlignment,
    ) {
        val main = overlay.main
        val factor = AodLyricTransitionPolicy.anchorFactor(mainAlignment)
        val promoted = pending.plans.getValue(AodLyricRow.MAIN).enter == AodRowEnter.PROMOTED
        // 新主行：锚点（左/中/右由对齐决定）从旧下一句首行文字的锚点放大飞到主行首行文字的锚点。
        var promoteDy = 0f
        var anchorDx = 0f
        var startScale = 1f
        if (promoted) {
            val layout = main.layout
            val targetLeft = main.compoundPaddingLeft + (layout?.getLineLeft(0) ?: 0f)
            val targetWidth = layout?.getLineWidth(0) ?: main.paint.measureText(main.text.toString())
            val targetAnchor = main.left + targetLeft + targetWidth * factor
            val sourceAnchor = overlay.next.left + pending.oldNextSourceLeft +
                pending.oldNextSourceWidth * factor
            promoteDy = (pending.oldNextTop - main.top).toFloat()
            anchorDx = sourceAnchor - targetAnchor
            startScale = AodLyricTransitionPolicy.promotedStartScale(main.textSize, pending.oldNextTextSize)
            main.pivotX = targetLeft + targetWidth * factor
            main.pivotY = 0f
            pending.ghosts[AodLyricRow.NEXT]?.let { ghost ->
                ghost.pivotX = pending.oldNextSourceLeft + pending.oldNextSourceWidth * factor
                ghost.pivotY = 0f
                ghost.endDx = -anchorDx
                ghost.endDy = -promoteDy
                ghost.endScale = 1f / startScale
            }
        }
        val blockEnterTravel = AodLyricTransitionPolicy.ENTER_TRAVEL_FACTOR * (
            main.takeIf { it.visibility == View.VISIBLE }?.height
                ?: AodLyricRow.entries.map { viewOf(overlay, it) }
                    .firstOrNull { it.visibility == View.VISIBLE }?.height
                ?: 0
            )
        val steps = pending.plans.mapNotNull { (row, plan) ->
            val view = viewOf(overlay, row)
            if (view.visibility != View.VISIBLE) return@mapNotNull null
            val moveFrom = pending.oldTops[row]?.let { (it - view.top).toFloat() } ?: 0f
            val enterTravel = if (pending.kind == AodTransitionKind.BLOCK) {
                blockEnterTravel
            } else {
                AodLyricTransitionPolicy.ENTER_TRAVEL_FACTOR * view.height
            }
            when (plan.enter) {
                AodRowEnter.NONE -> null
                AodRowEnter.MOVE -> if (moveFrom == 0f) null else step { move, _ ->
                    view.translationY = (1f - move) * moveFrom
                }
                AodRowEnter.ENTER -> step { move, _ ->
                    view.translationY = (1f - move) * enterTravel
                    view.alpha = move
                }
                AodRowEnter.RIDE -> step { move, _ ->
                    view.translationY = (1f - move) * promoteDy
                    view.alpha = move
                }
                AodRowEnter.FADE_IN -> step { _, fade ->
                    view.alpha = fade
                }
                AodRowEnter.PROMOTED -> step { move, _ ->
                    view.translationX = (1f - move) * anchorDx
                    view.translationY = (1f - move) * promoteDy
                    val scale = AodLyricTransitionPolicy.lerp(startScale, 1f, move)
                    view.scaleX = scale
                    view.scaleY = scale
                    view.alpha = AodLyricTransitionPolicy.crossfadeIn(move)
                }
            }
        }
        val ghosts = pending.ghosts.values.toList()
        ghosts.forEach { ghost ->
            if (ghost.exit == AodRowExit.EXIT_UP) ghost.endDy = -pending.exitTravel
        }
        fun frame(linear: Float) {
            val move = AodLyricTransitionPolicy.moveProgress(linear)
            val fade = AodLyricTransitionPolicy.fadeProgress(linear)
            steps.forEach { it(move, fade) }
            ghosts.forEach { ghost ->
                when (ghost.exit) {
                    AodRowExit.EXIT_UP -> {
                        ghost.dy = move * ghost.endDy
                        ghost.alpha = 1f - move
                    }
                    AodRowExit.FADE_OUT -> ghost.alpha = 1f - fade
                    AodRowExit.PROMOTE_SOURCE -> {
                        ghost.dx = move * ghost.endDx
                        ghost.dy = move * ghost.endDy
                        ghost.scale = AodLyricTransitionPolicy.lerp(1f, ghost.endScale, move)
                        ghost.alpha = AodLyricTransitionPolicy.crossfadeOut(move)
                    }
                    AodRowExit.NONE -> Unit
                }
            }
            running.layer.invalidateSelf()
        }
        frame(0f)
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = AodLyricTransitionPolicy.MOVE_DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (overlay.transition !== running) return@addUpdateListener
                frame(it.animatedValue as Float)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (overlay.transition === running) finish(overlay)
                }
            })
        }
        running.animator = animator
        overlay.drawWakeLock.acquire(NotificationMediaAodLyricHooker.DRAW_WAKE_LOCK_TIMEOUT_MS)
        animator.start()
    }

    /**
     * 立即结束转场并落到终点：所有行回到静止值，快照移除。没有转场时什么也不做。
     * 复位的唯一入口：先停动画再写回静止值，避免残余帧把中间值写回。
     */
    fun finish(overlay: LyricOverlay) {
        val running = overlay.transition ?: return
        overlay.transition = null
        running.preDraw?.let { listener ->
            running.observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
        }
        running.preDraw = null
        running.animator?.let { animator ->
            animator.removeAllUpdateListeners()
            animator.removeAllListeners()
            animator.cancel()
        }
        running.animator = null
        AodLyricRow.entries.forEach { row ->
            val view = viewOf(overlay, row)
            view.alpha = 1f
            view.translationX = 0f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
        }
        running.layerTypes.forEach { (view, type) -> view.setLayerType(type, null) }
        running.layerTypes.clear()
        // 快照不主动 recycle：渲染线程可能仍持有上一帧的绘制记录，交给 GC 回收。
        running.host.overlay.remove(running.layer)
        overlay.root.clipChildren = true
        overlay.root.invalidate()
    }

    private fun step(block: (move: Float, fade: Float) -> Unit) = block

    private fun snapshot(view: View): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    /** [view] 左上角在 [host] 坐标系中的位置。 */
    private fun offsetIn(view: View, host: View): Pair<Int, Int> {
        var x = 0
        var y = 0
        var current: View? = view
        while (current != null && current !== host) {
            x += current.left
            y += current.top
            current = current.parent as? View
        }
        return x to y
    }

    /** 旧内容某一行的快照，坐标相对歌词块（root）左上角。 */
    internal class Ghost(
        val bitmap: Bitmap,
        val left: Float,
        val top: Float,
        val exit: AodRowExit,
    ) {
        var pivotX = 0f
        var pivotY = 0f
        var dx = 0f
        var dy = 0f
        var scale = 1f
        var alpha = 1f
        var endDx = 0f
        var endDy = 0f
        var endScale = 1f
    }

    /** 画在媒体卡片 overlay 上的旧内容快照；上沿裁在歌词块顶部，与原先上浮退出的裁切一致。 */
    internal class GhostLayer(private val ghosts: List<Ghost>) : Drawable() {
        var originX = 0f
        var originY = 0f
        var clipTop = 0f
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: Canvas) {
            val save = canvas.save()
            canvas.clipRect(bounds.left.toFloat(), clipTop, bounds.right.toFloat(), bounds.bottom.toFloat())
            ghosts.forEach { ghost ->
                if (ghost.alpha <= 0f || ghost.bitmap.isRecycled) return@forEach
                val inner = canvas.save()
                canvas.translate(originX + ghost.left + ghost.dx, originY + ghost.top + ghost.dy)
                if (ghost.scale != 1f) canvas.scale(ghost.scale, ghost.scale, ghost.pivotX, ghost.pivotY)
                paint.alpha = (ghost.alpha.coerceIn(0f, 1f) * 255).toInt()
                canvas.drawBitmap(ghost.bitmap, 0f, 0f, paint)
                canvas.restoreToCount(inner)
            }
            canvas.restoreToCount(save)
        }

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
