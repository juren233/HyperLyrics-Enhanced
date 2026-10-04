/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import com.juren233.hyperlyricsenhanced.root.reload.SystemUiHookLifetime
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exact runtime identifiers verified from the original phone and tablet plugin DEX files.
 *
 * Do not replace these with JADX aliases or broaden the matchers. The full width event and its
 * state update and transition lifecycle must run, even for an identical target. The controller
 * already counted this transition in expectDispatches; replacing it with NoOp leaks a completion.
 * Lottie scene selection is scoped to the exact width transition.
 */
internal object IslandWidthEventMethodProfile {
    const val ANIMATION_CONTROLLER_CLASS =
        "miui.systemui.dynamicisland.anim.DynamicIslandAnimationController"
    const val TRANSITION_TYPE_CLASS =
        "miui.systemui.dynamicisland.anim.model.AnimTransitionType"
    const val BIG_ISLAND_CHANGED_CLASS =
        "miui.systemui.dynamicisland.anim.model.AnimTransitionType\$BigIslandChanged"
    const val EVENT_COORDINATOR_CLASS =
        "miui.systemui.dynamicisland.event.DynamicIslandEventCoordinator"
    const val EVENT_CLASS = "miui.systemui.dynamicisland.event.DynamicIslandEvent"
    const val UPDATE_WIDTH_EVENT_CLASS =
        "miui.systemui.dynamicisland.event.DynamicIslandEvent\$UpdateDynamicIslandWidth"
    const val CONTENT_VIEW_CLASS =
        "miui.systemui.dynamicisland.window.content.DynamicIslandContentView"
    const val DISPATCH_METHOD = "dispatchEvent"
    const val DISPATCH_TRANSITION_METHOD = "dispatchTransition"
    const val VISIBLE_LOTTIE_SCENES_METHOD = "visibleLottieScenesFor"

    fun isDispatchMethod(method: Method): Boolean = isDispatchMethod(
        name = method.name,
        returnTypeName = method.returnType.name,
        parameterTypeNames = method.parameterTypes.map { it.name },
        isStatic = Modifier.isStatic(method.modifiers),
    )

    internal fun isDispatchMethod(
        name: String,
        returnTypeName: String,
        parameterTypeNames: List<String>,
        isStatic: Boolean = false,
    ): Boolean = name == DISPATCH_METHOD &&
        !isStatic &&
        returnTypeName == Void.TYPE.name &&
        parameterTypeNames == listOf(EVENT_CLASS, CONTENT_VIEW_CLASS)

    fun isDispatchTransitionMethod(method: Method): Boolean = isDispatchTransitionMethod(
        name = method.name,
        returnTypeName = method.returnType.name,
        parameterTypeNames = method.parameterTypes.map { it.name },
        isStatic = Modifier.isStatic(method.modifiers),
    )

    internal fun isDispatchTransitionMethod(
        name: String,
        returnTypeName: String,
        parameterTypeNames: List<String>,
        isStatic: Boolean = false,
    ): Boolean = name == DISPATCH_TRANSITION_METHOD &&
        !isStatic &&
        returnTypeName == Void.TYPE.name &&
        parameterTypeNames == listOf(TRANSITION_TYPE_CLASS, CONTENT_VIEW_CLASS)

    fun isVisibleLottieScenesMethod(method: Method): Boolean = isVisibleLottieScenesMethod(
        name = method.name,
        returnTypeName = method.returnType.name,
        parameterTypeNames = method.parameterTypes.map { it.name },
        isStatic = Modifier.isStatic(method.modifiers),
    )

    internal fun isVisibleLottieScenesMethod(
        name: String,
        returnTypeName: String,
        parameterTypeNames: List<String>,
        isStatic: Boolean = false,
    ): Boolean = name == VISIBLE_LOTTIE_SCENES_METHOD &&
        !isStatic &&
        returnTypeName == Set::class.java.name &&
        parameterTypeNames == listOf(TRANSITION_TYPE_CLASS)
}

/**
 * Keeps the complete native width event and transition, while avoiding a Lottie restart through
 * the generic island-transition manager for a lyric width update.
 *
 * The request and event scopes are thread-local and identity-gated. The resulting transition may
 * start asynchronously, so it is carried across that boundary in a small identity map and removed
 * the first time the same coordinator asks for its visible Lottie scenes. A bounded map makes an
 * unmatched/cancelled transition fail open instead of retaining arbitrary host objects.
 */
