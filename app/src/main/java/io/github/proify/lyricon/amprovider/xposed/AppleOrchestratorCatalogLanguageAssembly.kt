/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.AppleOriginalMetadataPolicy
import io.github.proify.lyricon.amprovider.xposed.hooks.AppleContentLocalizationHooks

/**
 * R6 目录/语言组装群。
 *
 * 持有目录解析器、内容语言偏好与本地化 Hook。偏好值通过属性在调用期读取，
 * 组装群不提前快照；[contentUiLanguagePreferenceListener] 是系统弱引用监听器
 * 的唯一强引用持有者，必须保持“赋值先于注册”。
 */
internal class AppleOrchestratorCatalogLanguageAssembly(
    private val application: Application,
    private val classLoader: ClassLoader,
    private val hookResolver: AppleMusicHookResolver,
    private val runtime: AppleMusicProviderRuntime,
) {
    internal lateinit var internalCatalogResolver: AppleInternalCatalogResolver
        private set

    internal lateinit var contentLocalizationHooks: AppleContentLocalizationHooks
        private set

    /** Apple Music 远程偏好；由 [AppleMusicProviderOrchestrator.initializeContentUiLanguage] 注入。 */
    internal var contentUiLanguagePrefs: SharedPreferences? = null
        private set

    /**
     * 远程 SharedPreferences 只持有监听器的弱引用，必须由本组装群强引用；
     * 注册顺序固定为“先赋值、后 registerOnSharedPreferenceChangeListener”。
     */
    internal var contentUiLanguagePreferenceListener:
        SharedPreferences.OnSharedPreferenceChangeListener? = null
        private set

    internal val catalogResolverOrNull: AppleInternalCatalogResolver?
        get() = if (::internalCatalogResolver.isInitialized) internalCatalogResolver else null

    fun assemble() {
        internalCatalogResolver = AppleInternalCatalogResolver(
            context = application,
            classLoader = classLoader,
            hookResolver = hookResolver,
            mainHandler = Handler(Looper.getMainLooper())
        )
        contentLocalizationHooks = AppleContentLocalizationHooks(
            runtime = runtime,
            preferences = { contentUiLanguagePrefs },
            catalogResolver = { internalCatalogResolver },
        )
    }

    fun attachPreferences(prefs: SharedPreferences) {
        contentUiLanguagePrefs = prefs
    }

    fun attachPreferenceListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        contentUiLanguagePreferenceListener = listener
    }

    fun applyConfiguredContentUiLanguage(prefs: SharedPreferences? = contentUiLanguagePrefs) {
        prefs ?: return
        val selection = prefs.getInt(
            RootConstants.KEY_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE,
        )
        internalCatalogResolver.applyContentUiLanguage(selection)
        internalCatalogResolver.setPersistentLocalizedCacheEnabled(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_APPLE_MUSIC_LOCALIZED_METADATA_CACHE,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LOCALIZED_METADATA_CACHE,
            )
        )
    }

    fun configuredContentUiLanguage(): Int {
        val prefs = contentUiLanguagePrefs
        return prefs?.getInt(
            RootConstants.KEY_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE,
        ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE
    }

    fun shouldOverrideAccountLanguage(selection: Int): Boolean {
        if (selection == RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_NONE) return false
        return contentUiLanguagePrefs?.getBoolean(
            RootConstants.KEY_HOOK_APPLE_MUSIC_OVERRIDE_ACCOUNT_LANGUAGE,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_OVERRIDE_ACCOUNT_LANGUAGE,
        ) == true
    }

    fun isRestoreCjkOriginalMetadataEnabled(): Boolean =
        contentUiLanguagePrefs?.getBoolean(
            RootConstants.KEY_HOOK_APPLE_MUSIC_RESTORE_CJK_ORIGINAL_METADATA,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_RESTORE_CJK_ORIGINAL_METADATA,
        ) == true

    fun shouldRestoreCjkOriginalMetadata(
        metadata: MediaMetadataCache.Metadata,
    ): Boolean = isRestoreCjkOriginalMetadataEnabled() &&
        AppleOriginalMetadataPolicy.shouldProbeCjkOriginalMetadata(
            mediaId = metadata.id,
            title = metadata.title,
            artist = metadata.artist,
            genre = metadata.genre,
        )

    fun isAodLyricsEnabled(): Boolean = contentUiLanguagePrefs?.getBoolean(
        RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS,
        RootConstants.DEFAULT_HOOK_ENABLE_AOD_LYRICS,
    ) ?: RootConstants.DEFAULT_HOOK_ENABLE_AOD_LYRICS
}
