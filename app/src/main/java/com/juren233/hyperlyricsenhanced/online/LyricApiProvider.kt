package com.juren233.hyperlyricsenhanced.online

import android.content.Context
import com.juren233.hyperlyricsenhanced.online.model.SearchSource
import com.juren233.hyperlyricsenhanced.online.source.ne.NeApi
import com.juren233.hyperlyricsenhanced.online.source.ne.NeSource
import com.juren233.hyperlyricsenhanced.online.source.kugou.KugouSource
import com.juren233.hyperlyricsenhanced.online.source.kuwo.KuwoSource
import com.juren233.hyperlyricsenhanced.online.source.qm.QmApi
import com.juren233.hyperlyricsenhanced.online.source.qm.QmSongCatalog
import com.juren233.hyperlyricsenhanced.online.source.qm.QmSource
import kotlinx.serialization.json.Json
import com.juren233.hyperlyricsenhanced.common.net.buildOwned
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object LyricApiProvider {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
    }

    private val baseClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .buildOwned()
    }

    private val neApi by lazy { NeApi(baseClient) }

    private val qmApi by lazy {
        val qmClient = baseClient.newBuilder()
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .addHeader("User-Agent", "okhttp/3.14.9")
                    .addHeader("Referer", "https://y.qq.com/")
                    .build()
                chain.proceed(req)
            }
            .buildOwned()

        QmApi(qmClient, json)
    }

    val qmSource: SearchSource by lazy { QmSource(qmApi) }
    internal val qmSongCatalog: QmSongCatalog by lazy { QmSongCatalog(qmApi) }
    val kuwoSource: SearchSource by lazy { KuwoSource() }
    val kugouSource: SearchSource by lazy { KugouSource() }

    private var _neSource: SearchSource? = null
    
    fun getNeSource(context: Context): SearchSource {
        if (_neSource == null) {
            _neSource = NeSource(neApi, json, context.applicationContext)
        }
        return _neSource!!
    }
}
