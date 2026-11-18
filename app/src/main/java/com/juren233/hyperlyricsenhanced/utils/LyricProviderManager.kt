package com.juren233.hyperlyricsenhanced.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.vector.ImageVector
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.ui.component.icon.GeminiColor
import java.text.Collator
import java.util.Locale

data class ModuleTag(
    val iconRes: Int? = null,
    val imageVector: ImageVector? = null,
    val title: String? = null,
    val titleRes: Int = -1,
    val isRainbow: Boolean = false
)

data class LyricModule(
    val packageInfo: PackageInfo,
    val label: String,
    val description: String?,
    val author: String?,
    val category: String?,
    val isCertified: Boolean = false,
    val tags: List<ModuleTag> = emptyList()
)

data class ProviderUiState(
    val modules: List<LyricModule> = emptyList(),
    val isLoading: Boolean = false
)

data class ModuleCategory(
    val name: String,
    val items: List<LyricModule>
)

object LyricProviderManager {

    suspend fun loadProviders(context: Context, stateFlow: MutableStateFlow<ProviderUiState>) {
        stateFlow.update { it.copy(isLoading = true) }

        withContext(Dispatchers.IO) {
            try {
                val packageManager = context.packageManager
                @Suppress("DEPRECATION")
                val getSignFlag =
                    PackageManager.GET_SIGNING_CERTIFICATES

                // 获取全部包名列表，减少初次获取的数据量
                val packageInfos = packageManager.getInstalledPackages(PackageManager.GET_META_DATA or getSignFlag)
                
                val targetPackages = packageInfos.filter { packageInfo ->
                    packageInfo.packageName != context.packageName && isValidModule(packageInfo)
                }

                if (targetPackages.isEmpty()) {
                    stateFlow.update { it.copy(isLoading = false, modules = emptyList()) }
                    return@withContext
                }

                val collator = Collator.getInstance(Locale.getDefault())
                val loadedModules = mutableListOf<LyricModule>()

                targetPackages.chunked(6).forEach { batch ->
                    val batchResults = batch.mapNotNull { processPackage(packageManager, it) }
                    loadedModules.addAll(batchResults)

                    val sortedList = loadedModules.sortedWith { m1, m2 ->
                        if (m1.isCertified != m2.isCertified) {
                            m2.isCertified.compareTo(m1.isCertified)
                        } else {
                            collator.compare(m1.label, m2.label)
                        }
                    }

                    stateFlow.update { 
                        it.copy(
                            modules = sortedList.toList(),
                            isLoading = loadedModules.size < targetPackages.size
                        )
                    }
                }
            } catch (e: Exception) {
                LogManager.e("LyricProviderManager", "加载歌词模块失败", e)
                stateFlow.update { it.copy(isLoading = false) }
            }
        }
    }

    internal fun isValidModule(packageInfo: PackageInfo): Boolean {
        val appInfo = packageInfo.applicationInfo ?: return false
        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val isUpdatedSystem = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        // 严格遵循 lyricon 逻辑：非系统应用且非更新后的系统应用 (即：!(isSystem || isUpdatedSystem))
        return !(isSystem || isUpdatedSystem) && appInfo.metaData?.getBoolean("lyricon_module") == true
    }

