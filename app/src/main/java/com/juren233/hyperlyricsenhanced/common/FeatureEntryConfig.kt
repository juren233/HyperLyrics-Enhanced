/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderCatalog

/**
 * 应用设置中“功能入口”分组的可用性判定与默认开值。
 *
 * 入口开关本身始终允许用户手动切换；这里的判定只决定首次进入（或升级后尚未写入配置）时的默认值，
 * 以及主页面需要保留哪些页面。
 */
object FeatureEntryConfig {

    /** 厂商与品牌字符串中用于匹配米系设备的关键词。 */
    private val XIAOMI_BRAND_MARKERS = listOf("xiaomi", "redmi")

    /** 判定厂商与品牌字符串是否属于小米或红米。 */
    fun isXiaomiOrRedmi(manufacturer: String?, brand: String?): Boolean {
        return listOfNotNull(manufacturer, brand).any { value ->
            val normalized = value.trim().lowercase()
            normalized.isNotEmpty() && XIAOMI_BRAND_MARKERS.any(normalized::contains)
        }
    }

    /** 当前设备是否为小米或红米设备。 */
    fun isXiaomiOrRedmiDevice(): Boolean = isXiaomiOrRedmi(Build.MANUFACTURER, Build.BRAND)

    /** 当前设备是否安装了 Apple Music。 */
    fun isAppleMusicInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(
                OfficialProviderCatalog.APPLE_MUSIC_PACKAGE_NAME,
                PackageManager.PackageInfoFlags.of(0L),
            )
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    /** 米系超级岛歌词入口默认开值：仅小米/红米设备默认开启。 */
    fun defaultHyperIslandEntryEnabled(): Boolean = isXiaomiOrRedmiDevice()

    /** 米系息屏歌词入口默认开值：仅小米/红米设备默认开启。 */
    fun defaultAodLyricsEntryEnabled(): Boolean = isXiaomiOrRedmiDevice()

    /** Apple Music 体验优化入口默认开值：仅安装了 Apple Music 的设备默认开启。 */
    fun defaultAppleMusicEntryEnabled(context: Context): Boolean = isAppleMusicInstalled(context)
}
