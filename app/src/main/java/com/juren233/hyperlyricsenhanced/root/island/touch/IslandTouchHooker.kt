/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island.touch

import com.juren233.hyperlyricsenhanced.root.reload.SystemUiHookLifetime
import android.graphics.Rect
import android.media.session.MediaController
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.IslandTouchAction
import com.juren233.hyperlyricsenhanced.common.IslandTouchConfig
import com.juren233.hyperlyricsenhanced.common.IslandTouchGesture
import com.juren233.hyperlyricsenhanced.common.IslandTouchSide
import com.juren233.hyperlyricsenhanced.root.HookEntry
import com.juren233.hyperlyricsenhanced.root.island.IslandProbeUtils
import com.juren233.hyperlyricsenhanced.root.island.IslandRuntimePreferenceReader
import com.juren233.hyperlyricsenhanced.root.island.IslandViewHelper
import com.juren233.hyperlyricsenhanced.root.island.IslandViewRegistry
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

internal object IslandTouchHooker {
    private const val TAG = "IslandTouch"

    private val dispatchHooks = mutableListOf<DispatchHook>()

    internal fun releaseForReload() {
        IslandMediaOutput.releaseForReload()
        dispatchHooks.forEach(DispatchHook::dispose)
        dispatchHooks.clear()
    }

    fun hook(module: XposedModule, loader: ClassLoader) {
        runCatching {
            val window = loader.loadClass(IslandTouchHookProfile.WINDOW)
            val dispatch = window.getDeclaredMethod(IslandTouchHookProfile.DISPATCH, MotionEvent::class.java)
            val expanded = loader.loadClass(IslandTouchHookProfile.BASE_CONTENT)
                .getDeclaredMethod(IslandTouchHookProfile.EXPANDED)
            check(dispatch.returnType == Boolean::class.javaPrimitiveType && !Modifier.isStatic(dispatch.modifiers))
            check(expanded.returnType == Boolean::class.javaPrimitiveType && !Modifier.isStatic(expanded.modifiers))
            val click = loader.loadClass(IslandTouchHookProfile.CONTENT)
                .getDeclaredMethod(IslandTouchHookProfile.CLICK)
            check(click.returnType == Void.TYPE && !Modifier.isStatic(click.modifiers))
            val interactor = loader.loadClass(IslandTouchHookProfile.TOUCH_INTERACTOR)
            val longClick = interactor.getDeclaredMethod(IslandTouchHookProfile.LONG_CLICK)
            check(longClick.returnType == Boolean::class.javaPrimitiveType && !Modifier.isStatic(longClick.modifiers))
            val windowField = interactor.getDeclaredField(IslandTouchHookProfile.WINDOW_FIELD)
            check(windowField.type == window && !Modifier.isStatic(windowField.modifiers))
            windowField.isAccessible = true
            val expansionApi = runCatching { IslandTouchExpansionApi.create(loader) }
                .onSuccess { HookLogger.i(TAG, "超级岛原生展开入口已解析") }
                .onFailure { HookLogger.w(TAG, "超级岛原生展开入口不可用: ${it.javaClass.simpleName}") }
                .getOrNull()
            val freeformApi = runCatching { IslandTouchFreeformApi.create(loader) }
                .onSuccess { HookLogger.i(TAG, "超级岛原生小窗启动入口已解析") }
                .onFailure { HookLogger.w(TAG, "超级岛原生小窗启动入口不可用: ${it.javaClass.simpleName}") }
                .getOrNull()
            val dispatchHook = DispatchHook(expanded, expansionApi, freeformApi, click)
            module.hook(longClick).intercept(object : Hooker {
                private var firstHit = false
                override fun intercept(chain: Chain): Any? {
                    if (SystemUiHookLifetime.retired) return chain.proceed()
                    if (BuildConfig.DEBUG && !firstHit) {
                        firstHit = true
                        HookLogger.d(TAG, "系统长按入口首次回调")
                    }
                    val host = windowField.get(chain.thisObject) as? ViewGroup
                    // false avoids View's own long-press haptic for a suppressed callback.
                    return if (dispatchHook.interceptNativeLongPress(host)) false else chain.proceed()
                }
            })
            HookLogger.i(TAG, "系统长按分流 Hook 已安装")
            module.hook(dispatch).intercept(dispatchHook)
            dispatchHooks += dispatchHook
            HookLogger.i(TAG, "超级岛触控 Hook 已安装")
        }.onFailure { HookLogger.w(TAG, "超级岛触控入口不可用: ${it.javaClass.simpleName}") }
    }

