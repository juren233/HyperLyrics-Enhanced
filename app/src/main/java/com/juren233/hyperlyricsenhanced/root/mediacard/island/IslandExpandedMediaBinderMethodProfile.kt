/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.island

import android.graphics.drawable.Drawable
import java.lang.reflect.Method

/** Runtime method descriptors used by the expanded-island media binder. */
internal object IslandExpandedMediaBinderMethodProfile {
    const val MEDIA_DATA_CLASS = "com.android.systemui.media.controls.shared.model.MediaData"
    const val LEGACY_ARTWORK_METHOD = "setAlbumImage"
    const val OS4_ARTWORK_METHOD = "setMusicBgShader"

    fun isArtworkUpdate(method: Method): Boolean {
        return isArtworkUpdate(
            name = method.name,
            returnTypeName = method.returnType.name,
            parameterTypeNames = method.parameterTypes.map(Class<*>::getName),
        )
    }

    internal fun isArtworkUpdate(
        name: String,
        returnTypeName: String,
        parameterTypeNames: List<String>,
    ): Boolean {
        return when (name) {
            LEGACY_ARTWORK_METHOD -> parameterTypeNames.size == 1
            OS4_ARTWORK_METHOD ->
                returnTypeName == Void.TYPE.name &&
                    parameterTypeNames == listOf(MEDIA_DATA_CLASS, Drawable::class.java.name)

            else -> false
        }
    }
}
