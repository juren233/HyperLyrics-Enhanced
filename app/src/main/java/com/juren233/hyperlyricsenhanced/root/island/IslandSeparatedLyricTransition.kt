/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Picture
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.core.view.OneShotPreDrawListener
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.IRichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.view.RichLyricLineView
import com.juren233.hyperlyricsenhanced.lyric.view.line.SpaceGatePromotionGeometry
import com.juren233.hyperlyricsenhanced.lyric.view.line.SpaceGatePromotionRenderer
import com.juren233.hyperlyricsenhanced.lyric.view.line.SpaceGatePromotionSnapshot
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.util.WeakHashMap

/** The full-island glyph morph, applied to two independently split Rich lyric rows. */
internal object IslandSeparatedLyricTransition {
    private val roles = WeakHashMap<RichLyricLineView, Boolean>()
    private val running = WeakHashMap<RichLyricLineView, Transition>()

    fun contentApplied(view: View, split: Boolean?) {
        if (view !is RichLyricLineView) return
        if (split == null) roles.remove(view) else roles[view] = split
    }

    fun isApplying(view: View): Boolean = running[view]?.applying == true

    fun cancel(view: View) { running[view]?.finish() }

    fun forget(view: View) {
        cancel(view)
        roles.remove(view)
    }

    fun update(
        view: View,
        split: Boolean,
        key: String,
        duration: Long,
        wholeLine: IRichLyricLine?,
        incoming: (RichLyricLineView, Boolean) -> IRichLyricLine?,
        apply: (RichLyricLineView, IRichLyricLine?) -> Unit,
    ): IslandShortLyricTransition.UpdateResult {
        val (left, right) = pair(view) ?: return IslandShortLyricTransition.UpdateResult.NOT_HANDLED
        val active = running[right]
        if (active?.key == key) return IslandShortLyricTransition.UpdateResult.GEOMETRY_ONLY
        val lines = listOf(incoming(left, true), incoming(right, false))
        val preview = left.willAnimateNextLinePromotion(lines[0]) || right.willAnimateNextLinePromotion(lines[1])
        if (active == null && !IslandSeparatedMorphGeometry.shouldStart(roles[left], roles[right], split, preview)) {
            return IslandShortLyricTransition.UpdateResult.NOT_HANDLED
        }
        if (listOf(left, right).any { !it.isAttachedToWindow || !it.isShown || it.width <= 0 || it.height <= 0 || it.rawLine == null }) {
            active?.finish()
            return IslandShortLyricTransition.UpdateResult.NOT_HANDLED
        }
        val transition = Transition(left, right, key, duration, roles[right] == true, split, lines, wholeLine)
        transition.capture(active)
        active?.finish()
        running[left] = transition
        running[right] = transition
        left.cancelLayoutRoleTransition = transition::finish
        right.cancelLayoutRoleTransition = transition::finish
        left.layoutRoleDrawing = { transition.draw(it, left) }
        right.layoutRoleDrawing = { transition.draw(it, right) }
        transition.bind(apply)
        return IslandShortLyricTransition.UpdateResult.CONTENT_APPLIED
    }

    private fun pair(view: View): Pair<RichLyricLineView, RichLyricLineView>? {
        if (view !is RichLyricLineView) return null
        var parent = view.parent as? ViewGroup
        while (parent != null) {
            val left = parent.findViewWithTag<View>(IslandProbeUtils.LEFT_TEST_VIEW_TAG) as? RichLyricLineView
            val right = parent.findViewWithTag<View>(IslandProbeUtils.RIGHT_TEST_VIEW_TAG) as? RichLyricLineView
            if (left != null && right != null && (view === left || view === right)) return left to right
            parent = parent.parent as? ViewGroup
        }
        return null
    }

    private data class Source(
        val snapshot: SpaceGatePromotionSnapshot,
        val clip: IslandSeparatedMorphGeometry.Clip,
        val alpha: Float,
        val slot: RichLyricLineView? = null,
        val preview: Boolean = false,
    )

    private class Track(
        val side: Int,
        var source: Source,
        val outgoing: Boolean = false,
        val advanceBefore: Float = 0f,
        val totalAdvance: Float = 0f,
        var renderer: SpaceGatePromotionRenderer? = null,
    ) {
        val original = source.snapshot
        var targetClip = source.clip
        fun visible(): Source = renderer?.let {
            Source(it.snapshot(), source.clip.towards(targetClip, it.fraction), 1f)
        } ?: source
    }

    private class LayoutStamp(slot: RichLyricLineView) {
        private val width = slot.width
        private val height = slot.height
        private val mainWidth = slot.main.width
        private val mainHeight = slot.main.height
        private val mainTop = slot.main.top
        private val secondaryTop = slot.secondary.top
        fun matches(slot: RichLyricLineView) = width == slot.width && height == slot.height &&
            mainWidth == slot.main.width && mainHeight == slot.main.height &&
            mainTop == slot.main.top && secondaryTop == slot.secondary.top
    }

