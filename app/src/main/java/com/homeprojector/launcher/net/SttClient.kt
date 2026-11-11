package com.homeprojector.launcher.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Распознавание речи через Whisper-совместимый сервер (OpenAI /v1/audio/transcriptions). */
object SttClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun transcribe(url: String, wav: File, apiKey: String = ""): String = withContext(Dispatchers.IO) {
        if (url.isBlank()) {
            throw IOException("Не задан адрес сервера распознавания речи (Настройки → Распознавание речи)")
        }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.wav", wav.asRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("language", "ru")
            .addFormDataPart("response_format", "json")
            .build()
        val requestBuilder = Request.Builder().url(url).post(body)
        if (apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }
        client.newCall(requestBuilder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("Сервер распознавания вернул HTTP ${resp.code}: ${text.take(200)}")
            }
            runCatching { JSONObject(text).optString("text") }.getOrDefault("").trim()
        }
    }
}