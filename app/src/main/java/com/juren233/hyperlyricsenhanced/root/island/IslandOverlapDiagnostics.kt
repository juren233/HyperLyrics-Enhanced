/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import android.graphics.Rect
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.lyric.view.RichLyricLineView
import com.juren233.hyperlyricsenhanced.lyric.view.SpaceGateRichLyricLineView
import com.juren233.hyperlyricsenhanced.lyric.view.line.LyricLineView
import com.juren233.hyperlyricsenhanced.lyric.view.line.SpaceGateLyricLineView
import com.juren233.hyperlyricsenhanced.root.HookEntry
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.island.view.MaxWidthFrameLayout
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * Issue #35: read-only Super Island geometry and drawing-state snapshots.
 * No listener, polling loop, extra measure/layout, or change to clipping is installed.
 * A bounded series samples the same content after native width calculation, through
 * the width animation, and after the marquee has had time to move. All entry points
 * are gated in Debug builds; Release never collects these snapshots.
 */
internal object IslandOverlapDiagnostics {
    private const val TAG = "IslandOverlapDiag"
    private val sampleDelaysMs = longArrayOf(0L, 120L, 500L, 1_500L, 3_500L, 6_000L)

    private class State {
        var generation = 0
        var lastSeriesAt = 0L
        var lastContentKey = ""
        var lastResultKey = ""
        var widthInput = ""
        var nativeResult = ""
    }

    private val states = WeakHashMap<ViewGroup, State>()
    private val resultMethods = WeakHashMap<Class<*>, List<Method>>()

    /** Called after the module's natural-width pre-measure, before native calculation. */
    fun beforeWidthCalculation(root: ViewGroup) {
        if (!BuildConfig.DEBUG) return
        state(root).widthInput = "host=${IslandPadContentAnchor.currentIslandWidth(root)} " +
            "left=${measuredArea(root, "area_left")} right=${measuredArea(root, "area_right")}"
    }

    /** The native result already includes the optional unlock and status-bar limiter. */
    fun afterWidthCalculation(root: ViewGroup, result: Any?) {
        if (!BuildConfig.DEBUG) return
        val state = state(root)
        val resultKey = resultSummary(result)
        val changedWidth = state.lastResultKey != resultKey
        state.lastResultKey = resultKey
        state.nativeResult = resultKey
        val now = SystemClock.uptimeMillis()
        val contentKey = contentKey(root)
        if (contentKey != state.lastContentKey ||
            (changedWidth && now - state.lastSeriesAt >= 2_000L) ||
            now - state.lastSeriesAt >= 10_000L
        ) {
            startSeries(root, state, "width_result", contentKey, now)
        }
    }

    fun onContentApplied(root: ViewGroup, reason: String) {
        if (!BuildConfig.DEBUG) return
        val state = state(root)
        val now = SystemClock.uptimeMillis()
        val contentKey = contentKey(root)
        // The same line can be rebound more than once during a host width animation.
        if (contentKey != state.lastContentKey || now - state.lastSeriesAt >= 2_000L) {
            startSeries(root, state, reason, contentKey, now)
        }
    }

    private fun startSeries(root: ViewGroup, state: State, reason: String, key: String, now: Long) {
        state.lastSeriesAt = now
        state.lastContentKey = key
        val generation = ++state.generation
        val weakRoot = WeakReference(root)
        HookLogger.i(
            TAG,
            "start pid=${Process.myPid()} host=${id(root)} seq=$generation reason=$reason " +
                "content=$key input=${state.widthInput} result=${state.nativeResult}",
        )
        sampleDelaysMs.forEach { delay ->
            root.postDelayed({
                val target = weakRoot.get() ?: return@postDelayed
                // A width-only recalculation must not cancel the late marquee sample.
                // A new song/line does cancel it, to avoid attributing stale geometry.
                if (state.generation != generation && contentKey(target) != key) return@postDelayed
                runCatching { snapshot(target, generation, reason, delay) }
                    .onFailure { HookLogger.w(TAG, "snapshot_error seq=$generation delay=$delay type=${it.javaClass.simpleName}") }
            }, delay)
        }
    }

