package com.homeprojector.launcher

import android.content.Context
import android.content.SharedPreferences

/** Настройки лаунчера (NAS, чат-бот, распознавание речи, озвучка). */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)

    // --- NAS (SMB) ---
    var nasHost: String
        get() = sp.getString("nas_host", "192.168.1.100") ?: ""
        set(v) = sp.edit().putString("nas_host", v.trim()).apply()

    var nasShare: String
        get() = sp.getString("nas_share", "video") ?: ""
        set(v) = sp.edit().putString("nas_share", v.trim().trim('/')).apply()

    var nasPath: String
        get() = sp.getString("nas_path", "") ?: ""
        set(v) = sp.edit().putString("nas_path", v.trim().trim('/')).apply()

    var nasUser: String
        get() = sp.getString("nas_user", "guest") ?: ""
        set(v) = sp.edit().putString("nas_user", v.trim()).apply()

    var nasPassword: String
        get() = sp.getString("nas_password", "") ?: ""
        set(v) = sp.edit().putString("nas_password", v).apply()

    var nasDomain: String
        get() = sp.getString("nas_domain", "") ?: ""
        set(v) = sp.edit().putString("nas_domain", v.trim()).apply()

    // --- Чат-бот ---
    var chatBaseUrl: String
        get() = sp.getString("chat_base_url", "https://api.neuraldeep.ru/v1") ?: ""
        set(v) = sp.edit().putString("chat_base_url", v.trim().trimEnd('/')).apply()

    /** "ollama" или "openai" (OpenAI-совместимый сервер). */
    var chatApi: String
        get() = sp.getString("chat_api", "openai") ?: "openai"
        set(v) = sp.edit().putString("chat_api", v).apply()

    var chatModel: String
        get() = sp.getString("chat_model", "qwen3.8-flash-next-ru-noreason") ?: ""
        set(v) = sp.edit().putString("chat_model", v.trim()).apply()

    var chatApiKey: String
        get() = sp.getString("chat_api_key", BuildConfig.NEURALDEEP_API_KEY) ?: ""
        set(v) = sp.edit().putString("chat_api_key", v.trim()).apply()

    var chatSystemPrompt: String
        get() = sp.getString(
            "chat_system_prompt",
            "Ты — голосовой ассистент домашнего проектора. Отвечай кратко, по делу, на русском языке."
        ) ?: ""
        set(v) = sp.edit().putString("chat_system_prompt", v).apply()

    // --- Распознавание речи ---
    /** "server" — Whisper-совместимый сервер, "android" — встроенный распознаватель. */
    var sttMode: String
        get() = sp.getString("stt_mode", "server") ?: "server"
        set(v) = sp.edit().putString("stt_mode", v).apply()

    var sttUrl: String
        get() = sp.getString("stt_url", "https://api.neuraldeep.ru/v1/audio/transcriptions") ?: ""
        set(v) = sp.edit().putString("stt_url", v.trim()).apply()

    var sttApiKey: String
        get() = sp.getString("stt_api_key", BuildConfig.NEURALDEEP_API_KEY) ?: ""
        set(v) = sp.edit().putString("stt_api_key", v.trim()).apply()

    // --- Разделы медиатеки (папки внутри общего ресурса NAS) ---
    var musicPath: String
        get() = sp.getString("music_path", "music") ?: ""
        set(v) = sp.edit().putString("music_path", v.trim().trim('/')).apply()

    var photosPath: String
        get() = sp.getString("photos_path", "photo") ?: ""
        set(v) = sp.edit().putString("photos_path", v.trim().trim('/')).apply()

    var booksPath: String
        get() = sp.getString("books_path", "books") ?: ""
        set(v) = sp.edit().putString("books_path", v.trim().trim('/')).apply()

    var schoolPath: String
        get() = sp.getString("school_path", "school") ?: ""
        set(v) = sp.edit().putString("school_path", v.trim().trim('/')).apply()

    // --- Избранное ---
    var favoriteTracks: Set<String>
        get() = sp.getStringSet("favorite_tracks", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("favorite_tracks", v).apply()

    // --- Озвучка ответов ---
    var ttsEnabled: Boolean
        get() = sp.getBoolean("tts_enabled", true)
        set(v) = sp.edit().putBoolean("tts_enabled", v).apply()

    companion object {
        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context.applicationContext).also { instance = it }
            }
    }
}