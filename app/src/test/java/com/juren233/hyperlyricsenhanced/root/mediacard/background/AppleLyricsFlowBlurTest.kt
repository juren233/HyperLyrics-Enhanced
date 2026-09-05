package com.juren233.hyperlyricsenhanced.root.mediacard.background

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLyricsFlowBlurTest {
    @Test
    fun `constant colors remain constant including edges and single pixel buffers`() {
        for ((w, h) in listOf(1 to 1, 2 to 3, 61 to 19)) {
            for (color in listOf(0xff000000.toInt(), 0xffffffff.toInt(), 0xff3ac795.toInt())) {
                val input = IntArray(w * h) { color }
                assertArrayEquals(input, blur(input, w, h))
            }
        }
    }

    @Test
    fun `center impulse spreads symmetrically without introducing other color channels`() {
        val input = IntArray(61) { 0xff000000.toInt() }
        input[30] = 0xffff0000.toInt()
        val output = blur(input, 61, 1)
        assertTrue((output[30] ushr 16 and 255) in 1..254)
        assertTrue((output[25] ushr 16 and 255) > 0)
        for (x in output.indices) {
            assertEquals(output[x], output[60 - x])
            assertEquals(0, output[x] and 0xffff)
            assertEquals(255, output[x] ushr 24)
        }
    }

    @Test
    fun `vertical and horizontal inputs produce the same one dimensional blur`() {
        val input = IntArray(41) { if (it < 20) 0xff1122ee.toInt() else 0xffddaa33.toInt() }
        assertArrayEquals(blur(input, 41, 1), blur(input, 1, 41))
    }

    private fun blur(input: IntArray, w: Int, h: Int): IntArray {
        val scratch = IntArray(input.size)
        val output = IntArray(input.size)
        AppleLyricsFlowBlur.apply(input, scratch, w, h, vertical = false)
        AppleLyricsFlowBlur.apply(scratch, output, w, h, vertical = true)
        return output
    }
}
