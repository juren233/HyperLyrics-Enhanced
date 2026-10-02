/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

/**
 * Verified from the connected phone's original MiuiSystemUI.apk, 2026-10-02:
 * SHA-256 22afac555f2df2a33749083fb2093393835cf7dbdeb3f27595c2d07bb88b5ff0.
 * classes2.dex: DarkIconDispatcherImpl.applyIconTint()V dispatches mIconTint:I
 * and mTintAreas:Ljava/util/ArrayList; after applyDarkIntensity(F)V interpolates
 * the native light/dark colors. getTint(Collection, View, int) uses screen coordinates.
 * classes.dex: Dependency.sDependency and getDependencyInner(Object) provide the
 * existing default-display dispatcher, including when loaded after its construction.
 * These are raw DEX names, not JADX display aliases.
 */
internal object IslandStatusBarColorProfile {
    const val DISPATCHER = "com.android.systemui.statusbar.phone.DarkIconDispatcherImpl"
    const val DISPATCHER_INTERFACE = "com.android.systemui.plugins.DarkIconDispatcher"
    const val APPLY_TINT = "applyIconTint"
    const val TINT = "mIconTint"
    const val AREAS = "mTintAreas"
    const val DUMP_NAME = "mDumpableName"
    const val DEFAULT_DISPLAY_DUMP_NAME = "DarkIconDispatcherImpl"
    const val GET_TINT = "getTint"
    const val DEPENDENCY = "com.android.systemui.Dependency"
    const val DEPENDENCY_INSTANCE = "sDependency"
    const val GET_DEPENDENCY = "getDependencyInner"
}