internal object IslandWidthEventRebindGuard {
    private const val TAG = "IslandWidthEventGuard"
    private const val MAX_PENDING_TRANSITIONS = 16

    private data class WidthEventScope(
        val coordinator: Any,
        val target: Any,
    )

    private val lyricRelayoutTargets = ThreadLocal<ArrayDeque<Any>>()
    private val activeWidthEvents = ThreadLocal<ArrayDeque<WidthEventScope>>()
    private val pendingTransitionCoordinators = IdentityHashMap<Any, Any>()

    fun <T> aroundLyricWidthRelayout(target: Any, action: () -> T): T {
        val targets = lyricRelayoutTargets.get()
            ?: ArrayDeque<Any>().also(lyricRelayoutTargets::set)
        targets.addLast(target)
        return try {
            action()
        } finally {
            targets.removeLast()
            if (targets.isEmpty()) lyricRelayoutTargets.remove()
        }
    }

    internal fun isScopedWidthEvent(eventClassName: String?, dispatchedTarget: Any?): Boolean {
        val activeTarget = lyricRelayoutTargets.get()?.peekLast() ?: return false
        return eventClassName == IslandWidthEventMethodProfile.UPDATE_WIDTH_EVENT_CLASS &&
            dispatchedTarget === activeTarget
    }

    internal fun <T> duringWidthEvent(
        coordinator: Any,
        target: Any,
        action: () -> T,
    ): T {
        val scopes = activeWidthEvents.get()
            ?: ArrayDeque<WidthEventScope>().also(activeWidthEvents::set)
        scopes.addLast(WidthEventScope(coordinator, target))
        return try {
            action()
        } finally {
            scopes.removeLast()
            if (scopes.isEmpty()) activeWidthEvents.remove()
        }
    }

    internal fun markWidthTransition(
        transitionClassName: String?,
        transition: Any?,
        dispatchedTarget: Any?,
    ): Boolean {
        val scope = activeWidthEvents.get()?.peekLast() ?: return false
        if (transitionClassName != IslandWidthEventMethodProfile.BIG_ISLAND_CHANGED_CLASS ||
            transition == null || dispatchedTarget !== scope.target
        ) {
            return false
        }
        synchronized(pendingTransitionCoordinators) {
            if (pendingTransitionCoordinators.size >= MAX_PENDING_TRANSITIONS &&
                !pendingTransitionCoordinators.containsKey(transition)
            ) {
                pendingTransitionCoordinators.clear()
            }
            pendingTransitionCoordinators[transition] = scope.coordinator
        }
        return true
    }

    internal fun consumeWidthTransition(coordinator: Any?, transition: Any?): Boolean {
        if (coordinator == null || transition == null) return false
        synchronized(pendingTransitionCoordinators) {
            val markedCoordinator = pendingTransitionCoordinators[transition] ?: return false
            if (markedCoordinator !== coordinator) return false
            pendingTransitionCoordinators.remove(transition)
            return true
        }
    }

    private fun discardWidthTransition(transition: Any?) {
        if (transition == null) return
        synchronized(pendingTransitionCoordinators) {
            pendingTransitionCoordinators.remove(transition)
        }
    }

    internal fun clearStateForTest() {
        lyricRelayoutTargets.remove()
        activeWidthEvents.remove()
        synchronized(pendingTransitionCoordinators) {
            pendingTransitionCoordinators.clear()
        }
    }

