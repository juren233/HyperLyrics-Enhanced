/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.lyric.view.yoyo.YoYoAnimation
import com.juren233.hyperlyricsenhanced.lyric.view.line.WidthPreparationGate
import com.juren233.hyperlyricsenhanced.root.island.renderer.BaseIslandRenderer
import com.juren233.hyperlyricsenhanced.root.reload.SystemUiHookLifetime
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Original phone classes2.dex, APK SHA-256 f07be6a32708b0205d5ecc91ed8ce1a38fd64a17de3d49dd88f6306f9d2dab99.
 * Use the real host delegate's actual self/app/freeform motion flags. The coordinator's aggregate
 * isAnimationRunning:Z can stay true after a lost completion and must not hold content indefinitely.
 */
internal object IslandContentUpdateProfile {
    const val BASE_CLASS = "miui.systemui.dynamicisland.window.content.DynamicIslandBaseContentView"
    const val REAL_CLASS = "miui.systemui.dynamicisland.window.content.DynamicIslandContentView"
    const val DELEGATE_CLASS = "miui.systemui.dynamicisland.anim.DynamicIslandAnimationDelegate"
    const val DELEGATE_GETTER = "getAnimatorDelegate"
    const val REAL_GETTER = "getRealView"
    const val SELF_RUNNING_GETTER = "isAnimating"
    const val WINDOW_RUNNING_GETTER = "getIslandWindowAnimRunning"

    fun isGetter(method: Method, name: String, returnTypeName: String): Boolean =
        method.name == name && method.returnType.name == returnTypeName &&
            method.parameterCount == 0 && Modifier.isPublic(method.modifiers) &&
            !Modifier.isStatic(method.modifiers) && !method.isBridge && !method.isSynthetic

}

/** Main-thread presentation barrier. Data sources keep running; the final bind reads current data. */
internal object IslandContentUpdateCoordinator {
    private const val TAG = "IslandContentUpdate"
    private class Access(
        val base: Class<*>,
        val real: Class<*>,
        val realView: Method,
        val motion: IslandContentMotionReader,
    )

    @Volatile private var access: Access? = null
    private val pending = IdentityHashMap<ViewGroup, IslandDeferredContentState<Any>>()
    private class PendingWidth {
        val roots: MutableSet<ViewGroup> = Collections.newSetFromMap(IdentityHashMap())
        var protectLyricLottie = true
    }
    private val batchWidths = IdentityHashMap<ViewGroup, PendingWidth>()
    private val preparationGates = IdentityHashMap<ViewGroup, WidthPreparationGate>()
    private val unavailablePreparationGate = WidthPreparationGate()
    private var framePosted = false
    private var committing = false
    private var drainingWidths = false
    private var firstDeferredLogged = false
    private var firstCommitLogged = false
    private var firstBoundWidthLogged = false
    private var preparationTransitionsLogged = 0
    private val nativeDispatchDepth = ThreadLocal.withInitial { 0 }

    fun isRealContent(root: ViewGroup): Boolean = access?.real?.isInstance(root) == true

