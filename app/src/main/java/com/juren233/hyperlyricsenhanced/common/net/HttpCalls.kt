/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.common.net

import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.ResponseBody

/**
 * The status and headers of a finished call and, for a 2xx answer other than 204/205, its
 * decoded body. Same contract the sources had from Retrofit's `Response`.
 */
internal class HttpResult<T>(
    private val code: Int,
    private val headers: Headers,
    private val body: T?,
) {
    val isSuccessful: Boolean get() = code in 200..299
    fun code(): Int = code
    fun headers(): Headers = headers
    fun body(): T? = body
}

/**
 * Synchronous POST on this client. Called directly rather than through Retrofit: Retrofit reads
 * its endpoint annotations reflectively, and libcore caches every annotation instance per class
 * for the rest of the process, which keeps this module generation's loader reachable.
 *
 * Network errors and [decode] failures propagate, as they did from Retrofit's `execute()`.
 */
internal fun <T> OkHttpClient.post(
    url: String,
    body: RequestBody,
    headers: Map<String, String> = emptyMap(),
    decode: (ResponseBody) -> T,
): HttpResult<T> {
    val request = Request.Builder()
        .url(url)
        .post(body)
        .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
        .build()
    newCall(request).execute().use { response ->
        val code = response.code
        val decoded = if (response.isSuccessful && code != 204 && code != 205) decode(response.body!!) else null
        return HttpResult(code, response.headers, decoded)
    }
}
