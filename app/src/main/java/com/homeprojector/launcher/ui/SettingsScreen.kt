package com.homeprojector.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.homeprojector.launcher.Prefs
import com.homeprojector.launcher.nas.NasClient
import com.homeprojector.launcher.net.ChatClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(prefs: Prefs, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()

    var nasHost by remember { mutableStateOf(prefs.nasHost) }
    var nasShare by remember { mutableStateOf(prefs.nasShare) }
    var nasPath by remember { mutableStateOf(prefs.nasPath) }
    var nasUser by remember { mutableStateOf(prefs.nasUser) }
    var nasPassword by remember { mutableStateOf(prefs.nasPassword) }
    var nasDomain by remember { mutableStateOf(prefs.nasDomain) }
    var nasTest by remember { mutableStateOf<String?>(null) }

    var chatUrl by remember { mutableStateOf(prefs.chatBaseUrl) }
    var chatApi by remember { mutableStateOf(prefs.chatApi) }
    var chatModel by remember { mutableStateOf(prefs.chatModel) }
    var chatApiKey by remember { mutableStateOf(prefs.chatApiKey) }
    var systemPrompt by remember { mutableStateOf(prefs.chatSystemPrompt) }
    var chatTest by remember { mutableStateOf<String?>(null) }

    var sttMode by remember { mutableStateOf(prefs.sttMode) }
    var sttUrl by remember { mutableStateOf(prefs.sttUrl) }
    var sttApiKey by remember { mutableStateOf(prefs.sttApiKey) }
    var ttsEnabled by remember { mutableStateOf(prefs.ttsEnabled) }

    var musicPath by remember { mutableStateOf(prefs.musicPath) }
    var photosPath by remember { mutableStateOf(prefs.photosPath) }
    var booksPath by remember { mutableStateOf(prefs.booksPath) }
    var schoolPath by remember { mutableStateOf(prefs.schoolPath) }

    ScreenFrame(title = "Настройки", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionTitle("Видеотека NAS (SMB)")
            Field("Адрес NAS", nasHost, { nasHost = it; prefs.nasHost = it }, "192.168.1.100")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("Общая папка (share)", nasShare, { nasShare = it; prefs.nasShare = it }, "video", Modifier.weight(1f))
                Field("Папка внутри share", nasPath, { nasPath = it; prefs.nasPath = it }, "например: Films", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("Пользователь", nasUser, { nasUser = it; prefs.nasUser = it }, "guest — для гостевого доступа", Modifier.weight(1f))
                Field("Пароль", nasPassword, { nasPassword = it; prefs.nasPassword = it }, "", Modifier.weight(1f), password = true)
            }
            Field("Домен (обычно не нужен)", nasDomain, { nasDomain = it; prefs.nasDomain = it }, "WORKGROUP")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = {
                    nasTest = "Проверяю…"
                    scope.launch {
                        nasTest = try {
                            val n = withContext(Dispatchers.IO) { NasClient(prefs).testConnection() }
                            "Подключение успешно, элементов в корне: $n"
                        } catch (e: Exception) {
                            "Ошибка: ${e.message}"
                        }
                    }
                }) { Text("Проверить подключение") }
                nasTest?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (it.startsWith("Ошибка")) ErrorColor else SuccessColor
                    )
                }
            }

            SectionTitle("Чат-бот (сервер ИИ)")
            Field("Адрес сервера", chatUrl, { chatUrl = it; prefs.chatBaseUrl = it }, "https://api.neuraldeep.ru/v1 или http://192.168.1.124:11434")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Тип API:", style = MaterialTheme.typography.bodyLarge, color = OnSurface)
                FilterChip(
                    selected = chatApi == "ollama",
                    onClick = { chatApi = "ollama"; prefs.chatApi = "ollama" },
                    label = { Text("Ollama") }
                )
                FilterChip(
                    selected = chatApi == "openai",
                    onClick = { chatApi = "openai"; prefs.chatApi = "openai" },
                    label = { Text("OpenAI-совместимый") }
                )
            }
            Field("Модель", chatModel, { chatModel = it; prefs.chatModel = it }, "qwen3.8-flash-next-ru-noreason, gpt-oss-20b, …")
            Field("API-ключ", chatApiKey, { chatApiKey = it; prefs.chatApiKey = it }, "sk-… (для NeuralDeep; для локальной Ollama не нужен)", password = true)
            Field("Системный промпт", systemPrompt, { systemPrompt = it; prefs.chatSystemPrompt = it }, "", singleLine = false)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = {
                    chatTest = "Проверяю…"
                    scope.launch {
                        chatTest = try {
                            val answer = withContext(Dispatchers.IO) {
                                ChatClient.streamChat(
                                    baseUrl = prefs.chatBaseUrl,
                                    api = prefs.chatApi,
                                    model = prefs.chatModel,
                                    systemPrompt = "",
                                    history = listOf(ChatClient.Msg("user", "Ответь одним словом: pong")),
                                    apiKey = prefs.chatApiKey
                                )
                            }
                            "Ответ сервера: ${answer.trim().take(120).ifEmpty { "(пусто)" }}"
                        } catch (e: Exception) {
                            "Ошибка: ${e.message}"
                        }
                    }
                }) { Text("Проверить чат-бот") }
                chatTest?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (it.startsWith("Ошибка")) ErrorColor else SuccessColor
                    )
                }
            }

            SectionTitle("Распознавание речи")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Режим:", style = MaterialTheme.typography.bodyLarge, color = OnSurface)
                FilterChip(
                    selected = sttMode == "server",
                    onClick = { sttMode = "server"; prefs.sttMode = "server" },
                    label = { Text("Сервер Whisper") }
                )
                FilterChip(
                    selected = sttMode == "android",
                    onClick = { sttMode = "android"; prefs.sttMode = "android" },
                    label = { Text("Встроенный Android") }
                )
            }
            if (sttMode == "server") {
                Field(
                    "Адрес Whisper-сервера",
                    sttUrl,
                    { sttUrl = it; prefs.sttUrl = it },
                    "https://api.neuraldeep.ru/v1/audio/transcriptions"
                )
                Field(
                    "API-ключ распознавания",
                    sttApiKey,
                    { sttApiKey = it; prefs.sttApiKey = it },
                    "sk-… (для NeuralDeep; для локального сервера не нужен)",
                    password = true
                )
                Text(
                    "Подойдёт любой OpenAI-совместимый сервер распознавания: NeuralDeep (whisper-1), " +
                        "faster-whisper-server, whisper.cpp server, LocalAI и т.п. Аудио отправляется как WAV 16 кГц.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceDim
                )
            } else {
                Text(
                    "Используется системный распознаватель Android (требуются сервисы Google на устройстве).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceDim
                )
            }

            SectionTitle("Медиатека (папки внутри общего ресурса NAS)")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("Музыка", musicPath, { musicPath = it; prefs.musicPath = it }, "music", Modifier.weight(1f))
                Field("Фото", photosPath, { photosPath = it; prefs.photosPath = it }, "photo", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("Книги", booksPath, { booksPath = it; prefs.booksPath = it }, "books", Modifier.weight(1f))
                Field("Учёба (школьные видео)", schoolPath, { schoolPath = it; prefs.schoolPath = it }, "school", Modifier.weight(1f))
            }
            Text(
                "Раздел «Учёба» показывает подпапки как предметы (Математика, Физика, …). " +
                    "Видео воспроизводятся сразу, учебники (PDF/EPUB/FB2) открываются во встроенной читалке.",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceDim
            )

            SectionTitle("Озвучка ответов")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = ttsEnabled, onCheckedChange = { ttsEnabled = it; prefs.ttsEnabled = it })
                Text("Озвучивать ответы ассистента (TTS Android)", style = MaterialTheme.typography.bodyLarge, color = OnSurface)
            }
            Text(
                "Голос записывается с USB-микрофона ReSpeaker XVF3800, если он подключён; " +
                    "иначе используется встроенный микрофон устройства.",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceDim,
                modifier = Modifier.padding(bottom = 24.dp)
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = Primary,
        modifier = Modifier.padding(top = 16.dp)
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    password: Boolean = false,
    singleLine: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        modifier = modifier,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None
    )
}