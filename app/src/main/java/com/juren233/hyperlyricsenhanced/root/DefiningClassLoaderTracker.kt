/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root

import java.util.Collections
import java.util.WeakHashMap

/**
 * 记录实际定义 [className] 的 ClassLoader，供开关重新开启时给已加载的宿主和插件补装 Hook。
 *
 * SystemUI 会为每个 `BaseDexClassLoader` 触发构造回调，其中绝大多数看不到目标类。
 * 这里只记录目标类的定义 loader（多个子 loader 委托到同一个定义 loader 时只记一项），
 * 且只弱引用持有，不延长宿主或插件 loader 的生命周期（issue #80）。
 */
internal class DefiningClassLoaderTracker(private val className: String) {
    private val loaders = Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())

    /**
     * 通过 [loader] 解析目标类并记录其定义 loader；[loader] 看不到目标类时返回 null，且不做记录。
     * 只吞掉 [ClassNotFoundException]，其他加载错误交给调用方记录。
     */
    fun resolve(loader: ClassLoader): Class<*>? {
        // loadClass 放在锁外：类加载可能耗时，也不能让清理路径等待它。
        val targetClass = try {
            loader.loadClass(className)
        } catch (_: ClassNotFoundException) {
            return null
        }
        targetClass.classLoader?.let { definingLoader ->
            synchronized(loaders) { loaders.add(definingLoader) }
        }
        return targetClass
    }

    /** 当前仍存活的定义 loader 中的目标类。 */
    fun knownClasses(): List<Class<*>> = snapshot().mapNotNull { loader ->
        runCatching { loader.loadClass(className) }.getOrNull()
    }

    fun snapshot(): List<ClassLoader> = synchronized(loaders) { loaders.toList() }

    fun clear() {
        synchronized(loaders) { loaders.clear() }
    }
}
