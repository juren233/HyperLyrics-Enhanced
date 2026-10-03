/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island.touch

import android.view.View
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Executes only the native collapsed-island expansion branch, never its open-app branch. */
internal class IslandTouchExpansionApi private constructor(
    private val getState: Method,
    private val canClick: Method,
    private val getData: Method,
    private val getView: Method,
    private val getCoordinator: Method,
    private val resetOpenApp: Method,
    private val dispatch: Method,
    private val setUserExpanded: Method,
    private val clickEvent: Any,
) {
    fun expand(content: View): Boolean {
        val state = getState.invoke(content) ?: return false
        if (!IslandTouchExpansionProfile.isCollapsedState(state.javaClass.name)) return false
        val data = getData.invoke(content) ?: return false
        if (!IslandTouchExpansionProfile.canExpand(
                state.javaClass.name, canClick.invoke(content, state) == true, getView.invoke(data) != null,
            )) return false
        val coordinator = getCoordinator.invoke(content) ?: return false
        // Same order as DynamicIslandContentView.onIslandClick's BigIsland branch.
        resetOpenApp.invoke(content)
        dispatch.invoke(coordinator, clickEvent, content)
        setUserExpanded.invoke(coordinator, true)
        if (BuildConfig.DEBUG) HookLogger.d("IslandTouch", "native_expand_requested")
        return true
    }

    companion object {
        fun create(loader: ClassLoader): IslandTouchExpansionApi {
            val profile = IslandTouchExpansionProfile
            val base = loader.loadClass(IslandTouchHookProfile.BASE_CONTENT)
            val content = loader.loadClass(profile.CONTENT)
            val state = loader.loadClass(profile.STATE)
            val data = loader.loadClass(profile.DATA)
            val coordinator = loader.loadClass(profile.COORDINATOR)
            val event = loader.loadClass(profile.EVENT)
            val clickEvent = loader.loadClass(profile.CLICK_EVENT)
            val instance = clickEvent.getDeclaredField(profile.INSTANCE)
            check(instance.type == clickEvent && Modifier.isStatic(instance.modifiers))
            return IslandTouchExpansionApi(
                method(base, profile.GET_STATE, state),
                method(content, profile.CAN_CLICK, Boolean::class.javaPrimitiveType!!, state),
                method(base, profile.GET_DATA, data),
                method(data, profile.GET_VIEW, View::class.java),
                method(base, profile.GET_COORDINATOR, coordinator),
                method(content, profile.RESET_OPEN_APP, Void.TYPE),
                method(coordinator, profile.DISPATCH, Void.TYPE, event, content),
                method(coordinator, profile.SET_USER_EXPANDED, Void.TYPE, Boolean::class.javaPrimitiveType!!),
                requireNotNull(instance.get(null)),
            )
        }

        private fun method(owner: Class<*>, name: String, result: Class<*>, vararg args: Class<*>): Method =
            owner.getDeclaredMethod(name, *args).apply {
                check(returnType == result && !Modifier.isStatic(modifiers))
                isAccessible = true
            }
    }
}
