/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

/**
 * 流光息屏保活 tick 的屏幕状态门控：保活唯一有活干的场景是非交互（息屏 doze，
 * 需要续 draw wake lock 才有帧合成）；亮屏时帧合成由视图自身帧循环驱动、
 * 播放态校正有数据重绑路径，tick 每 700ms 醒来纯属空转。
 * 亮屏时 tick 停止，由屏幕广播在息屏时重新拉起。
 */
internal object FlowKeepAlivePolicy {

    fun tickNeeded(screenOn: Boolean): Boolean = !screenOn
}
