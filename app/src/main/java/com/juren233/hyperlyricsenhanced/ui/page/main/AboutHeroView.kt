/*
 * Copyright 2026 juren233
 * Licensed under the GNU General Public License v3.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.main

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.TextViewCompat
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.R
import kotlin.math.roundToInt

/** 将流光与 Logo 放在同一棵原生 View 树，避免跨 Compose 图层取样闪烁。 */
internal class AboutHeroView(context: Context) : FrameLayout(context) {
    private val meshView = AboutColorMeshView(context)
    private val materialRoot = FrameLayout(context)
    private val logoFrame = FrameLayout(context)
    private val logoView = createLogoColumn()
    private val shadeView = createLogoColumn()

    private var appName = ""
    private var darkMode: Boolean? = null
    private var lastHeroStateMarker: String? = null
    private var hasLoggedFirstLayout = false

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO

        addView(
            meshView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        addView(
            materialRoot,
            LayoutParams(LayoutParams.MATCH_PARENT, dpToPx(HERO_HEIGHT_DP)),
        )
        materialRoot.addView(logoFrame, createLogoLayoutParams())
        logoFrame.addView(logoView.root, createLogoColumnLayoutParams())
        logoFrame.addView(shadeView.root, createLogoColumnLayoutParams())
        shadeView.root.visibility = View.INVISIBLE
    }

    /** 绑定稳定内容；只有名称或主题变化时才重新安装一次材质。 */
    fun bind(appName: String, darkMode: Boolean) {
        val nameChanged = this.appName != appName
        val previousDarkMode = this.darkMode
        val materialChanged = nameChanged || previousDarkMode != darkMode
        this.appName = appName
        this.darkMode = darkMode
        if (materialChanged) {
            AboutDebugLog.d(
                "bind nameChanged=$nameChanged darkMode=$darkMode previousDarkMode=$previousDarkMode " +
                    "attached=$isAttachedToWindow",
            )
        }
        if (nameChanged) {
            // 避免 Compose 滚动重组反复触发名称重新布局和材质重绘。
            logoView.name.setMaskText(appName)
            shadeView.name.setMaskText(appName)
        }
        meshView.setDarkMode(darkMode)
        if (materialChanged && isAttachedToWindow) applyHyperCeilerMaterial(darkMode)
    }

    /** 同步滚动状态，背景与 Logo 独立淡出，且不触碰 blur/blender 配置。 */
    fun updateVisualState(
        active: Boolean,
        backgroundAlpha: Float,
        logoAlpha: Float,
        logoScale: Float,
        scrollOffsetPx: Float,
        pageOffsetFraction: Float,
    ) {
        meshView.setRunning(active)
        meshView.alpha = backgroundAlpha.coerceIn(0f, 1f)
        val effectiveLogoAlpha = if (active) logoAlpha else 0f
        val offsetBucket = (pageOffsetFraction * 20f).toInt()
        val stateMarker = "$active:$offsetBucket:${backgroundAlpha.toInt()}:${effectiveLogoAlpha.toInt()}"
        if (stateMarker != lastHeroStateMarker) {
            lastHeroStateMarker = stateMarker
            AboutDebugLog.d(
                "hero_state active=$active offset=$pageOffsetFraction offsetBucket=$offsetBucket " +
                    "backgroundAlpha=$backgroundAlpha logoAlpha=$effectiveLogoAlpha scale=$logoScale " +
                    "scrollOffsetPx=$scrollOffsetPx " +
                    "translationX=${aboutLogoTranslationX(pageOffsetFraction, width)}",
            )
        }
        updateLogoTransform(
            logoFrame,
            effectiveLogoAlpha,
            logoScale,
            scrollOffsetPx,
            pageOffsetFraction,
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        AboutDebugLog.d("hero_attached size=${width}x$height")
        // AndroidView 可能先 attach 后 update；等名称绑定后再安装，避免首帧重复切材质。
        if (appName.isNotEmpty()) applyHyperCeilerMaterial(darkMode ?: isNightMode())
    }

    override fun onDetachedFromWindow() {
        AboutDebugLog.d("hero_detached size=${width}x$height")
        meshView.setRunning(false)
        clearBlurState()
        super.onDetachedFromWindow()
    }

    /** 使用共同硬件层将 Logo 与同坐标的流光背景做空间合成。 */
    private fun applyHyperCeilerMaterial(darkMode: Boolean) {
        detachLogoMasks()
        clearBlurState()
        applySpatialLogoMaterial(darkMode)
    }

    /**
     * 保留 130279 已在真机确认能产生空间多色的层级，只用 HyperCeiler
     * mode 18/19 对应的 COLOR_DODGE/COLOR_BURN，仅微调中性灰基色的亮度。
     */
    private fun applySpatialLogoMaterial(darkMode: Boolean) {
        val logoColor = if (darkMode) DARK_SPATIAL_COLOR else LIGHT_SPATIAL_COLOR
        val logoTint = ColorStateList.valueOf(logoColor)
        val blendMode = if (darkMode) BlendMode.COLOR_DODGE else BlendMode.COLOR_BURN
        logoView.icon.setBackgroundResource(R.drawable.ic_about_logo_mask)
        logoView.icon.backgroundTintList = logoTint
        logoView.name.attachMask(logoTint)
        logoFrame.setLayerType(
            LAYER_TYPE_HARDWARE,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.blendMode = blendMode
            },
        )
        logoView.root.visibility = View.VISIBLE
        shadeView.root.visibility = View.INVISIBLE
        AboutDebugLog.d(
            "material_branch=spatial darkMode=$darkMode iconDrawable=${R.drawable.ic_about_logo_mask} " +
                "logoColor=$logoColor iconTint=${logoTint.defaultColor} " +
                "nameTint=${logoTint.defaultColor} blendMode=$blendMode logoFrameLayer=${logoFrame.layerType} " +
                "logoFrameChildren=${logoFrame.childCount} nameWidth=${logoView.name.layoutParams.width}",
        )
    }

