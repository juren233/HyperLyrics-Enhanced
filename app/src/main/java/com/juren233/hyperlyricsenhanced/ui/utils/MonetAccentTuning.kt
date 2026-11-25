package com.juren233.hyperlyricsenhanced.ui.utils

import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.Colors

/**
 * 对 miuix 生成的莫奈配色做的个别修正，App 主题、强调色色块、外观预览都经 [ThemeUtils.themeColors] 套用这一份。
 *
 * 深色 + 莫奈 + 红色：TonalSpot 深色方案的主色固定取色调 80（#FFB4A9），红色在这个亮度下彩度只剩约 29，
 * 看起来像粉色。只把这一种组合的主色换成 #F78E80（色相约 26、彩度约 45、色调约 70），其余强调色和浅色方案不动。
 * 试过 #FF7262（彩度 64、色调 65），真机上太艳，所以取在原值和它之间。和深色莫奈粉 #FFB2BE
 * （色相约 8、彩度约 32、色调约 80）相比，色相差约 18、彩度高约 13、亮度低约 10，仍能分开。
 * 主色上的文字色 onPrimary 仍是色调 20 的 #561E18，与新主色对比度约 5.7:1（≥ 4.5）；
 * 主色容器 / 容器上的文字色 / 禁用色与主色无关，保持原值。
 */
object MonetAccentTuning {
    /** [MonetPalette.keyColors] 里红色的下标。 */
    const val RED_INDEX = 3

    val DARK_RED_PRIMARY = Color(0xFFF78E80)

    fun adjust(colors: Colors, monet: Boolean, dark: Boolean, monetColorIndex: Int): Colors {
        if (!monet || !dark || monetColorIndex != RED_INDEX) return colors
        // 滑块的圆点 miuix 默认就是主色，一起换掉，避免同一页里出现两种红。
        return colors.copy(primary = DARK_RED_PRIMARY, sliderKeyPoint = DARK_RED_PRIMARY)
    }
}
