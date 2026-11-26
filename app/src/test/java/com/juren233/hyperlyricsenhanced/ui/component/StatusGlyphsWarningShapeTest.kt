package com.juren233.hyperlyricsenhanced.ui.component

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 头卡装饰、警告横幅用的描边大警告三角（[StatusGlyphs.Warning]）：三个顶点（大小、位置）不变，
 * 三个角都要圆得明显——每个角的曲线最凸处离尖角顶点至少 1.5 个单位（原来只圆 2 个单位时约 0.87）。
 */
class StatusGlyphsWarningShapeTest {
    private fun corners(): List<Pair<Offset, Offset>> {
        val outline = StatusGlyphs.Warning.root.first() as VectorPath
        var current = Offset.Zero
        val result = mutableListOf<Pair<Offset, Offset>>()
        for (node in outline.pathData) {
            when (node) {
                is PathNode.MoveTo -> current = Offset(node.x, node.y)
                is PathNode.LineTo -> current = Offset(node.x, node.y)
                is PathNode.QuadTo -> {
                    val control = Offset(node.x1, node.y1)
                    val end = Offset(node.x2, node.y2)
                    // 二次曲线 t=0.5 处就是这个角最凸的点。
                    val apex = current * 0.25f + control * 0.5f + end * 0.25f
                    result += control to apex
                    current = end
                }
                else -> Unit
            }
        }
        return result
    }

    @Test
    fun verticesUnchanged() {
        val vertices = corners().map { it.first }.toSet()
        assertEquals(setOf(Offset(12f, 3f), Offset(22f, 20.5f), Offset(2f, 20.5f)), vertices)
    }

    @Test
    fun cornersAreClearlyRounded() {
        val list = corners()
        assertEquals(3, list.size)
        list.forEach { (vertex, apex) ->
            val d = (apex - vertex).getDistance()
            assertTrue("顶点 $vertex 的圆角最凸处离顶点只有 ${"%.2f".format(d)} 个单位", d >= 1.5f)
        }
    }

    @Test
    fun exclamationCenteredInTriangle() {
        // 线宽 1.6，留白量到描边内侧：圆点（半径 1）底部到底边、竖线顶端（圆头）到两条斜边，两者应基本相等。
        val half = 1.6f / 2f
        val outline = StatusGlyphs.Warning.root.first() as VectorPath
        val stemTop = outline.pathData.mapNotNull {
            when (it) {
                is PathNode.MoveTo -> it.takeIf { n -> n.x == 12f }?.y
                is PathNode.LineTo -> it.takeIf { n -> n.x == 12f }?.y
                else -> null
            }
        }.min()
        val dot = StatusGlyphs.Warning.root[1] as VectorPath
        val dotY = (dot.pathData.first() as PathNode.MoveTo).y
        val bottomGap = (20.5f - half) - (dotY + 1f)
        val y = stemTop - half
        val halfWidth = (y - 3f) / 17.5f * 10f
        val topGap = halfWidth * 17.5f / Offset(10f, 17.5f).getDistance() - half
        assertTrue("圆点到底边留白 ${"%.2f".format(bottomGap)}，竖线顶端到斜边留白 ${"%.2f".format(topGap)}，应基本相等", kotlin.math.abs(bottomGap - topGap) <= 0.15f)
    }
}
