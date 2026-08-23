/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common.color

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Chroma-preserving OKLCH interpolation for clean cover-derived gradients. */
object PerceptualGradient {
    private const val POWERLESS_CHROMA = 0.02
    private const val COMPLEMENTARY_HUE_THRESHOLD = 150.0
    private const val HUE_PATH_CHROMA_EPSILON = 0.005
    private const val GAMUT_SEARCH_ITERATIONS = 24

    /** Builds the three visible anchors: extracted start, generated midpoint, extracted end. */
    fun threeColorAnchors(startColor: Int, endColor: Int): IntArray {
        if (startColor == endColor) return intArrayOf(startColor)
        val path = Path.from(startColor, endColor)
        return intArrayOf(startColor, path.colorAt(0.5), endColor)
    }

    internal fun oklchChroma(color: Int): Double = Oklch.fromArgb(color).chroma

    internal fun oklabDistance(first: Int, second: Int): Double {
        val a = Oklab.fromArgb(first)
        val b = Oklab.fromArgb(second)
        return kotlin.math.sqrt(
            square(a.lightness - b.lightness) +
                square(a.a - b.a) +
                square(a.b - b.b)
        )
    }

    private data class Path(
        val start: Oklch,
        val end: Oklch,
        val startHue: Double,
        val hueDelta: Double,
    ) {
        fun colorAt(t: Double): Int {
            val amount = t.coerceIn(0.0, 1.0)
            if (amount <= 0.0) return start.argb
            if (amount >= 1.0) return end.argb
            val lightness = lerp(start.lightness, end.lightness, amount)
            val chroma = lerp(start.chroma, end.chroma, amount)
            val hue = normalizeHue(startHue + hueDelta * amount)
            val alpha = lerp(start.alpha.toDouble(), end.alpha.toDouble(), amount).roundToInt()
            return gamutMap(lightness, chroma, hue, alpha).argb
        }

        companion object {
            fun from(startColor: Int, endColor: Int): Path {
                val start = Oklch.fromArgb(startColor)
                val end = Oklch.fromArgb(endColor)
                val hues = resolvedHues(start, end)
                val shortDelta = shortestHueDelta(hues.first, hues.second)
                val lightnessMid = (start.lightness + end.lightness) / 2.0
                val chromaMid = (start.chroma + end.chroma) / 2.0
                val delta = if (
                    start.chroma >= POWERLESS_CHROMA &&
                    end.chroma >= POWERLESS_CHROMA &&
                    abs(shortDelta) >= COMPLEMENTARY_HUE_THRESHOLD
                ) {
                    chooseComplementaryHuePath(
                        startHue = hues.first,
                        shortDelta = shortDelta,
                        lightnessMid = lightnessMid,
                        chromaMid = chromaMid,
                    )
                } else {
                    shortDelta
                }
                return Path(start, end, hues.first, delta)
            }
        }
    }

    private fun resolvedHues(start: Oklch, end: Oklch): Pair<Double, Double> = when {
        start.chroma < POWERLESS_CHROMA && end.chroma < POWERLESS_CHROMA ->
            start.hue to start.hue

        start.chroma < POWERLESS_CHROMA -> end.hue to end.hue
        end.chroma < POWERLESS_CHROMA -> start.hue to start.hue
        else -> start.hue to end.hue
    }

    private fun chooseComplementaryHuePath(
        startHue: Double,
        shortDelta: Double,
        lightnessMid: Double,
        chromaMid: Double,
    ): Double {
        val longDelta = if (shortDelta >= 0.0) shortDelta - 360.0 else shortDelta + 360.0
        val shortMapped = gamutMap(
            lightnessMid,
            chromaMid,
            normalizeHue(startHue + shortDelta / 2.0),
            255,
        )
        val longMapped = gamutMap(
            lightnessMid,
            chromaMid,
            normalizeHue(startHue + longDelta / 2.0),
            255,
        )
        return if (longMapped.chroma > shortMapped.chroma + HUE_PATH_CHROMA_EPSILON) {
            longDelta
        } else {
            shortDelta
        }
    }

