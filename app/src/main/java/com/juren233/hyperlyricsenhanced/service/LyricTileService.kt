package com.juren233.hyperlyricsenhanced.service
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.UIConstants


import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.edit

class LyricTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    private fun updateTileState() {
        val prefs = getSharedPreferences(UIConstants.PREF_NAME, MODE_PRIVATE)
        val isEnabled = prefs.getBoolean(RootConstants.KEY_HOOK_ENABLE_DYNAMIC_ISLAND, RootConstants.DEFAULT_HOOK_ENABLE_DYNAMIC_ISLAND)
        
        val tile = qsTile ?: return
        tile.label = "HyperLyrics Enhanced 媒体信息监听"
        if (isEnabled) {
            tile.state = Tile.STATE_ACTIVE
        } else {
            tile.state = Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val prefs = getSharedPreferences(UIConstants.PREF_NAME, MODE_PRIVATE)
        val isEnabled = prefs.getBoolean(RootConstants.KEY_HOOK_ENABLE_DYNAMIC_ISLAND, RootConstants.DEFAULT_HOOK_ENABLE_DYNAMIC_ISLAND)
        val nextState = !isEnabled
        
        prefs.edit { putBoolean(RootConstants.KEY_HOOK_ENABLE_DYNAMIC_ISLAND, nextState) }
        
        // 不再需要发 Intent 给 ForegroundLyricService，
        // LiveLyricService 内的 NotificationPresenter 会通过 Flow 自动感知开关变化。
        // 如果 LiveLyricService 未连接，尝试重新绑定。
        if (nextState) {
            LiveLyricService.ensureListenerBound(this)
        }
        
        updateTileState()
    }

}
