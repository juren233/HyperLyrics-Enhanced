package com.juren233.hyperlyricsenhanced.root.island

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.view.ViewGroup
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.media.MediaMetadataHelper
import com.juren233.hyperlyricsenhanced.root.HookIslandGlow
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger

internal object IslandHostFacade {
    private var loggedCutoutInfo = false

    fun logCameraCutoutInfo(rootView: ViewGroup) {
        if (loggedCutoutInfo) return
        val cutoutView = IslandViewHelper.findViewByName(rootView, "area_cutout")
        if (cutoutView != null) {
            val location = IntArray(2)
            cutoutView.getLocationOnScreen(location)
            HookLogger.d(
                "IslandHostFacade",
                "摄像头挖孔宽度=${cutoutView.width}px，x=${location[0]}"
            )
        } else {
            HookLogger.d("IslandHostFacade", "未找到摄像头挖孔视图")
        }
        loggedCutoutInfo = true
    }

    fun applyHostSettings(rootView: ViewGroup, prefs: SharedPreferences) {
        val showAlbum = prefs.getBoolean(
            RootConstants.KEY_HOOK_ISLAND_LEFT_ALBUM,
            RootConstants.DEFAULT_HOOK_ISLAND_LEFT_ALBUM
        )
        val showRhythm = prefs.getBoolean(
            RootConstants.KEY_HOOK_ISLAND_RIGHT_ICON,
            RootConstants.DEFAULT_HOOK_ISLAND_RIGHT_ICON
        )

        IslandViewHelper.toggleContainer(rootView, IslandProbeUtils.LEFT_PARENT_NAME, "island_container_module_icon", showAlbum)
        IslandViewHelper.toggleContainer(rootView, IslandProbeUtils.RIGHT_PARENT_NAME, "island_container_module_icon", showRhythm)
        IslandViewHelper.toggleContainer(rootView, IslandProbeUtils.LEFT_PARENT_NAME, IslandProbeUtils.TEXT_CONTAINER_NAME, true)
        IslandViewHelper.toggleContainer(rootView, IslandProbeUtils.RIGHT_PARENT_NAME, IslandProbeUtils.TEXT_CONTAINER_NAME, true)

        if (!showAlbum) {
            IslandViewHelper.clearTextContainerMargin(rootView, IslandProbeUtils.LEFT_PARENT_NAME, clearStart = true, clearEnd = false)
        }
        if (!showRhythm) {
            IslandViewHelper.clearTextContainerMargin(rootView, IslandProbeUtils.RIGHT_PARENT_NAME, clearStart = false, clearEnd = true)
        }
    }

    fun clearAndRefresh(rootView: ViewGroup) {
        // Status-bar width refreshes also reach this path while paused. Share the
        // visible-content guard used by native updates so an already cleared host
        // does not restart its layout on each status-bar tick.
        IslandTextHookerSupport.clearInjectedIsland(rootView)
    }

    fun clearInjectedViews(rootView: ViewGroup) {
        IslandNativeTextCollisionGuard.clear(rootView)
        IslandViewHelper.clearInjectedViews(rootView)
        IslandProgressGlowController.clear(rootView)
    }

    fun triggerSystemRelayout(rootView: ViewGroup) {
        IslandViewHelper.triggerSystemRelayout(rootView)
    }

    fun triggerLyricContentRelayout(rootView: ViewGroup) {
        IslandViewHelper.triggerLyricContentRelayout(rootView)
    }

    fun injectHostGlow(viewGroup: ViewGroup, islandData: Any?, prefs: SharedPreferences) {
        HookIslandGlow.injectAndTriggerGlow(viewGroup, islandData, prefs)
    }

    fun updateHostGlow(rootView: ViewGroup, albumArt: Bitmap?, prefs: SharedPreferences) {
        HookIslandGlow.updateMusicGlow(rootView, albumArt, prefs)
    }

    fun updateProgressGlow(
        rootView: ViewGroup,
        packageName: String,
        mediaInfo: MediaMetadataHelper.MediaInfo,
        prefs: SharedPreferences
    ) {
        IslandProgressGlowController.update(rootView, packageName, mediaInfo, prefs)
    }

    fun updateProgressGlow(
        rootView: ViewGroup,
        packageName: String,
        prefs: SharedPreferences
    ) {
        IslandProgressGlowController.update(rootView, packageName, null, prefs)
    }
}
