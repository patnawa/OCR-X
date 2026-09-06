package com.tsm.ocrx

import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tsm.ocrx.export.CorpusCase
import com.tsm.ocrx.ocr.ExtractedFields
import com.tsm.ocrx.ocr.OcrSettings
import com.tsm.ocrx.ocr.RecModel
import com.tsm.ocrx.ui.theme.ChipShape
import com.tsm.ocrx.ui.theme.SafetyAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/* ---------------------------------------------------------------------------
 * Row inspector — the source pixels behind a recognized row
 * ------------------------------------------------------------------------- */

/**
 * Shows the slice of the scanned image a table row came from, above an editable copy
 * of the row's text.
 *
 * Flagging a row as low-confidence is only half an answer: to act on it the user has
 * to find that row in the original photo and compare, which on a phone means pinching
 * around a full-page image. Cropping straight to the line closes that loop — the
 * evidence and the correction sit in the same dialog.
 */
@Composable
fun RowInspectorDialog(
    source: RowSource,
    text: String,
    onTextChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val crop by produceState<ImageBitmap?>(initialValue = null, source) {
        value = withContext(Dispatchers.IO) { decodeRegion(source)?.asImageBitmap() }
    }
    var edited by remember(source) { mutableStateOf(text) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                "VERIFY ROW",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                fontSize = 14.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp, max = 200.dp)
                        .background(MaterialTheme.colorScheme.background, ChipShape)
                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), ChipShape),
                    contentAlignment = Alignment.Center
                ) {
                    val bitmap = crop
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = "Source image for this row",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().padding(4.dp)
                        )
                    } else {
                        Text(
                            "Loading source…",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                }
                Text(
                    "Compare with the recognized text and correct it if needed.",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = edited,
                    onValueChange = { edited = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium
                        .copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    shape = ChipShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onTextChange(edited); onDismiss() }) {
                Text("APPLY", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CLOSE", letterSpacing = 1.sp) }
        }
    )
}

/**
 * Decodes just the row's region from the cached scan image.
 *
 * [BitmapRegionDecoder] reads only the requested tile, so inspecting a row costs a few
 * hundred kilobytes rather than decoding a whole 2048px page — which matters because
 * this app is already fighting an OEM memory killer.
 */
private fun decodeRegion(source: RowSource): android.graphics.Bitmap? = try {
    val file = File(source.imagePath)
    if (!file.isFile) null else file.inputStream().use { stream ->
        @Suppress("DEPRECATION")
        val decoder = BitmapRegionDecoder.newInstance(stream, false)
        // Pad generously: a line read in isolation is hard to judge, and the
        // neighbouring rows give the eye something to align against.
        val padX = (source.box.width * 0.04f).roundToInt() + 8
        val padY = (source.box.height * 0.45f).roundToInt() + 6
        val rect = Rect(
            (source.box.left - padX).coerceAtLeast(0),
            (source.box.top - padY).coerceAtLeast(0),
            (source.box.right + padX).coerceAtMost(source.imageWidth),
            (source.box.bottom + padY).coerceAtMost(source.imageHeight)
        )
        if (rect.width() <= 0 || rect.height() <= 0) return@use null
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        decoder?.decodeRegion(rect, options).also { decoder?.recycle() }
    }
} catch (_: Throwable) {
    null
}

/* ---------------------------------------------------------------------------
 * Accuracy corpus
 * ------------------------------------------------------------------------- */

/**
 * Picks which pages of the session become golden test cases.
 *
 * A test case is only worth having if its text is right, so pages the user actually
 * corrected are pre-selected and untouched ones are not: an unchecked page may be
 * perfect or may carry errors nobody looked at, and the dialog cannot tell which.
 * Each row also shows the raw scan's error rate against the correction, which is the
 * first real accuracy number this app produces for a page.
 */
@Composable
fun CorpusExportDialog(
    cases: List<CorpusCase>,
    onConfirm: (List<CorpusCase>) -> Unit,
    onDismiss: () -> Unit
) {
    val selected = remember(cases) {
        mutableStateListOf<String>().apply { addAll(cases.filter { it.edited }.map { it.name }) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                "SAVE AS TEST CASES",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                fontSize = 14.sp
            )
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "Each page is saved as the scanned image plus its text as shown now, " +
                        "for the accuracy harness. Include only pages whose text you have checked.",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                cases.forEach { case ->
                    val checked = case.name in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (checked) selected.remove(case.name) else selected.add(case.name)
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary
                            )
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                "SCAN ${"%02d".format(case.index)} · ${case.lineCount} lines",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                corpusCaseSummary(case),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = if (case.edited) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "The file contains the scanned documents themselves.",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty(),
                onClick = { onConfirm(cases.filter { it.name in selected }) }
            ) {
                Text("SAVE ZIP", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL", letterSpacing = 1.sp) }
        }
    )
}

/** "corrected · raw scan 3.1% wrong", "unchanged", or "origin unknown". */
internal fun corpusCaseSummary(case: CorpusCase): String {
    val cer = case.rawCer ?: return "Origin unknown · text only"
    return if (case.edited) "Corrected · raw scan ${"%.1f".format(cer * 100)}% wrong"
    else "Unchanged · not checked?"
}

/* ---------------------------------------------------------------------------
 * Extracted fields
 * ------------------------------------------------------------------------- */

/**
 * The document reduced to the values someone actually files: vendor, date, total.
 * Only shown when at least one field was found with confidence — a panel full of
 * blanks would be worse than no panel.
 */
