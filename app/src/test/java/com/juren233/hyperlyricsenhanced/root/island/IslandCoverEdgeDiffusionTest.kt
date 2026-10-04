/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import java.util.Random
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class IslandCoverEdgeDiffusionTest {
    @Test
    fun gaussianPixelsExactlyMatchThePreviousAlgorithmAcrossRadiiAndClampedEdges() {
        val random = Random(20261004)
        for (height in listOf(0, 1, 2, 9, 31, 104, 127)) {
            val pixels = IntArray(height) { random.nextInt() }
            val diffusion = IslandCoverEdgeDiffusion(pixels)
            // Grow, shrink, and repeat kernels to exercise retained coefficient storage.
            for (radius in listOf(0f, 0.49f, 0.5f, 0.75f, 2.4f, 8.05f, 31.9f, 80f, 3f, 3f, 0f)) {
                diffusion.prepareRadius(radius)
                for (row in -2..height + 2) {
                    assertEquals("height=$height radius=$radius row=$row",
                        previousColor(pixels, row, radius), diffusion.colorAt(row))
                }
            }
        }
    }

    @Test
    fun transparentAndOpaqueEdgesKeepTheirOriginalChannelRounding() {
        for (pixels in listOf(
            intArrayOf(0, -1, 0x00ff0000, 0x8000ff00.toInt(), 0xff0000ff.toInt()),
            IntArray(104) { -1 },
            IntArray(104),
        )) {
            val diffusion = IslandCoverEdgeDiffusion(pixels)
            for (radius in listOf(0.5f, 4.25f, 15.28125f, 52f)) {
                diffusion.prepareRadius(radius)
                for (row in pixels.indices) {
                    assertEquals(previousColor(pixels, row, radius), diffusion.colorAt(row))
                }
            }
        }
    }

    @Test
    fun preblurMutatesTheTextureArrayUsedByBothExistingBitmapPaths() {
        val texturePixels = intArrayOf(0x8000ff00.toInt(), 0x000000ff)
        val cpuPixels = premultiplyArgb(texturePixels)
        assertSame(texturePixels, cpuPixels)
        assertEquals(0x80008000.toInt(), texturePixels[0])
        assertEquals(0, texturePixels[1])
    }

    /** Independent reference keeps the old per-pixel Gaussian calculation and Float operation order. */
    private fun previousColor(column: IntArray, centerY: Int, radius: Float): Int {
        if (column.isEmpty()) return 0
        val center = centerY.coerceIn(0, column.lastIndex)
        if (radius < 0.5f) return column[center]
        val sigma = (radius * 0.5f).coerceAtLeast(0.75f)
        val extent = ceil(radius).toInt().coerceAtLeast(1)
        val denominator = 2f * sigma * sigma
        var sum = 0f
        val channels = FloatArray(4)
        for (offset in -extent..extent) {
            val distance = offset.toFloat()
            val weight = exp(-(distance * distance) / denominator)
            val color = column[(center + offset).coerceIn(0, column.lastIndex)]
            sum += weight
            channels[0] += (color ushr 24) * weight
            channels[1] += (color ushr 16 and 0xff) * weight
            channels[2] += (color ushr 8 and 0xff) * weight
            channels[3] += (color and 0xff) * weight
        }
        return (channels[0] / sum).roundToInt().coerceIn(0, 255).shl(24) or
            (channels[1] / sum).roundToInt().coerceIn(0, 255).shl(16) or
            (channels[2] / sum).roundToInt().coerceIn(0, 255).shl(8) or
            (channels[3] / sum).roundToInt().coerceIn(0, 255)
    }
}
