package com.homeprojector.launcher.voice

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

/**
 * Запись голоса для ассистента.
 * При подключённом USB-микрофоне (ReSpeaker XVF3800 и подобные USB Audio Class
 * устройства) запись идёт именно с него через setPreferredDevice.
 * Простой VAD по энергии сигнала: остановка после паузы тишины, когда речь уже была.
 */
class VoiceRecorder(private val context: Context) {

    data class Result(val wavFile: File, val deviceName: String, val durationMs: Long)

    fun usbInputDevice(): AudioDeviceInfo? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }
    }

    /** Best-effort запрос USB-разрешения для аудиоустройств (нужно на части прошивок). */
    private fun ensureUsbPermission() {
        val um = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val audioDevices = um.deviceList.values.filter { dev ->
            (0 until dev.interfaceCount).any { dev.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }
        }
        val needPermission = audioDevices.filter { !um.hasPermission(it) }
        if (needPermission.isEmpty()) return

        val action = "com.homeprojector.launcher.USB_PERMISSION"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {}
        }
        runCatching {
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(action), ContextCompat.RECEIVER_EXPORTED
            )
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName), flags)
            needPermission.forEach { um.requestPermission(it, pi) }
            // Отписка произойдёт при следующем вызове; держим ссылку, чтобы не текло
            pendingReceiver = receiver
        }
    }

    private var pendingReceiver: BroadcastReceiver? = null

    private fun unregisterPending() {
        pendingReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        pendingReceiver = null
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO запрашивается в UI до вызова
    suspend fun record(
        maxMs: Long = 30_000,
        silenceMs: Long = 1500,
        onLevel: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): Result = withContext(Dispatchers.IO) {
        ensureUsbPermission()

        val usbDevice = usbInputDevice()
        val deviceName = usbDevice?.productName?.toString()?.takeIf { it.isNotBlank() }
            ?: if (usbDevice != null) "USB-микрофон" else "встроенный микрофон"

        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) throw IllegalStateException("Аудиозапись недоступна на этом устройстве")

        val builder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 4)
        val record = builder.build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Не удалось открыть микрофон")
        }
        if (usbDevice != null) {
            record.setPreferredDevice(usbDevice)
        }

        val pcm = ByteArrayOutputStream()
        val frame = ShortArray(1024) // ~64 мс при 16 кГц
        val speechThreshold = 500f
        var speechFrames = 0
        var speechStarted = false
        var silenceMsAcc = 0L
        val startTs = System.currentTimeMillis()

        try {
            record.startRecording()
            while (!isCancelled()) {
                val n = record.read(frame, 0, frame.size)
                if (n <= 0) continue
                for (i in 0 until n) {
                    val v = frame[i].toInt()
                    pcm.write(v and 0xFF)
                    pcm.write((v shr 8) and 0xFF)
                }

                var sum = 0.0
                for (i in 0 until n) sum += frame[i].toDouble() * frame[i]
                val rms = sqrt(sum / n).toFloat()
                onLevel((rms / 4000f).coerceIn(0f, 1f))

                val frameMs = n * 1000L / sampleRate
                if (rms > speechThreshold) {
                    speechFrames++
                    silenceMsAcc = 0
                    if (speechFrames >= 3) speechStarted = true
                } else if (speechStarted) {
                    silenceMsAcc += frameMs
                    if (silenceMsAcc >= silenceMs) break
                }
                if (System.currentTimeMillis() - startTs >= maxMs) break
            }
        } finally {
            runCatching { record.stop() }
            record.release()
            unregisterPending()
        }

        val durationMs = System.currentTimeMillis() - startTs
        val wav = File(context.cacheDir, "voice_input.wav")
        FileOutputStream(wav).use { out ->
            val data = pcm.toByteArray()
            out.write(wavHeader(data.size, sampleRate))
            out.write(data)
        }
        Result(wav, deviceName, durationMs)
    }

    private fun wavHeader(dataSize: Int, sampleRate: Int): ByteArray {
        val byteRate = sampleRate * 2
        val header = ByteArray(44)
        fun put(offset: Int, value: Int, len: Int) {
            for (i in 0 until len) header[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
        }
        fun putAscii(offset: Int, s: String) {
            s.forEachIndexed { i, c -> header[offset + i] = c.code.toByte() }
        }
        putAscii(0, "RIFF"); put(4, 36 + dataSize, 4); putAscii(8, "WAVE")
        putAscii(12, "fmt "); put(16, 16, 4); put(20, 1, 2); put(22, 1, 2)
        put(24, sampleRate, 4); put(28, byteRate, 4); put(32, 2, 2); put(34, 16, 2)
        putAscii(36, "data"); put(40, dataSize, 4)
        return header
    }
}