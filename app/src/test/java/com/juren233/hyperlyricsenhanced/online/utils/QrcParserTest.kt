package com.juren233.hyperlyricsenhanced.online.utils

import com.juren233.hyperlyricsenhanced.online.model.LyricsData
import com.juren233.hyperlyricsenhanced.online.OnlineLyricTargeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class QrcParserTest {
    @Test
    fun `parses QQ QRC romanization instead of treating it as LRC`() {
        val result = QrcParser.parse(
            LyricsData(
                original = qrc(
                    """
                    [ti:金童子]
                    [0,152]金(0,50)童(50,50)子(100,52)
                    [730,1817]遗(730,216)憾(946,240)要(1186,305)告(1491,263)诉(1754,249)你(2003,544)
                    """.trimIndent(),
                ),
                type = "qrc",
                romanization = qrc(
                    """
                    [ti:金童子]
                    [0,152]以(0,30)下(30,30)音(60,30)译(90,62)
                    [730,1817]wai (730,216)han (946,240)yiu (1186,305)gou (1491,263)sou (1754,249)nei (2003,544)
                    """.trimIndent(),
                ),
            ),
        )

        val romanization = assertNotNull(result.romanization).let { result.romanization!! }
        assertEquals(2, romanization.size)
        assertEquals(730L, romanization[1].start)
        assertEquals(
            "wai han yiu gou sou nei",
            romanization[1].words.joinToString("") { it.text },
        )
        assertEquals(
            "wai han yiu gou sou nei",
            OnlineLyricTargeter.toLrcLines(result)
                .single { it.startTimeMs == 730L }
                .romanization,
        )
    }

    @Test
    fun `keeps plain LRC auxiliary lyrics compatible`() {
        val result = QrcParser.parse(
            LyricsData(
                original = qrc("[730,1817]遗(730,216)憾(946,240)要(1186,305)告(1491,263)诉(1754,249)你(2003,544)"),
                type = "qrc",
                translated = "[00:00.730]遗憾要告诉你",
                romanization = "[00:00.730]wai han yiu gou sou nei",
            ),
        )

        assertEquals("遗憾要告诉你", result.translated!!.single().words.single().text)
        assertEquals("wai han yiu gou sou nei", result.romanization!!.single().words.single().text)
    }

    @Test
    fun `inserts one space between QRC pronunciation units`() {
        val result = QrcParser.parse(
            LyricsData(
                original = qrc(
                    "[730,1817]遗(730,216)憾(946,240)要(1186,305)告(1491,263)诉(1754,249)你(2003,544)"
                ),
                type = "qrc",
                romanization = qrc(
                    "[730,1817]wai(730,216)han(946,240)yiu(1186,305)" +
                        "gou(1491,263)sou(1754,249)nei(2003,544)"
                ),
            ),
        )

        assertEquals(
            "wai han yiu gou sou nei",
            result.romanization!!.single().words.single().text,
        )
    }

    private fun qrc(content: String): String =
        """
        <?xml version="1.0" encoding="utf-8"?>
        <QrcInfos>
        <QrcHeadInfo SaveTime="199" Version="100"/>
        <LyricInfo LyricCount="1">
        <Lyric_1 LyricType="1" LyricContent="$content"/>
        </LyricInfo>
        </QrcInfos>
        """.trimIndent()
}
