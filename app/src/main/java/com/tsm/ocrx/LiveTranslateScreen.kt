package com.tsm.ocrx

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tsm.ocrx.translate.Language
import com.tsm.ocrx.translate.LiveTranslator
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

/**
 * Live camera translation. Continuously recognizes text (ML Kit) from the camera
 * preview and translates it on-device in near real time, showing the latest
 * source text and its translation in a panel over the preview.
 */
@Composable
fun LiveTranslateScreen(target: Language, onClose: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            LiveContent(target = target)
        } else {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Camera permission needed", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("GRANT")
                }
            }
        }

        // Top bar: close + live badge.
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .background(Color(0xFFFF7A18), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text("● LIVE → ${target.name.uppercase()}", color = Color(0xFF0F1114), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = onClose,
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0x66000000))
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

@Composable
private fun LiveContent(target: Language) {
    var recognized by remember { mutableStateOf("") }
    var translated by remember { mutableStateOf("") }
    var paused by remember { mutableStateOf(false) }
    val translator = remember(target.code) { LiveTranslator(target.code) }

    DisposableEffect(target.code) {
        onDispose { translator.close() }
    }

    LaunchedEffect(recognized, target.code) {
        if (recognized.isBlank()) return@LaunchedEffect
        delay(250) // settle before translating
        translated = translator.translate(recognized)
    }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clickable { paused = !paused }
        ) {
            CameraPreview(
                onText = { recognized = it },
                paused = paused,
                modifier = Modifier.fillMaxSize()
            )
            // Freeze/pause indicator.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .background(Color(0xCC0F1114), RoundedCornerShape(4.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    if (paused) "❚❚  PAUSED — tap to resume" else "▶  LIVE — tap to freeze",
                    color = if (paused) Color(0xFFFF7A18) else Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        LivePanel(recognized = recognized, translated = translated, paused = paused)
    }
}

@Composable
private fun LivePanel(recognized: String, translated: String, paused: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp)
            .background(Color(0xFF0F1114))
            .navigationBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            if (paused) "DETECTED · FROZEN" else "DETECTED",
            fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.sp,
            color = if (paused) Color(0xFFFF7A18) else Color(0xFF8A96A3)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            recognized.ifBlank { "Point the camera at text…" },
            color = Color(0xFFB9C0C8),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Translate, contentDescription = null, tint = Color(0xFFFF7A18), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("TRANSLATION", fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.sp, color = Color(0xFFFF7A18))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            translated.ifBlank { "…" },
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp
        )
    }
}

/** Only emit text once it has been read identically twice in a row (anti-flicker). */
private class Stabilizer {
    var pending = ""
    var count = 0
    var emitted = ""
}

private const val THROTTLE_MS = 1000L

@Composable
private fun CameraPreview(onText: (String) -> Unit, paused: Boolean, modifier: Modifier) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val lastRun = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    val stabilizer = remember { Stabilizer() }
    val pausedState = rememberUpdatedState(paused)
    val onTextState = rememberUpdatedState(onText)

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            recognizer.close()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy ->
                    val now = System.currentTimeMillis()
                    if (pausedState.value || now - lastRun.get() < THROTTLE_MS) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    lastRun.set(now)
                    processFrame(proxy, recognizer) { text ->
                        // Require the same reading twice before showing it, so
                        // frame-to-frame OCR jitter doesn't make the panel flicker.
                        if (text == stabilizer.pending) {
                            stabilizer.count++
                        } else {
                            stabilizer.pending = text
                            stabilizer.count = 1
                        }
                        if (stabilizer.count >= 2 && text != stabilizer.emitted) {
                            stabilizer.emitted = text
                            onTextState.value(text)
                        }
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }
    )
}

@OptIn(ExperimentalGetImage::class)
private fun processFrame(
    proxy: androidx.camera.core.ImageProxy,
    recognizer: com.google.mlkit.vision.text.TextRecognizer,
    onText: (String) -> Unit
) {
    val media = proxy.image
    if (media == null) {
        proxy.close()
        return
    }
    val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
    recognizer.process(input)
        .addOnSuccessListener { onText(it.text.trim()) }
        .addOnCompleteListener { proxy.close() }
}
