/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import com.juren233.hyperlyricsenhanced.lyric.view.SpaceGateRichLyricLineView
import com.juren233.hyperlyricsenhanced.root.HookEntry
import com.juren233.hyperlyricsenhanced.root.island.renderer.BaseIslandRenderer
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Both slots use the right lyric viewport, including its actual native width limit. */
internal object IslandShortLyricLayout {
    private data class Capacity(val width: Int, val config: IslandSlotRuntimeConfig, val screenWidth: Int)
    private val capacities = WeakHashMap<SpaceGateRichLyricLineView, Capacity>()
    private class Observation(val refresh: Runnable, val widthRefresh: IslandShortLyricWidthRefresh)
    private val refreshes = WeakHashMap<SpaceGateRichLyricLineView, Observation>()

    /** Called with the native, status-bar-limited capacity BEFORE content shrink. */
    fun updateCapacity(right: SpaceGateRichLyricLineView, config: IslandSlotRuntimeConfig, width: Int) {
        val capacity = Capacity(width, config, right.resources.displayMetrics.widthPixels)
        if (capacities.put(right, capacity) == capacity || !config.shortLyricSongInfo) return
        requestRefresh(right)
    }

    private fun requestRefresh(right: SpaceGateRichLyricLineView) {
        refreshes[right]?.refresh?.let { refresh ->
            right.removeCallbacks(refresh)
            right.post(refresh)
        }
    }

    fun onMorphFinished(right: SpaceGateRichLyricLineView) {
        if (refreshes[right]?.widthRefresh?.onMorphFinished() == true) requestRefresh(right)
    }

    fun usesSongInfo(view: View, prefs: SharedPreferences, config: IslandSlotRuntimeConfig): Boolean {
        if (!config.isFullIslandMode || !config.shortLyricSongInfo ||
            !config.shouldInjectLeft || !config.shouldInjectRight
        ) return false
        val richView = view as? SpaceGateRichLyricLineView ?: return false
        val right = if (view.tag == IslandProbeUtils.RIGHT_TEST_VIEW_TAG) richView
            else richView.main.siblingView?.parent as? SpaceGateRichLyricLineView ?: return false
        var ancestor = right.parent as? ViewGroup
        while (ancestor != null) {
            if (BaseIslandRenderer.isSlotReservedByNextSongPreview(
                    ancestor, IslandProbeUtils.RIGHT_TEST_VIEW_TAG,
                )) return false
            ancestor = ancestor.parent as? ViewGroup
        }
        // A shrunken viewport is demand, not capacity. Keep the pre-shrink native limit
        // for classification, so a moderately longer next line need not expand to full
        // island and immediately switch back. Configuration/rotation invalidate it.
        val capacity = capacities[right]?.takeIf {
            config.dynamicWidthEnabled && it.config == config &&
                it.screenWidth == right.resources.displayMetrics.widthPixels
        }
        val availableWidth = ((capacity?.width ?: right.width) -
            right.paddingLeft - right.paddingRight).coerceAtLeast(0)
        if (availableWidth == 0) return false
        IslandSlotContentAssembler.configureView(right, prefs, config, mode = 7)
        val line = IslandSlotContentAssembler.buildSlotLyricLine(right, prefs, config, isLeft = false)
            ?: return false
        return IslandShortLyricPolicy.usesSongInfo(
            config.activeMode, config.shortLyricSongInfo,
            right.measureIndependentContentWidth(line), availableWidth,
        )
    }

    /** One listener per right view; coalesce and rebind outside native layout callbacks. */
    fun observeWidth(right: SpaceGateRichLyricLineView) {
        val reference = WeakReference(right)
        val refresh = Runnable {
            val right = reference.get() ?: return@Runnable
            if (!right.isAttachedToWindow || !right.isShown) return@Runnable
            val prefs = HookEntry.instance?.prefs ?: return@Runnable
            val config = IslandSlotRuntimeConfig.from(prefs)
            if (!config.isFullIslandMode || !config.shortLyricSongInfo) return@Runnable
            // Reuse playback/pause gates, exact host preview reservations, content
            // signatures and width refresh. Do not clear caches or force a full refresh.
            BaseIslandRenderer.updateLyricLine()
        }
        val widthRefresh = IslandShortLyricWidthRefresh()
        refreshes[right] = Observation(refresh, widthRefresh)
        right.addOnLayoutChangeListener { _, left, _, end, _, oldLeft, _, oldEnd, _ ->
            if (end - left != oldEnd - oldLeft) {
                // The morph consumes the actual layout in pre-draw. Do not feed its animated
                // viewport back into a full content/native-width update on every frame.
                // Real capacity changes still use updateCapacity's immediate refresh above.
                if (widthRefresh.onWidthChanged(IslandShortLyricTransition.isRunning(right))) {
                    requestRefresh(right)
                }
            }
        }
        right.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                right.removeCallbacks(refresh)
                widthRefresh.clear()
                capacities.remove(right)
            }
        })
    }
}
