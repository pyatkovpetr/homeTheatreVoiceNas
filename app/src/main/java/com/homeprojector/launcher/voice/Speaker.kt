package com.homeprojector.launcher.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Озвучка ответов ассистента встроенным TTS Android. */
class Speaker(context: Context) {

    private var tts: TextToSpeech? = null

    @Volatile
    var ready: Boolean = false
        private set

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val t = tts ?: return@TextToSpeech
                val res = t.setLanguage(Locale("ru", "RU"))
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    t.setLanguage(Locale.getDefault())
                }
                ready = true
            }
        }
    }

    fun speak(text: String) {
        if (ready && text.isNotBlank()) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant_reply")
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}