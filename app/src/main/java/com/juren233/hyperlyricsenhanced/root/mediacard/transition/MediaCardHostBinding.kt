/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.transition

import android.view.View
import android.view.ViewGroup
import com.juren233.hyperlyricsenhanced.root.mediacard.host.SystemUiMediaCapability
import com.juren233.hyperlyricsenhanced.root.mediacard.host.SystemUiMediaCapabilityKind
import com.juren233.hyperlyricsenhanced.root.mediacard.host.SystemUiMediaHostAdapter
import com.juren233.hyperlyricsenhanced.root.mediacard.host.SystemUiMediaTarget

/**
 * Typed hand-off from the verified SystemUI host adapter to the per-card session.
 *
 * The transition layer never resolves a SystemUI class or field itself. A caller
 * creates this object from Agent A's verified [SystemUiMediaHostAdapter.Binding] and
 * supplies only the host-specific layout/height mapping that it has independently
 * observed for this concrete controller.
 */
internal typealias RootLayoutParamsFactory = (ViewGroup, View?, Int) -> ViewGroup.LayoutParams?

internal class MediaCardHostBinding private constructor(
    val systemUiBinding: SystemUiMediaHostAdapter.Binding,
    private val rootLayoutParamsFactory: RootLayoutParamsFactory,
    val nativeHeightIndex: Int?,
    private val verifiedNativeTargetHeightFactory: ((ViewGroup, Boolean) -> Int?)?,
) {
    val classLoader: ClassLoader
        get() = systemUiBinding.classLoader

    val capability: SystemUiMediaCapability
        get() = systemUiBinding.capability

    fun isCompatibleWith(player: ViewGroup): Boolean =
        player.javaClass.classLoader === classLoader &&
            capability.supports(SystemUiMediaCapabilityKind.MEDIA_CONTROLLER_LIFECYCLE) &&
            capability.supports(SystemUiMediaCapabilityKind.MEDIA_HEADER_GEOMETRY) &&
            capability.supports(SystemUiMediaCapabilityKind.FULL_AOD_CALLBACK)

    fun isEquivalentTo(other: MediaCardHostBinding?): Boolean =
        this === other || (other != null &&
            systemUiBinding === other.systemUiBinding &&
            nativeHeightIndex == other.nativeHeightIndex)

    fun createRootLayoutParams(
        player: ViewGroup,
        anchor: View?,
        topGapPx: Int,
    ): ViewGroup.LayoutParams? = runCatching {
        rootLayoutParamsFactory(player, anchor, topGapPx)
    }.getOrNull()

    /** Finds the exact verified header class, never a decompiler/display-name alias. */
    fun findHeader(root: View): View? {
        val headerClass = systemUiBinding.loadedClasses[SystemUiMediaTarget.MEDIA_HEADER]
            ?: return null
        if (root.javaClass === headerClass) return root
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) {
            findHeader(root.getChildAt(index))?.let { return it }
        }
        return null
    }

    fun verifiedNativeTargetHeight(player: ViewGroup, targetFullAod: Boolean): Int? =
        runCatching { verifiedNativeTargetHeightFactory?.invoke(player, targetFullAod) }
            .getOrNull()
            ?.takeIf { it > 0 }

    companion object {
        /**
         * Returns null when the adapter is not verified for the concrete loader or
         * the required media-header capability is unavailable. A null result is a
         * deliberate fail-closed signal, not permission to guess a fallback class.
         */
        fun fromVerifiedBinding(
            binding: SystemUiMediaHostAdapter.Binding?,
            rootLayoutParamsFactory: RootLayoutParamsFactory,
            nativeHeightIndex: Int? = null,
            verifiedNativeTargetHeightFactory: ((ViewGroup, Boolean) -> Int?)? = null,
        ): MediaCardHostBinding? {
            binding ?: return null
            if (!binding.capability.supports(SystemUiMediaCapabilityKind.MEDIA_CONTROLLER_LIFECYCLE) ||
                !binding.capability.supports(SystemUiMediaCapabilityKind.MEDIA_HEADER_GEOMETRY) ||
                !binding.capability.supports(SystemUiMediaCapabilityKind.FULL_AOD_CALLBACK)
            ) {
                return null
            }
            if (nativeHeightIndex != null && nativeHeightIndex < 0) return null
            return MediaCardHostBinding(
                systemUiBinding = binding,
                rootLayoutParamsFactory = rootLayoutParamsFactory,
                nativeHeightIndex = nativeHeightIndex,
                verifiedNativeTargetHeightFactory = verifiedNativeTargetHeightFactory,
            )
        }
    }
}
