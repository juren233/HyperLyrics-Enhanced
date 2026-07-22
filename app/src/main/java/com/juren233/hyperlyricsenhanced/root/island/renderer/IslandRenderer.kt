package com.juren233.hyperlyricsenhanced.root.island.renderer

/**
 * 超级岛渲染器接口。
 * 仅负责外部事件驱动的更新（歌词变化、播放状态、进度同步）。
 * 注入逻辑由 IslandTextHooker 在 bind() 中统一处理。
 */
interface IslandRenderer {
    fun refreshActiveIsland()
    fun updateLyricLine()
    fun updatePosition(position: Long)
    fun seekTo(position: Long)
    fun onPlaybackStateChanged(isPlaying: Boolean)
    fun clearAllViews()
}
