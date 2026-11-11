package com.homeprojector.launcher.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.homeprojector.launcher.Prefs
import com.homeprojector.launcher.net.ChatClient
import com.homeprojector.launcher.net.SttClient
import com.homeprojector.launcher.voice.Speaker
import com.homeprojector.launcher.voice.VoiceRecorder
import com.homeprojector.launcher.voice.androidRecognize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private enum class VoicePhase { IDLE, LISTENING, TRANSCRIBING, THINKING, SPEAKING, ERROR }

@Composable
fun VoiceScreen(prefs: Prefs, onBack: () -> Unit, onChat: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { VoiceRecorder(context) }
    val speaker = remember { Speaker(context) }
    val callRef = remember { AtomicReference<Call?>(null) }
    val stopRecording = remember { AtomicBoolean(false) }

    var phase by remember { mutableStateOf(VoicePhase.IDLE) }
    var micLevel by remember { mutableStateOf(0f) }
    var transcript by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    val micName = remember {
        recorder.usbInputDevice()?.productName?.toString()?.takeIf { it.isNotBlank() }
            ?: if (recorder.usbInputDevice() != null) "USB-микрофон" else null
    }

    DisposableEffect(Unit) {
        onDispose {
            job?.cancel()
            speaker.shutdown()
        }
    }

    fun startVoice() {
        if (job?.isActive == true) return
        stopRecording.set(false)
        transcript = ""
        reply = ""
        error = null
        speaker.stop()
        job = scope.launch {
            try {
                phase = VoicePhase.LISTENING
                val result = withContext(Dispatchers.IO) {
                    recorder.record(
                        onLevel = { l -> scope.launch(Dispatchers.Main) { micLevel = l } },
                        isCancelled = { stopRecording.get() || !isActive }
                    )
                }
                micLevel = 0f
                phase = VoicePhase.TRANSCRIBING
                val text = if (prefs.sttMode == "android") {
                    androidRecognize(context)
                } else {
                    SttClient.transcribe(prefs.sttUrl, result.wavFile, prefs.sttApiKey)
                }
                transcript = text
                if (text.isBlank()) {
                    phase = VoicePhase.ERROR
                    error = "Речь не распознана. Попробуйте ещё раз."
                    return@launch
                }
                phase = VoicePhase.THINKING
                val full = withContext(Dispatchers.IO) {
                    ChatClient.streamChat(
                        baseUrl = prefs.chatBaseUrl,
                        api = prefs.chatApi,
                        model = prefs.chatModel,
                        systemPrompt = prefs.chatSystemPrompt,
                        history = listOf(ChatClient.Msg("user", text)),
                        apiKey = prefs.chatApiKey,
                        registerCall = { callRef.set(it) },
                        onToken = { t -> scope.launch(Dispatchers.Main) { reply += t } }
                    )
                }
                if (prefs.ttsEnabled && full.isNotBlank()) {
                    phase = VoicePhase.SPEAKING
                    speaker.speak(full)
                }
                phase = VoicePhase.IDLE
            } catch (e: CancellationException) {
                phase = VoicePhase.IDLE
                throw e
            } catch (e: Exception) {
                phase = VoicePhase.ERROR
                error = e.message ?: e.javaClass.simpleName
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startVoice() }

    fun onMicClick() {
        when (phase) {
            VoicePhase.LISTENING -> stopRecording.set(true)
            VoicePhase.TRANSCRIBING, VoicePhase.THINKING -> {
                callRef.get()?.cancel()
                job?.cancel()
                phase = VoicePhase.IDLE
            }
            else -> {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    startVoice()
                }
            }
        }
    }

    ScreenFrame(
        title = "Голосовой ассистент",
        onBack = onBack,
        actions = {
            IconButton(onClick = onChat) {
                Icon(
                    Icons.Filled.Chat,
                    contentDescription = "Открыть чат",
                    tint = Primary
                )
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (micName != null) "Микрофон: $micName"
                else "USB-микрофон не найден — используется встроенный. Подключите ReSpeaker XVF3800 по USB.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (micName != null) SuccessColor else Secondary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val listening = phase == VoicePhase.LISTENING
                    val scale = 1f + micLevel * 0.3f
                    Box(
                        modifier = Modifier
                            .size(200.dp)
                            .graphicsLayer { scaleX = scale; scaleY = scale }
                            .clip(CircleShape)
                            .border(4.dp, if (listening) ErrorColor else Primary, CircleShape)
                            .background(if (listening) SurfaceVariant else Surface)
                            .focusable()
                            .clickable { onMicClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
                            contentDescription = "Микрофон",
                            tint = if (listening) ErrorColor else Primary,
                            modifier = Modifier.size(80.dp)
                        )
                    }
                    Text(
                        text = when (phase) {
                            VoicePhase.IDLE -> "Нажмите и говорите"
                            VoicePhase.LISTENING -> "Слушаю… нажмите ещё раз, чтобы отправить"
                            VoicePhase.TRANSCRIBING -> "Распознаю речь…"
                            VoicePhase.THINKING -> "Думаю…"
                            VoicePhase.SPEAKING -> "Отвечаю"
                            VoicePhase.ERROR -> error ?: "Ошибка"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (phase == VoicePhase.ERROR) ErrorColor else OnSurfaceDim,
                        modifier = Modifier.padding(top = 20.dp)
                    )
                }
            }

            if (transcript.isNotBlank()) {
                ReplyCard(label = "Вы", text = transcript, accent = Secondary)
            }
            if (reply.isNotBlank()) {
                ReplyCard(label = "Ассистент", text = reply, accent = Primary)
            }
        }
    }
}

@Composable
private fun ReplyCard(label: String, text: String, accent: androidx.compose.ui.graphics.Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .padding(16.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = accent)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = OnSurface,
            modifier = Modifier
                .padding(top = 4.dp)
                .height(96.dp)
                .verticalScroll(rememberScrollState())
        )
    }
}