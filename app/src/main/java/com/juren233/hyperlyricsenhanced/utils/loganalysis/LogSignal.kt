/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.utils.loganalysis

/**
 * 规则要认的日志信号。每条的标签和文案与源码里的日志逐字一致，且都是 Release 包也会写进模块日志的级别（i/w/e）。
 */
internal enum class LogSignal(private val matcher: (ModuleLogRecord) -> Boolean) {
    // ---- 系统界面里模块的各部分启动 ----
    /** HookEntry 启动系统环境失败（带异常的那条）。 */
    SYSTEM_ENVIRONMENT_FAILED({ it.tag == "HookEntry" && it.level == 'E' && it.message.startsWith("系统环境启动: state=") }),
    SYSTEM_ENVIRONMENT_READY({ it.tag == "HookEntry" && it.message.startsWith("系统环境初始化完成") }),
    LYRICON_CENTRAL_FAILED({ it.tag == "EmbeddedLyriconCentral" && it.level == 'E' && it.message.startsWith("内嵌 Lyricon Central 启动失败") }),
    LYRICON_CENTRAL_STARTED({ it.tag == "EmbeddedLyriconCentral" && it.message.startsWith("内嵌 Lyricon Central 已启动") }),
    LYRIC_SOURCE_FAILED({ it.tag == "LyricInfoSource" && it.level == 'E' && it.message.startsWith("数据源启动失败") }),
    LYRIC_SOURCE_STARTED({ it.message.startsWith("数据源已启动") }),
    ISLAND_PLUGIN_FAILED({
        (it.tag == "HookEntry" || it.tag == "SystemUIHookRegistry") && it.level == 'E' &&
            it.message.startsWith("注入超级岛插件失败")
    }),
    ISLAND_PLUGIN_READY({
        it.tag == "SystemUIHookRegistry" &&
            (it.message.startsWith("超级岛 Hook 已初始化") || it.message.startsWith("超级岛歌词 Hook 已初始化"))
    }),

    // ---- AI 翻译 ----
    AI_TRANSLATION_FAILED({
        (it.tag == "OpenAiTranslationClient" && AI_CLIENT_FAILURES.any(it.message::startsWith)) ||
            (it.tag == "AITranslationResponseParser" && it.message.startsWith("翻译响应缺少 JSON"))
    }),

    /** 系统界面启动后第一次成功、或失败后恢复时写的 i 级日志（见 AiTranslationHealthLog）。 */
    AI_TRANSLATION_SUCCEEDED({ it.tag == "OpenAiTranslationClient" && it.message.startsWith("翻译请求成功") }),

    // ---- 字体 ----
    CUSTOM_FONT_FAILED({
        it.tag == "FontHelper" &&
            (it.message.startsWith("自定义字体文件不存在或无法读取：") || it.message.startsWith("无法从文件创建字体："))
    }),
    NARROW_FONT_FAILED({
        it.tag == "FontHelper" &&
            (it.message.startsWith("小米窄字体文件不存在或无法读取：") || it.message.startsWith("无法创建窄字体："))
    }),

    // ---- 歌词源 ----
    /** SourceManager 选定（或没选到）歌词源：「启动歌词源: X」「切换歌词源: X」「没有可用的歌词源」「歌词源不可用: id」。 */
    LYRIC_SOURCE_SELECTED({
        it.tag == "SourceManager" && (
            it.message.startsWith(SOURCE_STARTED_PREFIX) ||
                it.message.startsWith(SOURCE_SWITCHED_PREFIX) ||
                it.message.startsWith("没有可用的歌词源") ||
                it.message.startsWith("歌词源不可用: ")
            )
    }),
    /**
     * SourceManager 选的源不可用时紧跟的「歌词源不可用，回退到: X」。回退本身由 SuperLyric 规则处理（看上面那条），
     * 这里只为让它不被算进「还有 N 条警告」。
     */
    LYRIC_SOURCE_FELL_BACK({ it.tag == "SourceManager" && it.message.startsWith("歌词源不可用，回退到: ") }),
    SUPER_LYRIC_REGISTER_FAILED({ it.tag == "SuperLyricSource" && it.level == 'E' && it.message.startsWith("注册接收端失败") }),

