package com.homeprojector.launcher.net

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Клиент чат-бота на локальном сервере (по умолчанию 192.168.1.124).
 * Поддерживает Ollama (/api/chat) и OpenAI-совместимые серверы (/v1/chat/completions),
 * потоковую выдачу токенов и обычный (непотоковый) ответ.
 */
object ChatClient {

    data class Msg(val role: String, val content: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    /**
     * Блокирующий вызов (запускать на Dispatchers.IO).
     * Возвращает полный текст ответа; [onToken] вызывается по мере поступления.
     * [registerCall] отдаёт OkHttp Call для отмены генерации.
     */
    fun streamChat(
        baseUrl: String,
        api: String,
        model: String,
        systemPrompt: String,
        history: List<Msg>,
        apiKey: String = "",
        registerCall: (Call) -> Unit = {},
        onToken: (String) -> Unit = {}
    ): String {
        if (baseUrl.isBlank()) throw IOException("Не задан адрес чат-бота (Настройки → Чат-бот)")

        val messages = JSONArray()
        if (systemPrompt.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        }
        history.forEach { m ->
            messages.put(JSONObject().put("role", m.role).put("content", m.content))
        }

        val isOpenAi = api == "openai"
        val url = if (isOpenAi) {
            if (baseUrl.endsWith("/v1")) "$baseUrl/chat/completions" else "$baseUrl/v1/chat/completions"
        } else {
            "$baseUrl/api/chat"
        }

        val payload = JSONObject()
            .put("model", model.ifBlank { "llama3.2" })
            .put("messages", messages)
            .put("stream", true)
        val requestBuilder = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }
        val request = requestBuilder.build()

        val call = client.newCall(request)
        registerCall(call)
        call.execute().use { resp ->
            val body = resp.body ?: throw IOException("Пустой ответ сервера")
            if (!resp.isSuccessful) {
                throw IOException("Чат-бот вернул HTTP ${resp.code}: ${body.string().take(200)}")
            }
            val contentType = resp.header("Content-Type").orEmpty()
            val full = StringBuilder()

            if (!isOpenAi) {
                // Ollama: NDJSON, каждая строка — {"message":{"content":...},"done":...}
                val source = body.source()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    val obj = JSONObject(line)
                    if (obj.has("error")) throw IOException(obj.optString("error"))
                    val token = obj.optJSONObject("message")?.optString("content").orEmpty()
                    if (token.isNotEmpty()) {
                        full.append(token)
                        onToken(token)
                    }
                    if (obj.optBoolean("done")) break
                }
            } else if (contentType.contains("text/event-stream")) {
                // OpenAI SSE: строки "data: {...}", финал — "data: [DONE]"
                val source = body.source()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    if (data.isEmpty()) continue
                    val obj = JSONObject(data)
                    if (obj.has("error")) {
                        throw IOException(obj.optJSONObject("error")?.optString("message") ?: "Ошибка сервера")
                    }
                    val token = obj.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("delta")
                        ?.optString("content")
                        .orEmpty()
                    if (token.isNotEmpty()) {
                        full.append(token)
                        onToken(token)
                    }
                }
            } else {
                // Непотоковый JSON-ответ
                val obj = JSONObject(body.string())
                val token = obj.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()
                if (token.isNotEmpty()) {
                    full.append(token)
                    onToken(token)
                }
            }
            return full.toString()
        }
    }
}