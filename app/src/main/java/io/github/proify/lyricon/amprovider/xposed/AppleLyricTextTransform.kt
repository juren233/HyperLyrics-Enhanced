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

    /**
     * 展示边界使用：开启繁转简时把解析到的繁体歌名/歌手名/专辑名转成简体。
     * 只用于写入 App UI、通知与框架元数据；持久化原名缓存必须保留原值，
     * 否则关闭开关无法恢复，也会改变在线检索使用的原名键。
     */
    fun displayAlias(alias: Alias): Alias = alias.copy(
        title = transform(alias.title).orEmpty(),
        artist = transform(alias.artist).orEmpty(),
        album = transform(alias.album).orEmpty(),
    )

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
