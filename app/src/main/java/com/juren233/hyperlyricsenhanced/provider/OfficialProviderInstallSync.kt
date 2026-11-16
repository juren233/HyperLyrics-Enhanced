/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

/**
 * 已安装状态变了（另一个页面安装、移除、启停了插件，或后台自动更新），把本机安装记录
 * （[OfficialProviderRepository.loadInstalledItems]）并进页面正在显示的列表。
 *
 * 不重新请求插件目录：已经拿到的目录信息（可用更新、发布版本）照旧保留；安装状态没变的条目
 * 原样返回，所以页面自己刚做完的操作再并一次不会有任何变化。
 */
internal object OfficialProviderInstallSync {

    /** 插件页的已安装列表：条目以本机记录为准增删。 */
    fun installedList(
        shown: List<OfficialProviderItem>,
        local: List<OfficialProviderItem>,
    ): List<OfficialProviderItem> {
        val shownById = shown.associateBy { it.catalog.id }
        return local.map { fresh -> shownById[fresh.catalog.id]?.withInstallState(fresh) ?: fresh }
    }

    /** 添加插件页的目录列表：条目不变，只换安装状态（被移除的插件重新可添加）。 */
    fun catalogList(
        shown: List<OfficialProviderItem>,
        local: List<OfficialProviderItem>,
    ): List<OfficialProviderItem> {
        val localById = local.associateBy { it.catalog.id }
        return shown.map { item -> item.withInstallState(localById[item.catalog.id]) }
    }

    private fun OfficialProviderItem.withInstallState(fresh: OfficialProviderItem?): OfficialProviderItem {
        // 本来就没装、现在也没装：目录条目原样保留。
        if (fresh == null && !installed) return this
        val versionCode = fresh?.installedVersionCode ?: 0
        val freshEnabled = fresh?.enabled ?: false
        val freshNeedsRepair = fresh?.needsRepair ?: false
        if (versionCode == installedVersionCode && freshEnabled == enabled && freshNeedsRepair == needsRepair) {
            return this
        }
        val versionChanged = versionCode != installedVersionCode
        return copy(
            installedVersionCode = versionCode,
            installedVersionName = if (versionChanged) fresh?.installedVersionName else installedVersionName,
            displayVersionName = if (versionChanged) fresh?.installedVersionName else displayVersionName,
            enabled = freshEnabled,
            needsRepair = freshNeedsRepair,
        )
    }
}