    /** 清理旧材质和隐藏 API 状态，避免 View 复用或离屏后残留。 */
    private fun clearBlurState() {
        shadeView.root.visibility = View.INVISIBLE
        HyperOsBlurUtils.clearBackgroundBlendColors(logoView.icon)
        HyperOsBlurUtils.clearBackgroundBlendColors(logoView.name)
        HyperOsBlurUtils.setViewBlurMode(logoView.icon, 0)
        HyperOsBlurUtils.setViewBlurMode(logoView.name, 0)
        HyperOsBlurUtils.clearBackgroundBlur(materialRoot)
        logoFrame.setLayerType(LAYER_TYPE_NONE, null)
    }

    /** 卸下已有蒙版，确保主题切换时不会叠加旧 tint。 */
    private fun detachLogoMasks() {
        logoView.icon.background = null
        logoView.icon.backgroundTintList = null
        logoView.name.detachMask()
        shadeView.icon.background = null
        shadeView.icon.backgroundTintList = null
        shadeView.name.detachMask()
    }

    /** 创建图标与名称列；TextView 不指定固定字重，以继承系统粗细调节。 */
    private fun createLogoColumn(): LogoColumn {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val icon = ImageView(context).apply {
            isClickable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(dpToPx(ICON_SIZE_DP), dpToPx(ICON_SIZE_DP))
        }
        val nameWidth = aboutNameContainerWidthPx(
            screenWidthPx = resources.displayMetrics.widthPixels,
            sideMarginPx = dpToPx(NAME_SCREEN_MARGIN_DP),
        )
        val name = AboutNameMaskView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            setHorizontallyScrolling(false)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, NAME_TEXT_SIZE_SP)
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this,
                NAME_MIN_TEXT_SIZE_SP,
                NAME_TEXT_SIZE_SP.toInt(),
                1,
                TypedValue.COMPLEX_UNIT_SP,
            )
            setPadding(dpToPx(NAME_SIDE_PADDING_DP), 0, dpToPx(NAME_SIDE_PADDING_DP), 0)
            setTextColor(Color.TRANSPARENT)
            isClickable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(
                nameWidth,
                dpToPx(NAME_HEIGHT_DP),
            ).apply {
                topMargin = dpToPx(ICON_NAME_GAP_DP)
            }
        }
        root.addView(icon)
        root.addView(name)
        return LogoColumn(root, icon, name)
    }

    /** Logo 跟随关于页同向进出，并继续响应关于页自身的纵向滚动。 */
    private fun updateLogoTransform(
        view: View,
        alpha: Float,
        scale: Float,
        scrollOffsetPx: Float,
        pageOffsetFraction: Float,
    ) {
        view.translationX = aboutLogoTranslationX(pageOffsetFraction, width)
        view.translationY = -scrollOffsetPx
        view.alpha = alpha.coerceIn(0f, 1f)
        view.scaleX = scale
        view.scaleY = scale
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        AboutDebugLog.d("hero_size width=$width height=$height old=${oldWidth}x$oldHeight")
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (BuildConfig.DEBUG && !hasLoggedFirstLayout && width > 0 && height > 0 && logoView.icon.width > 0) {
            hasLoggedFirstLayout = true
            val nameLayout = logoView.name.layout
            val nameAvailableWidth = (
                logoView.name.width - logoView.name.paddingLeft - logoView.name.paddingRight
                ).coerceAtLeast(0)
            AboutDebugLog.d(
                "hero_first_layout size=${width}x$height material=${materialRoot.width}x${materialRoot.height} " +
                    "logoFrame=${logoFrame.width}x${logoFrame.height} " +
                    "logoRoot=${logoView.root.width}x${logoView.root.height} " +
                    "icon=${logoView.icon.width}x${logoView.icon.height} " +
                    "name=${logoView.name.width}x${logoView.name.height} " +
                    "nameAvailableWidth=$nameAvailableWidth " +
                    "nameMeasuredTextWidth=${logoView.name.paint.measureText(appName)} " +
                    "nameTextSizePx=${logoView.name.textSize} " +
                    "nameEllipsis=${nameLayout?.takeIf { it.lineCount > 0 }?.getEllipsisCount(0)} " +
                    "logoFrameLayer=${logoFrame.layerType} logoFrameChildren=${logoFrame.childCount} " +
                    "mainParentIsFrame=${logoView.root.parent === logoFrame} " +
                    "shadeParentIsFrame=${shadeView.root.parent === logoFrame} " +
                    "iconBackground=${logoView.icon.background?.javaClass?.name} " +
                    "nameBackground=${logoView.name.background?.javaClass?.name}",
            )
        }
    }

    /** 复现当前关于页中已确认的 Logo 起始位置和占位高度。 */
    private fun createLogoLayoutParams(): LayoutParams = LayoutParams(
        aboutNameContainerWidthPx(
            screenWidthPx = resources.displayMetrics.widthPixels,
            sideMarginPx = dpToPx(NAME_SCREEN_MARGIN_DP),
        ),
        dpToPx(LOGO_CONTENT_HEIGHT_DP),
        Gravity.TOP or Gravity.CENTER_HORIZONTAL,
    ).apply {
        topMargin = dpToPx(LOGO_TOP_MARGIN_DP)
    }

    private fun createLogoColumnLayoutParams(): FrameLayout.LayoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
        Gravity.CENTER_HORIZONTAL,
    )

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun dpToPx(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private data class LogoColumn(
        val root: LinearLayout,
        val icon: ImageView,
        val name: AboutNameMaskView,
    )

    private companion object {
        private const val HERO_HEIGHT_DP = 478
        private const val LOGO_TOP_MARGIN_DP = 212
        private const val LOGO_CONTENT_HEIGHT_DP = 150
        private const val ICON_SIZE_DP = 90
        private const val ICON_NAME_GAP_DP = 20
        private const val NAME_HEIGHT_DP = 40
        private const val NAME_SCREEN_MARGIN_DP = 12
        private const val NAME_SIDE_PADDING_DP = 20
        private const val NAME_TEXT_SIZE_SP = 30f
        private const val NAME_MIN_TEXT_SIZE_SP = 22
        private const val LIGHT_SPATIAL_COLOR = 0xCC3A3A3A.toInt()
        private const val DARK_SPATIAL_COLOR = 0xE6B5B5B5.toInt()

    }
}