    private class Transition(
        val left: RichLyricLineView,
        val right: RichLyricLineView,
        val key: String,
        val duration: Long,
        val oldSplit: Boolean,
        val split: Boolean,
        val lines: List<IRichLyricLine?>,
        val wholeLine: IRichLyricLine?,
    ) {
        var applying = false
        private val slots = listOf(left, right)
        private val tracks = mutableListOf<Track>()
        private var oldFrames = emptyList<Picture>()
        private var newFrames = emptyList<Picture>()
        private var animator: ValueAnimator? = null
        private var preDraw: OneShotPreDrawListener? = null
        private var observer: ViewTreeObserver? = null
        private var geometryListener: ViewTreeObserver.OnPreDrawListener? = null
        private var closed = false
        private var finishRequestedWhileApplying = false
        private var fraction = 0f
        private var geometryStart = 0f
        private var finishAfterLayout = false
        private var leftLayout: LayoutStamp? = null
        private var rightLayout: LayoutStamp? = null
        private val watchdog = Runnable { finish() }

        private fun snapshot(slot: RichLyricLineView, preview: Boolean = false, incoming: IRichLyricLine? = null): SpaceGatePromotionSnapshot? =
            slot.layoutRoleSnapshot(preview, incoming)?.let { it.copy(geometry = SpaceGatePromotionGeometry.inIsland(
                it.geometry, left.width.toFloat(), right.width.toFloat(), slot === right,
            )) }

        private fun clip(side: Int, preview: Boolean = false): IslandSeparatedMorphGeometry.Clip {
            val row = if (preview) slots[side].secondary else slots[side].main
            val start = (if (side == 1) left.width else 0) + row.left
            return IslandSeparatedMorphGeometry.Clip(start.toFloat(), (start + row.scrollWidth).toFloat())
        }

        fun capture(active: Transition?) {
            val reused = mutableSetOf<Track>()
            val predicted = slots.mapIndexed { index, slot ->
                if (index == 0 && !split) null else lines[index]?.let { snapshot(slot, incoming = it) }
            }
            val wholeText = predicted.joinToString("") { it?.text.orEmpty() }
            val rightPreview = snapshot(right, preview = true)
            val rightMain = snapshot(right)
            var offset = 0
            for ((side, target) in predicted.withIndex()) {
                target ?: continue
                fun candidate(source: Source?): Source? = source?.takeIf {
                    it.snapshot.text == target.text && IslandSeparatedMorphGeometry.matches(it.snapshot.geometry, target.geometry)
                }
                val flying = active?.tracks?.firstNotNullOfOrNull { track ->
                    if (track.outgoing) null else candidate(track.visible())?.also { reused += track }
                }
                val existing = if (oldSplit == split) {
                    val slot = slots[side]
                    candidate(snapshot(slot, preview = true)?.let { Source(it, clip(side, true), slot.secondary.alpha * slot.alpha, slot, true) })
                        ?: candidate(snapshot(slot)?.let { Source(it, clip(side), slot.main.alpha * slot.alpha, slot) })
                } else if (split) {
                    fun part(source: SpaceGatePromotionSnapshot?, preview: Boolean): Source? {
                        source ?: return null
                        if (source.text != wholeText) return null
                        val geometry = IslandSeparatedMorphGeometry.slice(source.geometry, offset, offset + target.text.length) ?: return null
                        return candidate(Source(source.copy(text = target.text, geometry = geometry), clip(1, preview),
                            (if (preview) right.secondary.alpha else right.main.alpha) * right.alpha, right, preview))
                    }
                    part(rightPreview, true) ?: part(rightMain, false)
                } else null
                val fallback = if (split) {
                    val whole = wholeLine?.let { snapshot(right, incoming = it) }
                    val geometry = whole?.takeIf { it.text == wholeText }?.let {
                        IslandSeparatedMorphGeometry.slice(it.geometry, offset, offset + target.text.length)
                    }
                    candidate(whole?.takeIf { geometry != null }?.copy(text = target.text, geometry = geometry!!)
                        ?.let { Source(it, clip(1), 0f) })
                } else {
                    candidate(lines[side]?.let { snapshot(left, incoming = it) }?.let { Source(it, clip(0), 0f) })
                }
                val source = flying ?: existing ?: fallback ?: Source(
                    target.copy(geometry = SpaceGatePromotionGeometry.translated(target.geometry,
                        if (oldSplit != split) (if (split) left.width else -left.width).toFloat() else 0f)),
                    clip(if (oldSplit != split) 1 - side else side), 0f,
                )
                tracks += Track(side, source).also { track ->
                    track.renderer = SpaceGatePromotionRenderer(source.snapshot, source.snapshot, 0, 0,
                        slots[side].layoutRoleMain(lines[side]), source.alpha).apply {
                        prepareShadows(left.main.textPaint, right.main.textPaint)
                    }
                }
                offset += target.text.length
            }
            if (!oldSplit && split && active == null) {
                // A single right-row picture cannot hide only half of a grouped glyph.
                // If either slice is unsafe, retain the complete old row through the fade.
                for (previewRow in listOf(false, true)) {
                    val borrowed = tracks.filter { it.source.slot === right && it.source.preview == previewRow }
                    if (borrowed.isNotEmpty() && borrowed.size != tracks.size) for (track in borrowed) {
                        track.source = track.source.copy(alpha = 0f, slot = null)
                        track.renderer = SpaceGatePromotionRenderer(track.source.snapshot, track.source.snapshot, 0, 0,
                            slots[track.side].layoutRoleMain(lines[track.side]), 0f)
                    }
                }
            }
            // On entry to a gap the outgoing two halves contract towards the right slot,
            // while the exact native dots and the left metadata enter behind them.
            if (oldSplit && !split && active == null) {
                val outgoing = slots.map { snapshot(it) }
                fun advance(snapshot: SpaceGatePromotionSnapshot?) = snapshot?.geometry?.glyphs?.let {
                    (it.lastOrNull()?.naturalEnd ?: 0f) - (it.firstOrNull()?.naturalStart ?: 0f)
                } ?: 0f
                val total = outgoing.sumOf { advance(it).toDouble() }.toFloat()
                var before = 0f
                for ((side, source) in outgoing.withIndex()) {
                    source ?: continue
                    tracks += Track(1, Source(source, clip(side), slots[side].main.alpha * slots[side].alpha,
                        slots[side]), outgoing = true, advanceBefore = before, totalAdvance = total).also { track ->
                        track.renderer = SpaceGatePromotionRenderer(source, source, 0, 0,
                            slots[side].layoutRoleMain(slots[side].rawLine), track.source.alpha).apply {
                            prepareShadows(left.main.textPaint, right.main.textPaint)
                        }
                    }
                    before += advance(source)
                }
            }
            oldFrames = slots.map { slot ->
                if (active != null) slot.recordLayoutRoleFrame(draw = { active.draw(it, slot, excludedTracks = reused) })
                else slot.recordLayoutRoleFrame(
                    hideMain = tracks.any { it.source.slot === slot && !it.source.preview },
                    hideSecondary = tracks.any { it.source.slot === slot && it.source.preview },
                )
            }
        }

        fun bind(apply: (RichLyricLineView, IRichLyricLine?) -> Unit) {
            applying = true
            try {
                slots.forEachIndexed { index, slot -> slot.applyLayoutRoleContent { apply(slot, lines[index]) } }
                slots.forEach { it.pauseLayoutRoleContent(true) }
                preDraw = OneShotPreDrawListener.add(right) { begin() }
                right.postDelayed(watchdog, duration + 250L)
                invalidate()
            } catch (error: Exception) {
                finish()
                HookLogger.e("IslandSeparatedLyricTransition", "Unable to prepare lyric morph", error)
            } finally {
                applying = false
                if (finishRequestedWhileApplying) finish()
            }
        }

        private fun begin() {
            preDraw = null
            if (closed) return
            retarget(initial = true)
            if (BuildConfig.DEBUG) HookLogger.d("SwitchTrace", "separated morph begin split=$oldSplit->$split " +
                "tracks=${tracks.size} preview=${tracks.any { it.source.preview }} duration=$duration")
            geometryListener = ViewTreeObserver.OnPreDrawListener {
                if (!closed) {
                    if (finishAfterLayout || leftLayout?.matches(left) != true || rightLayout?.matches(right) != true) {
                        retarget(initial = false)
                    }
                    if (finishAfterLayout) finish()
                }
                true
            }.also { listener -> observer = right.viewTreeObserver.also { it.addOnPreDrawListener(listener) } }
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                this.duration = this@Transition.duration
                addUpdateListener {
                    fraction = it.animatedValue as Float
                    val local = ((fraction - geometryStart) / (1f - geometryStart).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
                    tracks.forEach { track -> track.renderer?.fraction = local }
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { finishAfterLayout = true; invalidate() }
                })
                start()
            }
        }

