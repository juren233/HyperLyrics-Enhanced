/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island.touch

/**
 * Original SystemUI APK SHA-256 22afac555f2df2a33749083fb2093393835cf7dbdeb3f27595c2d07bb88b5ff0.
 * Verified against classes.dex/classes2.dex on 2026-10-03, including static/instance
 * constraints. These are binary names, not JADX aliases. The host's Dagger provider
 * returns the injected singleton; do not construct a manager or a synthetic listener.
 */
internal object IslandMediaOutputProfile {
    const val FACTORY = "com.android.systemui.SystemUIAppComponentFactoryBase"
    const val INITIALIZER_FIELD = "systemUIInitializer"
    const val INITIALIZER_TYPE = "com.android.systemui.SystemUIInitializerImpl"
    const val INITIALIZER_BASE = "com.android.systemui.SystemUIInitializer"
    const val COMPONENT_GET = "getSysUIComponent"
    const val COMPONENT_TYPE = "com.android.systemui.dagger.SysUIComponent"
    const val COMPONENT_IMPL = "com.android.systemui.dagger.DaggerReferenceGlobalRootComponent\$ReferenceSysUIComponentImpl"
    const val MANAGER_PROVIDER = "provideMiuiMediaTransferManagerProvider"
    const val PROVIDER_TYPE = "dagger.internal.Provider"
    const val PROVIDER_INTERFACE = "javax.inject.Provider"
    const val PROVIDER_GET = "get"
    const val MANAGER = "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaTransferManagerImpl"
    const val CHECK_SUPPORT = "checkForTransferComponent"
    const val SUPPORT = "mSupportMiPlayAudio"
    const val LOCAL_MANAGER = "mLocalMediaManager"
    const val LOCAL_MANAGER_TYPE = "com.android.settingslib.media.LocalMediaManager"
    const val USER_TRACKER = "mUserTracker"
    const val USER_TRACKER_TYPE = "com.android.systemui.settings.UserTracker"
    const val USER_TRACKER_IMPL = "com.android.systemui.settings.UserTrackerImpl"
    const val GET_USER_ID = "getUserId"
    const val LISTENER = "mOnClickHandler"
    const val LISTENER_TYPE = "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaTransferManagerImpl\$2"
    const val ON_CLICK = "onClick"
    const val MODAL_VIEW = "com.android.systemui.statusbar.notification.modal.ModalWindowView"
    const val SHOW = "showMiPlay"
    const val PLUGIN_MANAGER = "com.android.systemui.controlcenter.phone.controls.MiPlayPluginManager"
    const val PLUGIN_MANAGER_FIELD = "mMiPlayPluginManager"
    const val PLUGIN_FIELD = "mMiPlayPlugin"
    const val PLUGIN_INTERFACE = "com.android.systemui.plugins.miui.controls.MiPlayPlugin"
    // Original interface descriptor: isInterconnectionCTAAgree(Landroid/content/Context;)Z.
    // Plugin DEX 0x2a6520 returns false immediately for null, before reading consent.
    const val CTA_CHECK = "isInterconnectionCTAAgree"
    const val CTA_STATE_KEY = "settings_key_interconnection_privacy_state"
    const val DETAIL = "com.android.systemui.statusbar.notification.modal.ModalQSControlDetail"
    const val DETAIL_SHOW = "handleShowingDetail"
    const val DETAIL_ADAPTER = "com.android.systemui.qs.miplay.MiPlayDetailAdapter"
    const val ISLAND_SOURCE = "dynamic_island"
}
