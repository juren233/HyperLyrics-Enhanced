package com.juren233.hyperlyricsenhanced.ui.utils

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonetPaletteTest {

    /** 抽出色板前 ThemeUtils 里 when 分支的取值，逐项对照，保证主题配色不变。 */
    private val previousMapping: Map<Int, Color?> = mapOf(
        0 to null,
        1 to Color(0xFF2196F3),
        2 to Color(0xFF4CAF50),
        3 to Color(0xFFF44336),
        4 to Color(0xFFFFEB3B),
        5 to Color(0xFFFF9800),
        6 to Color(0xFF9C27B0),
        7 to Color(0xFFE91E63),
    )

    @Test
    fun everyStoredIndexKeepsItsPreviousKeyColor() {
        previousMapping.forEach { (index, color) ->
            assertEquals("index $index", color, MonetPalette.keyColorOf(index))
        }
    }

    @Test
    fun outOfRangeStoredIndexFallsBackToSystemPalette() {
        assertNull(MonetPalette.keyColorOf(-1))
        assertNull(MonetPalette.keyColorOf(8))
        assertNull(MonetPalette.keyColorOf(99))
    }

    @Test
    fun paletteHasOneSwatchPerStoredIndex() {
        assertEquals(previousMapping.size, MonetPalette.keyColors.size)
    }
}
