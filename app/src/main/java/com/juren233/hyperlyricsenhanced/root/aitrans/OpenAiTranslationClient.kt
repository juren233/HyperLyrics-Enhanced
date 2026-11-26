package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.common.coroutines.ModuleDispatchers
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.common.extensions.json
import com.juren233.hyperlyricsenhanced.common.extensions.toJson
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.style.AiTranslationConfigs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import com.juren233.hyperlyricsenhanced.common.net.buildOwned
import com.juren233.hyperlyricsenhanced.common.net.httpUrlConnectionEquivalent
import com.juren233.hyperlyricsenhanced.common.net.withPlatformUserAgent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.EOFException

/** OpenAI-compatible Chat Completions client for lyric translation. */
internal object OpenAiTranslationClient {
    private val client by lazy { httpUrlConnectionEquivalent(60_000L, 180_000L).buildOwned() }
    private val JSON_TYPE = "application/json".toMediaType()

    suspend fun request(
        configs: AiTranslationConfigs,
        song: Song? = null,
        texts: List<String>
    ): List<TranslationItem>? = withContext(ModuleDispatchers.IO) {
        if (configs.apiKey.isNullOrBlank()) {
            HookLogger.w("OpenAiTranslationClient", "跳过翻译请求: reason=missing_api_key")
            return@withContext null
        }

        val baseUrl = configs.baseUrl?.removeSuffix("/") ?: "https://api.openai.com/v1"
        val apiUrl =
            if (baseUrl.endsWith("/chat/completions")) baseUrl else "$baseUrl/chat/completions"

        val requestItems = texts.mapIndexedNotNull { index, text ->
            text.trim().takeIf(::shouldRequestTranslation)?.let {
                TranslationRequestItem(index = index, text = it)
            }
        }
        if (requestItems.isEmpty()) {
            HookLogger.d("OpenAiTranslationClient", "跳过翻译请求: reason=no_translatable_lines")
            return@withContext emptyList()
        }

        val payload = TranslationRequest(lyrics = requestItems)
        val requestIndices = requestItems.map { it.index }.toSet()
        val chatRequest = OpenAiChatRequest(
            model = configs.model.orEmpty(),
            messages = listOf(
                ChatMessage("system", AITranslationPrompt.build(configs, song)),
                ChatMessage("user", payload.toJson())
            ),
            responseFormat = ResponseFormat("json_object"),
            temperature = configs.temperature,
            topP = configs.topP,
            maxTokens = configs.maxTokens.takeIf { it > 0 },
            presencePenalty = configs.presencePenalty,
            frequencyPenalty = configs.frequencyPenalty
        )

        try {
        HookLogger.d("OpenAiTranslationClient", "发送翻译请求: model=${configs.model}, url=$apiUrl")

            val request = Request.Builder()
                .url(apiUrl)
                // Bytes, not a String: the Content-Type stays exactly "application/json".
                .post(chatRequest.toJson().toByteArray(Charsets.UTF_8).toRequestBody(JSON_TYPE))
                .withPlatformUserAgent()
                .header("Authorization", "Bearer ${configs.apiKey}")
                .build()

            client.newCall(request).execute().use { response ->
                val responseCode = response.code
                if (responseCode == 200) {
                    val responseBody = response.body!!.bytes().toString(Charsets.UTF_8)
                    val responseObj = json.decodeFromString<OpenAiChatResponse>(responseBody)
                    val content = responseObj.choices.firstOrNull()?.message?.content ?: run {
                    HookLogger.w("OpenAiTranslationClient", "翻译响应为空")
                        AiTranslationHealthLog.onFailure()
                        return@withContext null
                    }
                HookLogger.d("OpenAiTranslationClient", "翻译请求完成: code=$responseCode")
                    val items = AITranslationResponseParser.parse(content, requestIndices)
                    // 解析不出任何一行（响应里没有 JSON 等）不算成功。
                    if (items.isNotEmpty()) AiTranslationHealthLog.onSuccess() else AiTranslationHealthLog.onFailure()
                    items
                } else {
                HookLogger.e("OpenAiTranslationClient", "翻译请求失败: code=$responseCode")
                    AiTranslationHealthLog.onFailure()
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: EOFException) {
            HookLogger.w("OpenAiTranslationClient", "翻译连接意外关闭: reason=EOF")
            AiTranslationHealthLog.onFailure()
            null
        } catch (e: Exception) {
            HookLogger.e(
                "OpenAiTranslationClient",
                "翻译网络请求异常: type=${e.javaClass.simpleName}",
                e
            )
            AiTranslationHealthLog.onFailure()
            null
        }
    }

    private fun shouldRequestTranslation(text: String): Boolean {
        if (text.isBlank()) return false
        return text.any { it.isLetter() }
    }
}



