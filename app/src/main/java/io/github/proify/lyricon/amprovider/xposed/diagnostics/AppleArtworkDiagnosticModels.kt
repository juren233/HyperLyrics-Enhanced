/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.view.View
import android.widget.ImageView
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method

internal data class DebugLibraryArtworkComposeCapture(
    val mediaKey: String,
    val mediaId: String,
    val title: String?,
    val persistentId: Long,
    val contentType: Int,
)

internal data class DebugLibraryArtworkPainterTrace(
    val mediaKey: String,
    val mediaId: String,
    val title: String?,
    val persistentId: Long,
    val contentType: Int,
    val requestIdentity: Int?,
    val requestDataDescription: String,
    val requestDataClass: String,
    val requestDataHash: Int?,
    val memoryCacheKey: String,
    val placeholderSignature: String,
    val placeholderIdentity: String,
    val errorSignature: String,
    val errorIdentity: String,
) {
    fun hasSameSemanticRequest(other: DebugLibraryArtworkPainterTrace): Boolean =
        mediaKey == other.mediaKey &&
            requestDataClass == other.requestDataClass &&
            requestDataHash == other.requestDataHash &&
            memoryCacheKey == other.memoryCacheKey &&
            placeholderSignature == other.placeholderSignature &&
            errorSignature == other.errorSignature
}

internal data class DebugLibraryArtworkPainterState(
    val lifecycle: String? = null,
    val imageStateFingerprint: String? = null,
)

internal data class DebugListenNowArtworkTrace(
    val mediaKey: String,
    val mediaId: String,
    val title: String?,
    val persistentId: Long,
    val contentType: Int,
    val liveData: WeakReference<Any>,
    val model: WeakReference<Any>,
    val root: WeakReference<View>?,
    val imageViews: List<WeakReference<ImageView>>,
)

internal data class DebugLibraryArtworkAccessors(
    val requestClass: Class<*>,
    val requestData: Field,
    val requestMemoryCacheKey: Field,
    val requestPlaceholder: Field,
    val requestError: Field,
    val painterState: Field,
    val painterDrawPainter: Field,
    val statePainter: Method,
    val stateKinds: Map<Class<*>, String>,
)
