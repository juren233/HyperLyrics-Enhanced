/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.lyric.view.SpaceGateRichLyricLineView
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.island.view.MaxWidthFrameLayout
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.WeakHashMap

/**
 * Keeps the injected SpaceGate belt from drawing over MIUI's own current-track title/artist
 * while MIUI is changing expanded-media templates. The native views remain untouched; both
 * injected slots yield together so the virtual text belt resumes as one continuous line.
 */
internal object IslandNativeTextCollisionGuard {
    private const val TAG = "IslandNativeTextGate"
    private const val MAX_VIEW_NODES = 256
    private const val MAX_DIAGNOSTIC_NODES = 2_048
    private const val DIAGNOSTIC_INTERVAL_MS = 2_000L
    private const val MIN_EFFECTIVE_ALPHA = 0.01f

    private val mainHandler = Handler(Looper.getMainLooper())
    private val states = WeakHashMap<ViewGroup, State>()

    private class TrackedWrapper(
        val view: WeakReference<View>,
        val originalAlpha: Float,
    )

    private class State(root: ViewGroup) {
        val root = WeakReference(root)
        var preDrawObserver: WeakReference<ViewTreeObserver>? = null
        var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null
        var attachListener: View.OnAttachStateChangeListener? = null
        var wrappers: List<TrackedWrapper> = emptyList()
        var collisionActive = false
        var lastTitle: String? = null
        var lastBridgeTitle: String? = null
        var lastArtist: String? = null
        var normalizedTrackText: Set<String> = emptySet()
        var diagnosticRunnable: Runnable? = null
    }

    private data class TextCollision(
        val textView: TextView,
        val textBounds: Rect,
        val wrapper: View,
        val wrapperBounds: Rect,
    )

    /** Called after injection or preference reconfiguration; all view work runs on main. */
    fun sync(root: ViewGroup, enabled: Boolean) {
        onMain(root) { target ->
            if (!enabled) {
                clearOnMain(target)
                return@onMain
            }
            val wrappers = resolveSpaceGateWrappers(target)
            if (wrappers == null) {
                clearOnMain(target)
                return@onMain
            }

            val state = states[target] ?: createState(target).also { states[target] = it }
            updateTrackedWrappers(state, wrappers)
            installPreDrawListener(target, state)
            updateCollision(target, state, wrappers)
            startDiagnostic(state)
        }
    }

    /** Called before injected views are removed, so no listener or temporary alpha is retained. */
    fun clear(root: ViewGroup) {
        onMain(root, ::clearOnMain)
    }

    private fun createState(root: ViewGroup): State {
        val state = State(root)
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                val target = state.root.get() ?: return
                if (states[target] === state) {
                    installPreDrawListener(target, state)
                    startDiagnostic(state)
                }
            }

