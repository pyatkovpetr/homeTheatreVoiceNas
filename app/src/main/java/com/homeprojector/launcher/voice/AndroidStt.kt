package com.homeprojector.launcher.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Запасной вариант: встроенный распознаватель речи Android (нужны сервисы Google). */
suspend fun androidRecognize(context: Context): String = suspendCancellableCoroutine { cont ->
    val main = Handler(Looper.getMainLooper())
    val resumed = AtomicBoolean(false)
    main.post {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            if (resumed.compareAndSet(false, true)) {
                cont.resumeWithException(
                    IOException("Встроенный распознаватель Android недоступен. Используйте сервер Whisper в настройках.")
                )
            }
            return@post
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (resumed.compareAndSet(false, true)) {
                    recognizer.destroy()
                    cont.resume(text)
                }
            }

            override fun onError(error: Int) {
                if (resumed.compareAndSet(false, true)) {
                    recognizer.destroy()
                    cont.resumeWithException(IOException("Распознаватель Android: ошибка $error"))
                }
            }
        })
        recognizer.startListening(intent)
        cont.invokeOnCancellation {
            main.post { runCatching { recognizer.destroy() } }
        }
    }
}