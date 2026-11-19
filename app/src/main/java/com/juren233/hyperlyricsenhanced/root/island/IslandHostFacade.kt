package com.juren233.hyperlyricsenhanced.root.island

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.view.ViewGroup
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.media.MediaMetadataHelper
import com.juren233.hyperlyricsenhanced.root.HookIslandGlow
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.util.WeakHashMap

internal object IslandHostFacade {
    private var loggedCutoutInfo = false

    /** 宿主上应用的封面与音频律动显隐。 */
    private data class IconVisibility(val album: Boolean, val rhythm: Boolean)

    /**
     * 各宿主上一次应用的封面与音频律动显隐；极简显示开始或结束时两者都可能临时变化
     * （封面临时显示，律动按「右侧显示」隐藏或显示），换句刷新据此判断是否需要重新应用。
     */
    private val appliedIconVisibility = WeakHashMap<ViewGroup, IconVisibility>()

    private fun iconVisibility(config: IslandSlotRuntimeConfig) = IconVisibility(
        album = IslandSlotContentAssembler.isAlbumVisible(config),
        rhythm = IslandSlotContentAssembler.isRhythmVisible(config),
    )

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

    /**
     * 「音频封面」关闭时，极简显示开始或结束带出的封面进出场（[IslandMinimalCoverTransition]）：出现时先等
     * 左槽歌词在原位淡出落定，再以透明状态占位（岛宽随之伸展）并淡入；消失时占位立即收起、封面以快照在
     * 原位淡出，结束后撤回样式。
     *
     * 封面显隐只在这里比对并记下：换句刷新、整岛刷新、注入与轻量恢复等会按目标摆放封面的入口都先经过这里，
     * 先到的入口不会直接按目标摆放而吞掉进出场（被吞掉时占位直接收起，渐变封面背景却留在完整显示）。
     * 须在摆放占位、边距与锚点之前调用。返回是否开始了进出场。
     */
    fun syncMinimalCoverTransition(rootView: ViewGroup, config: IslandSlotRuntimeConfig): Boolean {
        if (config.showAlbum) return false
        val appearing = IslandSlotContentAssembler.isAlbumVisible(config)
        synchronized(appliedIconVisibility) {
            val applied = appliedIconVisibility[rootView] ?: return false
            if (applied.album == appearing) return false
            appliedIconVisibility[rootView] = applied.copy(album = appearing)
        }
        if (BuildConfig.DEBUG) {
            HookLogger.d(
                "IslandHostFacade",
                "极简显示封面进出场: root=${System.identityHashCode(rootView).toString(16)}, appearing=$appearing",
            )
        }
        if (appearing) {
            // 先让左槽歌词在原位淡出落定，再放出封面占位并淡入，歌词不被占位挤动。
            IslandMinimalCoverTransition.deferEntry(rootView) {
                IslandMinimalCoverTransition.prepareShow(rootView)
                IslandLyricTextInjector.restoreExistingSlotsLightweight(rootView)
                triggerLyricContentRelayout(rootView)
                rootView.post {
                    IslandAlbumCoverStyleHooker.refresh()
                    IslandMinimalCoverTransition.startShow()
                }
            }
            // 挂起期间先把样式套上：放出占位后的样式刷新可能被宿主形变延后，届时封面已在淡入。
            if (IslandMinimalCoverTransition.entryPending) {
                rootView.post { IslandAlbumCoverStyleHooker.refresh() }
            }
        } else {
            IslandMinimalCoverTransition.startHide(rootView) {
                IslandLyricTextInjector.restoreExistingSlotsLightweight(rootView)
                IslandAlbumCoverStyleHooker.refresh()
                triggerLyricContentRelayout(rootView)
            }
        }
        return true
    }

    fun applyHostSettings(rootView: ViewGroup, prefs: SharedPreferences) {
        // 极简显示期间封面总是显示；「右侧显示」为倒计时圆点时律动按关闭处理（隐藏容器、去掉文字容器的边距）。
        val config = IslandSlotRuntimeConfig.from(prefs)
        // 「音频封面」开启时封面常驻，不需要极简显示的进出场；设置变更途中直接复位。
        if (config.showAlbum) IslandMinimalCoverTransition.cancel()
        syncMinimalCoverTransition(rootView, config)
        val visibility = iconVisibility(config)
        synchronized(appliedIconVisibility) { appliedIconVisibility[rootView] = visibility }
        // 封面占位：极简显示结束时立即收起（淡出由快照在原位完成），开始时等左槽歌词淡出落定再放出
        //（见 IslandMinimalCoverTransition）。记录的是目标显隐。
        val showAlbum = IslandSlotContentAssembler.isAlbumPlaced(config)
        val showRhythm = visibility.rhythm

        IslandViewHelper.toggleContainer(rootView, IslandProbeUtils.LEFT_PARENT_NAME, "island_container_module_icon", showAlbum)
        IslandViewHelper.toggleContainer(rootView, IslandProbeUtils.RIGHT_PARENT_NAME, "island_container_module_icon", showRhythm)
        IslandViewHelper.toggleContainer(rootView, IslandProbeUtils.LEFT_PARENT_NAME, IslandProbeUtils.TEXT_CONTAINER_NAME, true)
        IslandViewHelper.toggleContainer(rootView, IslandProbeUtils.RIGHT_PARENT_NAME, IslandProbeUtils.TEXT_CONTAINER_NAME, true)

        if (!showAlbum) {
            IslandViewHelper.clearTextContainerMargin(rootView, IslandProbeUtils.LEFT_PARENT_NAME, clearStart = true, clearEnd = false)
        } else {
            // 封面重新可见（极简显示开始或开关重新打开）：还原此前清掉的起始边距。
            IslandViewHelper.restoreTextContainerMarginStart(rootView, IslandProbeUtils.LEFT_PARENT_NAME)
        }
        if (!showRhythm) {
            IslandViewHelper.clearTextContainerMargin(rootView, IslandProbeUtils.RIGHT_PARENT_NAME, clearStart = false, clearEnd = true)
        } else {
            // 律动重新可见（极简显示结束或开关重新打开）：还原此前清掉的末端边距。
            IslandViewHelper.restoreTextContainerMarginEnd(rootView, IslandProbeUtils.RIGHT_PARENT_NAME)
        }
    }

    /** 与该宿主上次应用相比，封面、音频律动的显隐各自是否该变（极简显示开始或结束）。 */
    data class IconVisibilityChange(val album: Boolean, val rhythm: Boolean) {
        val any: Boolean get() = album || rhythm
    }

    /** 从未应用过时视为没有变化。 */
    fun iconVisibilityChange(rootView: ViewGroup, config: IslandSlotRuntimeConfig): IconVisibilityChange {
        val applied = synchronized(appliedIconVisibility) { appliedIconVisibility[rootView] }
            ?: return IconVisibilityChange(album = false, rhythm = false)
        val expected = iconVisibility(config)
        return IconVisibilityChange(
            album = applied.album != expected.album,
            rhythm = applied.rhythm != expected.rhythm,
        )
    }

    fun clearAndRefresh(rootView: ViewGroup) {
        // Status-bar width refreshes also reach this path while paused. Share the
        // visible-content guard used by native updates so an already cleared host
        // does not restart its layout on each status-bar tick.
        IslandTextHookerSupport.clearInjectedIsland(rootView)
    }

    fun clearInjectedViews(rootView: ViewGroup) {
        IslandGaplessSeam.restore(rootView)
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