    private fun gamutMap(lightness: Double, chroma: Double, hue: Double, alpha: Int): Oklch {
        val requested = Oklch(lightness, chroma.coerceAtLeast(0.0), hue, alpha, 0)
        if (requested.isInSrgbGamut()) return requested.withArgb(requested.toArgbClamped())

        var low = 0.0
        var high = requested.chroma
        repeat(GAMUT_SEARCH_ITERATIONS) {
            val candidate = (low + high) / 2.0
            if (Oklch(lightness, candidate, hue, alpha, 0).isInSrgbGamut()) {
                low = candidate
            } else {
                high = candidate
            }
        }
        val mapped = Oklch(lightness, low, hue, alpha, 0)
        return mapped.withArgb(mapped.toArgbClamped())
    }

    private data class Oklch(
        val lightness: Double,
        val chroma: Double,
        val hue: Double,
        val alpha: Int,
        val argb: Int,
    ) {
        fun isInSrgbGamut(): Boolean = toLinearRgb().all { it in -1e-7..1.0000001 }

        fun toArgbClamped(): Int {
            val rgb = toLinearRgb()
            val red = linearToSrgb(rgb[0]).coerceIn(0.0, 1.0)
            val green = linearToSrgb(rgb[1]).coerceIn(0.0, 1.0)
            val blue = linearToSrgb(rgb[2]).coerceIn(0.0, 1.0)
            return (alpha.coerceIn(0, 255) shl 24) or
                ((red * 255.0).roundToInt() shl 16) or
                ((green * 255.0).roundToInt() shl 8) or
                (blue * 255.0).roundToInt()
        }

        fun withArgb(value: Int): Oklch = copy(argb = value)

        private fun toLinearRgb(): DoubleArray {
            val radians = hue * PI / 180.0
            val a = chroma * cos(radians)
            val b = chroma * sin(radians)
            val lRoot = lightness + 0.3963377774 * a + 0.2158037573 * b
            val mRoot = lightness - 0.1055613458 * a - 0.0638541728 * b
            val sRoot = lightness - 0.0894841775 * a - 1.2914855480 * b
            val l = lRoot * lRoot * lRoot
            val m = mRoot * mRoot * mRoot
            val s = sRoot * sRoot * sRoot
            return doubleArrayOf(
                4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
                -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
                -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s,
            )
        }

        companion object {
            fun fromArgb(color: Int): Oklch {
                val lab = Oklab.fromArgb(color)
                val chroma = hypot(lab.a, lab.b)
                val hue = normalizeHue(atan2(lab.b, lab.a) * 180.0 / PI)
                return Oklch(
                    lightness = lab.lightness,
                    chroma = chroma,
                    hue = hue,
                    alpha = (color ushr 24) and 0xFF,
                    argb = color,
                )
            }
        }
    }

    private data class Oklab(
        val lightness: Double,
        val a: Double,
        val b: Double,
    ) {
        companion object {
            fun fromArgb(color: Int): Oklab {
                val red = srgbToLinear(((color ushr 16) and 0xFF) / 255.0)
                val green = srgbToLinear(((color ushr 8) and 0xFF) / 255.0)
                val blue = srgbToLinear((color and 0xFF) / 255.0)
                val l = 0.4122214708 * red + 0.5363325363 * green + 0.0514459929 * blue
                val m = 0.2119034982 * red + 0.6806995451 * green + 0.1073969566 * blue
                val s = 0.0883024619 * red + 0.2817188376 * green + 0.6299787005 * blue
                val lRoot = Math.cbrt(l)
                val mRoot = Math.cbrt(m)
                val sRoot = Math.cbrt(s)
                return Oklab(
                    lightness = 0.2104542553 * lRoot + 0.7936177850 * mRoot - 0.0040720468 * sRoot,
                    a = 1.9779984951 * lRoot - 2.4285922050 * mRoot + 0.4505937099 * sRoot,
                    b = 0.0259040371 * lRoot + 0.7827717662 * mRoot - 0.8086757660 * sRoot,
                )
            }
        }
    }

    private fun srgbToLinear(value: Double): Double =
        if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)

    private fun linearToSrgb(value: Double): Double =
        if (value <= 0.0031308) value * 12.92 else 1.055 * value.pow(1.0 / 2.4) - 0.055

    private fun shortestHueDelta(start: Double, end: Double): Double {
        var delta = (end - start) % 360.0
        if (delta > 180.0) delta -= 360.0
        if (delta <= -180.0) delta += 360.0
        return delta
    }

    private fun normalizeHue(value: Double): Double {
        val normalized = value % 360.0
        return if (normalized < 0.0) normalized + 360.0 else normalized
    }

    private fun lerp(start: Double, end: Double, amount: Double): Double =
        start + (end - start) * amount

    private fun square(value: Double): Double = value * value
}
