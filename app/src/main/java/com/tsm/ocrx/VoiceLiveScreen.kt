package com.tsm.ocrx

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.tsm.ocrx.ui.theme.ChipShape
import com.tsm.ocrx.ui.theme.PanelShape
import com.tsm.ocrx.voice.GeminiLiveClient
import com.tsm.ocrx.voice.VoiceSettings
import com.tsm.ocrx.voice.VoiceStatus
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val SYSTEM_INSTRUCTION =
    "You are OCR-X's realtime voice assistant. Reply in a natural, concise, " +
        "conversational way suitable for speech. You are great at translating between " +
        "languages: when the user asks to translate or speaks a phrase, translate it " +
        "clearly. If the user speaks Thai, you may answer in Thai."

@Composable
fun VoiceLiveScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { VoiceSettings(context) }
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var model by remember { mutableStateOf(settings.model) }
    var editing by remember { mutableStateOf(!settings.hasKey) }
    var client by remember { mutableStateOf<GeminiLiveClient?>(null) }

    var status by remember { mutableStateOf<VoiceStatus>(VoiceStatus.Idle) }
    var userText by remember { mutableStateOf("") }
    var modelText by remember { mutableStateOf("") }

    var audioGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    fun doConnect() {
        userText = ""; modelText = ""
        val c = GeminiLiveClient(settings.apiKey, settings.model, SYSTEM_INSTRUCTION)
        client = c
        c.connect()
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        audioGranted = granted
        if (granted) doConnect()
    }

    LaunchedEffect(client) {
        val c = client ?: return@LaunchedEffect
        coroutineScope {
            launch { c.status.collect { status = it } }
            launch { c.userTranscript.collect { userText = it } }
            launch { c.modelTranscript.collect { modelText = it } }
        }
    }
    DisposableEffect(Unit) { onDispose { client?.close() } }

    fun onMicTap() {
        val c = client
        if (c != null) {
            c.close(); client = null
        } else if (audioGranted) {
            doConnect()
        } else {
            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { client?.close(); onClose() }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text("VOICE · GEMINI LIVE", fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onBackground)
                Text("// realtime voice conversation", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
            }
            if (!editing) {
                IconButton(onClick = { client?.close(); client = null; editing = true }) {
                    Icon(Icons.Filled.VpnKey, contentDescription = "API key", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.primary)

        if (editing) {
            KeyEditor(
                apiKey = apiKey,
                model = model,
                canCancel = settings.hasKey,
                onApiKeyChange = { apiKey = it },
                onModelChange = { model = it },
                onSave = {
                    settings.apiKey = apiKey
                    settings.model = model.ifBlank { VoiceSettings.DEFAULT_MODEL }
                    model = settings.model
                    editing = false
                },
                onCancel = { editing = false }
            )
        } else {
            SessionContent(
                status = status,
                userText = userText,
                modelText = modelText,
                onMicTap = ::onMicTap
            )
        }
    }
}

@Composable
private fun KeyEditor(
    apiKey: String,
    model: String,
    canCancel: Boolean,
    onApiKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Enter your Google AI Studio (Gemini) API key. It is stored only on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            label = { Text("Gemini API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            shape = ChipShape
        )
        OutlinedTextField(
            value = model,
            onValueChange = onModelChange,
            label = { Text("Model") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = ChipShape
        )
        Text("Default model: ${VoiceSettings.DEFAULT_MODEL}. Get a key at aistudio.google.com.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onSave,
                enabled = apiKey.isNotBlank(),
                shape = ChipShape,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                modifier = Modifier.weight(1f).height(50.dp)
            ) { Text("SAVE", fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
            if (canCancel) {
                OutlinedButton(
                    onClick = onCancel,
                    shape = ChipShape,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.weight(1f).height(50.dp)
                ) { Text("CANCEL", color = MaterialTheme.colorScheme.onBackground) }
            }
        }
    }
}

@Composable
private fun SessionContent(
    status: VoiceStatus,
    userText: String,
    modelText: String,
    onMicTap: () -> Unit
) {
    val live = status is VoiceStatus.Live
    val connecting = status is VoiceStatus.Connecting

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        val statusText = when (status) {
            is VoiceStatus.Idle -> "TAP TO START"
            is VoiceStatus.Connecting -> "CONNECTING…"
            is VoiceStatus.Live -> "● LISTENING — speak now"
            is VoiceStatus.Error -> "ERROR"
        }
        Text(
            statusText,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            letterSpacing = 1.sp,
            color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Big mic button
        Box(
            Modifier
                .padding(8.dp)
                .size(120.dp)
                .background(
                    if (live || connecting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    CircleShape
                )
                .border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), CircleShape)
                .clickable { onMicTap() },
            contentAlignment = Alignment.Center
        ) {
            if (connecting) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Icon(
                    if (live) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = if (live) "Stop" else "Start",
                    tint = if (live) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(52.dp)
                )
            }
        }

        if (status is VoiceStatus.Error) {
            Text(status.message, color = MaterialTheme.colorScheme.error, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }

        TranscriptPanel("YOU", userText.ifBlank { "…" })
        TranscriptPanel("GEMINI", modelText.ifBlank { "…" }, accent = true)

        Text(
            "Speak naturally — Gemini listens and replies with voice in real time. " +
                "Ask it to translate, or just chat.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun TranscriptPanel(label: String, text: String, accent: Boolean = false) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, PanelShape)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), PanelShape)
            .padding(14.dp)
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(text, color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp)
    }
}