    private fun snapshot(root: ViewGroup, seq: Int, reason: String, delay: Long) {
        val prefs = HookEntry.instance?.prefs ?: return
        val config = IslandSlotRuntimeConfig.from(prefs)
        val left = slot(root, "L", "area_left", IslandProbeUtils.LEFT_PARENT_NAME,
            IslandProbeUtils.LEFT_TEST_WRAPPER_TAG, IslandProbeUtils.LEFT_TEST_VIEW_TAG)
        val right = slot(root, "R", "area_right", IslandProbeUtils.RIGHT_PARENT_NAME,
            IslandProbeUtils.RIGHT_TEST_WRAPPER_TAG, IslandProbeUtils.RIGHT_TEST_VIEW_TAG)
        val header = "seq=$seq t=+$delay reason=$reason pid=${Process.myPid()} host=${id(root)} " +
            "mode=${config.activeMode}/${config.leftMode}/${config.rightMode} " +
            "dynamic=${config.dynamicWidthEnabled} limit=${config.dynamicLimitEnabled} " +
            "caps=${config.leftMaxWidthDp}/${config.rightMaxWidthDp} " +
            "marquee=${config.lyricMarqueeEnabled}/${config.metadataMarqueeEnabled} " +
            "delay=${config.lyricMarqueeDelay}/${config.metadataMarqueeDelay} " +
            "speed=${config.lyricMarqueeSpeed}/${config.metadataMarqueeSpeed} " +
            "album=${config.showAlbum} rhythm=${config.showRhythm} " +
            "pkg=${LyriconDataBridge.currentLyricPackageName} " +
            "song=${LyriconDataBridge.currentSongName?.hashCode()} " +
            "hostWidth=${IslandPadContentAnchor.currentIslandWidth(root)} " +
            "input=${state(root).widthInput} result=${state(root).nativeResult} " +
            "root=${view(root)} pill=${view(IslandViewHelper.findViewByName(root, "big_island_view"))} " +
            "big=${view(IslandViewHelper.findViewByName(root, "big_container"))} " +
            "gaps=area:${gap(left.area, right.area)},wrapper:${gap(left.wrapper, right.wrapper)}," +
            "main:${gap(left.main, right.main)},visibleMain:${visibleGap(left.main, right.main)} " +
            "overhang=L:${overhang(left.wrapper, left.area, true)},R:${overhang(right.wrapper, right.area, false)}"
        logSnapshotLine("seq=$seq t=+$delay summary ", header)
        logSnapshotLine("seq=$seq t=+$delay L ", left.description)
        logSnapshotLine("seq=$seq t=+$delay R ", right.description)
    }

    private class Slot(
        val area: View?,
        val wrapper: View?,
        val main: View?,
        val description: String,
    )

    private fun slot(
        root: ViewGroup,
        side: String,
        areaName: String,
        moduleName: String,
        wrapperTag: String,
        lyricTag: String,
    ): Slot {
        val area = IslandViewHelper.findViewByName(root, areaName)
        val module = IslandViewHelper.findViewByName(root, moduleName)
        val text = (module as? ViewGroup)?.let {
            IslandViewHelper.findViewByName(it, IslandProbeUtils.TEXT_CONTAINER_NAME)
        }
        val wrapper = root.findViewWithTag<View>(wrapperTag)
        val lyric = root.findViewWithTag<View>(lyricTag)
        val main = when (lyric) {
            is RichLyricLineView -> lyric.main
            is SpaceGateRichLyricLineView -> lyric.main
            else -> null
        }
        val secondary = when (lyric) {
            is RichLyricLineView -> lyric.secondary
            is SpaceGateRichLyricLineView -> lyric.secondary
            else -> null
        }
        val raw = when (lyric) {
            is RichLyricLineView -> lyric.rawLine
            is SpaceGateRichLyricLineView -> lyric.rawLine
            else -> null
        }
        val description = "$side area=${view(area)} module=${view(module)} " +
            "text=${view(text)} wrapper=${view(wrapper)} lyric=${view(lyric)} " +
            "main=${view(main)} secondary=${view(secondary)} " +
            "icon=${view((module as? ViewGroup)?.let { IslandViewHelper.findViewByName(it, "island_container_module_icon") })} " +
            "raw=${raw?.let { "${it.begin}-${it.end}/${it.text.orEmpty().length}/${it.text.orEmpty().hashCode()}" } ?: "none"} " +
            "mainState=${lineState(main)} secondaryState=${lineState(secondary)} " +
            "clipPath=${clipPath(main, root)}"
        return Slot(area, wrapper, main, description)
    }

    private fun lineState(view: View?): String = when (view) {
        is LyricLineView -> view.overlapDiagnosticState()
        is SpaceGateLyricLineView -> view.overlapDiagnosticState()
        else -> "none"
    }

    private fun view(target: View?): String {
        if (target == null) return "missing"
        val bounds = bounds(target)
        val visible = Rect()
        val hasVisible = target.isAttachedToWindow &&
            runCatching { target.getGlobalVisibleRect(visible) }.getOrDefault(false)
        val group = target as? ViewGroup
        val params = target.layoutParams
        val margins = params as? ViewGroup.MarginLayoutParams
        val gravity = (params as? FrameLayout.LayoutParams)?.gravity
        val cap = (target as? MaxWidthFrameLayout)?.let {
            " max=${it.maxWidthPx} specW=${spec(it.diagnosticWidthSpec)} specH=${spec(it.diagnosticHeightSpec)}"
        }.orEmpty()
        return "${target.javaClass.simpleName}@${id(target)}:${bounds ?: "unattached"}" +
            "/vis=${if (hasVisible) visible else "none"}" +
            "/local=${target.left},${target.top},${target.right},${target.bottom}" +
            "/meas=${target.measuredWidth}x${target.measuredHeight}" +
            "/lp=${params?.width}x${params?.height},m=${margins?.leftMargin},${margins?.rightMargin},g=$gravity" +
            "/pad=${target.paddingLeft},${target.paddingRight}" +
            "/tx=${target.translationX},sx=${target.scaleX},scroll=${target.scrollX}" +
            "/alpha=${target.alpha},v=${target.visibility},shown=${target.isShown}" +
            "/clip=${group?.clipChildren}/${group?.clipToPadding}/${target.clipBounds}" + cap
    }

