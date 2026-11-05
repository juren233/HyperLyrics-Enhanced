/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.lyric.DynamicLyricData
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderRepository
import com.juren233.hyperlyricsenhanced.service.LiveLyricService
import java.io.BufferedReader
import java.io.InputStreamReader

internal data class LogExportMusicApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long?,
    val installSourcePackageName: String?,
    val installSourceLabel: String?,
)

/** 已启用的官方插件及其当前已安装（或内置基线）版本。 */
internal data class LogExportPlugin(
    val id: String,
    val displayName: String,
    val versionName: String?,
    val versionCode: Int,
)

internal data class LogExportEnvironment(
    val moduleVersionName: String,
    val moduleVersionCode: Int,
    val enabledPlugins: List<LogExportPlugin>,
    val deviceModel: String,
    val systemVersion: String,
    val androidVersion: String,
    val musicApp: LogExportMusicApp?,
)

internal object LogExportEnvironmentCollector {
    suspend fun collect(context: Context): LogExportEnvironment {
        val appContext = context.applicationContext ?: context
        val packageName = resolveCurrentMusicPackage(appContext)
        val enabledPlugins = runCatching {
            OfficialProviderRepository.loadInstalledItems(appContext)
                .filter { it.enabled }
                .map {
                    LogExportPlugin(
                        id = it.catalog.id,
                        displayName = it.catalog.displayName,
                        versionName = it.installedVersionName,
                        versionCode = it.installedVersionCode,
                    )
                }
        }.getOrDefault(emptyList())
        return LogExportEnvironment(
            moduleVersionName = BuildConfig.VERSION_NAME,
            moduleVersionCode = BuildConfig.VERSION_CODE,
            enabledPlugins = enabledPlugins,
            deviceModel = readSystemProperty("ro.product.marketname") ?: Build.MODEL,
            systemVersion = readSystemProperty("ro.build.version.incremental") ?: Build.DISPLAY,
            androidVersion = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            musicApp = packageName?.let { resolveMusicApp(appContext.packageManager, it) },
        )
    }

    private fun resolveCurrentMusicPackage(context: Context): String? {
        DynamicLyricData.currentState.targetPackageName
            .trim()
            .takeIf(String::isNotEmpty)
            ?.let { return it }

        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return null
        val componentName = ComponentName(context, LiveLyricService::class.java)
        val controllers = runCatching { manager.getActiveSessions(componentName) }
            .getOrDefault(emptyList())
        return controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        }?.packageName?.takeIf(String::isNotBlank)
            ?: controllers.firstOrNull()?.packageName?.takeIf(String::isNotBlank)
    }

    private fun resolveMusicApp(
        packageManager: PackageManager,
        packageName: String,
    ): LogExportMusicApp {
        val packageInfo = runCatching {
            packageManager.getPackageInfo(packageName, 0)
        }.getOrNull()
        val label = runCatching {
            packageManager.getApplicationInfo(packageName, 0)
                .loadLabel(packageManager)
                .toString()
                .trim()
        }.getOrNull().orEmpty().ifBlank { packageName }
        val installSourceInfo = runCatching {
            packageManager.getInstallSourceInfo(packageName)
        }.getOrNull()
        val installSourcePackageName = selectInstallSourcePackage(
            installingPackageName = installSourceInfo?.installingPackageName,
            initiatingPackageName = installSourceInfo?.initiatingPackageName,
            originatingPackageName = installSourceInfo?.originatingPackageName,
        )
        val installSourceLabel = installSourcePackageName?.let { installerPackage ->
            runCatching {
                packageManager.getApplicationInfo(installerPackage, 0)
                    .loadLabel(packageManager)
                    .toString()
                    .trim()
            }.getOrNull()?.takeIf(String::isNotEmpty)
        }
        return LogExportMusicApp(
            packageName = packageName,
            label = label,
            versionName = packageInfo?.versionName.orEmpty(),
            versionCode = packageInfo?.let(PackageInfoCompat::getLongVersionCode),
            installSourcePackageName = installSourcePackageName,
            installSourceLabel = installSourceLabel,
        )
    }

    private fun readSystemProperty(key: String): String? {
        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", key))
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.readLine()?.trim()?.takeIf(String::isNotEmpty)
            }.also { process.waitFor() }
        }.getOrNull()
    }
}

internal fun selectInstallSourcePackage(
    installingPackageName: String?,
    initiatingPackageName: String?,
    originatingPackageName: String?,
): String? = sequenceOf(
    installingPackageName,
    initiatingPackageName,
    originatingPackageName,
).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.firstOrNull()

/** 插件版本显示为「版本名 (版本号)」；缺版本名时只显示版本号。 */
internal fun formatPluginVersion(versionName: String?, versionCode: Int): String {
    val name = versionName?.trim().orEmpty()
    return if (name.isEmpty()) versionCode.toString() else "$name ($versionCode)"
}
