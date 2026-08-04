package com.juren233.hyperlyricsenhanced.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogDataTest {
    @Test
    fun `new version includes its own release and earlier releases`() {
        assertTrue(ChangelogData.isReleaseVisible("v7.1.0", "7.1.0"))
        assertTrue(ChangelogData.isReleaseVisible("v7.0.0", "7.1.0"))
    }

    @Test
    fun `future releases are excluded`() {
        assertFalse(ChangelogData.isReleaseVisible("v7.2.0", "7.1.0"))
        assertFalse(ChangelogData.isReleaseVisible("v7.1.1", "7.1.0"))
    }

    @Test
    fun `release tags must use vX dot X dot X format`() {
        assertFalse(ChangelogData.isReleaseVisible("7.0.0", "7.0.0"))
        assertFalse(ChangelogData.isReleaseVisible("v7.0", "7.0.0"))
        assertFalse(ChangelogData.isReleaseVisible("v7.0.0-100001", "7.0.0"))
    }

    @Test
    fun `unknown tags are excluded`() {
        assertFalse(ChangelogData.isReleaseVisible("latest", "7.0.0"))
        assertFalse(ChangelogData.isReleaseVisible("release-v7.0.0", "7.0.0"))
    }

    @Test
    fun `release details html is normalized for markdown rendering`() {
        val source = """
            ## 最新提交

            **修复** Markdown 渲染

            <details>
            <summary>自上次 Release 以来的提交记录</summary>

            - 修复 A
            - 修复 B

            </details>
        """.trimIndent()

        assertEquals(
            """
                ## 最新提交

                **修复** Markdown 渲染

                ### 自上次 Release 以来的提交记录

                - 修复 A
                - 修复 B
            """.trimIndent(),
            ChangelogData.normalizeReleaseMarkdown(source)
        )
    }

    @Test
    fun `latest commit label is replaced by the actual enlarged title`() {
        val content = ChangelogData.normalizeReleaseContent(
            """
                ## 最新提交
                chores: 更新应用图标并完善更新日志UI

                - 重新设计应用图标
                - 为更新日志接入 Markdown 渲染
            """.trimIndent()
        )

        assertEquals("chores: 更新应用图标并完善更新日志UI", content.title)
        assertEquals(
            """
                - 重新设计应用图标
                - 为更新日志接入 Markdown 渲染
            """.trimIndent(),
            content.summary
        )
        assertFalse(content.summary.contains("最新提交"))
    }

    @Test
    fun `first release line is enlarged and download instructions are hidden`() {
        val content = ChangelogData.normalizeReleaseContent(
            """
                feat: 新增双 AOD 歌词与更新检测

                新增功能：

                - 新增模块版本后台检测。

                <details>
                <summary>自上次 Release 以来的提交记录</summary>

                - ci: 自动同步 LSPosed 发布

                </details>

                ## 下载说明

                日常使用请选择 **Release** 包。
            """.trimIndent()
        )

        assertEquals("feat: 新增双 AOD 歌词与更新检测", content.title)
        assertEquals(
            """
                新增功能：

                - 新增模块版本后台检测。

                ### 自上次 Release 以来的提交记录

                - ci: 自动同步 LSPosed 发布
            """.trimIndent(),
            content.summary
        )
        assertFalse(content.summary.contains("下载说明"))
        assertFalse(content.summary.contains("日常使用请选择"))
    }

    @Test
    fun `download section ends at the next sibling heading`() {
        val content = ChangelogData.normalizeReleaseContent(
            """
                feat: 保留后续章节

                正文

                ## 下载说明

                需要隐藏

                ## 致谢

                需要保留
            """.trimIndent()
        )

        assertEquals("feat: 保留后续章节", content.title)
        assertEquals(
            """
                正文

                ## 致谢

                需要保留
            """.trimIndent(),
            content.summary
        )
    }
}
