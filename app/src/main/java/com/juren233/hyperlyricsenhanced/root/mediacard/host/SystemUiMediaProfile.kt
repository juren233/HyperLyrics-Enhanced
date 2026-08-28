/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.host

/** Exact binary descriptor, retained instead of a decompiler-rendered Kotlin name. */
data class SystemUiDexMethod(
    val ownerDescriptor: String,
    val name: String,
    val signature: String,
) {
    val descriptor: String
        get() = "$ownerDescriptor->$name$signature"
}

data class SystemUiDexField(
    val ownerDescriptor: String,
    val name: String,
    val typeDescriptor: String,
) {
    val descriptor: String
        get() = "$ownerDescriptor->$name:$typeDescriptor"
}

enum class SystemUiMediaTarget {
    VIEW_CONTROLLER,
    MEDIA_VIEW_HOLDER,
    MEDIA_DATA,
    MEDIA_HEADER,
    FULL_AOD_CONTROLLER,
    FULL_AOD_TRANSITION_LISTENER,
}

enum class SystemUiMediaOs {
    HYPEROS_3,
    HYPEROS_4,
}

data class SystemUiMediaProfile(
    val os: SystemUiMediaOs,
    val buildLabels: Set<String>,
    val classes: Map<SystemUiMediaTarget, String>,
    val methods: Map<String, SystemUiDexMethod>,
    val fields: Map<String, SystemUiDexField>,
    val evidence: String,
    val binaryVerified: Boolean,
) {
    fun classDescriptor(target: SystemUiMediaTarget): String? = classes[target]

    fun method(key: String): SystemUiDexMethod? = methods[key]

    fun field(key: String): SystemUiDexField? = fields[key]

    fun isForBuild(buildLabel: String?): Boolean = buildLabel != null &&
        buildLabels.any { buildLabel.startsWith(it) }

    companion object {
        const val MEDIA_VIEW_CONTROLLER_CLASS =
            "Lcom/android/systemui/statusbar/notification/mediacontrol/MiuiMediaViewControllerImpl;"
        const val MEDIA_VIEW_HOLDER_CLASS =
            "Lcom/android/systemui/statusbar/notification/mediacontrol/MiuiMediaViewHolder;"
        const val MEDIA_DATA_CLASS =
            "Lcom/android/systemui/media/controls/shared/model/MediaData;"
        const val MEDIA_HEADER_CLASS =
            "Lcom/android/systemui/statusbar/notification/mediacontrol/MiuiMediaHeaderView;"
        const val FULL_AOD_CONTROLLER_CLASS =
            "Lcom/android/systemui/statusbar/notification/fullaod/NotifiFullAodController;"
        const val FULL_AOD_LISTENER_CLASS =
            "Lcom/android/systemui/statusbar/notification/fullaod/NotifiFullAodController\$FullAodTransitionListener;"

        private fun profile(
            os: SystemUiMediaOs,
            builds: Set<String>,
            evidence: String,
        ): SystemUiMediaProfile {
            val classes = mapOf(
                SystemUiMediaTarget.VIEW_CONTROLLER to MEDIA_VIEW_CONTROLLER_CLASS,
                SystemUiMediaTarget.MEDIA_VIEW_HOLDER to MEDIA_VIEW_HOLDER_CLASS,
                SystemUiMediaTarget.MEDIA_DATA to MEDIA_DATA_CLASS,
                SystemUiMediaTarget.MEDIA_HEADER to MEDIA_HEADER_CLASS,
                SystemUiMediaTarget.FULL_AOD_CONTROLLER to FULL_AOD_CONTROLLER_CLASS,
                SystemUiMediaTarget.FULL_AOD_TRANSITION_LISTENER to FULL_AOD_LISTENER_CLASS,
            )
            val methods = listOf(
                "controller.attach" to SystemUiDexMethod(
                    MEDIA_VIEW_CONTROLLER_CLASS,
                    "attach",
                    "($MEDIA_VIEW_HOLDER_CLASS)V",
                ),
                "controller.bindMediaData" to SystemUiDexMethod(
                    MEDIA_VIEW_CONTROLLER_CLASS,
                    "bindMediaData",
                    "($MEDIA_DATA_CLASS)V",
                ),
                "controller.detach" to SystemUiDexMethod(
                    MEDIA_VIEW_CONTROLLER_CLASS,
                    "detach",
                    "()V",
                ),
                "controller.onFullAodStateChanged" to SystemUiDexMethod(
                    MEDIA_VIEW_CONTROLLER_CLASS,
                    "onFullAodStateChanged",
                    "(Z)V",
                ),
                "header.getIntrinsicHeight" to SystemUiDexMethod(
                    MEDIA_HEADER_CLASS,
                    "getIntrinsicHeight",
                    "()I",
                ),
                "header.getMinHeight" to SystemUiDexMethod(
                    MEDIA_HEADER_CLASS,
                    "getMinHeight",
                    "(Z)I",
                ),
                "header.setActualHeight" to SystemUiDexMethod(
                    MEDIA_HEADER_CLASS,
                    "setActualHeight",
                    "(IZ)V",
                ),
                "header.setAnimateHeight" to SystemUiDexMethod(
                    MEDIA_HEADER_CLASS,
                    "setAnimateHeight",
                    "(I)V",
                ),
                "transition.onBegin" to SystemUiDexMethod(
                    FULL_AOD_LISTENER_CLASS,
                    "onBegin",
                    "(Ljava/lang/Object;)V",
                ),
                "transition.onUpdate" to SystemUiDexMethod(
                    FULL_AOD_LISTENER_CLASS,
                    "onUpdate",
                    "(Ljava/lang/Object;Ljava/util/Collection;)V",
                ),
                "transition.onComplete" to SystemUiDexMethod(
                    FULL_AOD_LISTENER_CLASS,
                    "onComplete",
                    "(Ljava/lang/Object;)V",
                ),
                "transition.onCancel" to SystemUiDexMethod(
                    FULL_AOD_LISTENER_CLASS,
                    "onCancel",
                    "(Ljava/lang/Object;)V",
                ),
            ).toMap()
            val fields = listOf(
                "header.mediaLockScreenHeight" to SystemUiDexField(
                    MEDIA_HEADER_CLASS,
                    "mediaLockScreenHeight",
                    "I",
                ),
                "header.mAnimateHeight" to SystemUiDexField(
                    MEDIA_HEADER_CLASS,
                    "mAnimateHeight",
                    "I",
                ),
                "transition.owner" to SystemUiDexField(
                    FULL_AOD_LISTENER_CLASS,
                    "this$0",
                    FULL_AOD_CONTROLLER_CLASS,
                ),
                "transition.fraction" to SystemUiDexField(
                    FULL_AOD_CONTROLLER_CLASS,
                    "mCurrentFraction",
                    "F",
                ),
                "transition.enableFullAod" to SystemUiDexField(
                    FULL_AOD_CONTROLLER_CLASS,
                    "mEnableFullAod",
                    "Z",
                ),
                "transition.heightList" to SystemUiDexField(
                    FULL_AOD_CONTROLLER_CLASS,
                    "mHeightList",
                    "[I",
                ),
            ).toMap()
            return SystemUiMediaProfile(
                os = os,
                buildLabels = builds,
                classes = classes,
                methods = methods,
                fields = fields,
                evidence = evidence,
                binaryVerified = true,
            )
        }

        /**
         * Verified from the original HyperOS 3.0.301.0 DEX. Do not replace these
         * descriptors with names emitted by JADX/JADX-like decompilers.
         */
        val OS3: SystemUiMediaProfile = profile(
            os = SystemUiMediaOs.HYPEROS_3,
            builds = setOf("3.0.301.0", "HyperOS 3.0.301.0"),
            evidence = "original binary evidence: HyperOS 3.0.301.0 SystemUI DEX",
        )

        /**
         * Verified from the original OS4.0.0.6.XOCCNXM DEX captured in the local
         * SystemUI recapture workspace. All transition callbacks, fields and media
         * controller descriptors above were present with these exact descriptors.
         */
        val OS4: SystemUiMediaProfile = profile(
            os = SystemUiMediaOs.HYPEROS_4,
            builds = setOf("OS4.0.0.6", "OS4.0.0.6.XOCCNXM"),
            evidence = "workspace/systemui-recapture-os4.0.0.6-20260827/meta/targets + dexdump",
        )

        fun forBuild(buildLabel: String?): SystemUiMediaProfile? = when {
            OS4.isForBuild(buildLabel) -> OS4
            OS3.isForBuild(buildLabel) -> OS3
            else -> null
        }
    }
}
