package com.lidesheng.hyperlyric.root.aitrans

import com.lidesheng.hyperlyric.root.utils.HookLogger
import com.lidesheng.hyperlyric.common.extensions.json
import com.lidesheng.hyperlyric.common.extensions.toJson
import com.lidesheng.hyperlyric.lyric.model.Song
import com.lidesheng.hyperlyric.lyric.style.AiTranslationConfigs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/** OpenAI-compatible Chat Completions client for lyric translation. */
internal object OpenAiTranslationClient {
    suspend fun request(
        configs: AiTranslationConfigs,
        song: Song? = null,
        texts: List<String>
    ): List<TranslationItem>? = withContext(Dispatchers.IO) {
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

        var connection: HttpURLConnection? = null
        try {
            val url = URL(apiUrl)
        HookLogger.d("OpenAiTranslationClient", "发送翻译请求: model=${configs.model}, url=$apiUrl")

            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 60 * 1000
                readTimeout = 3 * (60 * 1000)
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${configs.apiKey}")
            }

            OutputStreamWriter(connection.outputStream).use {
                it.write(chatRequest.toJson())
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                val responseObj = json.decodeFromString<OpenAiChatResponse>(responseBody)
                val content = responseObj.choices.firstOrNull()?.message?.content ?: run {
                HookLogger.w("OpenAiTranslationClient", "翻译响应为空")
                    return@withContext null
                }
            HookLogger.d("OpenAiTranslationClient", "翻译请求完成: code=$responseCode")
                AITranslationResponseParser.parse(content, requestIndices)
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "无错误信息"
            HookLogger.e("OpenAiTranslationClient", "翻译请求失败: code=$responseCode")
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: EOFException) {
            HookLogger.w("OpenAiTranslationClient", "翻译连接意外关闭: reason=EOF")
            null
        } catch (e: Exception) {
            HookLogger.e(
                "OpenAiTranslationClient",
                "翻译网络请求异常: type=${e.javaClass.simpleName}",
                e
            )
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun shouldRequestTranslation(text: String): Boolean {
        if (text.isBlank()) return false
        return text.any { it.isLetter() }
    }
}



