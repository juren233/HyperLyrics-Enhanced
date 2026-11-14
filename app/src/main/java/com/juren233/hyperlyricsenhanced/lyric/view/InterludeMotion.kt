/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view

/**
 * 前奏/间奏相关转场的共用参数，只在此处定义：歌词视图的间奏提升（[RichLyricLineView]、
 * [SpaceGateRichLyricLineView]）与超级岛极简显示期间封面的进出场共用同一时长。
 */
internal object InterludeMotion {
    const val TRANSITION_DURATION_MS = 320L
}
