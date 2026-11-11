/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

/**
 * Verified in original DEX, not JADX aliases:
 * SystemUI OS4.0.0.6 classes2.dex: PhoneStatusBarView.onAttachedToWindow()V.
 * workspace/apks/miui-systemui-phone-20260912/plugin-dex/classes2.dex:
 * IslandContentViewCalculationResult.<init>(IIIIIIIII)V and all getters below ()I;
 * DynamicIslandBaseContentView.getStatusBarDatePosX()I. Constructor order verified
 * against its iput instructions. Pad x remains (screenWidth - width)/2, with the
 * native date-position translation applied by the host, as in the existing unlock hook.
 */
internal object IslandDynamicLimitProfile {
    /**
     * 岛侧视图包前缀：动态岛自身的视图（内容视图、小岛过渡内容、FakeView）永远不会是
     * 合法的状态栏避让障碍。2026-09-15 平板日志实证：两个 50px 非文本 span 恒位于
     * 岛视觉右缘 (174+W)+15px margin 且随岛宽 1:1 左移（上限被逐秒棘轮压向胶囊下限），
     * 即岛内部布局在岛右缘之外的视图被 collect() 误收为障碍。
     */
    const val ISLAND_VIEW_PACKAGE_PREFIX = "miui.systemui.dynamicisland."
    const val STATUS_BAR_CLASS = "com.android.systemui.statusbar.phone.PhoneStatusBarView"

    /**
     * 通知图标容器：其图标槽位被宿主排在岛几何之后并随岛宽重排（随动内容），
     * 收进障碍集会让上限恒等于岛当前宽度，形成逐秒棘轮收缩。
     * 2026-09-16 平板运行时身份日志实证：影子 span（50px、左缘恒=岛右缘+15px、
     * 随岛在途动画 1:1 移动）全部来自该容器内的 StatusBarIconView。
     */
    const val NOTIFICATION_ICON_CONTAINER_CLASS =
        "com.android.systemui.statusbar.phone.NotificationIconContainer"

    /**
     * 手机端状态栏时钟视图的运行时类名，动态上限的锚点按此身份确定，不再用几何猜测。
     * 依据：OS4.0.0.8 MiuiSystemUI.apk classes3.dex 中
     * `Lcom/android/systemui/statusbar/views/MiuiClock;` 的 superclass 是 `Landroid/widget/TextView;`；
     * 2026-11-11 真机视图层级导出确认 Span(83,266) 即该类。
     * 其他系统版本是否存在该类：未验证。
     */
    const val STATUS_BAR_CLOCK_CLASS = "com.android.systemui.statusbar.views.MiuiClock"
    const val ATTACH_METHOD = "onAttachedToWindow"
    const val DATE_POSITION_GETTER = "getStatusBarDatePosX"
    const val SCREEN_WIDTH_GETTER = "getScreenWidth"
    // Original phone plugin classes2.dex: BaseHelper declares public abstract ()I;
    // PhoneHelper and PadHelper implement public instance ()I. This is the capsule
    // height; DynamicIslandContentView.height becomes the screen height in AppExpanded.
    const val ISLAND_HEIGHT_GETTER = "getIslandViewHeight"

    fun readIslandHeight(helper: Any): Int {
        val method = IslandReflect.noArgMethod(helper.javaClass, ISLAND_HEIGHT_GETTER)
            ?: throw NoSuchMethodException(ISLAND_HEIGHT_GETTER)
        require(method.returnType == Int::class.javaPrimitiveType)
        return (method.invoke(helper) as Int).also { require(it > 0) }
    }
    val RESULT_GETTERS = listOf(
        "getBigIslandViewWidth", "getBigIslandLeftWidth", "getBigIslandRightWidth",
        "getBigIslandX", "getBigIslandMarginWidth", "getBigIslandViewWidthHasSmallIsland",
        "getBigIslandLeftWidthHasSmallIsland", "getBigIslandRightWidthHasSmallIsland",
        "getBigIslandXHasSmallIsland",
    )
}