@Composable
fun FieldsPanel(fields: ExtractedFields, onCopy: (String) -> Unit) {
    if (fields.isEmpty) return
    val pairs = fields.asPairs()
    IndustrialPanel {
        SectionLabel("Document fields", "${pairs.size} found")
        Spacer(Modifier.height(10.dp))
        pairs.forEachIndexed { index, (label, value) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onCopy(value) }
                    .padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.ReceiptLong,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    label.uppercase(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(110.dp)
                )
                Text(
                    value,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
            }
            if (index < pairs.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Tap a value to copy it.",
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/* ---------------------------------------------------------------------------
 * Post-OCR corrections
 * ------------------------------------------------------------------------- */

/** Reports what the corrector changed, so a repair is never invisible. */
@Composable
fun CorrectionsBanner(corrections: List<String>) {
    if (corrections.isEmpty()) return
    Row(
        Modifier
            .fillMaxWidth()
            .background(SafetyAmber.copy(alpha = 0.10f), ChipShape)
            .border(BorderStroke(1.dp, SafetyAmber.copy(alpha = 0.4f)), ChipShape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.AutoFixHigh,
            contentDescription = null,
            tint = SafetyAmber,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Corrected in numeric columns: " + corrections.take(4).joinToString(", ") +
                if (corrections.size > 4) " (+${corrections.size - 4} more)" else "",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

/* ---------------------------------------------------------------------------
 * Accuracy settings
 * ------------------------------------------------------------------------- */

/** The three switches that trade scan time for accuracy. */
@Composable
fun AccuracyPanel(
    settings: OcrSettings,
    partnerName: String?,
    onMixedScript: (Boolean) -> Unit,
    onDeskew: (Boolean) -> Unit,
    onNnapi: (Boolean) -> Unit
) {
    IndustrialPanel {
        SectionLabel("Accuracy")
        Spacer(Modifier.height(4.dp))
        ToggleRow(
            icon = Icons.Filled.Translate,
            title = "Mixed script",
            subtitle = partnerName?.let { "Also read $it on the same page" }
                ?: "No second script available",
            checked = settings.mixedScript,
            enabled = partnerName != null,
            onCheckedChange = onMixedScript
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        ToggleRow(
            icon = Icons.Filled.Straighten,
            title = "Auto-straighten",
            subtitle = "Flatten angled or tilted photos",
            checked = settings.deskew,
            onCheckedChange = onDeskew
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        ToggleRow(
            icon = Icons.Filled.Bolt,
            title = "Hardware acceleration",
            subtitle = "NNAPI · faster on some devices",
            checked = settings.useNnapi,
            onCheckedChange = onNnapi
        )
    }
}

/* ---------------------------------------------------------------------------
 * Recognition model manager
 * ------------------------------------------------------------------------- */

/**
 * Adds and removes the recognition models that are not bundled in the app.
 *
 * Latin and Thai ship inside the APK; Chinese/Japanese and Korean are ~30 MB of
 * character dictionaries that most users never need, so they are fetched only when
 * chosen and can be removed again to reclaim the space.
 */
@Composable
fun OcrModelPanel(
    installed: Set<RecModel>,
    download: ModelDownload,
    sizeOnDisk: (RecModel) -> Long,
    isRemovable: (RecModel) -> Boolean,
    onDownload: (RecModel) -> Unit,
    onDelete: (RecModel) -> Unit
) {
    IndustrialPanel {
        SectionLabel("Recognition models", "${installed.size} installed")
        Spacer(Modifier.height(6.dp))
        RecModel.downloadable.forEachIndexed { index, model ->
            val running = download as? ModelDownload.Running
            val isRunning = running?.model == model
            val ready = model in installed
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        model.displayName,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        when {
                            isRunning -> "Downloading… ${(running.progress * 100).toInt()}%"
                            ready -> "Installed · ${formatBytes(sizeOnDisk(model))}"
                            else -> "Not installed · ${formatBytes(model.downloadBytes)} download"
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                when {
                    isRunning -> CircularProgressIndicator(
                        progress = { running.progress },
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    ready && isRemovable(model) -> IconButton(onClick = { onDelete(model) }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Remove ${model.displayName}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    !ready -> IconButton(
                        onClick = { onDownload(model) },
                        enabled = download !is ModelDownload.Running
                    ) {
                        Icon(
                            Icons.Filled.Download,
                            contentDescription = "Download ${model.displayName}",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            if (index < RecModel.downloadable.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
        (download as? ModelDownload.Failed)?.let { failed ->
            Spacer(Modifier.height(8.dp))
            Text(
                failed.message,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "One download per language; scanning stays fully offline afterwards.",
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Prompt shown when the selected script's model is not on the device yet. */
@Composable
fun MissingModelBanner(model: RecModel, download: ModelDownload, onDownload: () -> Unit) {
    val running = (download as? ModelDownload.Running)?.takeIf { it.model == model }
    Column(
        Modifier
            .fillMaxWidth()
            .background(SafetyAmber.copy(alpha = 0.12f), ChipShape)
            .border(BorderStroke(1.dp, SafetyAmber.copy(alpha = 0.5f)), ChipShape)
            .padding(12.dp)
    ) {
        Text(
            "${model.displayName.uppercase()} MODEL REQUIRED",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Download ${formatBytes(model.downloadBytes)} once to read this script. " +
                "Scanning works offline afterwards.",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        if (running != null) {
            LinearProgressIndicator(
                progress = { running.progress },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Button(
                onClick = onDownload,
                shape = ChipShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("DOWNLOAD", fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontSize = 12.sp)
            }
        }
        (download as? ModelDownload.Failed)?.takeIf { it.model == model }?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it.message,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%d KB".format(bytes / 1_000)
    else -> "$bytes B"
}
