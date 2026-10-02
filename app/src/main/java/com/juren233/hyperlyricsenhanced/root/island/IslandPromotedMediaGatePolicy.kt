/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

/**
 * 双播断供窗口的媒体通知提升放行判定（**已证伪停用**，留档防复用）。
 *
 * 2026-09-29 深挖定稿：媒体岛由主 APK MiuiIslandMediaController 独立管线驱动，
 * 媒体通知被焦点门禁拒绝是常态（单播正常时同样发生），promoted 标记与断供
 * 无关；强行放行会使媒体通知进入原生 liveupdate 模板管线并崩溃（见
 * DEBUGGING_MISTAKES.md「岛消失」条目）。真实根因与修复见
 * [IslandSelfHealGuardPolicy]。
 */
internal object IslandPromotedMediaGatePolicy {
    fun shouldPromote(
        sbnIsMediaNotification: Boolean,
        sbnPackageName: String?,
        lyricPackageName: String?,
        playbackActive: Boolean,
    ): Boolean = sbnIsMediaNotification &&
        playbackActive &&
        !lyricPackageName.isNullOrEmpty() &&
        sbnPackageName == lyricPackageName
}
