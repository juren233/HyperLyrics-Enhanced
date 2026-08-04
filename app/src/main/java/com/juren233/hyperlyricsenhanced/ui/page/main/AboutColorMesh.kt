/*
 * Copyright 2026 juren233
 * Licensed under the GNU General Public License v3.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.main

import android.content.Context
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.SystemClock
import android.view.View
import com.juren233.hyperlyricsenhanced.R
import kotlin.math.floor

/** 在原生 View 树中持续绘制流光，供同层 Logo 稳定取样。 */
internal class AboutColorMeshView(context: Context) : View(context) {
    private val renderer = AboutMeshRenderer(resources.displayMetrics.density)
    private val runtimeShader = RuntimeShader(loadShader(context))
    private val frameRunnable = Runnable { renderNextFrame() }

    private var running = false
    private var hasLoggedFirstFrame = false

    init {
        // HyperCeiler 以透明原生 View 承载 Shader RenderEffect。
        setBackgroundColor(Color.TRANSPARENT)
    }

    /** 更新主题时只替换配色，不重建 Shader 或重置动画相位。 */
    fun setDarkMode(darkMode: Boolean) {
        if (renderer.darkMode == darkMode) return
        renderer.darkMode = darkMode
        if (running && isAttachedToWindow) scheduleFrame()
    }

    /** 页面完全离屏后停止请求帧，再次显示时沿用单调时钟相位。 */
    fun setRunning(running: Boolean) {
        if (this.running == running) return
        this.running = running
        hasLoggedFirstFrame = false
        AboutDebugLog.d("mesh_running=$running attached=$isAttachedToWindow size=${width}x$height")
        removeCallbacks(frameRunnable)
        if (running && isAttachedToWindow) {
            scheduleFrame()
        } else if (!running) {
            setRenderEffect(null)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (running) scheduleFrame()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frameRunnable)
        setRenderEffect(null)
        super.onDetachedFromWindow()
    }

    /** 每帧更新 Shader 后安装 RenderEffect，复用上游的 RenderThread 输出路径。 */
    private fun renderNextFrame() {
        if (!running || !isAttachedToWindow) return
        if (width <= 0 || height <= 0) {
            scheduleFrame()
            return
        }

        renderer.apply(runtimeShader, width.toFloat(), height.toFloat())
        setRenderEffect(RenderEffect.createShaderEffect(runtimeShader))
        if (!hasLoggedFirstFrame) {
            hasLoggedFirstFrame = true
            AboutDebugLog.d(
                "mesh_first_frame size=${width}x$height bound=${renderer.lastBound.contentToString()} " +
                    "darkMode=${renderer.darkMode}",
            )
        }
        scheduleFrame()
    }

    /** 保持单一帧回调，避免主题或滚动更新叠加多个动画循环。 */
    private fun scheduleFrame() {
        removeCallbacks(frameRunnable)
        postOnAnimation(frameRunnable)
    }

    private companion object {
        fun loadShader(context: Context): String = context.resources
            .openRawResource(R.raw.about_color_mesh_frag)
            .bufferedReader()
            .use { it.readText() }
    }
}

