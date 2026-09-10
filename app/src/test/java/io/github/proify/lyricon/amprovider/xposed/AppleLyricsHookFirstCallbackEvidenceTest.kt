/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P3 拆分接线保护：关键补充/缺词 Hook 组的安装日志与 Debug 首次回调证据
 * 必须随拆分保留。见 DEBUGGING_MISTAKES.md 的 REFACTOR-P3-ROLLBACK-20260909：
 * 下一轮区分性证据（supplement receive、availability、activation、注入、
 * 呈现刷新、first callback）依赖这些标记存在。
 */
class AppleLyricsHookFirstCallbackEvidenceTest {

    private fun readSource(fileName: String): String {
        val relativeSourcePath =
            "src/main/java/io/github/proify/lyricon/amprovider/xposed/$fileName"
        return listOf("app/$relativeSourcePath", relativeSourcePath)
            .map(::File)
            .first(File::isFile)
            .readText()
    }

    @Test
    fun `missing lyrics hook groups keep installation and first callback evidence`() {
        val source = readSource("AppleMissingLyricsHooks.kt")
        listOf(
            "无歌词补充结果呈现改写 Hook 已安装" to "无歌词补充结果呈现 Hook 首次命中",
            "无歌词补充时间轴地图改写 Hook 已安装" to "无歌词补充时间轴地图改写 Hook 首次命中",
            "无歌词补充页面恢复 Hook 已安装" to "无歌词补充页面恢复 Hook 首次命中",
            "无歌词补充歌词可用性 Hook 已安装" to "无歌词补充歌词可用性 Hook 首次命中",
        ).forEach { (installLog, firstHit) ->
            assertTrue("缺少安装日志: $installLog", source.contains(installLog))
            assertTrue("缺少首次命中证据: $firstHit", source.contains(firstHit))
        }
    }

    @Test
    fun `translation and pronunciation preference hooks keep evidence`() {
        val source = readSource("lyrics/AppleLyricsSupplementHooks.kt")
        listOf(
            "歌词翻译偏好 Hook 已安装" to "歌词翻译偏好 Hook 首次命中",
            "歌词发音偏好 Hook 已安装" to "歌词发音偏好 Hook 首次命中",
        ).forEach { (installLog, firstHit) ->
            assertTrue("缺少安装日志: $installLog", source.contains(installLog))
            assertTrue("缺少首次命中证据: $firstHit", source.contains(firstHit))
        }
    }
}
