/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.graphics.Typeface
import android.text.TextPaint
import android.widget.TextView
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

internal data class AppleSystemFontTextViewState(
    val originalTypeface: Typeface,
    val requestedWeight: Int,
    val italic: Boolean,
    val originalStyle: Int,
)

internal data class AppleSystemFontTemplateFieldPath(
    val bindingField: Field,
    val textField: Field,
) {
    fun get(adapter: Any): TextView? = runCatching {
        val binding = bindingField.get(adapter) ?: return@runCatching null
        textField.get(binding) as? TextView
    }.getOrNull()
}

internal data class AppleSystemFontLayoutInput(
    val text: CharSequence,
    val paint: TextPaint,
)

internal data class AppleSystemFontRequest(
    val original: Typeface,
    val semanticWeight: Int,
    val italic: Boolean,
)

internal data class AppleSystemFontVariationMethods(
    val axisConstructor: Constructor<*>,
    val createFromTypefaceWithVariation: Method,
    /**
     * Android 17 (HyperOS 4) removed [Typeface.isVariationInstance]; it only feeds draw
     * diagnostics, so a missing method must not invalidate the functional variation APIs.
     */
    val isVariationInstance: Method?,
)

internal class AppleSystemFontVariationCache<K : Any, V : Any>(
    private val maxEntries: Int,
) {
    private val values = LinkedHashMap<IdentityVariationKey<K>, V>()

    init {
        require(maxEntries > 0)
    }

    @Synchronized
    fun getOrCreate(
        original: K,
        effectiveWeight: Int,
        italic: Boolean,
        create: () -> V?,
    ): V? {
        val key = IdentityVariationKey(original, effectiveWeight, italic)
        values[key]?.let { return it }
        val created = create() ?: return null
        if (values.size >= maxEntries) {
            val iterator = values.entries.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        values[key] = created
        return created
    }

    @Synchronized
    fun clear() {
        values.clear()
    }

    private class IdentityVariationKey<K : Any>(
        private val original: K,
        private val effectiveWeight: Int,
        private val italic: Boolean,
    ) {
        override fun equals(other: Any?): Boolean =
            other is IdentityVariationKey<*> &&
                original === other.original &&
                effectiveWeight == other.effectiveWeight &&
                italic == other.italic

        override fun hashCode(): Int =
            31 * (31 * System.identityHashCode(original) + effectiveWeight) + italic.hashCode()
    }
}

internal data class AppleSystemFontReplacementSignature(
    val effectiveSfProWeight: Int,
    val semanticWeight: Int,
    val usesCjkFallback: Boolean,
    val italic: Boolean,
)

internal data class AppleLyricsGradientAnimatorSample(
    val animatorIdentity: Int,
    val animatedFraction: Float,
    val currentPlayTimeMs: Long,
    val durationMs: Long,
    val capturedAtUptimeMs: Long,
)

internal data class HyperOsFontWeightMethods(
    val loadFontSettingMethod: Method,
    val fontScaleField: Field,
    val getWeightIdxMethod: Method,
    val getScaleWghtMethod: Method,
    val miuiFontType: Any,
    val miuiFontPath: String,
    val typefaceFontFamiliesField: Field,
)
