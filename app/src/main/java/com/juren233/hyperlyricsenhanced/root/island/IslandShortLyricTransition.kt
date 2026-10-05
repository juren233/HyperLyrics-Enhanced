/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Picture
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.view.OneShotPreDrawListener
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.IRichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.view.SpaceGateRichLyricLineView
import com.juren233.hyperlyricsenhanced.lyric.view.line.SpaceGatePromotionRenderer
import com.juren233.hyperlyricsenhanced.lyric.view.line.SpaceGatePromotionGeometry
import com.juren233.hyperlyricsenhanced.lyric.view.line.SpaceGatePromotionSnapshot
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.util.WeakHashMap

/** One visible glyph trajectory through two fixed camera viewports, including role changes. */
internal object IslandShortLyricTransition {
    private val running = WeakHashMap<SpaceGateRichLyricLineView, Transition>()

    enum class UpdateResult(val contentChanged: Boolean) {
        NOT_HANDLED(false), CONTENT_APPLIED(true), GEOMETRY_ONLY(false),
    }

    fun isApplying(view: View): Boolean = pair(view)?.second?.let { running[it]?.applying } == true

    fun isRunning(view: View): Boolean = pair(view)?.second?.let { running.containsKey(it) } == true

    fun cancel(view: View) {
        pair(view)?.second?.let { running[it]?.finish() }
    }

    fun update(
        view: View,
        continuous: Boolean,
        key: String,
        duration: Long,
        incoming: IRichLyricLine?,
        allowRightPreviewHandoff: Boolean = false,
        apply: (SpaceGateRichLyricLineView, Boolean) -> Unit,
    ): UpdateResult {
        val (left, right) = pair(view) ?: return UpdateResult.NOT_HANDLED
        val requestKey = key
        val active = running[right]
        if (active?.key == requestKey) {
            // The shared pre-draw updates geometry after both slots have laid out. Reporting a
            // content change here makes each native width frame start another width transition.
            return UpdateResult.GEOMETRY_ONLY
        }
        val rightPreview = if (active == null && allowRightPreviewHandoff) {
            right.rightPreviewHandoffSnapshot(incoming)
        } else null
        if (active == null && rightPreview == null &&
            left.continuousSpaceGate == continuous && right.continuousSpaceGate == continuous
        ) return UpdateResult.NOT_HANDLED
        if (incoming == null || listOf(left, right).any {
                !it.isAttachedToWindow || !it.isShown || it.width <= 0 || it.height <= 0 || it.rawLine == null
            }) {
            active?.finish()
            return UpdateResult.NOT_HANDLED
        }

        val fallback = right.layoutRoleSnapshot(incoming = incoming) ?: run {
            active?.finish()
            return UpdateResult.NOT_HANDLED
        }
        val flying = active?.renderer?.snapshot()?.takeIf { it.text == fallback.text }
        val rightHandoff = rightPreview != null || (flying != null && active?.rightHandoff == true)
        val promoting = active == null && !right.isNextLinePromotionRunning &&
            right.willAnimateNextLinePromotion(incoming)
        val sameMain = active == null && !right.isNextLinePromotionRunning &&
            (right.main.drawnText == fallback.text)
        val source = flying ?: when {
            rightPreview != null -> rightPreview
            promoting -> right.layoutRoleSnapshot(preview = true)
            sameMain -> right.layoutRoleSnapshot()
            else -> null
        } ?: fallback
        val sourceAlpha = when {
            flying != null -> 1f // Its interpolated alpha is already in the snapshot paint.
            rightPreview != null -> 1f
            promoting -> right.secondary.alpha
            sameMain -> right.main.alpha
            else -> 0f
        }
        val oldFull = right.continuousSpaceGate
        val preserveLeftMetadata = IslandRightHandoffPolicy.preservesLeftMetadata(
            oldFull, continuous, rightHandoff, active?.preserveLeftMetadata,
        )
        val outgoing = if (rightHandoff) active?.outgoingSnapshot() ?: right.layoutRoleSnapshot() else null
        fun oldFrame(slot: SpaceGateRichLyricLineView): Picture {
            if (flying != null && active != null && !(slot === left && active.preserveLeftMetadata)) {
                return slot.recordLayoutRoleFrame(draw = { active.draw(it, slot, includeLyric = false) })
            }
            val lyricSlot = slot === right || oldFull
            return slot.recordLayoutRoleFrame(
                hideMain = (sameMain || rightHandoff) && lyricSlot,
                hideSecondary = promoting && lyricSlot,
            )
        }
        // Capture before cancelling, so a fast reversal starts at the visible frame, not the old target.
        val oldLeft = if (preserveLeftMetadata) null else oldFrame(left)
        val oldRight = oldFrame(right)
        active?.finish()
        val transition = Transition(
            left, right, requestKey, IslandRightHandoffPolicy.durationMillis(duration, rightHandoff || preserveLeftMetadata),
            incoming, source, fallback, sourceAlpha,
            oldLeft, oldRight, oldFull, continuous, promoting,
            rightHandoff, outgoing, preserveLeftMetadata,
        )
        running[right] = transition
        left.cancelLayoutRoleTransition = transition::finish
        right.cancelLayoutRoleTransition = transition::finish
        if (!preserveLeftMetadata) left.layoutRoleDrawing = { transition.draw(it, left) }
        right.layoutRoleDrawing = { transition.draw(it, right) }
        transition.bind(apply)
        return UpdateResult.CONTENT_APPLIED
    }

