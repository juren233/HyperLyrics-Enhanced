/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.hooksettings

import com.juren233.hyperlyricsenhanced.common.coroutines.ModuleDispatchers
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.IslandMusicAppCatalog
import com.juren233.hyperlyricsenhanced.ui.utils.LocaleUtils
import com.juren233.hyperlyricsenhanced.utils.LogManager
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 应用选择页的应用列表与图标。
 *
 * 列表只读名称（多线程并行），图标在行可见时才按需加载；两者都在进程内缓存，
 * 再次进入页面先显示上次的列表，同时后台重新读取。
 */
internal object InstalledAppRepository {
    private const val TAG = "InstalledAppRepository"
    private const val ICON_BITMAP_SIZE_PX = 96

    private val lists = ConcurrentHashMap<Boolean, List<IslandMusicAppCatalog.App>>()

    private val icons = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 16).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    /** 无图标的包也记下，免得每次滚过都重读。 */
    private val missingIcons = ConcurrentHashMap.newKeySet<String>()

    private val iconDispatcher = ModuleDispatchers.IO.limitedParallelism(4)

    private val labelParallelism = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

    fun cachedApps(includeNonLauncherApps: Boolean): List<IslandMusicAppCatalog.App>? =
        lists[includeNonLauncherApps]

    suspend fun loadApps(context: Context, includeNonLauncherApps: Boolean): List<IslandMusicAppCatalog.App> =
        withContext(ModuleDispatchers.IO) {
            val startedAt = SystemClock.elapsedRealtime()
            val packageManager = LocaleUtils.systemLanguageContext(context).packageManager
            val applications = installedApplications(packageManager, includeNonLauncherApps)
                .asSequence()
                .filter { it.packageName != context.packageName }
                .distinctBy { it.packageName }
                .toList()
            val chunkSize = ((applications.size + labelParallelism - 1) / labelParallelism).coerceAtLeast(1)
            val apps = coroutineScope {
                applications.chunked(chunkSize)
                    .map { chunk -> async { chunk.map { it.toApp(packageManager) } } }
                    .awaitAll()
                    .flatten()
            }.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER, IslandMusicAppCatalog.App::displayName)
                    .thenBy(IslandMusicAppCatalog.App::packageName),
            )
            lists[includeNonLauncherApps] = apps
            if (BuildConfig.DEBUG) {
                LogManager.d(
                    TAG,
                    "应用列表读取完成: count=${apps.size}, nonLauncher=$includeNonLauncherApps, " +
                        "elapsed=${SystemClock.elapsedRealtime() - startedAt}ms",
                )
            }
            apps
        }

    fun cachedIcon(packageName: String): Bitmap? = icons.get(packageName)

    fun isIconMissing(packageName: String): Boolean = packageName in missingIcons

    suspend fun loadIcon(context: Context, packageName: String): Bitmap? {
        icons.get(packageName)?.let { return it }
        if (packageName in missingIcons) return null
        return withContext(iconDispatcher) {
            icons.get(packageName) ?: runCatching {
                context.packageManager.getApplicationIcon(packageName).toBitmap(
                    width = ICON_BITMAP_SIZE_PX,
                    height = ICON_BITMAP_SIZE_PX,
                    config = Bitmap.Config.ARGB_8888,
                )
            }.getOrNull().also { icon ->
                if (icon != null) icons.put(packageName, icon) else missingIcons += packageName
            }
        }
    }

    private fun installedApplications(
        packageManager: PackageManager,
        includeNonLauncherApps: Boolean,
    ): List<ApplicationInfo> = if (includeNonLauncherApps) {
        packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
    } else {
        packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
        ).mapNotNull { it.activityInfo?.applicationInfo }
    }

    private fun ApplicationInfo.toApp(packageManager: PackageManager) = IslandMusicAppCatalog.App(
        packageName = packageName,
        displayName = runCatching { loadLabel(packageManager).toString() }
            .getOrDefault(packageName)
            .takeUnless(String::isBlank)
            ?: packageName,
    )
}
