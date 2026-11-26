package com.juren233.hyperlyricsenhanced.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 头卡说明文字断行规则（[heroLineBreaks]）的规则测试：汉字宽 1，其他字符宽 0.5。
 * 按词切开用的是系统的 ICU 中文分词，所以走 Robolectric。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class HeroLineBreaksTest {

    private fun measure(s: String) = s.sumOf { c -> if (c in '一'..'鿿' || c in '＀'..'￯' || c in '　'..'〿') 1.0 else 0.5 }.toFloat()

    private fun lines(text: String, width: Float) = heroLineBreaks(text, width, ::measure).split("\n")

    @Test
    fun breaksAfterPunctuationInsteadOfInsideWords() {
        // 不按标点的话会断成「Apple Music 插件已添加但未开启，点 / 击此处去开启」。
        assertEquals(
            listOf("Apple Music 插件已添加但未开启，", "点击此处去开启"),
            lines("Apple Music 插件已添加但未开启，点击此处去开启", 16f),
        )
    }

    @Test
    fun overlongSegment_breaksAtSpaceBetweenChineseAndEnglish_neverInsideEnglishPhrase() {
        val result = lines("需要先在 Lyricon 配置页添加 Apple Music 插件，方可使用", 12f)
        assertEquals(listOf("需要先在 Lyricon", "配置页添加 Apple Music", "插件，方可使用"), result)
    }

    @Test
    fun segmentWithoutPunctuation_breaksBetweenWords_withoutOrphan() {
        // 一整段没有标点也没有空格：按词断，不拆「正常」「工作」，最后一行也不只剩一两个字。
        // 系统默认逐字断时，这一句在 250092 的详情页头卡里断成「……无法正常 / 工作」，窄一点就成了「正 / 常工作」。
        val text = "标红的项会让模块或部分功能无法正常工作"
        for (width in listOf(16f, 17f, 18f)) {
            val result = lines(text, width)
            assertEquals(text, result.joinToString(""))
            assert(result.last().length > 2) { "宽 $width 时最后一行是孤字：$result" }
            assert(result.all { measure(it) <= width }) { "宽 $width 时有一行超宽：$result" }
            val joints = result.runningFold(0) { acc, line -> acc + line.length }.drop(1).dropLast(1)
            for (word in listOf("模块", "部分", "功能", "无法", "正常", "工作")) {
                val start = text.indexOf(word)
                assert(joints.none { it > start && it < start + word.length }) { "宽 $width 时拆开了「$word」：$result" }
            }
        }
    }

    @Test
    fun versionNumberIsNeverSplit() {
        assertEquals(listOf("版本", "8.5.0-canary-250092"), lines("版本 8.5.0-canary-250092", 11f))
    }

    @Test
    fun englishTextIsLeftToTheSystem() {
        val text = "Apple Music plugin is added but disabled, tap here to enable"
        assertEquals(text, heroLineBreaks(text, 10f, ::measure))
    }

    @Test
    fun fitsOnOneLine_unchanged() {
        assertEquals("有新版本 8.5.1", heroLineBreaks("有新版本 8.5.1", 40f, ::measure))
    }
}