    private fun pair(view: View): Pair<SpaceGateRichLyricLineView, SpaceGateRichLyricLineView>? {
        val slot = view as? SpaceGateRichLyricLineView ?: return null
        val sibling = slot.main.siblingView?.parent as? SpaceGateRichLyricLineView ?: return null
        return if (slot.main.isRightSide) sibling to slot else slot to sibling
    }

    private class Transition(
        val left: SpaceGateRichLyricLineView,
        val right: SpaceGateRichLyricLineView,
        val key: String,
        val duration: Long,
        val incoming: IRichLyricLine,
        val source: SpaceGatePromotionSnapshot,
        val fallback: SpaceGatePromotionSnapshot,
        val sourceAlpha: Float,
        val oldLeft: Picture?,
        val oldRight: Picture,
        val oldFull: Boolean,
        val targetFull: Boolean,
        val promoting: Boolean,
        val rightHandoff: Boolean,
        val outgoingSource: SpaceGatePromotionSnapshot?,
        val preserveLeftMetadata: Boolean,
    ) {
        var applying = false
        var renderer: SpaceGatePromotionRenderer? = SpaceGatePromotionRenderer(
            source, source, 0, 0, right.layoutRoleMain(incoming), sourceAlpha,
        )
        private var outgoingRenderer: SpaceGatePromotionRenderer? = outgoingSource?.let {
            SpaceGatePromotionRenderer(it, it, 0, 0, right.layoutRoleMain(incoming), 1f)
        }

        fun outgoingSnapshot(): SpaceGatePromotionSnapshot? = outgoingRenderer?.snapshot()
        private var newLeft: Picture? = null
        private var newRight: Picture? = null
        private var animator: ValueAnimator? = null
        private var preDraw: OneShotPreDrawListener? = null
        private var geometryObserver: ViewTreeObserver? = null
        private var geometryPreDraw: ViewTreeObserver.OnPreDrawListener? = null
        private var finishAfterLayout = false
        private var closed = false
        private var fraction = 0f
        private var geometryStartFraction = 0f
        private val watchdog = Runnable { finish() }
        private val timing = if (BuildConfig.DEBUG) FrameTiming() else null

        fun bind(apply: (SpaceGateRichLyricLineView, Boolean) -> Unit) {
            applying = true
            try {
                left.applyLayoutRoleContent(keepLive = preserveLeftMetadata) { apply(left, !preserveLeftMetadata) }
                right.applyLayoutRoleContent { apply(right, true) }
                if (!preserveLeftMetadata) left.pauseLayoutRoleContent(true)
                right.pauseLayoutRoleContent(true)
                // The incoming view's actual layout supplies both endpoints, including row tops.
                preDraw = OneShotPreDrawListener.add(right) { begin() }
                right.postDelayed(watchdog, duration + 250L)
                invalidate()
            } catch (error: Exception) {
                finish()
                HookLogger.e("IslandShortLyricTransition", "Unable to prepare lyric morph", error)
            } finally {
                applying = false
            }
        }

        private fun begin() {
            preDraw = null
            if (closed) return
            val target = right.layoutRoleSnapshot() ?: return finish()
            fun matches(candidate: SpaceGatePromotionSnapshot): Boolean =
                candidate.text == target.text && candidate.geometry.glyphs.size == target.geometry.glyphs.size &&
                    candidate.geometry.glyphs.zip(target.geometry.glyphs).all { (a, b) ->
                        a.charStart == b.charStart && a.charEnd == b.charEnd
                    }
            val original = source.takeIf(::matches) ?: fallback.takeIf(::matches) ?: return finish()
            val start = rebase(original, target)
            renderer = SpaceGatePromotionRenderer(
                start, target, 0, 0, right.layoutRoleMain(incoming),
                if (original === source) sourceAlpha else 0f,
            ).also { it.prepareShadows(left.main.textPaint, right.main.textPaint) }
            outgoingRenderer = outgoingSource?.let { outgoing ->
                movingOutgoing(rebase(outgoing, target), start, target)
            }
            newLeft = if (preserveLeftMetadata) null else
                left.recordLayoutRoleFrame(hideMain = targetFull, includeTransition = false)
            newRight = right.recordLayoutRoleFrame(hideMain = true, includeTransition = false)
            if (BuildConfig.DEBUG) HookLogger.d("SwitchTrace",
                "role morph begin full=$oldFull->$targetFull preview=$promoting rightHandoff=$rightHandoff " +
                    "leftLive=$preserveLeftMetadata duration=$duration " +
                    "from=${start.geometry.glyphs.firstOrNull()?.start} to=${target.geometry.glyphs.firstOrNull()?.start} " +
                    "seam=${target.geometry.seam} target=${target.geometry.hashCode()}")
            geometryPreDraw = ViewTreeObserver.OnPreDrawListener {
                if (!closed) {
                    refreshGeometry()
                    // ValueAnimator ends before traversal. Hand back to normal drawing only
                    // after consuming the same layout that the final visible frame will use.
                    if (finishAfterLayout) finish()
                }
                true
            }.also { listener ->
                geometryObserver = right.viewTreeObserver.also { it.addOnPreDrawListener(listener) }
            }
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                // Match the existing next-line promotion's duration and platform interpolator.
                this.duration = this@Transition.duration
                addUpdateListener {
                    if (BuildConfig.DEBUG) timing?.frame(System.nanoTime())
                    fraction = it.animatedValue as Float
                    renderer?.fraction = ((fraction - geometryStartFraction) / (1f - geometryStartFraction))
                        .coerceIn(0f, 1f)
                    outgoingRenderer?.fraction = renderer?.fraction ?: 0f
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        finishAfterLayout = true
                        invalidate()
                    }
                })
                start()
            }
        }

        /** Native island resizing retargets the remaining path without restarting content or the clock. */
        private fun refreshGeometry() {
            if (closed || preDraw != null) return
            val current = renderer ?: return
            val seam = left.main.scrollWidth.toFloat()
            val width = seam + right.main.scrollWidth
            if (!finishAfterLayout && current.geometry.target.seam == seam && current.geometry.target.viewWidth == width) return
            val started = if (BuildConfig.DEBUG) System.nanoTime() else 0L
            val target = right.layoutRoleSnapshot() ?: return
            if (current.geometry.target == target.geometry) return
            val visible = rebase(current.snapshot(), target)
            val outgoing = outgoingRenderer?.snapshot()?.let { rebase(it, target) }
            renderer = SpaceGatePromotionRenderer(visible, target, 0, 0, right.layoutRoleMain(incoming), 1f).apply {
                if (finishAfterLayout) this.fraction = 1f
                prepareShadows(left.main.textPaint, right.main.textPaint)
            }
            outgoingRenderer = outgoing?.let { movingOutgoing(it, visible, target) }?.apply {
                if (finishAfterLayout) this.fraction = 1f
            }
            geometryStartFraction = fraction
            newLeft = if (preserveLeftMetadata) null else
                left.recordLayoutRoleFrame(hideMain = targetFull, includeTransition = false)
            newRight = right.recordLayoutRoleFrame(hideMain = true, includeTransition = false)
            if (BuildConfig.DEBUG) timing?.retarget(System.nanoTime() - started)
            invalidate()
        }

        private fun rebase(source: SpaceGatePromotionSnapshot, target: SpaceGatePromotionSnapshot) =
            source.copy(geometry = SpaceGatePromotionGeometry.rebaseCamera(
                source.geometry, target.geometry.viewWidth, target.geometry.seam,
            ))

        private fun movingOutgoing(
            outgoing: SpaceGatePromotionSnapshot,
            preview: SpaceGatePromotionSnapshot,
            target: SpaceGatePromotionSnapshot,
        ): SpaceGatePromotionRenderer {
            val shift = (target.geometry.glyphs.firstOrNull()?.start ?: 0f) -
                (preview.geometry.glyphs.firstOrNull()?.start ?: 0f)
            val end = outgoing.copy(geometry = SpaceGatePromotionGeometry.translated(outgoing.geometry, shift))
            return SpaceGatePromotionRenderer(outgoing, end, 0, 0, right.layoutRoleMain(incoming), 1f)
                .also { it.prepareShadows(left.main.textPaint, right.main.textPaint) }
        }

        fun draw(canvas: Canvas, slot: SpaceGateRichLyricLineView, includeLyric: Boolean = true) {
            // An independent metadata slot keeps its own live drawing and marquee. The
            // outgoing lyric must also remain clipped to the right during a short-to-short handoff.
            if (slot === left && preserveLeftMetadata) return
            val started = if (BuildConfig.DEBUG) System.nanoTime() else 0L
            val rightSide = slot === right
            val old = if (rightSide) oldRight else oldLeft
            val next = if (rightSide) newRight else newLeft
            val save = canvas.save()
            canvas.clipRect(0, 0, slot.width, slot.height)
            // The moving lyric stays visible. Only the outgoing line and the metadata trade opacity.
            old?.let {
                drawPicture(canvas, slot, it, 1f - fraction,
                    y = if (promoting && (rightSide || oldFull)) -slot.main.height * 0.65f * fraction else 0f,
                    x = if (!rightSide) slot.width - it.width -
                        (if (!oldFull) slot.width * 0.12f * fraction else 0f) else 0f)
            }
            next?.let {
                drawPicture(canvas, slot, it, fraction,
                    x = if (!rightSide && !targetFull) -slot.width * 0.12f * (1f - fraction) else 0f)
            }
            if (includeLyric) {
                outgoingRenderer?.let { outgoing ->
                    // Both sentences travel the same distance. In the short-line layout,
                    // let the old lyric yield to the incoming left-side song information.
                    val layer = if (!targetFull) canvas.saveLayerAlpha(0f, 0f, slot.width.toFloat(),
                        slot.height.toFloat(), (255 * (1f - fraction)).toInt()) else canvas.save()
                    outgoing.draw(canvas, rightSide, slot.width, slot.height,
                        left.main.scrollWidth.toFloat(), shadowStyle = slot.main.textPaint)
                    canvas.restoreToCount(layer)
                }
                renderer?.draw(canvas, rightSide, slot.width, slot.height,
                    left.main.scrollWidth.toFloat(), shadowStyle = slot.main.textPaint)
            }
            canvas.restoreToCount(save)
            if (BuildConfig.DEBUG) timing?.draw(System.nanoTime() - started)
        }

        private fun drawPicture(canvas: Canvas, slot: View, picture: Picture, alpha: Float, x: Float = 0f, y: Float = 0f) {
            if (alpha <= 0f) return
            val layer = canvas.saveLayerAlpha(0f, 0f, slot.width.toFloat(), slot.height.toFloat(), (255 * alpha).toInt())
            canvas.translate(x, y)
            canvas.drawPicture(picture)
            canvas.restoreToCount(layer)
        }

        private fun invalidate() {
            left.invalidate()
            right.invalidate()
        }

        fun finish() {
            if (closed) return
            closed = true
            animator?.removeAllListeners()
            animator?.removeAllUpdateListeners()
            animator?.cancel()
            animator = null
            preDraw?.removeListener()
            preDraw = null
            geometryPreDraw?.let { listener ->
                geometryObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
            }
            geometryPreDraw = null
            geometryObserver = null
            right.removeCallbacks(watchdog)
            if (BuildConfig.DEBUG && renderer != null) HookLogger.d("SwitchTrace",
                "role morph end completed=${fraction >= 1f} full=$targetFull rightHandoff=$rightHandoff " +
                    "leftLive=$preserveLeftMetadata " +
                    "same=${renderer?.geometry?.target == right.layoutRoleSnapshot()?.geometry} $timing")
            left.layoutRoleDrawing = null
            right.layoutRoleDrawing = null
            left.cancelLayoutRoleTransition = null
            right.cancelLayoutRoleTransition = null
            running.remove(right)
            if (!preserveLeftMetadata) left.pauseLayoutRoleContent(false)
            right.pauseLayoutRoleContent(false)
            IslandShortLyricLayout.onMorphFinished(right)
            invalidate()
        }
    }

    /** Per-transition aggregates only; no allocation or sampling is enabled in Release. */
    private class FrameTiming {
        private var frames = 0
        private var previousFrame = 0L
        private var maxGap = 0L
        private var maxDraw = 0L
        private var retargets = 0
        private var maxRetarget = 0L

        fun frame(now: Long) {
            if (previousFrame != 0L) maxGap = maxOf(maxGap, now - previousFrame)
            previousFrame = now
            frames++
        }

        fun draw(elapsed: Long) { maxDraw = maxOf(maxDraw, elapsed) }

        fun retarget(elapsed: Long) {
            retargets++
            maxRetarget = maxOf(maxRetarget, elapsed)
        }

        override fun toString() = "frames=$frames maxGapMs=${maxGap / 1_000_000f} " +
            "maxDrawMs=${maxDraw / 1_000_000f} retargets=$retargets maxRetargetMs=${maxRetarget / 1_000_000f}"
    }
}