    private fun processPackage(pm: PackageManager, packageInfo: PackageInfo): LyricModule? {
        return try {
            val appInfo = packageInfo.applicationInfo ?: return null
            val metaData = appInfo.metaData ?: return null
            val label = appInfo.loadLabel(pm).toString()

            val isCertified = validateSignature(packageInfo)

            LyricModule(
                packageInfo = packageInfo,
                label = label,
                description = metaData.getString("lyricon_module_description"),
                author = metaData.getString("lyricon_module_author"),
                category = metaData.getString("lyricon_module_category"),
                isCertified = isCertified,
                tags = extractTags(pm, appInfo, metaData)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun extractTags(pm: PackageManager, appInfo: ApplicationInfo, metaData: android.os.Bundle): List<ModuleTag> {
        val tagsResId = metaData.getInt("lyricon_module_tags")
        val rawTags = if (tagsResId != 0) {
            runCatching {
                val resources = pm.getResourcesForApplication(appInfo)
                resources.getStringArray(tagsResId).toList()
            }.getOrDefault(emptyList())
        } else {
            metaData.getString("lyricon_module_tags")?.let { listOf(it) } ?: emptyList()
        }
        return rawTags.mapNotNull { tagKey ->
            if (tagKey.isBlank()) return@mapNotNull null
            getPredefinedTag(tagKey) ?: ModuleTag(title = tagKey)
        }
    }

    private fun getPredefinedTag(key: String): ModuleTag? {
        return when (key) {
            $$"$syllable" -> ModuleTag(
                imageVector = GeminiColor,
                titleRes = R.string.module_tag_syllable,
                isRainbow = true
            )
            $$"$translation" -> ModuleTag(
                iconRes = R.drawable.translate_24px,
                titleRes = R.string.module_tag_translation
            )
            $$"$bluetooth" -> ModuleTag(titleRes = R.string.module_tag_bluetooth)
            else -> null
        }
    }

    private fun validateSignature(packageInfo: PackageInfo): Boolean {
        // 简化版的签名校验逻辑，实际项目中可能需要更复杂的实现
        // 这里暂时返回 false，或者你可以实现完整的校验
        return false 
    }

    /**
     * 独立 Provider 对应的目标 App，分两类：
     * - Xposed 模块：读模块声明的作用域，即旧版 Xposed 的 `xposedscope` 元数据（字符串数组资源）
     *   与 libxposed 的 `META-INF/xposed/scope.list`（上游 tomakino/LyricProvider 两种都在用）。
     *   通用模块（如车载 Provider）声明为空，实际作用域由用户在 LSPosed 里勾选，读不到。
     * - 音乐 App 自带的 Provider：App 自己声明 `lyricon_module`，既无 `xposedmodule` 也无
     *   `META-INF/xposed/`（2026-11-18 真机核对 MD3Music、Halcyon、椒盐音乐），目标就是它自己。
     */
    fun declaredTargetPackages(context: Context): Set<String> {
        val packageManager = context.packageManager
        val modules = runCatching {
            packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList()).filter { it.packageName != context.packageName && isValidModule(it) }
        return modules.flatMapTo(linkedSetOf()) { module ->
            val appInfo = module.applicationInfo ?: return@flatMapTo emptyList()
            val apkXposed = apkXposedInfo(appInfo)
            providerTargets(
                packageName = module.packageName,
                isXposedModule = appInfo.metaData?.containsKey("xposedmodule") == true || apkXposed.hasXposedEntries,
                declaredScope = resourceScope(packageManager, appInfo) + apkXposed.scopeList,
            )
        }
    }

    /** 非 Xposed 模块的 Provider 由音乐 App 自带，刷新目标是它自己；Xposed 模块取声明的作用域。 */
    internal fun providerTargets(
        packageName: String,
        isXposedModule: Boolean,
        declaredScope: List<String>,
    ): List<String> = if (isXposedModule) declaredScope else listOf(packageName)

    private fun resourceScope(packageManager: PackageManager, appInfo: ApplicationInfo): List<String> {
        val resId = appInfo.metaData?.getInt("xposedscope") ?: 0
        if (resId == 0) return emptyList()
        return runCatching {
            packageManager.getResourcesForApplication(appInfo).getStringArray(resId).toList()
        }.getOrDefault(emptyList()).map(String::trim).filter(String::isNotEmpty)
    }

    private class ApkXposedInfo(val hasXposedEntries: Boolean, val scopeList: List<String>)

    /** libxposed 模块在 `META-INF/xposed/` 下放入口与作用域文件；有任何一项即视为 Xposed 模块。 */
    private fun apkXposedInfo(appInfo: ApplicationInfo): ApkXposedInfo = runCatching {
        java.util.zip.ZipFile(appInfo.sourceDir).use { zip ->
            val hasXposedEntries = zip.entries().asSequence().any { it.name.startsWith("META-INF/xposed/") }
            val scopeList = zip.getEntry("META-INF/xposed/scope.list")?.let { entry ->
                zip.getInputStream(entry).bufferedReader().use { reader -> parseScopeList(reader.readText()) }
            }.orEmpty()
            ApkXposedInfo(hasXposedEntries, scopeList)
        }
    }.getOrDefault(ApkXposedInfo(hasXposedEntries = false, scopeList = emptyList()))

    internal fun parseScopeList(text: String): List<String> =
        text.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.toList()

    fun categorizeModules(modules: List<LyricModule>, defaultCategory: String): List<ModuleCategory> {
        if (modules.isEmpty()) return emptyList()
        val grouped = modules.groupBy { it.category ?: defaultCategory }
        
        if (grouped.size == 1 && grouped.containsKey(defaultCategory)) {
            return listOf(ModuleCategory("", grouped[defaultCategory]!!))
        }
        
        return grouped.map { (name, items) -> ModuleCategory(name, items) }
            .sortedBy { it.name }
    }
}
