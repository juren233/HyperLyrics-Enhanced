/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.graphics.drawable.Drawable
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import com.juren233.hyperlyricsenhanced.lyric.view.InterludeMotion
import com.juren233.hyperlyricsenhanced.lyric.view.yoyo.YoYoAnimation
import java.util.WeakHashMap

/**
 * 「音频封面」关闭时，极简显示开始/结束带出的封面进出场：淡入、淡出，岛宽只随占位变化一次。
 *
 * - 出现：先等左槽歌词在原位淡出落定（[deferEntry]，此前不放出封面占位，歌词不被挤动），
 *   再让封面容器以透明状态占位（岛宽随之由原生动画伸展）并淡入；渐变样式的封面背景
 *   （[EmbeddedIslandAlbumCoverDrawable]）读同一进度一起淡入。
 * - 消失：封面占位立即收起（歌词与岛宽一次落到最终布局），封面以快照在原位淡出（挂在封面容器
 *   父布局的覆盖层上，不参与布局）；渐变样式保持到淡出结束才撤回，背景读同一进度淡出。渐变样式的
 *   撤回在宿主形变期间会被暂存到形变结束（见 IslandContentUpdateCoordinator.deferAction），此前已
 *   挂上的封面背景按 [presenceFor] 保持隐藏，直到真正撤回、重新套用或再次进场。
 *
 * 只在主线程调用。时长与歌词的间奏提升共用 [InterludeMotion.TRANSITION_DURATION_MS]，插值沿用
 * 属性动画默认的先加速后减速（与间奏提升一致）。
 */
internal object IslandMinimalCoverTransition {
    private const val ICON_CONTAINER_NAME = "island_container_module_icon"

    private val hosts = WeakHashMap<ViewGroup, Unit>()
    private val snapshots = WeakHashMap<ViewGroup, CoverSnapshot>()
    private var animator: ValueAnimator? = null
    private var generation = 0

    /** 封面显示进度：1 为完全显示（没有转场时恒为 1），0 为完全隐藏。 */
    @Volatile
    var presence: Float = 1f
        private set

    /** 正在淡出：此间封面已收起占位，快照与样式仍在，见 [IslandSlotContentAssembler.isAlbumStyled]。 */
    @Volatile
    var hiding: Boolean = false
        private set

    private class PendingEntry(val root: ViewGroup, val onReady: () -> Unit) {
        lateinit var callback: Choreographer.FrameCallback
    }

    private var entry: PendingEntry? = null

    /** 封面将要出现、正等左槽歌词淡出落定：此间不放出封面占位，见 [IslandSlotContentAssembler.isAlbumPlaced]。 */
    val entryPending: Boolean
        get() = entry != null

    /**
     * 封面将要出现：左槽歌词还在原位淡出时先挂起，等它淡出落定（切换动画的新内容已写入）再执行
     * [onReady]（放出占位、[prepareShow]、套样式并 [startShow]）。没有正在淡出的歌词时下一帧即执行。
     * 淡出途中反向（封面还在淡出）直接执行，从当前进度接着淡入。
     */
    fun deferEntry(root: ViewGroup, onReady: () -> Unit) {
        if (hiding) {
            onReady()
            return
        }
        stopAnimator()
        cancelEntry()
        removeSnapshots()
        // 挂起期间渐变背景可能已被样式刷新挂上，保持不可见，等进场再淡入。
        presence = 0f
        hosts[root] = Unit
        applyFrame()
        val pending = PendingEntry(root, onReady)
        pending.callback = Choreographer.FrameCallback {
            if (entry !== pending) return@FrameCallback
            val left = root.findViewWithTag<View>(IslandProbeUtils.LEFT_TEST_VIEW_TAG)
            if (left != null && left.isAttachedToWindow && YoYoAnimation.isAwaitingContent(left)) {
                Choreographer.getInstance().postFrameCallback(pending.callback)
                return@FrameCallback
            }
            entry = null
            pending.onReady()
        }
        entry = pending
        // 同一次内容更新随后才启动左槽的切换动画，下一帧再检查。
        Choreographer.getInstance().postFrameCallback(pending.callback)
    }

    private fun cancelEntry() {
        val pending = entry ?: return
        entry = null
        Choreographer.getInstance().removeFrameCallback(pending.callback)
    }

    /** 淡出结束的次数；在此之后既没有重新套用、也没有再次进场的渐变封面背景等待撤回，不再绘制。 */
    private var withdrawnGeneration = 0

    /** 再次进场或复位时对齐 [withdrawnGeneration]：等待撤回的封面背景重新按 [presence] 绘制。 */
    private var revealedGeneration = 0

    /** 渐变封面背景挂上或重新套用时记下的代际，交给 [presenceFor]。 */
    fun coverGeneration(): Int = withdrawnGeneration

    /** 渐变封面背景的显示进度：淡出结束后、真正撤回之前为 0。 */
    fun presenceFor(shownGeneration: Int): Float =
        if (maxOf(shownGeneration, revealedGeneration) < withdrawnGeneration) 0f else presence

    /**
     * 封面将要出现：在容器显示之前调用，让它以透明状态出现；淡出途中反向时从当前进度接着来。
     */
    fun prepareShow(root: ViewGroup) {
        val wasHiding = hiding
        stopAnimator()
        cancelEntry()
        hiding = false
        revealedGeneration = withdrawnGeneration
        // 淡出途中反向：容器重新占位并从当前进度淡入，快照不再需要。
        removeSnapshots()
        if (!wasHiding) presence = 0f
        hosts[root] = Unit
        applyFrame()
    }

