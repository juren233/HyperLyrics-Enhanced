/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.reload

import android.app.Application
import android.view.ViewGroup
import com.juren233.hyperlyricsenhanced.root.HookEntry
import com.juren233.hyperlyricsenhanced.root.UnlockFocusWhitelist
import com.juren233.hyperlyricsenhanced.root.UnlockIslandWhitelist
import com.juren233.hyperlyricsenhanced.root.island.*
import com.juren233.hyperlyricsenhanced.root.island.renderer.BaseIslandRenderer
import com.juren233.hyperlyricsenhanced.root.island.touch.IslandTouchHooker
import com.juren233.hyperlyricsenhanced.root.lyricon.central.EmbeddedLyriconCentralController
import com.juren233.hyperlyricsenhanced.root.mediacard.island.IslandExpandedMediaAmbientFlowHooker
import com.juren233.hyperlyricsenhanced.root.mediacard.notification.NotificationMediaAmbientFlowHooker
import com.juren233.hyperlyricsenhanced.root.mediacard.notification.NotificationMediaAodLyricHooker
import com.juren233.hyperlyricsenhanced.root.mediacard.notification.NotificationMediaCoverStyleHooker
import com.juren233.hyperlyricsenhanced.root.mediacard.notification.background.MediaBackgroundRendererPool
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.root.utils.RuntimePerfDiagnostics
import io.github.libxposed.api.XposedInterface.HookHandle

/** The host owns transferred objects; each module generation creates its own listeners and UI. */
internal object SystemUiHotReload {
    fun prepare(module: HookEntry, app: Application): Map<String, Any?> {
        val state = ReloadSnapshot.create().apply {
            put("app", app)
            put("systemLoader", requireNotNull(module.systemUiClassLoader))
            put("islandLoaders", SystemUIHookRegistry.snapshotClassLoaders())
            put("islands", IslandViewRegistry.snapshotAttached().map { (view, pkg) ->
                arrayOf(view, pkg)
            }.toTypedArray())
            put("candidates", IslandViewRegistry.snapshotCandidates().toTypedArray())
            put("statusBar", IslandStatusBarSpaceMonitor.snapshotForReload())
            put("covers", IslandAlbumCoverStyleHooker.snapshotForReload())
            put("waves", IslandMusicWaveColorHooker.snapshotForReload())
            put("expandedMedia", IslandExpandedMediaAmbientFlowHooker.snapshotForReload())
            put("notificationCover", NotificationMediaCoverStyleHooker.snapshotForReload())
            put("notificationMedia", NotificationMediaAmbientFlowHooker.snapshotForReload())
            put("aod", NotificationMediaAodLyricHooker.snapshotForReload())
        }
        // Runs synchronously on the UI thread. A posted release is not a completed release.
        BaseIslandRenderer.beginHotReload()
        module.cleanupForHotReload()
        IslandTouchHooker.releaseForReload()
        IslandStatusBarSpaceMonitor.releaseForReload()
        IslandStatusBarColorMonitor.releaseForReload()
        IslandAlbumCoverStyleHooker.releaseAll()
        IslandAlbumCoverStyleHooker.cleanup()
        IslandExpandedMediaAmbientFlowHooker.releaseAll()
        NotificationMediaCoverStyleHooker.releaseAll()
        NotificationMediaAmbientFlowHooker.releaseAll()
        NotificationMediaAodLyricHooker.releaseAll()
        IslandMusicWaveColorHooker.releaseForReload()
        IslandProgressGlowController.clearAll()
        MediaBackgroundRendererPool.releaseAll()
        BaseIslandRenderer.releaseForReload()
        EmbeddedLyriconCentralController.releaseForReload()
        RuntimePerfDiagnostics.stopForReload()
        return state
    }

    fun restore(module: HookEntry, state: Map<*, *>, oldHooks: List<HookHandle>) {
        val app = state["app"] as? Application ?: error("SystemUI reload state has no Application")
        // Reinstall through the SAME verified installers as a cold start. Name-only matching
        // loses overloads, constructors, shared hook chains and newly introduced target groups.
        oldHooks.forEach { it.unhook() }
        module.initializeAfterHotReload(app)
        module.installSystemUiHooks(state["systemLoader"] as? ClassLoader
            ?: error("SystemUI reload state has no original ClassLoader"))
        ReloadSnapshot.items(state, "islandLoaders").filterIsInstance<ClassLoader>().forEach { loader ->
            SystemUIHookRegistry.hook(module, loader)
            UnlockIslandWhitelist.doHookInClassLoader(loader)
            UnlockFocusWhitelist.doHookInClassLoader(loader)
        }
        val aod = ReloadSnapshot.items(state, "aod")
        (aod.getOrNull(2) as? Array<*>)?.filterIsInstance<ClassLoader>()?.forEach {
            NotificationMediaAodLyricHooker.hookAodPlugin(module, it)
        }
        IslandStatusBarSpaceMonitor.restoreAfterReload(ReloadSnapshot.items(state, "statusBar"))
        ReloadSnapshot.items(state, "candidates").filterIsInstance<ViewGroup>().forEach {
            IslandViewRegistry.rememberCandidate(it)
        }
        ReloadSnapshot.rows(state, "islands").forEach { row ->
            val view = row.getOrNull(0) as? ViewGroup ?: return@forEach
            val pkg = row.getOrNull(1) as? String ?: return@forEach
            if (view.isAttachedToWindow) IslandViewRegistry.register(view, pkg)
        }
        IslandAlbumCoverStyleHooker.restoreAfterReload(ReloadSnapshot.rows(state, "covers"))
        IslandMusicWaveColorHooker.restoreAfterReload(ReloadSnapshot.items(state, "waves"))
        NotificationMediaCoverStyleHooker.restoreAfterReload(ReloadSnapshot.rows(state, "notificationCover"))
        NotificationMediaAmbientFlowHooker.restoreAfterReload(ReloadSnapshot.rows(state, "notificationMedia"))
        IslandExpandedMediaAmbientFlowHooker.restoreAfterReload(ReloadSnapshot.items(state, "expandedMedia"))
        NotificationMediaAodLyricHooker.restoreAfterReload(aod)
        BaseIslandRenderer.refreshActiveIsland()
        HookLogger.i("HookEntry", "系统界面热重载恢复完成：超级岛、触控、媒体卡片、AOD")
    }
}
