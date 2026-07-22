package com.juren233.hyperlyricsenhanced.root.island

import com.juren233.hyperlyricsenhanced.root.HookIslandGlow
import com.juren233.hyperlyricsenhanced.root.utils.DynamicFinder
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.libxposed.api.XposedModule

/**
 * SystemUI hook registry.
 */
object SystemUIHookRegistry {
    lateinit var module: XposedModule

    private val hookedClassLoaders = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<ClassLoader, Boolean>())
    )

    @Volatile
    var isHookedSuccess = false
        private set

    fun hook(xposedModule: XposedModule, cl: ClassLoader, lyricsOnly: Boolean = false) {
        if (cl.javaClass.name.contains("BootClassLoader")) return

        val islandPkg = "miui.systemui.dynamicisland"

        runCatching {
            cl.loadClass("$islandPkg.template.IslandTemplateBuilder")
        }.getOrNull() ?: DynamicFinder.findClassByConstantString(cl, "$islandPkg.template", "IslandTemplateBuilder") ?: return

        if (!hookedClassLoaders.add(cl)) return
        module = xposedModule

        try {
            IslandTextHooker.hook(module, cl, includeMediaHooks = !lyricsOnly)
            if (!lyricsOnly) {
                HookIslandGlow.init(module, cl)
                IslandProgressGlowHooker.hook(module, cl)
                IslandMusicWaveColorHooker.hook(module, cl)
                IslandAlbumCoverStyleHooker.hook(module, cl)
            }

            isHookedSuccess = true
            HookLogger.i(
                "SystemUIHookRegistry",
                if (lyricsOnly) "超级岛歌词 Hook 已初始化" else "超级岛 Hook 已初始化"
            )
        } catch (e: ClassNotFoundException) {
            HookLogger.w("SystemUIHookRegistry", "跳过不支持的超级岛插件: reason=${e.message}")
        } catch (e: Exception) {
            HookLogger.e("SystemUIHookRegistry", "注入超级岛插件失败", e)
        }
    }
}
