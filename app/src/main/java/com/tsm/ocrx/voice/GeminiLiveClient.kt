package com.tsm.ocrx.voice

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

sealed interface VoiceStatus {
    data object Idle : VoiceStatus
    data object Connecting : VoiceStatus
    data object Live : VoiceStatus
    data class Error(val message: String) : VoiceStatus
}

/**
 * Realtime bidirectional voice with the Gemini Live API. Captures microphone
 * audio (16 kHz PCM), streams it over a WebSocket, and plays back Gemini's
 * 24 kHz PCM audio replies. Input/output transcripts are surfaced as text.
 */
class GeminiLiveClient(
    private val apiKey: String,
    private val model: String,
    private val systemInstruction: String
) {
    val status = MutableStateFlow<VoiceStatus>(VoiceStatus.Idle)
    val userTranscript = MutableStateFlow("")
    val modelTranscript = MutableStateFlow("")

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var running = false
    @Volatile private var micStarted = false

    private var recorder: AudioRecord? = null
    private var micThread: Thread? = null

    private var track: AudioTrack? = null
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private var playbackThread: Thread? = null

    private val inputSb = StringBuilder()
    private val outputSb = StringBuilder()

    fun connect() {
        if (running) return
        running = true
        status.value = VoiceStatus.Connecting
        val url = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, listener)
    }

    fun close() {
        running = false
        micStarted = false
        try { micThread?.interrupt() } catch (_: Throwable) {}
        try { recorder?.stop(); recorder?.release() } catch (_: Throwable) {}
        recorder = null
        audioQueue.clear()
        audioQueue.offer(ByteArray(0)) // wake the playback thread to exit
        try { track?.stop(); track?.release() } catch (_: Throwable) {}
        track = null
        try { webSocket?.close(1000, "bye") } catch (_: Throwable) {}
        webSocket = null
        status.value = VoiceStatus.Idle
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            ws.send(setupMessage())
        }

        override fun onMessage(ws: WebSocket, text: String) = handle(text)
        override fun onMessage(ws: WebSocket, bytes: ByteString) = handle(bytes.utf8())

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            status.value = VoiceStatus.Error(t.message ?: "Connection failed")
            running = false
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            if (running) status.value = VoiceStatus.Error("Closed: ${reason.ifBlank { code.toString() }}")
            running = false
        }
    }

    private fun setupMessage(): String {
        val setup = JSONObject()
            .put("model", "models/$model")
            .put("responseModalities", org.json.JSONArray().put("AUDIO"))
            .put("systemInstruction", JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", systemInstruction))))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
        return JSONObject().put("setup", setup).toString()
    }

    @SuppressLint("MissingPermission")
    private fun handle(message: String) {
        val json = try { JSONObject(message) } catch (t: Throwable) { return }

        if (json.has("setupComplete")) {
            status.value = VoiceStatus.Live
            startPlayback()
            startMic()
            return
        }

        val server = json.optJSONObject("serverContent") ?: return

        server.optJSONObject("inputTranscription")?.optString("text")?.let {
            if (it.isNotEmpty()) {
                inputSb.append(it)
                userTranscript.value = inputSb.toString().takeLast(2000)
            }
        }
        server.optJSONObject("outputTranscription")?.optString("text")?.let {
            if (it.isNotEmpty()) {
                outputSb.append(it)
                modelTranscript.value = outputSb.toString().takeLast(2000)
            }
        }

        server.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
                val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                val mime = inline.optString("mimeType")
                if (mime.startsWith("audio/")) {
                    val data = inline.optString("data")
                    if (data.isNotEmpty()) {
                        audioQueue.offer(Base64.decode(data, Base64.NO_WRAP))
                    }
                }
            }
        }

        if (server.optBoolean("turnComplete")) {
            inputSb.append('\n'); outputSb.append('\n')
        }
    }

    @SuppressLint("MissingPermission")
    private fun startMic() {
        if (micStarted) return
        micStarted = true
        val minBuf = AudioRecord.getMinBufferSize(
            16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, 3200)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize * 2
        )
        recorder = rec
        rec.startRecording()
        micThread = Thread {
            val buf = ByteArray(bufSize)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    val b64 = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)
                    val msg = JSONObject().put(
                        "realtimeInput",
                        JSONObject().put(
                            "audio",
                            JSONObject().put("data", b64).put("mimeType", "audio/pcm;rate=16000")
                        )
                    )
                    webSocket?.send(msg.toString())
                }
            }
        }.also { it.start() }
    }

    private fun startPlayback() {
        if (track != null) return
        val bufSize = AudioTrack.getMinBufferSize(
            24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(24000)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(24000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.play()
        playbackThread = Thread {
            while (running) {
                val chunk = try { audioQueue.take() } catch (t: InterruptedException) { break }
                if (chunk.isEmpty()) continue
                try { t.write(chunk, 0, chunk.size) } catch (_: Throwable) { break }
            }
        }.also { it.start() }
    }
}