    private class DispatchHook(
        private val expanded: Method,
        private val expansionApi: IslandTouchExpansionApi?,
        private val freeformApi: IslandTouchFreeformApi?,
        private val click: Method,
    ) : Hooker {
        private val windows = WeakHashMap<ViewGroup, TouchController>()
        private var firstHit = false
        fun dispose() {
            windows.values.toList().forEach(TouchController::dispose)
            windows.clear()
        }

        fun interceptNativeLongPress(host: ViewGroup?): Boolean =
            host?.let { windows[it]?.interceptNativeLongPress() } ?: false
        override fun intercept(chain: Chain): Any? {
            if (SystemUiHookLifetime.retired) return chain.proceed()
            val window = chain.thisObject as? ViewGroup ?: return chain.proceed()
            val event = chain.args.firstOrNull() as? MotionEvent ?: return chain.proceed()
            if (BuildConfig.DEBUG && !firstHit) {
                firstHit = true
                HookLogger.d(TAG, "超级岛触控首次回调")
            }
            val controller = windows.getOrPut(window) { TouchController(window, expanded, expansionApi, freeformApi, click) }
            if (controller.dispatchingNativeCancel) return chain.proceed()
            val consumed = runCatching { controller.onTouch(event) }.getOrElse {
                val wasConsuming = controller.consuming
                controller.cancel()
                if (BuildConfig.DEBUG) HookLogger.w(TAG, "触控已取消: ${it.javaClass.simpleName}")
                wasConsuming
            }
            if (consumed) return true
            val result = chain.proceed()
            return if (event.actionMasked == MotionEvent.ACTION_DOWN && controller.consuming) true else result
        }
    }

    private fun config(): IslandTouchConfig? {
        val prefs = HookEntry.instance?.prefs ?: return null
        return IslandTouchConfig.read(
            { key, default -> IslandRuntimePreferenceReader.getBoolean(prefs, key, default) },
            { key, default -> IslandRuntimePreferenceReader.getInt(prefs, key, default) },
        )
    }

    private class Target(
        root: ViewGroup,
        val packageName: String,
        val side: IslandTouchSide,
        val config: IslandTouchConfig,
        val controller: MediaController?,
    ) {
        val root = WeakReference(root)
        override fun equals(other: Any?): Boolean = other is Target && root.get() === other.root.get() &&
            packageName == other.packageName && side == other.side && config == other.config &&
            controller?.sessionToken == other.controller?.sessionToken
        override fun hashCode(): Int = packageName.hashCode() * 31 + side.hashCode()
    }

