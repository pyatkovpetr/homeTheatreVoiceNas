package com.homeprojector.launcher.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

@Composable
fun ChatScreen(prefs: Prefs, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { VoiceRecorder(context) }
    val speaker = remember { Speaker(context) }
    val callRef = remember { AtomicReference<Call?>(null) }
    val stopRecording = remember { AtomicBoolean(false) }
    val listState = rememberLazyListState()

    var messages by remember { mutableStateOf(listOf<ChatClient.Msg>()) }
    var input by remember { mutableStateOf("") }
    var streaming by remember { mutableStateOf(false) }
    var voiceBusy by remember { mutableStateOf(false) }
    var voiceStatus by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var voiceJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            job?.cancel()
            voiceJob?.cancel()
            speaker.shutdown()
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || streaming) return
        val history = messages.filter { it.role != "error" } + ChatClient.Msg("user", trimmed)
        messages = history + ChatClient.Msg("assistant", "")
        input = ""
        streaming = true
        job = scope.launch {
            try {
                val full = withContext(Dispatchers.IO) {
                    ChatClient.streamChat(
                        baseUrl = prefs.chatBaseUrl,
                        api = prefs.chatApi,
                        model = prefs.chatModel,
                        systemPrompt = prefs.chatSystemPrompt,
                        history = history,
                        apiKey = prefs.chatApiKey,
                        registerCall = { callRef.set(it) },
                        onToken = { t ->
                            scope.launch(Dispatchers.Main) {
                                val last = messages.lastOrNull()
                                if (last != null && last.role == "assistant") {
                                    messages = messages.dropLast(1) +
                                        ChatClient.Msg("assistant", last.content + t)
                                }
                            }
                        }
                    )
                }
                if (prefs.ttsEnabled && full.isNotBlank()) speaker.speak(full)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages = messages + ChatClient.Msg("error", e.message ?: e.javaClass.simpleName)
            } finally {
                streaming = false
            }
        }
    }

    fun startDictation() {
        if (voiceBusy || streaming) return
        stopRecording.set(false)
        voiceBusy = true
        voiceStatus = "Слушаю…"
        voiceJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    recorder.record(isCancelled = { stopRecording.get() || !isActive })
                }
                voiceStatus = "Распознаю…"
                val text = if (prefs.sttMode == "android") {
                    androidRecognize(context)
                } else {
                    SttClient.transcribe(prefs.sttUrl, result.wavFile, prefs.sttApiKey)
                }
                voiceBusy = false
                voiceStatus = ""
                if (text.isNotBlank()) send(text) else voiceStatus = "Не расслышал, попробуйте ещё раз"
            } catch (e: CancellationException) {
                voiceBusy = false
                throw e
            } catch (e: Exception) {
                voiceBusy = false
                voiceStatus = e.message ?: "Ошибка распознавания"
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startDictation() }

    fun onMicClick() {
        if (voiceBusy) {
            stopRecording.set(true)
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startDictation()
        }
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    ScreenFrame(
        title = "Чат-бот",
        onBack = onBack,
        actions = {
            Text(
                text = "${prefs.chatModel} · ${prefs.chatBaseUrl}",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceDim
            )
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "Задайте вопрос текстом или голосом.\nОтветы озвучиваются, если включена озвучка в настройках.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = OnSurfaceDim
                            )
                        }
                    }
                }
                items(messages) { msg -> MessageBubble(msg) }
            }

            if (voiceStatus.isNotEmpty()) {
                Text(
                    voiceStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Secondary,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Сообщение…") },
                    maxLines = 3,
                    enabled = !streaming
                )
                IconButton(onClick = { onMicClick() }, modifier = Modifier.size(52.dp)) {
                    Icon(
                        imageVector = if (voiceBusy) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = "Голосовой ввод",
                        tint = if (voiceBusy) ErrorColor else Primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
                if (streaming) {
                    TextButton(onClick = {
                        callRef.get()?.cancel()
                        job?.cancel()
                        streaming = false
                    }) {
                        Text("Стоп", color = ErrorColor)
                    }
                } else {
                    IconButton(
                        onClick = { send(input) },
                        modifier = Modifier.size(52.dp),
                        enabled = input.isNotBlank()
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Отправить",
                            tint = Primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatClient.Msg) {
    val isUser = msg.role == "user"
    val isError = msg.role == "error"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    when {
                        isError -> SurfaceVariant
                        isUser -> SurfaceVariant
                        else -> Surface
                    }
                )
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                text = when {
                    isError -> "Ошибка"
                    isUser -> "Вы"
                    else -> "Ассистент"
                },
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    isError -> ErrorColor
                    isUser -> Secondary
                    else -> Primary
                }
            )
            Text(
                text = msg.content.ifEmpty { "…" },
                style = MaterialTheme.typography.bodyLarge,
                color = if (isError) ErrorColor else OnSurface,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}