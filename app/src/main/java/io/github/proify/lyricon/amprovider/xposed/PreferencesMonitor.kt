/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.annotation.SuppressLint
import android.content.Context

@SuppressLint("StaticFieldLeak")
object PreferencesMonitor {

    private lateinit var context: Context
    var listener: Listener? = null

    fun initialize(context: Context) {
        if (::context.isInitialized) return
        this.context = context.applicationContext

    }

    fun notifyTranslationSelectedChanged(selected: Boolean) {
        listener?.onTranslationSelectedChanged(selected)
    }

    fun isTranslationSelected(): Boolean =
        runCatching {
            AppleReflection.callStatic(
                context.classLoader.loadClass("com.apple.android.music.utils.AppSharedPreferences"),
                "isLyricsTranslationSelected"
            ) as? Boolean
        }.getOrNull() ?: true

    interface Listener {
        fun onTranslationSelectedChanged(selected: Boolean)
    }
}