        private fun retarget(initial: Boolean) {
            for (track in tracks) {
                val slot = slots[track.side]
                val target = if (track.outgoing) track.original.copy(
                    geometry = IslandSeparatedMorphGeometry.gatherRight(track.original.geometry,
                        track.advanceBefore, track.totalAdvance, left.width.toFloat(), right.width.toFloat()),
                    baseline = right.main.top + right.main.measuredHeight / 2f -
                        (track.original.paint.fontMetrics.descent + track.original.paint.fontMetrics.ascent) / 2f,
                ) else snapshot(slot) ?: continue
                val visible = if (initial) track.source else track.visible()
                val compatible = visible.snapshot.text == target.text && IslandSeparatedMorphGeometry.matches(visible.snapshot.geometry, target.geometry)
                val raw = if (compatible) visible else Source(target, clip(track.side), 0f)
                val delta = target.geometry.seam - raw.snapshot.geometry.seam
                track.source = raw.copy(
                    snapshot = raw.snapshot.copy(geometry = SpaceGatePromotionGeometry.rebaseCamera(
                        raw.snapshot.geometry, target.geometry.viewWidth, target.geometry.seam,
                    )),
                    clip = raw.clip.shifted(delta),
                )
                track.targetClip = clip(track.side)
                track.renderer = SpaceGatePromotionRenderer(track.source.snapshot, target, 0, 0,
                    slot.layoutRoleMain(lines[track.side]), track.source.alpha).apply {
                    prepareShadows(left.main.textPaint, right.main.textPaint)
                    if (finishAfterLayout) fraction = 1f
                }
            }
            geometryStart = fraction
            leftLayout = LayoutStamp(left)
            rightLayout = LayoutStamp(right)
            newFrames = slots.mapIndexed { index, slot -> slot.recordLayoutRoleFrame(
                hideMain = tracks.any { !it.outgoing && it.side == index && it.renderer != null },
            ) }
        }