    fun install(module: XposedModule, classLoader: ClassLoader) {
        IslandContentUpdateCoordinator.install(classLoader)
        val coordinatorClass = classLoader.loadClass(
            IslandWidthEventMethodProfile.EVENT_COORDINATOR_CLASS
        )
        val animationControllerClass = classLoader.loadClass(
            IslandWidthEventMethodProfile.ANIMATION_CONTROLLER_CLASS
        )
        val dispatchMethods = coordinatorClass.declaredMethods.filter(
            IslandWidthEventMethodProfile::isDispatchMethod
        )
        val dispatchTransitionMethods = animationControllerClass.declaredMethods.filter(
            IslandWidthEventMethodProfile::isDispatchTransitionMethod
        )
        val visibleLottieScenesMethods = coordinatorClass.declaredMethods.filter(
            IslandWidthEventMethodProfile::isVisibleLottieScenesMethod
        )
        check(dispatchMethods.size == 1) {
            "Expected one exact DynamicIslandEventCoordinator.dispatchEvent descriptor, " +
                "found ${dispatchMethods.size}"
        }
        check(dispatchTransitionMethods.size == 1) {
            "Expected one exact DynamicIslandAnimationController.dispatchTransition descriptor, " +
                "found ${dispatchTransitionMethods.size}"
        }
        check(visibleLottieScenesMethods.size == 1) {
            "Expected one exact DynamicIslandEventCoordinator.visibleLottieScenesFor descriptor, " +
                "found ${visibleLottieScenesMethods.size}"
        }

        val dispatchMethod = dispatchMethods.single().apply { isAccessible = true }
        val dispatchTransitionMethod = dispatchTransitionMethods.single().apply {
            isAccessible = true
        }
        val visibleLottieScenesMethod = visibleLottieScenesMethods.single().apply {
            isAccessible = true
        }
        module.deoptimize(dispatchMethod)
        module.deoptimize(dispatchTransitionMethod)
        module.deoptimize(visibleLottieScenesMethod)
        module.hook(dispatchMethod).intercept(DispatchHook())
        module.hook(dispatchTransitionMethod).intercept(DispatchTransitionHook())
        module.hook(visibleLottieScenesMethod).intercept(VisibleLottieScenesHook())
        HookLogger.i(
            TAG,
            "歌词宽度转场律动保护 Hook 已安装: dispatch=$dispatchMethod, " +
                "transition=$dispatchTransitionMethod, scenes=$visibleLottieScenesMethod"
        )
    }

    private class DispatchHook : Hooker {
        private val firstCallbackLogged = AtomicBoolean()

        override fun intercept(chain: Chain): Any? {
            if (SystemUiHookLifetime.retired) return chain.proceed()
            logFirstCallback(firstCallbackLogged, IslandWidthEventMethodProfile.DISPATCH_METHOD)
            val event = chain.args.getOrNull(0)
            val target = chain.args.getOrNull(1)
            if (!isScopedWidthEvent(event?.javaClass?.name, target)) return chain.proceed()
            val coordinator = chain.thisObject ?: return chain.proceed()
            target ?: return chain.proceed()

            if (BuildConfig.DEBUG) {
                HookLogger.d(
                    TAG,
                    "歌词宽度事件完整分发: target=${System.identityHashCode(target)}"
                )
            }
            return duringWidthEvent(coordinator, target) { chain.proceed() }
        }
    }

    private class DispatchTransitionHook : Hooker {
        private val firstCallbackLogged = AtomicBoolean()

        override fun intercept(chain: Chain): Any? {
            if (SystemUiHookLifetime.retired) return chain.proceed()
            logFirstCallback(
                firstCallbackLogged,
                IslandWidthEventMethodProfile.DISPATCH_TRANSITION_METHOD,
            )
            val transition = chain.args.getOrNull(0)
            val target = chain.args.getOrNull(1)
            val marked = markWidthTransition(
                transitionClassName = transition?.javaClass?.name,
                transition = transition,
                dispatchedTarget = target,
            )
            if (marked && BuildConfig.DEBUG) {
                HookLogger.d(
                    TAG,
                    "已标记歌词宽度 BigIslandChanged: " +
                        "transition=${System.identityHashCode(transition)}, " +
                        "target=${System.identityHashCode(target)}"
                )
            }
            return try {
                IslandContentUpdateCoordinator.duringNativeTransition(target) { chain.proceed() }
            } catch (throwable: Throwable) {
                if (marked) discardWidthTransition(transition)
                throw throwable
            }
        }
    }

    private class VisibleLottieScenesHook : Hooker {
        private val firstCallbackLogged = AtomicBoolean()

        override fun intercept(chain: Chain): Any? {
            if (SystemUiHookLifetime.retired) return chain.proceed()
            logFirstCallback(
                firstCallbackLogged,
                IslandWidthEventMethodProfile.VISIBLE_LOTTIE_SCENES_METHOD,
            )
            val transition = chain.args.getOrNull(0)
            if (!consumeWidthTransition(chain.thisObject, transition)) return chain.proceed()

            if (BuildConfig.DEBUG) {
                HookLogger.d(
                    TAG,
                    "已跳过歌词宽度转场的 Lottie 重置: " +
                        "transition=${System.identityHashCode(transition)}"
                )
            }
            return emptySet<String>()
        }
    }

    private fun logFirstCallback(marker: AtomicBoolean, methodName: String) {
        if (BuildConfig.DEBUG && marker.compareAndSet(false, true)) {
            HookLogger.d(TAG, "Hook 首次真实回调: method=$methodName")
        }
    }
}