/** 外置 Logo 与位于 Pager 右侧的关于页保持同向位移。 */
internal fun aboutLogoTranslationX(pageOffsetFraction: Float, pageWidthPx: Int): Float =
    -pageOffsetFraction * pageWidthPx

/** 给长应用名使用完整屏幕可用宽度，避免复用 HyperCeiler 的固定 280dp 后被截断。 */
internal fun aboutNameContainerWidthPx(screenWidthPx: Int, sideMarginPx: Int): Int =
    (screenWidthPx - sideMarginPx.coerceAtLeast(0) * 2).coerceAtLeast(0)

/** 使用 TextView 的最终 TextPaint 生成 MIUI blender 所需的文字背景蒙版。 */
private class AboutNameMaskView(context: Context) : AppCompatTextView(context) {
    private val maskDrawable = AppNameMaskDrawable(this)

    init {
        setTextColor(Color.TRANSPARENT)
    }

    fun setMaskText(value: String) {
        text = value
        contentDescription = value
        maskDrawable.setText(value)
    }

    fun attachMask(tint: ColorStateList) {
        maskDrawable.setMaskColor(tint.defaultColor)
        background = maskDrawable
    }

    fun detachMask() {
        background = null
    }
}

/** 绘制完整应用名；TextView 已负责自动缩放，这里不再做第二次 ellipsize。 */
private class AppNameMaskDrawable(
    private val owner: TextView,
) : Drawable() {
    private var text = ""
    private var maskColor = Color.WHITE
    private var drawableAlpha = 255
    private var drawableColorFilter: ColorFilter? = null

    fun setText(value: String) {
        if (text == value) return
        text = value
        invalidateSelf()
    }

    fun setMaskColor(color: Int) {
        if (maskColor == color) return
        maskColor = color
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty || text.isEmpty()) return
        val paint = TextPaint(owner.paint).apply {
            color = maskColor
            alpha = drawableAlpha
            colorFilter = drawableColorFilter
            textAlign = Paint.Align.LEFT
        }
        val textWidth = paint.measureText(text)
        val x = bounds.centerX() - textWidth / 2f
        val metrics = paint.fontMetrics
        val baseline = bounds.centerY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, x, baseline, paint)
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        drawableColorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
