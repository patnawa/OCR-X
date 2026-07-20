package com.tsm.ocrx

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tsm.ocrx.translate.Language
import com.tsm.ocrx.translate.TranslationEngine
import com.tsm.ocrx.translate.TranslationModels
import com.tsm.ocrx.ui.theme.ChipShape
import com.tsm.ocrx.ui.theme.PanelShape
import kotlinx.coroutines.launch

/**
 * Lets the user pre-download / remove on-device translation language models,
 * so offline (and live) translation works without a network round-trip later.
 */
@Composable
fun LanguageManagerScreen(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var downloaded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf<Set<String>>(emptySet()) }
    var wifiOnly by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        downloaded = try { TranslationModels.downloaded() } catch (t: Throwable) { emptySet() }
    }

    LaunchedEffect(Unit) { refresh() }

    fun download(lang: Language) {
        scope.launch {
            busy = busy + lang.code
            message = null
            try {
                TranslationModels.download(lang.code, wifiOnly)
                refresh()
                message = "${lang.name} downloaded"
            } catch (t: Throwable) {
                message = "Couldn't download ${lang.name}" +
                    if (wifiOnly) " (waiting for Wi-Fi?)" else " (check connection)"
            } finally {
                busy = busy - lang.code
            }
        }
    }

    fun delete(lang: Language) {
        scope.launch {
            busy = busy + lang.code
            try {
                TranslationModels.delete(lang.code)
                refresh()
                message = "${lang.name} removed"
            } catch (t: Throwable) {
                message = "Couldn't remove ${lang.name}"
            } finally {
                busy = busy - lang.code
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        // Header
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
            }
            Spacer(Modifier.width(4.dp))
            Column {
                Text("OFFLINE LANGUAGES", fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onBackground)
                Text("// download once, translate offline", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.primary)

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Wi-Fi only toggle
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, PanelShape)
                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), PanelShape)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Wi-Fi only", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                    Text("Avoid using mobile data for downloads", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = wifiOnly,
                    onCheckedChange = { wifiOnly = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            message?.let {
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }

            TranslationEngine.LANGUAGES.forEach { lang ->
                LanguageRow(
                    lang = lang,
                    installed = lang.code in downloaded,
                    busy = lang.code in busy,
                    onDownload = { download(lang) },
                    onDelete = { delete(lang) }
                )
            }

            Text(
                "Each model is ~30 MB. Downloading needs internet once; after that " +
                    "the language works fully offline, including Live translate.",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun LanguageRow(
    lang: Language,
    installed: Boolean,
    busy: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, PanelShape)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), PanelShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(lang.name, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text(
                if (installed) "INSTALLED · ${lang.code}" else "NOT INSTALLED · ${lang.code}",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                color = if (installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        when {
            busy -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            installed -> IconAction(Icons.Filled.Delete, "Remove ${lang.name}", MaterialTheme.colorScheme.error, onDelete)
            else -> IconAction(Icons.Filled.Download, "Download ${lang.name}", MaterialTheme.colorScheme.primary, onDownload)
        }
    }
}

@Composable
private fun IconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), ChipShape)
            .clickable { onClick() }
            .padding(8.dp)
    ) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(22.dp))
    }
}
