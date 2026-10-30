/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import java.io.File

/**
 * 目标进程侧的插件状态存储（HLE 插件化计划 1.4）。
 *
 * libxposed 在被 Hook 进程里只提供只读的远程偏好与远程文件，崩溃计数、
 * 坏版本、实际加载版本三类状态因此写入目标 App 自己的 filesDir：
 * `files/hle_provider/<pluginId>.{marker,bad,loaded}`。目标进程对自己的
 * filesDir 有完整读写权限，进程死亡时文件留存，天然满足崩溃标记的语义。
 *
 * 所有操作都是尽力而为：任何 IO 失败都退化为「无标记/无记录」，绝不影响
 * 插件加载本身。App 侧经 root 通道批量读回（见
 * [OfficialProviderHealthStore]），无 root 时这些状态只在目标进程内部生效。
 */
class ProviderTargetStateStore(private val dir: File) {

    fun readMarker(pluginId: String): ProviderLoadMarker.MarkerState? =
        ProviderLoadMarker.decode(readFile(markerFile(pluginId)))

    fun writeMarker(pluginId: String, state: ProviderLoadMarker.MarkerState) {
        writeFile(markerFile(pluginId), ProviderLoadMarker.encode(state))
    }

    fun clearMarker(pluginId: String) {
        runCatching { markerFile(pluginId).delete() }
    }

    fun readBadVersions(pluginId: String): Set<Int> =
        readFile(badVersionsFile(pluginId))
            ?.lineSequence()
            ?.mapNotNull(String::toIntOrNull)
            ?.toSet()
            .orEmpty()

    fun addBadVersion(pluginId: String, versionCode: Int) {
        val merged = readBadVersions(pluginId) + versionCode
        writeFile(badVersionsFile(pluginId), merged.sorted().joinToString("\n"))
    }

    fun readLoadedVersion(pluginId: String): Int =
        readFile(loadedVersionFile(pluginId))?.trim()?.toIntOrNull() ?: 0

    fun writeLoadedVersion(pluginId: String, versionCode: Int) {
        writeFile(loadedVersionFile(pluginId), versionCode.toString())
    }

    /** 本次启动没有加载任何版本（无可回退的上一版）时清掉过期的已加载记录。 */
    fun clearLoadedVersion(pluginId: String) {
        runCatching { loadedVersionFile(pluginId).delete() }
    }

    private fun markerFile(pluginId: String) = File(dir, "$pluginId.marker")
    private fun badVersionsFile(pluginId: String) = File(dir, "$pluginId.bad")
    private fun loadedVersionFile(pluginId: String) = File(dir, "$pluginId.loaded")

    private fun readFile(file: File): String? = runCatching {
        file.takeIf(File::isFile)?.readText(Charsets.UTF_8)
    }.getOrNull()

    private fun writeFile(file: File, content: String) {
        runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) return
            val temporary = File(dir, file.name + ".tmp")
            temporary.writeText(content, Charsets.UTF_8)
            if (!temporary.renameTo(file)) {
                // 目标 App 可能并发读取；rename 失败时退化为直接写。
                file.writeText(content, Charsets.UTF_8)
                temporary.delete()
            }
        }
    }

    companion object {
        const val DIRECTORY_NAME = "hle_provider"

        /**
         * 在目标进程内构造状态目录。handleLoadPackage 阶段拿不到目标
         * Context，按当前 uid 推导 `/data/user/<userId>/<pkg>/files`；
         * 目录不可写时返回 null，调用方退化为无标记模式继续加载。
         */
        fun forTargetProcess(packageName: String): ProviderTargetStateStore? = runCatching {
            val userId = android.os.Process.myUid() / 100_000
            val base = File("/data/user/$userId/$packageName/files/$DIRECTORY_NAME")
            if (!base.isDirectory && !base.mkdirs()) return null
            ProviderTargetStateStore(base)
        }.getOrNull()
    }
}