    // ---- 依赖系统界面内部接口的功能 ----
    ISLAND_COVER_STYLE_FAILED({
        it.tag == "IslandAlbumCoverStyleHooker" && (
            it.message.startsWith("当前插件不支持超级岛封面样式") ||
                it.message.startsWith("超级岛封面字段不可用") ||
                it.message.startsWith("初始化超级岛封面样式 Hook 失败") ||
                it.message.startsWith("跳过封面样式 Hook")
            )
    }),
    ISLAND_COVER_STYLE_READY({ it.tag == "IslandAlbumCoverStyleHooker" && it.message.startsWith("超级岛封面样式 Hook 已初始化") }),
    MUSIC_WAVE_COLOR_FAILED({
        it.tag == "IslandMusicWaveColorHooker" && (
            it.message.startsWith("当前插件不支持音频律动") ||
                it.message.startsWith("音频律动颜色字段不可用") ||
                it.message.startsWith("初始化音频律动封面色 Hook 失败") ||
                it.message.startsWith("音频律动 Hook 已初始化: color=false")
            )
    }),
    MUSIC_WAVE_COLOR_READY({ it.tag == "IslandMusicWaveColorHooker" && it.message.startsWith("音频律动 Hook 已初始化: color=true") }),
    NOTIFICATION_MEDIA_COVER_FAILED({
        it.tag == "NotificationMediaCoverStyleHooker" && (
            it.message.startsWith("跳过通知中心媒体封面 Hook") ||
                it.message.startsWith("安装通知中心媒体封面 Hook 失败") ||
                it.message.startsWith("通知中心媒体封面 Hook 安装不完整")
            )
    }),
    NOTIFICATION_MEDIA_COVER_READY({ it.tag == "NotificationMediaCoverStyleHooker" && it.message.startsWith("通知中心媒体封面 Hook 已初始化") }),
    SETTINGS_ENTRY_FAILED({
        it.tag == "SettingsEntry" && (
            it.message.startsWith("设置入口反射初始化失败") ||
                it.message.startsWith("未找到 updateHeaderList 目标") ||
                it.message.startsWith("updateHeaderList Hook 安装失败")
            )
    }),
    SETTINGS_ENTRY_READY({ it.tag == "SettingsEntry" && it.message.startsWith("设置首页入口 Hook 已安装: updateHeaderList") }),
    AOD_LYRICS_FAILED({
        it.tag == "NotificationMediaAodLyricHooker" && (
            it.message.startsWith("跳过息屏歌词 Hook: reason=native_api_unavailable") ||
                it.message.startsWith("息屏歌词 Hook 安装不完整") ||
                it.message.startsWith("通知图标式息屏歌词契约不兼容") ||
                it.message.startsWith("通知图标式息屏歌词 Hook 安装不完整")
            )
    }),
    AOD_LYRICS_READY({
        it.tag == "NotificationMediaAodLyricHooker" &&
            (it.message.startsWith("息屏歌词 Hook 已初始化") || it.message.startsWith("通知图标式息屏歌词 Hook 已初始化"))
    }),
    ISLAND_EXPANDED_FLOW_FAILED({
        it.tag == "IslandExpandedMediaAmbientFlowHooker" && (
            it.message.startsWith("跳过展开态媒体流光 Hook") || it.message.startsWith("展开态媒体流光 Hook 不完整")
            )
    }),
    ISLAND_EXPANDED_FLOW_READY({ it.tag == "IslandExpandedMediaAmbientFlowHooker" && it.message.startsWith("展开态媒体流光 Hook 已初始化") }),
    NOTIFICATION_MEDIA_CARD_FAILED({
        it.tag == "NotificationMediaAmbientFlowHooker" && (
            it.message.startsWith("通知中心媒体控制器不可用") || it.message.startsWith("未找到兼容的通知中心媒体控制方法")
            )
    }),
    NOTIFICATION_MEDIA_CARD_READY({ it.tag == "NotificationMediaAmbientFlowHooker" && it.message.startsWith("通知中心媒体流光 Hook 已初始化") }),
    NOTIFICATION_MEDIA_BACKGROUND_FAILED({
        it.tag == "NotificationMediaAmbientFlowHooker" && it.message.startsWith("通知中心原生背景接口不可用")
    }),
    ISLAND_GLOW_FAILED({
        it.tag == "HookIslandGlow" && (
            it.message.startsWith("未找到 updateTemplate") ||
                it.message.startsWith("跳过不支持的媒体岛光效 Hook") ||
                it.message.startsWith("初始化媒体岛光效 Hook 失败")
            )
    }),
    ISLAND_GLOW_READY({ it.tag == "HookIslandGlow" && it.message.startsWith("媒体岛光效 Hook 已初始化") }),

    // ---- 真实律动 ----
    /** 状态只在翻转时输出一行（i 级，release 保留）：「真实律动状态: NO_ENERGY 已接管，…」。 */
    REAL_RHYTHM_STATE({ it.tag == "IslandRealRhythm" && it.message.startsWith(REAL_RHYTHM_STATE_PREFIX) }),
    REAL_RHYTHM_START_FAILED({ it.tag == "IslandRealRhythm" && it.message.startsWith("真实律动 Visualizer 启动失败") }),

    // ---- AI 翻译缓存 ----
    // 读、写分开：存储满时读还正常、写一直失败，读成功不能当成写也恢复了。
    TRANSLATION_CACHE_READ_FAILED({ it.tag == "AITranslationCache" && it.message.startsWith("查询翻译缓存失败") }),
    TRANSLATION_CACHE_WRITE_FAILED({
        it.tag == "AITranslationCache" && (
            it.message.startsWith("写入翻译缓存失败") || it.message.startsWith("清空翻译缓存数据库失败")
            )
    }),
    TRANSLATION_CACHE_READ_RECOVERED({ it.tag == "AITranslationCache" && it.message.startsWith("翻译缓存读取已恢复") }),
    TRANSLATION_CACHE_WRITE_RECOVERED({ it.tag == "AITranslationCache" && it.message.startsWith("翻译缓存写入已恢复") }),
    ;

    companion object {
        const val SOURCE_STARTED_PREFIX = "启动歌词源: "
        const val SOURCE_SWITCHED_PREFIX = "切换歌词源: "
        const val REAL_RHYTHM_STATE_PREFIX = "真实律动状态: "

        private val AI_CLIENT_FAILURES = listOf(
            "翻译请求失败: code=",
            "翻译网络请求异常",
            "翻译连接意外关闭",
            "翻译响应为空",
        )

        private val ALL = entries.toTypedArray()

        fun of(record: ModuleLogRecord): LogSignal? = ALL.firstOrNull { it.matcher(record) }
    }
}
