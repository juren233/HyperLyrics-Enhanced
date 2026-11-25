package com.juren233.hyperlyricsenhanced.ui.utils

import androidx.compose.ui.graphics.Color

/**
 * 莫奈强调色色板：下标即 KEY_MONET_COLOR 的存储值。0 为「系统」，不指定种子色，由系统调色板（壁纸/系统主题）决定；
 * 1–7 为固定种子色。主题与设置页的色块共用这一份，避免两边数值对不上。
 */
object MonetPalette {
    val keyColors: List<Color?> = listOf(
        null, // 系统
        Color(0xFF2196F3), // 蓝色
        Color(0xFF4CAF50), // 绿色
        Color(0xFFF44336), // 红色
        Color(0xFFFFEB3B), // 黄色
        Color(0xFFFF9800), // 橙色
        Color(0xFF9C27B0), // 紫色
        Color(0xFFE91E63), // 粉色
    )

    /** 存储值对应的种子色；0 或超出范围返回 null（按系统调色板）。 */
    fun keyColorOf(index: Int): Color? = keyColors.getOrNull(index)
}
