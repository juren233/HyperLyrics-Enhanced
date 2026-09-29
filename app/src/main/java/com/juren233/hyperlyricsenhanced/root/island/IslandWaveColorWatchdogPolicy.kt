/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

/**
 * 音频律动色模式对账看门狗的窗口化策略：同一首歌内颜色只来自封面，封面不变
 * 颜色就不该变，常驻 1Hz 对账是纯空转。看门狗只在事件（切歌/设置修改/取色应用/
 * 初始化）后布防一个窗口；窗口外与「功能关闭且原生色已还原」状态下完全不运行。
 * 窗口需覆盖取色链的延迟重试周期（2s 重检 × 递增两次 + 1.5s 最小间隔）。
 */
internal object IslandWaveColorWatchdogPolicy {

    const val WINDOW_MS = 15_000L

    /** 功能启用（事件链将来还会用到对账）或覆盖仍挂着（需要找机会还原）才布防。 */
    fun shouldArm(featureEnabled: Boolean, overrideApplied: Boolean): Boolean =
        featureEnabled || overrideApplied

    fun shouldKeepRunning(nowMs: Long, deadlineMs: Long): Boolean = nowMs < deadlineMs
}
