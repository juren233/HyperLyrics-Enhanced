/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.animation.ValueAnimator
import java.lang.ref.WeakReference
import java.lang.reflect.Method

internal data class AppleLyricsHyperOsMethods(
    val setSelfBlur: Method?,
    val setSelfBlurType: Method?,
)

internal data class AppleLyricsBlurRuntimeState(
    var adapterRef: WeakReference<Any>? = null,
    var recyclerHeight: Int = 0,
    var settledAnchorTopY: Float? = null,
    var suspendedForScroll: Boolean = false,
    var pendingProgrammaticRecenterPosition: Int? = null,
    var lastActivePositions: Set<Int> = emptySet(),
    var pendingOutgoingPositions: Set<Int> = emptySet(),
    var outgoingZoneTopByPosition: Map<Int, Float> = emptyMap(),
    var pendingApplyBlur: Runnable? = null,
    var lastDiagnosticSignature: String? = null,
    var blurMode: Int? = null,
    var currentBlurRadius: Float = 0f,
    var targetBlurRadius: Float = 0f,
    var blurAnimator: ValueAnimator? = null,
)
