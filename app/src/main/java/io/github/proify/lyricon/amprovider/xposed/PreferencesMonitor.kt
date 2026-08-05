/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.annotation.SuppressLint
import android.content.Context

@SuppressLint("StaticFieldLeak")
object PreferencesMonitor {

    private lateinit var context: Context
    private lateinit var hookResolver: AppleMusicHookResolver
    var listener: Listener? = null

    internal fun initialize(context: Context, hookResolver: AppleMusicHookResolver) {
        if (::context.isInitialized) return
        this.context = context.applicationContext
        this.hookResolver = hookResolver

    }

    fun notifyTranslationSelectedChanged(selected: Boolean) {
        listener?.onTranslationSelectedChanged(selected)
    }

    fun notifyPronunciationSelectedChanged(selected: Boolean) {
        listener?.onPronunciationSelectedChanged(selected)
    }

    fun isTranslationSelected(): Boolean =
        runCatching {
            AppleReflection.callStatic(
                hookResolver.resolveClass(
                    AppleMusicHookPoint.APPLE_SHARED_PREFERENCES_CLASS,
                ).clazz,
                "isLyricsTranslationSelected"
            ) as? Boolean
        }.getOrNull() ?: true

    fun isPronunciationSelected(): Boolean =
        runCatching {
            AppleReflection.callStatic(
                hookResolver.resolveClass(
                    AppleMusicHookPoint.APPLE_SHARED_PREFERENCES_CLASS,
                ).clazz,
                "isLyricsPronunciationSelected"
            ) as? Boolean
        }.getOrNull() ?: false

    interface Listener {
        fun onTranslationSelectedChanged(selected: Boolean)
        fun onPronunciationSelectedChanged(selected: Boolean)
    }
}
