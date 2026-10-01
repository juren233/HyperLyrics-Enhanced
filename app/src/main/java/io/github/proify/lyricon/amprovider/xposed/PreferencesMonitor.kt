/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.annotation.SuppressLint
import android.content.Context
import java.util.concurrent.atomic.AtomicReference

@SuppressLint("StaticFieldLeak")
object PreferencesMonitor {

    private lateinit var context: Context
    private lateinit var hookResolver: AppleMusicHookResolver
    private val pronunciationSnapshot = AtomicReference<Boolean?>()
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
        pronunciationSnapshot.set(selected)
        listener?.onPronunciationSelectedChanged(selected)
    }

    fun isTranslationSelected(): Boolean =
        runCatching {
            val resolved = hookResolver.resolveClass(AppleMusicHookPoint.APPLE_SHARED_PREFERENCES_CLASS)
            readAppleLyricsPreference(resolved.clazz, resolved.target, pronunciation = false)
        }.getOrNull() ?: true

    fun isPronunciationSelected(): Boolean =
        runCatching {
            val resolved = hookResolver.resolveClass(AppleMusicHookPoint.APPLE_SHARED_PREFERENCES_CLASS)
            readAppleLyricsPreference(
                resolved.clazz, resolved.target, pronunciation = true,
                pronunciationSnapshot = pronunciationSnapshot,
            )
        }.getOrNull() ?: false

    interface Listener {
        fun onTranslationSelectedChanged(selected: Boolean)
        fun onPronunciationSelectedChanged(selected: Boolean)
    }
}

/** 1606 split the old static preferences into a cache and a typed DataStore key. */
internal fun readAppleLyricsPreference(
    clazz: Class<*>,
    target: AppleMusicHookTarget,
    pronunciation: Boolean,
    pronunciationSnapshot: AtomicReference<Boolean?>? = null,
): Boolean? {
    val getter = target.runtimeMemberNameOrNull(
        if (pronunciation) AppleMusicRuntimeMember.LYRICS_PREFERENCES_PRONUNCIATION_GETTER
        else AppleMusicRuntimeMember.LYRICS_PREFERENCES_TRANSLATION_GETTER,
    )
    if (getter != null) return AppleReflection.callStatic(clazz, getter) as? Boolean

    val cached = clazz.getDeclaredField(
        target.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_PREFERENCES_PRONUNCIATION_CACHE_FIELD),
    ).apply { isAccessible = true }.get(null) as? Boolean
    if (cached != null) return cached
    pronunciationSnapshot?.get()?.let { return it }

    val key = clazz.getDeclaredField(
        target.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_PREFERENCES_PRONUNCIATION_KEY_FIELD),
    ).apply { isAccessible = true }.get(null)
    val store = AppleReflection.callStatic(
        clazz, target.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_PREFERENCES_STORE_GETTER),
    ) ?: return null
    val selected = AppleReflection.call(
        store, target.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_PREFERENCES_STORE_READ_METHOD),
        key, false,
    ) as? Boolean
    // The original store read blocks on DataStore; avoid repeating it in lyric getters.
    // A real setter callback that arrives during the cold read must win over its snapshot.
    pronunciationSnapshot?.compareAndSet(null, selected)
    return pronunciationSnapshot?.get() ?: selected
}
