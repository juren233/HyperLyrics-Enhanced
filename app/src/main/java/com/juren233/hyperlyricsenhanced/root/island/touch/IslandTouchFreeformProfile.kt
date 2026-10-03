/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island.touch

/**
 * Original plugin classes2.dex, APK SHA-256
 * f07be6a32708b0205d5ecc91ed8ce1a38fd64a17de3d49dd88f6306f9d2dab99:
 * BaseContentView.getCurrentIslandData()DynamicIslandData / getDynamicIslandEventCoordinator()DynamicIslandEventCoordinator;
 * EventCoordinator.getKeyguardShowing()Z / openFreeForm(Landroid/os/Bundle;)V.
 * All are public final instance methods. The exact capital F in openFreeForm is required.
 * Original SystemUI classes2.dex, APK SHA-256
 * 22afac555f2df2a33749083fb2093393835cf7dbdeb3f27595c2d07bb88b5ff0:
 * DynamicIslandData.getExtras()Landroid/os/Bundle; is public final instance.
 * The host's onDynamicPluginCallback_openFreeform branch reads the Bundle keys below,
 * prefers the SBN's contentIntent when an SBN exists, and otherwise uses pending.intent.
 * See docs/knowledge-base/island-touch-freeform.md for the verified native launch path.
 */
internal object IslandTouchFreeformProfile {
    const val BASE_CONTENT = IslandTouchHookProfile.BASE_CONTENT
    const val DATA = IslandTouchExpansionProfile.DATA
    const val COORDINATOR = IslandTouchExpansionProfile.COORDINATOR
    const val GET_DATA = IslandTouchExpansionProfile.GET_DATA
    const val GET_COORDINATOR = IslandTouchExpansionProfile.GET_COORDINATOR
    const val GET_EXTRAS = "getExtras"
    const val GET_KEYGUARD_SHOWING = "getKeyguardShowing"
    const val OPEN_FREEFORM = "openFreeForm"
    const val PACKAGE_NAME = "miui.pkg.name"
    const val NOTIFICATION = "miui.sbn"
    const val PENDING_INTENT = "miui.pending.intent"
    const val WORKBENCH_MODE = "extra_open_freeform_in_workbench_mode"

    fun canOpen(
        expectedPackage: String,
        currentPackage: String?,
        isActivity: Boolean,
        keyguardShowing: Boolean,
    ): Boolean = expectedPackage.isNotBlank() && expectedPackage == currentPackage &&
        isActivity && !keyguardShowing
}