    private fun bounds(view: View?): Rect? {
        if (view == null || !view.isAttachedToWindow) return null
        return runCatching {
            val xy = IntArray(2)
            view.getLocationOnScreen(xy)
            Rect(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)
        }.getOrNull()
    }

    /** Positive gap means separation; negative means the two viewports overlap. */
    private fun gap(left: View?, right: View?): Int? {
        val a = bounds(left) ?: return null
        val b = bounds(right) ?: return null
        return b.left - a.right
    }

    private fun visibleGap(left: View?, right: View?): Int? {
        val a = Rect()
        val b = Rect()
        if (runCatching { left?.getGlobalVisibleRect(a) }.getOrNull() != true ||
            runCatching { right?.getGlobalVisibleRect(b) }.getOrNull() != true) return null
        return b.left - a.right
    }

    private fun overhang(wrapper: View?, area: View?, leftSide: Boolean): Int? {
        val w = bounds(wrapper) ?: return null
        val a = bounds(area) ?: return null
        return if (leftSide) w.right - a.right else a.left - w.left
    }

    private fun clipPath(from: View?, root: ViewGroup): String {
        val parts = ArrayList<String>()
        var current = from
        while (current != null && parts.size < 12) {
            val group = current as? ViewGroup
            parts += "${current.javaClass.simpleName}@${id(current)}:${group?.clipChildren}/${group?.clipToPadding}/${current.clipBounds}"
            if (current === root) break
            current = current.parent as? View
        }
        return parts.joinToString(">")
    }

    private fun contentKey(root: ViewGroup): String = buildString {
        append(LyriconDataBridge.currentSongName?.hashCode())
        for (tag in listOf(IslandProbeUtils.LEFT_TEST_VIEW_TAG, IslandProbeUtils.RIGHT_TEST_VIEW_TAG)) {
            val target = root.findViewWithTag<View>(tag)
            val raw = when (target) {
                is RichLyricLineView -> target.rawLine
                is SpaceGateRichLyricLineView -> target.rawLine
                else -> null
            }
            val main = when (target) {
                is RichLyricLineView -> target.main.model.text
                is SpaceGateRichLyricLineView -> target.main.model.text
                else -> ""
            }
            val secondary = when (target) {
                is RichLyricLineView -> target.secondary.model.text
                is SpaceGateRichLyricLineView -> target.secondary.model.text
                else -> ""
            }
            append('|').append(id(target)).append(':').append(main.length).append('/')
                .append(main.hashCode()).append(':').append(secondary.length).append('/')
                .append(secondary.hashCode()).append('@').append(raw?.begin).append('-').append(raw?.end)
        }
    }

    private fun measuredArea(root: ViewGroup, name: String): String {
        val area = IslandViewHelper.findViewByName(root, name)
        return "${area?.measuredWidth}x${area?.measuredHeight}/layout=${area?.width}x${area?.height}"
    }

    private fun resultSummary(result: Any?): String {
        if (result == null) return "null"
        if (result is Number) return result.toString()
        return runCatching {
            val names = listOf("W", "L", "R", "X", "M", "SW", "SL", "SR", "SX")
            val getters = synchronized(resultMethods) {
                resultMethods.getOrPut(result.javaClass) {
                    IslandDynamicLimitProfile.RESULT_GETTERS.map { result.javaClass.getMethod(it) }
                }
            }
            getters.mapIndexed { index, getter ->
                "${names[index]}=${(getter.invoke(result) as? Number)?.toInt() ?: "?"}"
            }.joinToString(",")
        }.getOrElse { "${result.javaClass.simpleName}/unreadable:${it.javaClass.simpleName}" }
    }

    /** Android log lines can be truncated; every chunk keeps the sequence and side. */
    private fun logSnapshotLine(prefix: String, value: String) {
        value.chunked(1_700).forEachIndexed { index, chunk ->
            HookLogger.i(TAG, "$prefix part=${index + 1} $chunk")
        }
    }

    private fun spec(value: Int?): String {
        value ?: return "none"
        val mode = when (View.MeasureSpec.getMode(value)) {
            View.MeasureSpec.EXACTLY -> "EXACT"
            View.MeasureSpec.AT_MOST -> "AT_MOST"
            else -> "UNSPEC"
        }
        return "$mode/${View.MeasureSpec.getSize(value)}"
    }

    private fun id(view: View?): String = view?.let { System.identityHashCode(it).toString(16) } ?: "none"

    private fun state(root: ViewGroup): State = synchronized(states) { states.getOrPut(root) { State() } }
}