/** 与背景和 Logo 回退层共享的时间轴及 Shader 输入。 */
internal class AboutMeshRenderer(
    private val density: Float,
) {
    private val animationEpochNanos = SystemClock.elapsedRealtimeNanos()

    var darkMode: Boolean = false
    var lastBound: FloatArray = floatArrayOf()
        private set

    fun apply(shader: RuntimeShader, widthPx: Float, heightPx: Float) {
        if (widthPx <= 0f || heightPx <= 0f) return

        val animationTime =
            (SystemClock.elapsedRealtimeNanos() - animationEpochNanos) / NANOS_PER_SECOND
        val palette = if (darkMode) DARK_PALETTE else LIGHT_PALETTE
        val effectHeight = (ABOUT_EFFECT_HEIGHT_DP * density).coerceAtMost(heightPx)
        val heightRatio = effectHeight / heightPx
        val bound = if (widthPx <= effectHeight) {
            floatArrayOf(0f, 1f - heightRatio, 1f, heightRatio)
        } else {
            val horizontalInset = ((widthPx - effectHeight) / 2f) / widthPx
            floatArrayOf(horizontalInset, 1f - heightRatio, effectHeight / widthPx, heightRatio)
        }
        lastBound = bound

        shader.setFloatUniform("uResolution", widthPx, heightPx)
        shader.setFloatUniform("uAnimTime", animationTime * palette.animationSpeed)
        shader.setFloatUniform("uBound", bound)
        shader.setFloatUniform("uPoints", COLOR_POINTS)
        shader.setFloatUniform("uColors", interpolatePalette(palette, animationTime))
        shader.setFloatUniform("uAlphaMulti", 1f)
        shader.setFloatUniform("uNoiseScale", 1.5f)
        shader.setFloatUniform("uPointOffset", palette.pointOffset)
        shader.setFloatUniform("uPointRadiusMulti", 1f)
        shader.setFloatUniform("uSaturateOffset", palette.saturateOffset)
        shader.setFloatUniform("uLightOffset", palette.lightOffset)
        shader.setFloatUniform("uAlphaOffset", 0.5f)
    }

    private companion object {
        private const val ABOUT_EFFECT_HEIGHT_DP = 472f
        private const val NANOS_PER_SECOND = 1_000_000_000.0f

        private val COLOR_POINTS = floatArrayOf(
            0.8f, 0.2f, 1f,
            0.8f, 0.9f, 1f,
            0.2f, 0.9f, 1f,
            0.2f, 0.2f, 1f,
        )

        /** 在三组已验证配色之间平滑循环，避免颜色切换产生跳变。 */
        private fun interpolatePalette(
            palette: AboutMeshPalette,
            animationTime: Float,
        ): FloatArray {
            val segmentPosition = (animationTime / palette.colorInterpPeriodSeconds) % 4f
            val segment = floor(segmentPosition).toInt()
            val linearProgress = segmentPosition - segment
            val progress = linearProgress * linearProgress * (3f - 2f * linearProgress)
            val start = when (segment) {
                0 -> palette.colors2
                1 -> palette.colors1
                2 -> palette.colors2
                else -> palette.colors3
            }
            val end = when (segment) {
                0 -> palette.colors1
                1 -> palette.colors2
                2 -> palette.colors3
                else -> palette.colors2
            }
            return FloatArray(start.size) { index ->
                start[index] + (end[index] - start[index]) * progress
            }
        }

        /** 首版关于页的基底、色点和动画参数；不要用低 alpha 蓝紫参数替代。 */
        private val LIGHT_PALETTE = AboutMeshPalette(
            colors1 = floatArrayOf(
                1.0f, 0.90f, 0.94f, 1.0f,
                1.0f, 0.84f, 0.89f, 1.0f,
                0.97f, 0.73f, 0.82f, 1.0f,
                0.64f, 0.65f, 0.98f, 1.0f,
            ),
            colors2 = floatArrayOf(
                0.58f, 0.74f, 1.0f, 1.0f,
                1.0f, 0.90f, 0.93f, 1.0f,
                0.74f, 0.76f, 1.0f, 1.0f,
                0.97f, 0.77f, 0.84f, 1.0f,
            ),
            colors3 = floatArrayOf(
                0.98f, 0.86f, 0.90f, 1.0f,
                0.60f, 0.73f, 0.98f, 1.0f,
                0.92f, 0.93f, 1.0f, 1.0f,
                0.56f, 0.69f, 1.0f, 1.0f,
            ),
            pointOffset = 0.2f,
            saturateOffset = 0.2f,
            lightOffset = 0.1f,
            animationSpeed = 1.05f,
            colorInterpPeriodSeconds = 5f,
        )

        private val DARK_PALETTE = AboutMeshPalette(
            colors1 = floatArrayOf(
                0.20f, 0.06f, 0.88f, 0.40f,
                0.30f, 0.14f, 0.55f, 0.50f,
                0.00f, 0.64f, 0.96f, 0.50f,
                0.11f, 0.16f, 0.83f, 0.40f,
            ),
            colors2 = floatArrayOf(
                0.07f, 0.15f, 0.79f, 0.50f,
                0.62f, 0.21f, 0.67f, 0.50f,
                0.06f, 0.25f, 0.84f, 0.50f,
                0.00f, 0.20f, 0.78f, 0.50f,
            ),
            colors3 = floatArrayOf(
                0.58f, 0.30f, 0.74f, 0.40f,
                0.27f, 0.18f, 0.60f, 0.50f,
                0.66f, 0.26f, 0.62f, 0.50f,
                0.12f, 0.16f, 0.70f, 0.60f,
            ),
            pointOffset = 0.4f,
            saturateOffset = 0.17f,
            lightOffset = 0f,
            animationSpeed = 1f,
            colorInterpPeriodSeconds = 8f,
        )
    }
}

private data class AboutMeshPalette(
    val colors1: FloatArray,
    val colors2: FloatArray,
    val colors3: FloatArray,
    val pointOffset: Float,
    val saturateOffset: Float,
    val lightOffset: Float,
    val animationSpeed: Float,
    val colorInterpPeriodSeconds: Float,
)
