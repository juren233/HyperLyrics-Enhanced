/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.content.SharedPreferences
import com.juren233.hyperlyricsenhanced.common.UIConstants
import java.util.concurrent.atomic.AtomicLong

/**
 * Apple Music 体验优化入口的运行时门控。
 *
 * 入口关闭时，Apple Music 内的功能 Hook 回调一律让位给原生实现（等价于未安装），
 * 但功能自身的设置原样保留，重新打开入口后立即恢复，不会丢失关闭前的配置。
 */
internal object AppleMusicOptimizationGate {
    /** 缓存的入口状态：Hook 回调位于歌词热路径，不逐次读取远程偏好。 */
    @Volatile
    private var enabled = true

    /** 偏好实现可能只持有监听器的弱引用，这里保留强引用。 */
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val revision = AtomicLong()

    fun attach(prefs: SharedPreferences) {
        updateEnabled(readEntryEnabled(prefs))
        val changeListener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == null || key == UIConstants.KEY_FEATURE_ENTRY_APPLE_MUSIC) {
                updateEnabled(readEntryEnabled(changed))
            }
        }
        listener?.let { runCatching { prefs.unregisterOnSharedPreferenceChangeListener(it) } }
        listener = changeListener
        runCatching { prefs.registerOnSharedPreferenceChangeListener(changeListener) }
    }

    /**
     * 入口是否开启。
     *
     * 未写入入口键时默认开启：本门控只运行在 Apple Music 进程内，该进程存在即说明已安装 Apple Music，
     * 与设置页“已安装 Apple Music 时入口默认开启”的默认值一致。
     */
    fun isEnabled(): Boolean = enabled

    /** 入口每次切换后的单调代次，用于使已排队的异步工作永久失效。 */
    fun revision(): Long = revision.get()

    private fun updateEnabled(next: Boolean) {
        if (enabled != next) revision.incrementAndGet()
        enabled = next
    }

    private fun readEntryEnabled(prefs: SharedPreferences): Boolean = runCatching {
        prefs.getBoolean(UIConstants.KEY_FEATURE_ENTRY_APPLE_MUSIC, true)
    }.getOrDefault(true)
}