    /** 封面已显示（容器可见、样式已套上）：淡入到完全显示。 */
    fun startShow() {
        // 准备出现后、开始淡入前又转为消失（前奏/间奏极短）：交给淡出处理。
        if (hiding) return
        animateTo(1f) {
            presence = 1f
            applyFrame()
        }
    }

    /**
     * 封面将要消失：在容器收起之前调用，先把封面拍成快照留在原位，容器随后按目标显隐立即收起；
     * 快照与渐变背景淡出，结束后执行 [onHidden]（撤回样式、刷新岛宽）。
     */
    fun startHide(root: ViewGroup, onHidden: () -> Unit) {
        stopAnimator()
        if (entry != null) {
            // 封面还没放出就又要消失（前奏/间奏极短、切歌时来回切换）：取消进场。挂起期间样式可能已套上
            // （渐变背景以 0 进度挂着），按淡出已结束处理：封住已挂上的背景、撤回样式，再复位常态。
            cancelEntry()
            withdrawnGeneration++
            onHidden()
            presence = 1f
            applyFrame()
            return
        }
        hosts[root] = Unit
        if (snapshots[root] == null) capture(root)?.let { snapshots[root] = it }
        hiding = true
        animateTo(0f) {
            hiding = false
            removeSnapshots()
            // 先封住已挂上的渐变封面背景：撤回可能被暂存到宿主形变结束，复位进度后它不能以完整封面闪现。
            withdrawnGeneration++
            onHidden()
            // 容器已隐藏、样式撤回已发出，复位为常态，不影响下次「音频封面」开启时的显示。
            presence = 1f
            applyFrame()
        }
    }

    /** 取消转场并复位为常态（设置变更、宿主清理时）。 */
    fun cancel() {
        if (animator == null && !hiding && presence == 1f && revealedGeneration == withdrawnGeneration &&
            snapshots.isEmpty() && entry == null
        ) return
        stopAnimator()
        cancelEntry()
        hiding = false
        revealedGeneration = withdrawnGeneration
        removeSnapshots()
        presence = 1f
        applyFrame()
    }

    private fun animateTo(target: Float, onEnd: () -> Unit) {
        stopAnimator()
        val from = presence
        val token = ++generation
        val distance = kotlin.math.abs(target - from)
        if (distance <= 0f) {
            onEnd()
            return
        }
        animator = ValueAnimator.ofFloat(from, target).apply {
            duration = (InterludeMotion.TRANSITION_DURATION_MS * distance).toLong()
            addUpdateListener {
                if (token != generation) return@addUpdateListener
                presence = it.animatedValue as Float
                applyFrame()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (token != generation) return
                    animator = null
                    onEnd()
                }
            })
            start()
        }
    }

    private fun stopAnimator() {
        generation++
        animator?.cancel()
        animator = null
    }

    private fun applyFrame() {
        hosts.keys.toList().forEach { root ->
            iconContainer(root)?.apply {
                alpha = presence
            }
        }
        val alpha = (255f * presence).toInt().coerceIn(0, 255)
        snapshots.values.forEach { it.alpha = alpha }
        EmbeddedIslandAlbumCoverController.invalidateBigCovers()
    }

    /** 把当前可见的封面容器录成快照挂到其父布局的覆盖层，位置与容器一致。 */
    private fun capture(root: ViewGroup): CoverSnapshot? {
        val container = iconContainer(root) ?: return null
        val host = container.parent as? ViewGroup ?: return null
        if (!container.isShown || container.width <= 0 || container.height <= 0) return null
        val node = RenderNode("HyperLyricsMinimalCover").apply {
            setPosition(container.left, container.top, container.right, container.bottom)
        }
        val canvas = node.beginRecording(container.width, container.height)
        try {
            container.draw(canvas)
        } finally {
            node.endRecording()
        }
        return CoverSnapshot(host, node).apply {
            setBounds(container.left, container.top, container.right, container.bottom)
            alpha = (255f * presence).toInt().coerceIn(0, 255)
            host.overlay.add(this)
        }
    }

    private fun removeSnapshots() {
        if (snapshots.isEmpty()) return
        snapshots.values.forEach { it.detach() }
        snapshots.clear()
    }

    /** 封面快照：录制时的子视图绘制（含其圆形/圆角裁剪）按进度整体淡出。 */
    private class CoverSnapshot(
        private val host: ViewGroup,
        private val node: RenderNode,
    ) : Drawable() {
        private var currentAlpha = 255

        override fun draw(canvas: Canvas) {
            if (canvas.isHardwareAccelerated && node.hasDisplayList()) canvas.drawRenderNode(node)
        }

        override fun setAlpha(alpha: Int) {
            if (currentAlpha == alpha) return
            currentAlpha = alpha
            node.alpha = alpha / 255f
            invalidateSelf()
        }

        override fun getAlpha(): Int = currentAlpha

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        fun detach() {
            host.overlay.remove(this)
            node.discardDisplayList()
        }
    }

    private fun iconContainer(root: ViewGroup): View? {
        val parent = IslandViewHelper.findViewByName(root, IslandProbeUtils.LEFT_PARENT_NAME) as? ViewGroup
            ?: return null
        return IslandViewHelper.findViewByName(parent, ICON_CONTAINER_NAME)
    }
}
