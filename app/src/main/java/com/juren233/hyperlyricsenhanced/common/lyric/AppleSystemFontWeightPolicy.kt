package com.juren233.hyperlyricsenhanced.common.lyric

object AppleSystemFontWeightPolicy {
    private const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"
    private const val FONT_RESOURCE_TYPE = "font"
    private const val REGULAR_WEIGHT = 400
    private const val BOLD_WEIGHT = 700
    private const val SF_PRO_MIN_WEIGHT = 1
    private const val SF_PRO_MAX_WEIGHT = 1000
    private const val SYSTEM_SCALE_MIN = 0
    private const val SYSTEM_SCALE_NEUTRAL = 50
    private const val SYSTEM_SCALE_MAX = 100
    private const val SF_PRO_WEIGHT_SHIFT_AT_SCALE_EDGE = 100

    private val supportedFontResources = setOf(
        "regular",
        "medium",
        "semibold",
        "bold",
        "black",
    )

    fun shouldReplaceFontResource(
        packageName: String,
        resourceType: String,
        resourceName: String,
    ): Boolean = packageName == APPLE_MUSIC_PACKAGE &&
        resourceType == FONT_RESOURCE_TYPE &&
        resourceName in supportedFontResources

    fun semanticWeight(reportedWeight: Int, isBold: Boolean): Int = when {
        reportedWeight in 1..1000 -> reportedWeight
        isBold -> BOLD_WEIGHT
        else -> REGULAR_WEIGHT
    }

    /**
     * 自定义 fallback Typeface 对外仍应报告 Apple 原本的语义字重。
     *
     * SF Pro 与 MiSans 的真实可变轴会分别写入各自的 [Font]；不能再把
     * SF Pro 的有效轴值作为 composite 的公开 style weight，否则下游会把
     * 已映射的轴值误认为新的语义字重，污染 Apple 自己的测量和逐字样式判断。
     */
    fun compositeStyleWeight(semanticWeight: Int): Int =
        semanticWeight.coerceIn(SF_PRO_MIN_WEIGHT, SF_PRO_MAX_WEIGHT)

    /**
     * 将 HyperOS 的粗细滑块转换成 SF Pro 自己的标准 `wght` 轴。
     *
     * HyperOS FontWght 返回的是 MiSans/MiType 的内部坐标，不能直接写入 SF Pro。
     * 系统滑块 50 为中性档，0/100 分别让 SF Pro 在原字重上减/加 100，
     * 从而保留 Apple 原有 Regular/Medium/Semibold/Bold/Black 层级。
     */
    fun sfProWeightForSystemScale(
        semanticWeight: Int,
        systemScale: Int?,
    ): Int {
        val originalWeight = semanticWeight.coerceIn(SF_PRO_MIN_WEIGHT, SF_PRO_MAX_WEIGHT)
        val normalizedScale = (systemScale ?: SYSTEM_SCALE_NEUTRAL)
            .coerceIn(SYSTEM_SCALE_MIN, SYSTEM_SCALE_MAX)
        val shift =
            (normalizedScale - SYSTEM_SCALE_NEUTRAL) *
                SF_PRO_WEIGHT_SHIFT_AT_SCALE_EDGE /
                (SYSTEM_SCALE_MAX - SYSTEM_SCALE_NEUTRAL)
        return (originalWeight + shift).coerceIn(SF_PRO_MIN_WEIGHT, SF_PRO_MAX_WEIGHT)
    }

    fun shouldReplaceTextContent(text: CharSequence?): Boolean {
        if (text.isNullOrEmpty()) return false
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            if (Character.isLetterOrDigit(codePoint)) return true
            index += Character.charCount(codePoint)
        }
        return false
    }

    /**
     * SF Pro 不包含中日韩表意文字。只有真实包含 CJK 字形的文本才需要接入
     * HyperOS 的 MiSans 可变字体 fallback，拉丁文字继续完全由 SF Pro 绘制。
     */
    fun shouldUseSystemCjkFallback(text: CharSequence?): Boolean {
        if (text.isNullOrEmpty()) return false
        var containsHanOrBopomofo = false
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            val script = Character.UnicodeScript.of(codePoint)
            when (script) {
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA,
                Character.UnicodeScript.HANGUL,
                -> return false
                Character.UnicodeScript.HAN,
                Character.UnicodeScript.BOPOMOFO,
                -> containsHanOrBopomofo = true
                else -> Unit
            }
            index += Character.charCount(codePoint)
        }
        return containsHanOrBopomofo
    }
}
