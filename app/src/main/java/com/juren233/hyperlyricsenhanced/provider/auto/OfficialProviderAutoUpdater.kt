/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider.auto

import com.juren233.hyperlyricsenhanced.common.coroutines.ModuleDispatchers
import android.content.Context
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderAcquisitionLog
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderItem
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderRepository
import com.juren233.hyperlyricsenhanced.provider.ProviderAutoUpdateDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * 后台自动更新已安装且已启用的插件（HLE 插件化计划 1.3）。
 *
 * 分两步：[findUpdates] 只拉目录并判断哪些插件有新版本，不依赖 Xposed service；
 * 只有确有可更新项时，调用方才需要等 service 绑定并执行 [install]。这样没有更新的
 * 绝大多数检查周期里，后台进程很快就能结束，不必空等 service。
 *
 * 安装一律走 Remote File 并写后校验，不允许退回应用私有目录。每个插件更新成功后
 * 发一条通知，提醒用户重启对应的音乐 App 以加载新插件。
 */
object OfficialProviderAutoUpdater {

    data class Outcome(
        val checked: Int,
        val updated: List<String>,
        val failed: List<String>,
    )

    /** 一次检查的结果：已检查的插件数，以及其中确有新版本、待安装的插件。 */
    class Plan internal constructor(
        val checked: Int,
        internal val pending: List<OfficialProviderItem>,
    ) {
        val hasUpdates: Boolean get() = pending.isNotEmpty()

        fun pendingIds(): String = pending.joinToString { it.catalog.id }
    }

    suspend fun findUpdates(context: Context): Plan = withContext(ModuleDispatchers.IO) {
        // includeTargetState=false：后台只读界面流程持久化的目标状态缓存，
        // 不触发 root 读回（未授权时会反复弹授权）；缓存为空时坏版本降级
        // 自然退化为不降级。
        val items = OfficialProviderRepository.loadItems(context, includeTargetState = false)
        val updatable = items.filter { it.enabled && it.installed && !it.needsRepair }
        val pending = updatable.filter { item ->
            val release = item.selectedRelease ?: return@filter false
            ProviderAutoUpdateDecision.shouldReplace(
                installedVersionCode = item.installedVersionCode,
                candidateVersionCode = release.versionCode,
                badVersionCodes = item.badVersionCodes,
            )
        }
        Plan(checked = updatable.size, pending = pending)
    }

    suspend fun install(context: Context, plan: Plan): Outcome = withContext(ModuleDispatchers.IO) {
        val updated = mutableListOf<String>()
        val failed = mutableListOf<String>()
        plan.pending.forEach { item ->
            val release = item.selectedRelease ?: return@forEach
            try {
                OfficialProviderRepository.downloadAndInstall(
                    context = context,
                    item = item,
                    requireRemoteFile = true,
                )
                updated += item.catalog.id
                ProviderUpdateNotifier.notifyUpdated(context, item.catalog)
                OfficialProviderAcquisitionLog.info(
                    "插件后台自动更新完成: id=${item.catalog.id} " +
                        "${item.installedVersionCode} -> ${release.versionCode}",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += item.catalog.id
                OfficialProviderAcquisitionLog.warn(
                    "插件后台自动更新失败: id=${item.catalog.id} " +
                        "${item.installedVersionCode} -> ${release.versionCode} " +
                        "error=${OfficialProviderAcquisitionLog.describe(e)}",
                )
            }
        }
        Outcome(checked = plan.checked, updated = updated, failed = failed)
    }
}