    private val detachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) {
            (view as? ViewGroup)?.let(::discard)
        }
    }
    private val frameCallback = Choreographer.FrameCallback { drainFrame() }
    private val preparationDetachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) {
            (view as? ViewGroup)?.let(::discardPreparation)
        }
    }

    fun widthPreparationGate(view: View, enabled: Boolean): WidthPreparationGate {
        if (Looper.myLooper() != Looper.getMainLooper() || SystemUiHookLifetime.retired) return unavailablePreparationGate
        val root = host(view)?.let(::realHost) ?: return unavailablePreparationGate
        if (access?.real?.isInstance(root) != true) return unavailablePreparationGate
        if (!enabled) { discardPreparation(root); return unavailablePreparationGate }
        if (!root.isAttachedToWindow) return unavailablePreparationGate
        val gate = preparationGates.getOrPut(root) {
            root.addOnAttachStateChangeListener(preparationDetachListener)
            WidthPreparationGate()
        }
        gate.requestObservation()
        if (nativeDispatchDepth.get() != 0 || animating(root) != false) gate.onTransitionStarted()
        scheduleFrame()
        return gate
    }

    private fun discardPreparation(root: ViewGroup) {
        preparationGates.remove(root)?.onTransitionStarted()
        root.removeOnAttachStateChangeListener(preparationDetachListener)
    }

    fun install(classLoader: ClassLoader) {
        access = runCatching {
            val base = classLoader.loadClass(IslandContentUpdateProfile.BASE_CLASS)
            val real = classLoader.loadClass(IslandContentUpdateProfile.REAL_CLASS)
            val delegate = classLoader.loadClass(IslandContentUpdateProfile.DELEGATE_CLASS)
            val getDelegate = base.declaredMethods.single {
                IslandContentUpdateProfile.isGetter(it, IslandContentUpdateProfile.DELEGATE_GETTER, delegate.name)
            }.apply { isAccessible = true }
            val getReal = base.declaredMethods.single {
                IslandContentUpdateProfile.isGetter(it, IslandContentUpdateProfile.REAL_GETTER, real.name)
            }.apply { isAccessible = true }
            fun booleanGetter(name: String) = delegate.declaredMethods.single {
                IslandContentUpdateProfile.isGetter(it, name, "boolean")
            }.apply { isAccessible = true }
            Access(base, real, getReal, IslandContentMotionReader(getDelegate,
                booleanGetter(IslandContentUpdateProfile.SELF_RUNNING_GETTER),
                booleanGetter(IslandContentUpdateProfile.WINDOW_RUNNING_GETTER)))
        }.onFailure { HookLogger.w(TAG, "原生稳定状态不可用，保留即时刷新: ${it.javaClass.simpleName}") }.getOrNull()
        if (BuildConfig.DEBUG) HookLogger.i(TAG, "内容稳定后提交已接线: actualMotion=${access != null}")
    }

    private fun host(view: View): ViewGroup? {
        val currentAccess = access ?: return null
        var current: View? = view
        while (current != null) {
            if (currentAccess.base.isInstance(current)) return current as? ViewGroup
            current = current.parent as? View
        }
        return null
    }

    private fun animating(root: ViewGroup): Boolean? = runCatching {
        val currentAccess = access ?: return null
        currentAccess.motion.isAnimating(realHost(root))
    }.getOrNull()

    private fun realHost(root: ViewGroup): ViewGroup = runCatching {
        val currentAccess = access ?: return root
        if (currentAccess.real.isInstance(root)) root
        else currentAccess.realView.invoke(root) as? ViewGroup ?: root
    }.getOrDefault(root)

    private fun defer(root: ViewGroup): IslandDeferredContentState<Any>? {
        if (committing || SystemUiHookLifetime.retired || !root.isAttachedToWindow ||
            Looper.myLooper() != Looper.getMainLooper() || !IslandProbeUtils.isHyperIslandEnabled()
        ) return null
        pending[root]?.let { return it }
        if (nativeDispatchDepth.get() == 0 && animating(root) != true) return null
        // First attachment and genuinely missing content still need an initial presentation.
        if (!IslandLyricTextInjector.hasInjectedLyricView(root)) return null
        val state = IslandDeferredContentState<Any>()
        pending[root] = state
        root.addOnAttachStateChangeListener(detachListener)
        scheduleFrame()
        // An already queued YoYo callback must join this batch as well. Its cancellation
        // callback reaches deferContent() after pending[root] exists, preserving the old text.
        listOf(IslandProbeUtils.LEFT_TEST_VIEW_TAG, IslandProbeUtils.RIGHT_TEST_VIEW_TAG).forEach { tag ->
            root.findViewWithTag<View>(tag)?.let { slot ->
                if (YoYoAnimation.isAwaitingContent(slot)) {
                    state.merge(content = true)
                    YoYoAnimation.cancelAnimation(slot)
                }
            }
        }
        if (BuildConfig.DEBUG && !firstDeferredLogged) {
            firstDeferredLogged = true
            HookLogger.i(TAG, "首次暂存形变期间内容: host=${System.identityHashCode(root)}")
        }
        return state
    }

    fun deferContent(view: View, forceWidth: Boolean = false, isSeek: Boolean = false): Boolean {
        val root = host(view) ?: return false
        val state = defer(root) ?: return false
        state.merge(content = true, width = forceWidth, protectLyricLottie = false, seek = isSeek)
        return true
    }

    /** Covers synchronous scheduling before the first real animator onBegin callback. */
    fun <T> duringNativeTransition(target: Any?, action: () -> T): T {
        (target as? ViewGroup)?.let(::realHost)?.let { root ->
            preparationGates[root]?.let { gate ->
                val wasOpen = gate.isOpen
                gate.onTransitionStarted()
                if (wasOpen) tracePreparationPermit(root, false)
            }
        }
        val before = nativeDispatchDepth.get()
        nativeDispatchDepth.set(before + 1)
        return try { action() } finally {
            if (before == 0) nativeDispatchDepth.remove() else nativeDispatchDepth.set(before)
            scheduleFrame()
        }
    }

    /** A native icon bind can be replaced by the latest bind, never by a retained Xposed Chain. */
    fun deferAction(view: View, key: Any, action: () -> Unit): Boolean {
        val root = host(view) ?: return false
        val state = defer(root) ?: return false
        state.replaceAction(key, action)
        return true
    }

    fun deferRelayout(root: ViewGroup, protectLyricLottie: Boolean): Boolean {
        if (drainingWidths) return false
        val target = realHost(root)
        if (committing || batchWidths.containsKey(target) || awaitingContent(root) || awaitingContent(target)) {
            val request = batchWidths.getOrPut(target) { PendingWidth() }
            request.roots.add(root)
            request.roots.add(target)
            request.protectLyricLottie = request.protectLyricLottie && protectLyricLottie
            scheduleFrame()
            return true
        }
        val state = defer(root) ?: return false
        state.merge(width = true, protectLyricLottie = protectLyricLottie)
        return true
    }

    private fun awaitingContent(root: ViewGroup): Boolean = root.isAttachedToWindow && listOf(
        IslandProbeUtils.LEFT_TEST_VIEW_TAG, IslandProbeUtils.RIGHT_TEST_VIEW_TAG,
    ).any { tag -> root.findViewWithTag<View>(tag)?.let(YoYoAnimation::isAwaitingContent) == true }

    private fun scheduleFrame() {
        if (framePosted || (pending.isEmpty() && batchWidths.isEmpty() && preparationGates.values.none { it.needsObservation })) return
        framePosted = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun discard(root: ViewGroup) {
        pending.remove(root)
        root.removeOnAttachStateChangeListener(detachListener)
    }

    private fun drainFrame() {
        framePosted = false
        if (SystemUiHookLifetime.retired || !IslandProbeUtils.isHyperIslandEnabled()) {
            release()
            return
        }
        val ready = pending.entries.toList().mapNotNull { (root, state) ->
            if (!root.isAttachedToWindow) { discard(root); null }
            else if (state.onFrame(animating(root) == true)) root to state else null
        }
        // Remove the batch before invoking code that can synchronously request layout or bind again.
        ready.forEach { (root, _) -> discard(root) }
        committing = true
        try {
            ready.forEach { (root, state) ->
                runCatching {
                    state.takeActions().forEach { action ->
                        runCatching(action).onFailure { HookLogger.e(TAG, "提交最新封面失败", it) }
                    }
                    if (state.content) BaseIslandRenderer.refreshAfterIslandSettled(root, isSeek = state.seek)
                    if (state.width) deferRelayout(root, state.protectLyricLottie)
                }.onFailure { HookLogger.e(TAG, "提交最新超级岛内容失败", it) }
            }
            drainingWidths = true
            batchWidths.entries.toList().forEach { (root, request) ->
                if (!root.isAttachedToWindow) {
                    batchWidths.remove(root)
                    return@forEach
                }
                // The exit still draws the old line. Once the new content is bound,
                // resize during its entrance rather than waiting for the whole animation.
                if (request.roots.any(::awaitingContent) || animating(root) == true) return@forEach
                batchWidths.remove(root)
                if (BuildConfig.DEBUG && !firstBoundWidthLogged) {
                    firstBoundWidthLogged = true
                    val entering = request.roots.any { source -> listOf(
                        IslandProbeUtils.LEFT_TEST_VIEW_TAG, IslandProbeUtils.RIGHT_TEST_VIEW_TAG,
                    ).any { tag -> source.findViewWithTag<View>(tag)?.let(YoYoAnimation::isRunning) == true } }
                    HookLogger.i(TAG, "内容绑定后首次提交宽度: entranceRunning=$entering")
                }
                if (request.protectLyricLottie) IslandViewHelper.triggerLyricContentRelayout(root)
                else IslandViewHelper.triggerSystemRelayout(root)
            }
            if (BuildConfig.DEBUG && ready.isNotEmpty() && !firstCommitLogged) {
                firstCommitLogged = true
                HookLogger.i(TAG, "首次稳定后合并提交: hosts=${ready.size}, pendingWidths=${batchWidths.size}")
            }
        } finally {
            drainingWidths = false
            committing = false
        }
        // No polling once permits are open. Transition dispatch closes them before native
        // animation work; only the UI thread reads delegates, including stable Expanded.
        preparationGates.toList().forEach { (root, gate) ->
            if (!root.isAttachedToWindow) discardPreparation(root)
            else if (gate.needsObservation) {
                val wasOpen = gate.isOpen
                gate.onFrame(animating(root))
                if (!wasOpen && gate.isOpen) tracePreparationPermit(root, true)
            }
        }
        scheduleFrame()
    }

    private fun tracePreparationPermit(root: ViewGroup, open: Boolean) {
        if (BuildConfig.DEBUG && preparationTransitionsLogged < 12) {
            preparationTransitionsLogged++
            HookLogger.i(TAG, "后台宽度准备许可: host=${System.identityHashCode(root)} open=$open")
        }
    }

    fun release() {
        if (framePosted) Choreographer.getInstance().removeFrameCallback(frameCallback)
        framePosted = false
        pending.keys.toList().forEach(::discard)
        batchWidths.clear()
        preparationGates.keys.toList().forEach(::discardPreparation)
    }
}