    private class TouchController(
        window: ViewGroup,
        private val expanded: Method,
        private val expansionApi: IslandTouchExpansionApi?,
        private val freeformApi: IslandTouchFreeformApi?,
        private val click: Method,
    ) : View.OnAttachStateChangeListener {
        private val window = WeakReference(window)
        private val handler = Handler(Looper.getMainLooper())
        private val viewConfig = ViewConfiguration.get(window.context)
        private val router = IslandTouchGestureRouter<Target>(
            slop = viewConfig.scaledTouchSlop.toFloat(),
            swipeDistance = maxOf(viewConfig.scaledTouchSlop * 2f, 32f * window.resources.displayMetrics.density),
            doubleTapSlop = viewConfig.scaledDoubleTapSlop.toFloat(),
            longPressMs = ViewConfiguration.getLongPressTimeout().toLong(),
            doubleTapMs = IslandTouchConfig.DEFAULT_DOUBLE_TAP_MS.toLong(),
            isCustom = { target, gesture -> target.config.binding(target.side, gesture).action != IslandTouchAction.NONE },
            cancelNative = ::cancelNativeTouch,
            emit = ::perform,
        )
        var consuming = false
            private set
        private var lastEvent: MotionEvent? = null
        var dispatchingNativeCancel = false
            private set
        private val timer = Runnable {
            runCatching { router.advance(SystemClock.uptimeMillis()) }
                .onFailure { cancel() }
            schedule()
        }

        init { window.addOnAttachStateChangeListener(this) }

        fun onTouch(event: MotionEvent): Boolean {
            val host = window.get() ?: return false
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                if (consuming) cancel()
                val target = resolve(host, event)
                if (target == null) { cancel(); return false }
                rememberEvent(event)
                consuming = true
                router.down(target, event.rawX, event.rawY, event.eventTime,
                    doubleTapTimeoutMs = target.config.doubleTapMs.toLong(),
                    longPressTimeoutMs = target.config.longPressMs.toLong())
                schedule()
                return false // Keep the native press animation and long-press timer.
            }
            if (!consuming) return false
            rememberEvent(event)
            val consumed = if (router.nativeOwnsContact) {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    router.up(event.rawX, event.rawY, event.eventTime)
                }
                false // Native may expand/change state during its gesture; finish that stream.
            } else if (event.pointerCount != 1 || event.actionMasked == MotionEvent.ACTION_CANCEL ||
                router.active?.let { !valid(host, it) } != false) {
                router.cancel()
                true
            } else when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> router.move(event.rawX, event.rawY)
                MotionEvent.ACTION_UP -> router.up(event.rawX, event.rawY, event.eventTime)
                else -> true
            }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                consuming = false
                lastEvent?.recycle()
                lastEvent = null
            }
            schedule()
            return consumed
        }

        fun dispose() {
            cancel()
            window.get()?.removeOnAttachStateChangeListener(this)
        }

        fun cancel() {
            router.cancel()
            handler.removeCallbacks(timer)
            consuming = false
            lastEvent?.recycle()
            lastEvent = null
        }

        fun interceptNativeLongPress(): Boolean {
            val target = router.active ?: return false
            if (!router.nativeOwnsContact && window.get()?.let { valid(it, target) } != true) {
                router.cancel()
                return true
            }
            val intercepted = router.interceptNativeLongPress()
            schedule()
            return intercepted
        }

        private fun rememberEvent(event: MotionEvent) {
            lastEvent?.recycle()
            lastEvent = MotionEvent.obtain(event)
        }

        private fun cancelNativeTouch() {
            val host = window.get() ?: return
            val event = lastEvent ?: return
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            dispatchingNativeCancel = true
            try { host.dispatchTouchEvent(cancel) } finally {
                dispatchingNativeCancel = false
                cancel.recycle()
            }
        }

        private fun schedule() {
            handler.removeCallbacks(timer)
            router.nextDeadline?.let { handler.postAtTime(timer, it) }
        }

        private fun resolve(host: ViewGroup, event: MotionEvent): Target? {
            if (!IslandProbeUtils.isHyperIslandEnabled()) return null
            val config = config()?.takeIf { it.enabled } ?: return null
            for (root in IslandViewRegistry.snapshotCandidates().asReversed()) {
                if (!click.declaringClass.isInstance(root) || !belongsTo(root, host) ||
                    !root.isShown || root.alpha <= 0f || expanded.invoke(root) != false) continue
                val data = IslandProbeUtils.getCurrentIslandData(root)
                val info = IslandProbeUtils.extractMediaIslandInfo(data) ?: continue
                val pill = IslandViewHelper.findViewByName(root, "big_island_view") ?: continue
                if (!contains(pill, event.rawX, event.rawY)) continue
                if (config.unifiedSides) {
                    // One target across the collapsed pill, including space between the two areas.
                    if (config.handles(IslandTouchSide.LEFT)) {
                        return Target(root, info.packageName, IslandTouchSide.LEFT, config,
                            IslandTouchMediaActions.controller(host.context, info.packageName))
                    }
                    continue
                }
                for (side in IslandTouchSide.entries) {
                    val area = IslandViewHelper.findViewByName(root,
                        if (side == IslandTouchSide.LEFT) "area_left" else "area_right") ?: continue
                    if (contains(area, event.rawX, event.rawY) && config.handles(side)) {
                        return Target(root, info.packageName, side, config,
                            IslandTouchMediaActions.controller(host.context, info.packageName))
                    }
                }
            }
            return null
        }

        private fun valid(host: ViewGroup, target: Target): Boolean {
            val root = target.root.get() ?: return false
            return host.isAttachedToWindow && root.isAttachedToWindow && root.isShown && root.alpha > 0f &&
                belongsTo(root, host) && IslandProbeUtils.isHyperIslandEnabled() &&
                config() == target.config && expanded.invoke(root) == false &&
                IslandProbeUtils.extractMediaIslandInfo(IslandProbeUtils.getCurrentIslandData(root))
                    ?.packageName == target.packageName
        }

        private fun perform(target: Target, gesture: IslandTouchGesture) {
            // This also runs from the long-press/single-tap timer, outside the hook's try/catch.
            runCatching {
                val host = window.get() ?: return
                if (!valid(host, target)) return
                val binding = target.config.binding(target.side, gesture)
                if (binding.action == IslandTouchAction.NONE) {
                    if (gesture == IslandTouchGesture.TAP) {
                        target.root.get()?.let { click.invoke(it) }
                        if (BuildConfig.DEBUG) HookLogger.d(TAG, "单击已交给系统原操作")
                    }
                    return
                }
                val isExpansion = binding.action == IslandTouchAction.EXPAND_ISLAND
                val current = if (isExpansion) null
                    else IslandTouchMediaActions.controller(host.context, target.packageName)
                if (!isExpansion && current?.sessionToken != target.controller?.sessionToken) return
                // Acknowledge the recognized gesture before player command dispatch or expansion work.
                // Haptic failure must never prevent the action from being dispatched.
                if (target.config.hapticFeedback) {
                    runCatching {
                        host.performHapticFeedback(if (gesture == IslandTouchGesture.LONG_PRESS)
                            HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.CLOCK_TICK)
                    }.onFailure {
                        if (BuildConfig.DEBUG) HookLogger.d(TAG, "触觉反馈不可用: ${it.javaClass.simpleName}")
                    }
                }
                if (isExpansion) {
                    target.root.get()?.let { expansionApi?.expand(it) }
                } else if (binding.action == IslandTouchAction.OPEN_APP_FREEFORM) {
                    target.root.get()?.let { freeformApi?.open(it, target.packageName) }
                } else {
                    IslandTouchMediaActions.execute(host.context, target.packageName, current, binding,
                        source = target.root.get())
                }
            }.onFailure { if (BuildConfig.DEBUG) HookLogger.w(TAG, "触控目标失效: ${it.javaClass.simpleName}") }
        }

        override fun onViewDetachedFromWindow(v: View) = cancel()
        override fun onViewAttachedToWindow(v: View) = Unit
    }

    private fun contains(view: View, x: Float, y: Float): Boolean {
        if (!view.isShown || view.alpha <= 0f) return false
        val bounds = Rect()
        if (!view.getGlobalVisibleRect(bounds)) return false
        // getGlobalVisibleRect is relative to the root view; MotionEvent.rawX/Y are screen coordinates.
        val origin = IntArray(2)
        view.rootView.getLocationOnScreen(origin)
        bounds.offset(origin[0], origin[1])
        return bounds.contains(x.toInt(), y.toInt())
    }

    private fun belongsTo(root: View, host: ViewGroup): Boolean {
        var view: View? = root
        while (view != null) {
            if (view === host) return true
            view = view.parent as? View
        }
        return false
    }
}