        fun draw(canvas: Canvas, slot: RichLyricLineView, excludedTracks: Set<Track> = emptySet()) {
            val side = if (slot === right) 1 else 0
            val save = canvas.save()
            canvas.clipRect(0, 0, slot.width, slot.height)
            oldFrames.getOrNull(side)?.let { frame ->
                picture(canvas, slot, frame, 1f - fraction,
                    x = if (side == 0) slot.width - frame.width - (if (!oldSplit) slot.width * 0.12f * fraction else 0f) else 0f,
                    y = if (tracks.any { it.source.preview }) -slot.main.height * 0.65f * fraction else 0f)
            }
            newFrames.getOrNull(side)?.let { frame ->
                picture(canvas, slot, frame, fraction,
                    x = if (!split) -slot.width * 0.12f * (1f - fraction) else 0f)
            }
            for (track in tracks) {
                if (track in excludedTracks) continue
                val renderer = track.renderer ?: continue
                val clip = track.source.clip.towards(track.targetClip, renderer.fraction)
                val origin = if (side == 1) left.width.toFloat() else 0f
                val clipped = canvas.save()
                canvas.clipRect(clip.start - origin, 0f, clip.end - origin, slot.height.toFloat())
                if (track.outgoing) canvas.saveLayerAlpha(0f, 0f, slot.width.toFloat(), slot.height.toFloat(),
                    (255 * (1f - fraction)).toInt())
                renderer.draw(canvas, side == 1, slot.width, slot.height, left.width.toFloat(), slot.main.textPaint)
                canvas.restoreToCount(clipped)
            }
            canvas.restoreToCount(save)
        }

        private fun picture(canvas: Canvas, slot: View, frame: Picture, alpha: Float, x: Float, y: Float = 0f) {
            if (alpha <= 0f) return
            val layer = canvas.saveLayerAlpha(0f, 0f, slot.width.toFloat(), slot.height.toFloat(), (255 * alpha).toInt())
            canvas.translate(x, y)
            canvas.drawPicture(frame)
            canvas.restoreToCount(layer)
        }

        private fun invalidate() { left.invalidate(); right.invalidate() }

        fun finish() {
            if (closed) return
            if (applying) {
                finishRequestedWhileApplying = true
                return
            }
            closed = true
            animator?.removeAllListeners()
            animator?.removeAllUpdateListeners()
            animator?.cancel()
            preDraw?.removeListener()
            geometryListener?.let { listener -> observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener) }
            right.removeCallbacks(watchdog)
            if (BuildConfig.DEBUG) HookLogger.d("SwitchTrace", "separated morph end completed=${fraction >= 1f} split=$split " +
                "same=${tracks.filterNot { it.outgoing }.all { it.renderer?.geometry?.target == snapshot(slots[it.side])?.geometry }}")
            for (slot in slots) {
                slot.layoutRoleDrawing = null
                slot.cancelLayoutRoleTransition = null
                running.remove(slot)
                slot.pauseLayoutRoleContent(false)
            }
            invalidate()
        }
    }
}
