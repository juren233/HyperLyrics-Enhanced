/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import android.view.ViewGroup
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger

/**
 * 岛视图重挂扫描。
 *
 * 媒体切换（A app → B app）瞬间，原生只发一次岛更新；若这次更新早于歌词桥的
 * 包名切换，UpdateBigIslandViewHook 会按"包名不符"硬清并注销视图。之后若 B 的
 * 通知不像 Apple Music 那样逐秒带进度重发岛更新，被 hook 的入口不再触发，注册表
 * 永久为空（2026-09-20 椒盐音乐真机日志：no_attached_view × 9，数据层一切正常）。
 *
 * 本助手在渲染层发现注册表为空时，从 IslandViewRegistry 的候选（任何岛 hook 见
 * 过的 contentView）里重新读取岛数据，包名与当前歌词包一致即重新注册，打破对
 * 原生重发的依赖。register() 自带附着后补发刷新，注入会随之恢复。
 */
internal object IslandReattachAssistant {
    private const val TAG = "IslandReattach"

    /** 返回 true 表示找回并注册了匹配当前歌词包的岛视图。 */
    fun tryReattach(lyricPkg: String): Boolean {
        if (IslandViewRegistry.snapshotAttached(lyricPkg).isNotEmpty()) return false
        var reattached = false
        IslandViewRegistry.snapshotCandidates().forEach { candidate ->
            val data = runCatching { IslandProbeUtils.getCurrentIslandData(candidate) }.getOrNull()
            val info = runCatching { IslandProbeUtils.extractMediaIslandInfo(data) }.getOrNull()
            if (BuildConfig.DEBUG) {
                HookLogger.d(
                    TAG,
                    "岛重挂探测: 候选=${candidate.javaClass.simpleName}@${System.identityHashCode(candidate)}, " +
                        "岛包名=${info?.packageName ?: "无"}, 是媒体岛=${IslandProbeUtils.isMediaIsland(data)}, " +
                        "歌词包名=$lyricPkg",
                )
            }
            if (info?.packageName == lyricPkg) {
                // 岛数据包名匹配不代表子树仍可注入：失效宿主（媒体模块被原生重排、
                // 锚点不可达）必须跳过，否则注销后会被立即注册回来形成抖动。
                if (!IslandLyricTextInjector.hasInjectableAnchors(candidate)) {
                    if (BuildConfig.DEBUG) {
                        HookLogger.d(
                            TAG,
                            "重挂候选缺少注入锚点，跳过: 候选=${candidate.javaClass.simpleName}@${System.identityHashCode(candidate).toString(16)}, " +
                                "锚点=${IslandLyricTextInjector.describeAnchorState(candidate)}, 岛包名=${info.packageName}",
                        )
                    }
                    return@forEach
                }
                IslandViewRegistry.register(candidate, lyricPkg)
                reattached = true
            }
        }
        if (reattached && BuildConfig.DEBUG) {
            HookLogger.d(TAG, "已重挂岛视图: package=$lyricPkg")
        }
        return reattached
    }
}
