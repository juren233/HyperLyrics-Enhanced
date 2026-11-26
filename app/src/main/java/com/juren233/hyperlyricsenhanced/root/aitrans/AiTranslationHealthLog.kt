package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 给日志分析用的 AI 翻译成功记录（i 级，正式版也写）。
 * 不是每次请求都写：系统界面启动后第一次成功写一条，之后只有失败过再恢复时才再写一条，
 * 这样分析能看出「最近一次失败之后翻译已经恢复」，日志里也不会刷屏。
 */
internal object AiTranslationHealthLog {
    const val TAG = "OpenAiTranslationClient"
    const val SUCCESS_MESSAGE = "翻译请求成功"

    private val successPending = AtomicBoolean(true)

    fun onSuccess(log: (String) -> Unit = { HookLogger.i(TAG, it) }) {
        if (successPending.compareAndSet(true, false)) log(SUCCESS_MESSAGE)
    }

    fun onFailure() {
        successPending.set(true)
    }

    internal fun resetForTest() {
        successPending.set(true)
    }
}