            override fun onViewDetachedFromWindow(view: View) {
                removePreDrawListener(state)
                stopDiagnostic(state)
                restoreWrapperAlpha(state)
                state.collisionActive = false
            }
        }
        state.attachListener = attachListener
        root.addOnAttachStateChangeListener(attachListener)
        return state
    }

    private fun resolveSpaceGateWrappers(root: ViewGroup): List<View>? {
        val left = root.findViewWithTag<View>(IslandProbeUtils.LEFT_TEST_VIEW_TAG)
            as? SpaceGateRichLyricLineView ?: return null
        val right = root.findViewWithTag<View>(IslandProbeUtils.RIGHT_TEST_VIEW_TAG)
            as? SpaceGateRichLyricLineView ?: return null
        if (!left.main.spaceGateEnabled || !right.main.spaceGateEnabled) return null

        val leftWrapper = root.findViewWithTag<View>(IslandProbeUtils.LEFT_TEST_WRAPPER_TAG)
            as? MaxWidthFrameLayout ?: return null
        val rightWrapper = root.findViewWithTag<View>(IslandProbeUtils.RIGHT_TEST_WRAPPER_TAG)
            as? MaxWidthFrameLayout ?: return null
        return listOf(leftWrapper, rightWrapper)
    }

    private fun installPreDrawListener(root: ViewGroup, state: State) {
        if (!root.isAttachedToWindow) return
        val observer = root.viewTreeObserver
        if (!observer.isAlive) return
        if (state.preDrawObserver?.get() === observer && state.preDrawListener != null) return

        removePreDrawListener(state)
        val listener = ViewTreeObserver.OnPreDrawListener {
            val target = state.root.get()
            if (target != null && states[target] === state) {
                val wrappers = resolveSpaceGateWrappers(target)
                if (wrappers == null) {
                    clearOnMain(target)
                } else {
                    updateTrackedWrappers(state, wrappers)
                    updateCollision(target, state, wrappers)
                }
            }
            true
        }
        observer.addOnPreDrawListener(listener)
        state.preDrawObserver = WeakReference(observer)
        state.preDrawListener = listener
    }

    private fun removePreDrawListener(state: State) {
        val observer = state.preDrawObserver?.get()
        val listener = state.preDrawListener
        if (observer?.isAlive == true && listener != null) {
            runCatching { observer.removeOnPreDrawListener(listener) }
        }
        state.preDrawObserver = null
        state.preDrawListener = null
    }

    private fun updateTrackedWrappers(state: State, wrappers: List<View>) {
        val previous = state.wrappers
        previous.forEach { tracked ->
            val oldView = tracked.view.get() ?: return@forEach
            if (wrappers.none { it === oldView }) oldView.alpha = tracked.originalAlpha
        }
        state.wrappers = wrappers.map { wrapper ->
            previous.firstOrNull { it.view.get() === wrapper }
                ?: TrackedWrapper(WeakReference(wrapper), wrapper.alpha)
        }
    }

    private fun updateCollision(root: ViewGroup, state: State, wrappers: List<View>) {
        val conflict = findTextCollision(root, wrappers, trackText(state))
        val active = conflict != null
        state.wrappers.forEach { tracked ->
            val wrapper = tracked.view.get() ?: return@forEach
            val targetAlpha = if (active) 0f else tracked.originalAlpha
            if (wrapper.alpha != targetAlpha) wrapper.alpha = targetAlpha
        }
        if (state.collisionActive != active) {
            state.collisionActive = active
            if (BuildConfig.DEBUG) {
                val detail = conflict?.let {
                    "nativeView=${identity(it.textView)} textHash=${it.textView.text?.toString()?.hashCode()} " +
                        "native=${it.textBounds} wrapper=${identity(it.wrapper)} bounds=${it.wrapperBounds}"
                } ?: "nativeConflict=cleared"
                HookLogger.i(
                    TAG,
                    "[NativeTextOverlapGate] active=$active left=${identity(wrappers[0])} " +
                        "right=${identity(wrappers[1])} $detail",
                )
            }
        }
    }

    private fun trackText(state: State): Set<String> {
        val song = LyriconDataBridge.currentSong
        val title = song?.name
        val bridgeTitle = LyriconDataBridge.currentSongName
        val artist = song?.artist
        if (title != state.lastTitle || bridgeTitle != state.lastBridgeTitle || artist != state.lastArtist) {
            state.lastTitle = title
            state.lastBridgeTitle = bridgeTitle
            state.lastArtist = artist
            state.normalizedTrackText = listOfNotNull(title, bridgeTitle, artist)
                .map(::normalizeText)
                .filter { it.length >= 3 }
                .toSet()
        }
        return state.normalizedTrackText
    }

    private fun findTextCollision(
        root: ViewGroup,
        wrappers: List<View>,
        trackText: Set<String>,
    ): TextCollision? {
        if (trackText.isEmpty()) return null
        val wrapperBounds = wrappers.mapNotNull { wrapper ->
            visibleBounds(wrapper, root, includeViewAlpha = false)?.let { wrapper to it }
        }
        if (wrapperBounds.isEmpty()) return null

        val pending = ArrayDeque<View>()
        pending.addLast(root)
        var visited = 0
        while (pending.isNotEmpty() && visited < MAX_VIEW_NODES) {
            val view = pending.removeFirst()
            visited++
            // Do not treat either half of the module's own virtual belt as native metadata.
            if (wrappers.any { it === view }) continue

            if (view is TextView && isVisibleWithinRoot(view, root)) {
                val text = view.text?.toString().orEmpty()
                if (matchesTrackText(text, trackText)) {
                    val textBounds = visibleBounds(view, root, includeViewAlpha = true)
                    if (textBounds != null) {
                        wrapperBounds.firstOrNull { (_, bounds) -> Rect.intersects(textBounds, bounds) }
                            ?.let { (wrapper, bounds) ->
                                return TextCollision(view, textBounds, wrapper, bounds)
                            }
                    }
                }
            }

            val group = view as? ViewGroup ?: continue
            for (index in 0 until group.childCount) {
                group.getChildAt(index)?.let(pending::addLast)
            }
        }
        return null
    }

    private fun isVisibleWithinRoot(view: View, root: ViewGroup): Boolean {
        if (!view.isAttachedToWindow || !view.isShown) return false
        var current: View? = view
        var effectiveAlpha = 1f
        while (current != null) {
            if (current.visibility != View.VISIBLE) return false
            effectiveAlpha *= current.alpha
            if (effectiveAlpha <= MIN_EFFECTIVE_ALPHA) return false
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    private fun visibleBounds(view: View, root: ViewGroup, includeViewAlpha: Boolean): Rect? {
        if (!view.isAttachedToWindow || !view.isShown) return null
        var current: View? = if (includeViewAlpha) view else view.parent as? View
        var effectiveAlpha = 1f
        while (current != null) {
            if (current.visibility != View.VISIBLE) return null
            effectiveAlpha *= current.alpha
            if (effectiveAlpha <= MIN_EFFECTIVE_ALPHA) return null
            if (current === root) break
            current = current.parent as? View
        }
        if (current !== root) return null
        val bounds = Rect()
        if (!runCatching { view.getGlobalVisibleRect(bounds) }.getOrDefault(false) || bounds.isEmpty) {
            return null
        }
        return bounds
    }

    private fun matchesTrackText(value: String, trackText: Set<String>): Boolean {
        val candidate = normalizeText(value)
        if (candidate.length < 3) return false
        return trackText.any { label ->
            candidate == label ||
                (label.length >= 4 && candidate.contains(label)) ||
                (candidate.length >= 4 && label.contains(candidate))
        }
    }

    private fun normalizeText(value: String): String =
        value.trim().lowercase().filterNot(Char::isWhitespace)

    /** Debug-only periodic snapshot also covers a title that remains overlapped without redraw events. */
    private fun startDiagnostic(state: State) {
        if (!BuildConfig.DEBUG || state.diagnosticRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                val root = state.root.get()
                if (root == null || states[root] !== state || !root.isAttachedToWindow) {
                    state.diagnosticRunnable = null
                    return
                }
                if (root.isShown) logDiagnostic(root, state)
                mainHandler.postDelayed(this, DIAGNOSTIC_INTERVAL_MS)
            }
        }
        state.diagnosticRunnable = runnable
        mainHandler.postDelayed(runnable, DIAGNOSTIC_INTERVAL_MS)
    }

    private fun stopDiagnostic(state: State) {
        state.diagnosticRunnable?.let(mainHandler::removeCallbacks)
        state.diagnosticRunnable = null
    }

    private fun logDiagnostic(root: ViewGroup, state: State) {
        val windowRoot = root.rootView as? ViewGroup ?: root
        val wrappers = resolveSpaceGateWrappers(root) ?: return
        val area = Rect()
        wrappers.mapNotNull { visibleBounds(it, windowRoot, includeViewAlpha = false) }
            .forEach { area.union(it) }
        if (area.isEmpty) return

        val trackText = trackText(state)
        val pending = ArrayDeque<View>()
        pending.addLast(windowRoot)
        val nativeText = ArrayList<String>()
        val injected = ArrayList<String>()
        var visited = 0
        var textMatches = 0
        while (pending.isNotEmpty() && visited < MAX_DIAGNOSTIC_NODES) {
            val view = pending.removeFirst()
            visited++
            if (view is SpaceGateRichLyricLineView && isVisibleWithinRoot(view, windowRoot)) {
                if (injected.size < 12) {
                    injected += "${identity(view)}:${view.tag}/${visibleBounds(view, windowRoot, true)}" +
                        "/main=${view.main.visibility},${view.main.alpha},${view.main.translationX},${view.main.translationY}" +
                        "/second=${view.secondary.visibility},${view.secondary.alpha},${view.secondary.translationX},${view.secondary.translationY}"
                }
            }
            if (view is TextView && isVisibleWithinRoot(view, windowRoot) &&
                wrappers.none { isDescendantOf(view, it) }) {
                val bounds = visibleBounds(view, windowRoot, includeViewAlpha = true)
                if (bounds != null && Rect.intersects(bounds, area)) {
                    val value = view.text?.toString().orEmpty()
                    val matches = matchesTrackText(value, trackText)
                    if (matches) textMatches++
                    if (nativeText.size < 12) {
                        nativeText += "${identity(view)}:${view.javaClass.simpleName}" +
                            "/id=${view.id.toUInt().toString(16)}/len=${value.length}/hash=${value.hashCode()}" +
                            "/match=$matches/bounds=$bounds/alpha=${view.alpha}/parent=${parentPath(view, windowRoot)}"
                    }
                }
            }
            val group = view as? ViewGroup ?: continue
            for (index in 0 until group.childCount) {
                group.getChildAt(index)?.let(pending::addLast)
            }
        }
        val wrapperInfo = wrappers.joinToString(";") {
            "${identity(it)}:${visibleBounds(it, windowRoot, false)}/alpha=${it.alpha}"
        }
        val key = "root=${identity(root)} window=${identity(windowRoot)}"
        HookLogger.i(
            TAG,
            "[NativeTextLiveDiag] $key rootClass=${root.javaClass.simpleName} " +
                "windowClass=${windowRoot.javaClass.simpleName}" +
                "/token=${root.windowToken?.let { System.identityHashCode(it).toUInt().toString(16) }} " +
                "gate=${state.collisionActive} listener=${state.preDrawListener != null} " +
                "area=$area wrappers=[$wrapperInfo] nodes=$visited truncated=${pending.isNotEmpty()} " +
                "textMatches=$textMatches text=[${nativeText.joinToString(";")}]",
        )
        HookLogger.i(TAG, "[NativeTextLiveDiag] $key injected=[${injected.joinToString(";")}]")
    }

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun parentPath(view: View, root: ViewGroup): String {
        val names = ArrayList<String>()
        var current: View? = view.parent as? View
        while (current != null && names.size < 6) {
            names += "${current.javaClass.simpleName}@${identity(current)}"
            if (current === root) break
            current = current.parent as? View
        }
        return names.joinToString(">")
    }

    private fun clearOnMain(root: ViewGroup) {
        val state = states.remove(root) ?: return
        state.attachListener?.let(root::removeOnAttachStateChangeListener)
        removePreDrawListener(state)
        stopDiagnostic(state)
        restoreWrapperAlpha(state)
        state.wrappers = emptyList()
        state.collisionActive = false
    }

    private fun restoreWrapperAlpha(state: State) {
        state.wrappers.forEach { tracked ->
            tracked.view.get()?.alpha = tracked.originalAlpha
        }
    }

    private fun identity(view: View): String =
        System.identityHashCode(view).toUInt().toString(16)

    private fun onMain(root: ViewGroup, action: (ViewGroup) -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action(root)
        } else {
            val weakRoot = WeakReference(root)
            mainHandler.post { weakRoot.get()?.let(action) }
        }
    }
}
