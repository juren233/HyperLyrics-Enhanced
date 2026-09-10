/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P5 AOD 宿主生命周期收口证据：两类宿主（通知媒体控制器→锁屏 AOD、
 * 经典 AOD 插件）的销毁唯一责任方、资源释放顺序与轮询清理必须保持。
 * 见 docs/code-modularization-follow-up-plan.md P5 完成条件：
 * 销毁后无任务继续写 View、重复还原安全、经典 AOD 与锁屏 AOD 不串状态。
 */
class NotificationMediaAodLifecycleEvidenceTest {

    private fun source(fileName: String): String {
        val path =
            "src/main/java/com/juren233/hyperlyricsenhanced/root/mediacard/notification/$fileName.kt"
        return listOf(File(path), File("app/$path")).first(File::isFile).readText()
    }

    @Test
    fun `lock screen overlay removal disposes releases wakelock then restores`() {
        val remove = source("NotificationMediaAodLyricHooker")
            .substringAfter("private fun removeOverlay(state: ControllerState) {")
            .substringBefore("private fun removeAodPluginOverlay")
        val dispose = remove.indexOf("overlay.lifetime.dispose()")
        val wakeLock = remove.indexOf("overlay.drawWakeLock.isHeld")
        val restore = remove.indexOf("restorePlayerHeight(")
        val removeView = remove.indexOf("removeView(overlay.root)")
        assertTrue("缺少 lifetime.dispose", dispose >= 0)
        assertTrue("缺少 WakeLock 释放", wakeLock > dispose)
        assertTrue("WakeLock 释放应在还原高度之前", wakeLock < restore)
        assertTrue("还原应在移除视图之前", restore < removeView)
    }

    @Test
    fun `classic aod overlay removal removes preDraw listener before view`() {
        val remove = source("NotificationMediaAodLyricHooker")
            .substringAfter("private fun removeAodPluginOverlay(state: AodPluginState) {")
            .substringBefore("internal fun applyContentAlignment")
        val listener = remove.indexOf("removeOnPreDrawListener(listener)")
        val wakeLock = remove.indexOf("overlay.drawWakeLock.isHeld")
        val removeView = remove.indexOf("removeView(overlay.root)")
        assertTrue("缺少 preDraw listener 摘除", listener >= 0)
        assertTrue("摘除 listener 必须先于移除视图", listener < removeView)
        assertTrue("经典宿主同样需要 WakeLock 释放", wakeLock > listener && wakeLock < removeView)
    }

    @Test
    fun `releaseAll stops polling and clears both host states independently`() {
        val release = source("NotificationMediaAodLyricHooker")
            .substringAfter("fun releaseAll() = runOnMain {")
            .substringBefore("fun hideLockScreenOverlays")
        assertTrue("releaseAll 必须清理锁屏宿主状态", release.contains("states.clear()"))
        assertTrue("releaseAll 必须清理经典宿主状态", release.contains("aodPluginStates.clear()"))
        assertTrue(
            "releaseAll 必须移除位置轮询",
            release.contains("mainHandler.removeCallbacks(positionPollRunnable)"),
        )
        assertTrue(release.contains("positionPollScheduled = false"))
    }

    @Test
    fun `initial refresh schedule keeps weak host and generation invalidation`() {
        val overlay = source("NotificationMediaAodControllerOverlay")
        assertTrue(
            "经典 AOD 初始刷新必须按状态身份失效",
            overlay.contains("currentState !== state"),
        )
        assertTrue(
            "经典 AOD 初始刷新必须按刷新代次失效",
            overlay.contains("state.initialRefreshGeneration != generation"),
        )
        assertTrue(
            "经典 AOD 初始刷新必须以弱引用持有宿主视图",
            overlay.contains("val viewReference = WeakReference(aodView)"),
        )
    }
}
