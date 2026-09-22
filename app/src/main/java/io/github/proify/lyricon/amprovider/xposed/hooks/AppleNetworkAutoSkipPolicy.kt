/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.hooks

import java.io.EOFException
import java.io.InterruptedIOException
import java.lang.reflect.Field
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Apple Music 播放失败时的自动切歌决策。
 *
 * 原始 `shouldSkipToNextItem` 在底层播放出错后决定是否直接跳到下一首。弱网或服务端瞬时故障
 * 也会走到这里，于是用户看到的是「自动切歌」而不是持续缓冲。这里只拦截临时性网络失败，
 * 让 Apple Music 保留当前歌曲并由 Hook 自动重试。
 *
 * 判定只覆盖「临时性网络失败」：类型码 [NETWORK_FAILURE_TYPE] 且异常链上是可重试的网络异常。
 * 其余失败（会员/授权、资源缺失、类型码 8 的连接被拒等）一律返回 false，交回原生逻辑决定是否切歌。
 */
internal object AppleNetworkAutoSkipPolicy {
    /** ExoMediaPlayer 内部用于标记网络类播放失败的媒体错误码。 */
    const val NETWORK_FAILURE_TYPE = 14

    private const val MAX_CAUSE_DEPTH = 12

    /**
     * 永久性失败：重试无意义，交回原生逻辑继续切歌。
     *
     * 这些类型在原始实现中分别对应「会员/授权」与「资源不存在」，与网络抖动无关。
     */
    private val PERMANENT_FAILURE_SUFFIXES = listOf(
        "MediaAssetNotFoundException",
        "DownloadedMediaAssetNotFoundException",
        "PersistentKeyLoaderException",
        "ErrorConditionException",
        "HTTPErrorException",
        "NetworkConnectionDeniedException",
    )

    /** 可重试的瞬时网络异常；命中任意一个即认为是弱网抖动。 */
    private val TRANSIENT_EXCEPTIONS = listOf(
        EOFException::class.java,
        InterruptedIOException::class.java,
        ConnectException::class.java,
        NoRouteToHostException::class.java,
        SocketException::class.java,
        SocketTimeoutException::class.java,
        UnknownHostException::class.java,
    )

    /** ExoPlayer 服务端 5xx / 限流 / 超时类响应码，属于可重试范围。 */
    private val TRANSIENT_RESPONSE_CODES = setOf(408, 425, 429, 500, 502, 503, 504)

    /**
     * 判定一次播放失败是否属于「网络抖动」。
     *
     * [errorType] 必须为 [NETWORK_FAILURE_TYPE]，否则说明失败与网络无关，交由原生实现处理。
     * [exception] 会沿 `cause` 链向下查找，任一层的永久性失败都会否决整次判定。
     */
    fun isTransientNetworkFailure(exception: Throwable?, errorType: Int?): Boolean {
        if (exception == null || errorType != NETWORK_FAILURE_TYPE) return false

        var current: Throwable? = exception
        var depth = 0
        while (current != null && depth++ < MAX_CAUSE_DEPTH) {
            val cause = current
            if (PERMANENT_FAILURE_SUFFIXES.any { cause.javaClass.name.endsWith(it) }) {
                return false
            }
            if (TRANSIENT_EXCEPTIONS.any { it.isInstance(cause) }) return true
            // InvalidResponseCodeException/responseCode 是 ExoPlayer2 公开 API
            // （Apple Music 保留 com.google.android.exoplayer2 原始类名，未混淆），
            // 因此不进入 Apple 版本档案；字段缺失或不可读时返回 false，
            // 交回原生自动切歌决策。
            if (cause.javaClass.name.endsWith("InvalidResponseCodeException")) {
                val responseCode = readIntField(cause, "responseCode") ?: return false
                return responseCode in TRANSIENT_RESPONSE_CODES
            }
            current = cause.cause
        }
        return false
    }

    private fun readIntField(instance: Any, fieldName: String): Int? {
        var current: Class<*>? = instance.javaClass
        while (current != null) {
            val field: Field? = runCatching { current.getDeclaredField(fieldName) }.getOrNull()
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.getInt(instance)
                }.getOrNull()
            }
            current = current.superclass
        }
        return null
    }
}
