package io.github.proify.lyricon.amprovider.xposed

import android.content.Context
import com.juren233.hyperlyricsenhanced.online.utils.ChineseUtils

internal object AppleLyricTextTransform {
    private val rawReadDepth = ThreadLocal.withInitial { 0 }

    @Volatile
    private var context: Context? = null

    @Volatile
    private var enabled: (() -> Boolean)? = null

    fun initialize(context: Context, enabled: () -> Boolean) {
        this.context = context.applicationContext
        this.enabled = enabled
    }

    fun transform(text: String?): String? {
        text ?: return null
        if (isRawReadActive() || enabled?.invoke() != true) return text
        val currentContext = context ?: return text
        return ChineseUtils.toSimplified(currentContext, text)
    }

    fun isRawReadActive(): Boolean = (rawReadDepth.get() ?: 0) > 0

    fun <T> withRawReads(block: () -> T): T {
        rawReadDepth.set((rawReadDepth.get() ?: 0) + 1)
        return try {
            block()
        } finally {
            val nextDepth = (rawReadDepth.get() ?: 1) - 1
            if (nextDepth == 0) rawReadDepth.remove() else rawReadDepth.set(nextDepth)
        }
    }
}
