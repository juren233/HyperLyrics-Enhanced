/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R7 契约守卫：
 *
 * 1. `LyricPublicationOrigin` 只是事件记账，不参与任何来源决策；它唯一的生产消费是
 *    `publishAppleSong` 内 `BuildConfig.DEBUG` 下的计时日志。真正决定“官方/补充/手动”
 *    的是 Publication 的已确认来源选择与各内容可用性判定。
 * 2. Root 侧 `LyriconSourcePolicy` 与 AM 侧 `AppleLyricsSupplementPolicy` 依赖不同事实
 *    （前者是 LocalSong 内容可用性，后者是请求/指针/队列/页面身份），保持分层，不提取
 *    到 common，也不新增 Root↔AM 的政策依赖；现有的 AppleDirectBridgeContract/
 *    AppleSourceSwitchPerformanceDiagnostics 是有意保留的桥接契约。
 */
class LyricPublicationOriginContractTest {

    private fun repoRoot(): File =
        listOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }

    private fun source(relative: String): String = File(repoRoot(), relative).readText()

    @Test
    fun `origin is never read by a production decision`() {
        val decisionReaders = listOf(
            "app/src/main/java/com/juren233/hyperlyricsenhanced/root/source/LyriconSourcePolicy.kt",
            "app/src/main/java/com/juren233/hyperlyricsenhanced/root/source/LyriconPublication.kt",
            "app/src/main/java/com/juren233/hyperlyricsenhanced/root/source/LyriconSourceOnlineApply.kt",
            "app/src/main/java/com/juren233/hyperlyricsenhanced/root/source/LyriconSourceOnlineTranslation.kt",
            "app/src/main/java/com/juren233/hyperlyricsenhanced/root/source/LyriconSourceFallback.kt",
        )
        decisionReaders.forEach { path ->
            val body = source(path)
            assertFalse(
                "$path 不应读取 event.origin 做决策",
                Regex("\\.origin\\b").containsMatchIn(body),
            )
        }
    }

    @Test
    fun `origin only reaches the debug timing log`() {
        val sourceFile = source(
            "app/src/main/java/com/juren233/hyperlyricsenhanced/root/source/LyriconSource.kt",
        )
        // 记录：origin 的唯一消费点是 Debug 计时日志；若未来加入决策读取，本断言会失败，
        // 提示把 origin 重新按“实际来源决策”评审，而不是继续当作纯记账。
        val uses = Regex("origin\\b").findAll(sourceFile).count()
        assertTrue("origin 的引用数量变化，请复核是否新增了决策消费: $uses", uses in 1..20)
        assertTrue(
            "origin 必须仍处于 BuildConfig.DEBUG 守卫内",
            Regex("if \\(BuildConfig\\.DEBUG\\)[\\s\\S]{0,400}origin=").containsMatchIn(sourceFile),
        )
    }

    @Test
    fun `AM never depends on root source policies`() {
        val amDir = File(
            repoRoot(),
            "app/src/main/java/io/github/proify/lyricon/amprovider",
        )
        val offenders = amDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                file.readLines().any { line ->
                    line.startsWith("import ") &&
                        line.contains("com.juren233.hyperlyricsenhanced.root.source")
                }
            }
            .map(File::getName)
            .toList()
        assertTrue("AM 侧不得依赖 Root source 政策: $offenders", offenders.isEmpty())
    }
}
