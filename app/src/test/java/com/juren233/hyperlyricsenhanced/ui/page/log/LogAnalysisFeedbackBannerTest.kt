package com.juren233.hyperlyricsenhanced.ui.page.log

import com.juren233.hyperlyricsenhanced.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** 详情页页底的反馈横幅：debug 包与 release 包各用自己的文案，原文按 Cavan 给的逐字核对。 */
class LogAnalysisFeedbackBannerTest {
    private fun strings(path: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i)
            node.attributes.getNamedItem("name").nodeValue to node.textContent.replace("\\'", "'")
        }
    }

    @Test
    fun banner_picksTextByBuildType() {
        assertEquals(R.string.log_analysis_footer_debug, feedbackBannerText(debugBuild = true))
        assertEquals(R.string.log_analysis_footer_release, feedbackBannerText(debugBuild = false))
    }

    @Test
    fun banner_chineseTextMatchesRequestedWording() {
        val zh = strings("src/main/res/values/strings.xml")
        assertEquals(
            "如果遇到的问题这里没有检测出来，请进行导出完整调试日志后，在 GitHub 仓库发起 issue 并附带日志进行反馈",
            zh["log_analysis_footer_debug"],
        )
        assertEquals(
            "如果遇到的问题这里没有检测出来，请先下载安装 debug 包，再次复现问题并导出完整调试日志后，在 GitHub 仓库发起 issue 并附带日志进行反馈",
            zh["log_analysis_footer_release"],
        )
        // 旧的单句页脚已拆开，不再留着。
        assertFalse(zh.containsKey("log_analysis_footer"))
    }

    @Test
    fun banner_englishHasBothVariants() {
        val en = strings("src/main/res/values-en/strings.xml")
        assertFalse(en["log_analysis_footer_debug"].isNullOrBlank())
        assertFalse(en["log_analysis_footer_release"].isNullOrBlank())
        assertFalse(en.containsKey("log_analysis_footer"))
    }
}
