/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island.touch

/**
 * All identifiers verified in the original 2026-09-12 plugin classes2.dex, except
 * DynamicIslandData.getView()Landroid/view/View; in the 2026-10-01 SystemUI classes2.dex.
 * APK hashes and descriptors are recorded in docs/island-touch-controls.md.
 */
internal object IslandTouchExpansionProfile {
    const val CONTENT = "miui.systemui.dynamicisland.window.content.DynamicIslandContentView"
    const val STATE = "miui.systemui.dynamicisland.event.DynamicIslandState"
    const val BIG = "miui.systemui.dynamicisland.event.DynamicIslandState\$BigIsland"
    const val SHOW_ONCE_BIG = "miui.systemui.dynamicisland.event.DynamicIslandState\$ShowOnceBigIsland"
    const val DATA = "com.android.systemui.plugins.miui.dynamicisland.DynamicIslandData"
    const val COORDINATOR = "miui.systemui.dynamicisland.event.DynamicIslandEventCoordinator"
    const val EVENT = "miui.systemui.dynamicisland.event.DynamicIslandEvent"
    const val CLICK_EVENT = "miui.systemui.dynamicisland.event.DynamicIslandEvent\$ClickDynamicIsland"
    const val INSTANCE = "INSTANCE"
    const val GET_STATE = "getState"
    const val GET_DATA = "getCurrentIslandData"
    const val GET_VIEW = "getView"
    const val GET_COORDINATOR = "getDynamicIslandEventCoordinator"
    const val CAN_CLICK = "canClick"
    const val RESET_OPEN_APP = "resetOpenAppFromIsland"
    const val DISPATCH = "dispatchEvent"
    const val SET_USER_EXPANDED = "setUserExpanded"

    fun isCollapsedState(stateClass: String?): Boolean = stateClass == BIG || stateClass == SHOW_ONCE_BIG
    fun canExpand(stateClass: String?, nativeCanClick: Boolean, hasExpandedView: Boolean): Boolean =
        isCollapsedState(stateClass) && nativeCanClick && hasExpandedView
}
