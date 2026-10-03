/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island.touch

/**
 * Original MIUISystemUIPlugin.apk classes2.dex (2026-09-12), SHA-256
 * f07be6a32708b0205d5ecc91ed8ce1a38fd64a17de3d49dd88f6306f9d2dab99:
 * Lmiui/systemui/dynamicisland/window/DynamicIslandWindowView;->dispatchTouchEvent(Landroid/view/MotionEvent;)Z, public instance.
 * Lmiui/systemui/dynamicisland/window/content/DynamicIslandBaseContentView;->isExpandedShowing()Z, public final instance.
 * Lmiui/systemui/dynamicisland/window/content/DynamicIslandContentView;->onIslandClick()V, public instance.
 * Lmiui/systemui/dynamicisland/touch/domain/interactor/DynamicIslandTouchInteractor;->performLongClick()Z, public final instance.
 * Its windowView field is Lmiui/systemui/dynamicisland/window/DynamicIslandWindowView;, private final instance.
 * No decompiler aliases are accepted. See docs/island-touch-controls.md.
 */
internal object IslandTouchHookProfile {
    const val WINDOW = "miui.systemui.dynamicisland.window.DynamicIslandWindowView"
    const val BASE_CONTENT = "miui.systemui.dynamicisland.window.content.DynamicIslandBaseContentView"
    const val DISPATCH = "dispatchTouchEvent"
    const val EXPANDED = "isExpandedShowing"
    const val CONTENT = "miui.systemui.dynamicisland.window.content.DynamicIslandContentView"
    const val CLICK = "onIslandClick"
    const val TOUCH_INTERACTOR = "miui.systemui.dynamicisland.touch.domain.interactor.DynamicIslandTouchInteractor"
    const val LONG_CLICK = "performLongClick"
    const val WINDOW_FIELD = "windowView"
}
