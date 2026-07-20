package com.tsm.ocrx

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.tsm.ocrx.export.ExportFormat
import com.tsm.ocrx.export.Exporters
import com.tsm.ocrx.ocr.OcrEngineType
import com.tsm.ocrx.translate.Language
import com.tsm.ocrx.translate.TranslationEngine
import com.tsm.ocrx.ui.theme.ChipShape
import com.tsm.ocrx.ui.theme.OcrXTheme
import com.tsm.ocrx.ui.theme.PanelShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OcrXTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    OcrScreen()
                }
            }
        }
    }
}

private fun createImageUri(context: Context): Uri {
    val dir = File(context.cacheDir, "images").apply { mkdirs() }
    val file = File.createTempFile("capture_", ".jpg", dir)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

@Composable
fun OcrScreen(vm: OcrViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var pendingExport by remember { mutableStateOf(ExportFormat.CSV) }
    var pendingTranslated by remember { mutableStateOf(false) }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) vm.onImagePicked(uri) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok -> if (ok) pendingCameraUri?.let { vm.onImagePicked(it) } }

    fun exportTo(format: ExportFormat, target: Uri) {
        scope.launch {
            val result = if (pendingTranslated) state.translatedTable else state.table
            val ok = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(target)?.use { out ->
                        Exporters.write(format, result, out)
                    } != null
                } catch (e: Exception) {
                    false
                }
            }
            snackbar.showSnackbar(if (ok) "Saved ${format.label} file" else "Could not save file")
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) exportTo(pendingExport, uri) }

    fun startExport(format: ExportFormat, translated: Boolean) {
        pendingExport = format
        pendingTranslated = translated
        val prefix = if (translated) "ocr-x-${state.targetLang.code}" else "ocr-x-export"
        exportLauncher.launch("$prefix.${format.extension}")
    }

    fun launchCamera() {
        val uri = createImageUri(context)
        pendingCameraUri = uri
        cameraLauncher.launch(uri)
    }

    fun launchGallery() {
        galleryLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { IndustrialHeader(showClear = !state.isEmpty, onClear = { vm.reset() }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsPanel(
                engine = state.engine,
                onEngineChange = { vm.setEngine(it) },
                multiMode = state.multiMode,
                onMultiToggle = { vm.setMultiMode(it) },
                enhance = state.enhance,
                onEnhanceToggle = { vm.setEnhance(it) }
            )

            SourceButtons(
                multiMode = state.multiMode,
                hasPages = !state.isEmpty,
                onCamera = ::launchCamera,
                onGallery = ::launchGallery
            )

            if (state.isEmpty) {
                WelcomePanel()
            } else {
                state.pages.forEachIndexed { index, page ->
                    PageCard(
                        index = index + 1,
                        showIndex = state.multiMode,
                        page = page,
                        onTextChange = { vm.onPageTextChanged(page.id, it) },
                        onRemove = { vm.removePage(page.id) }
                    )
                }

                val combined = state.table
                if (combined.rows.isNotEmpty()) {
                    ExportSection(
                        rows = combined.rows,
                        pageCount = state.pages.size,
                        multiMode = state.multiMode,
                        combinedText = combined.rawText,
                        onCopy = { scope.launch { snackbar.showSnackbar("Text copied") } },
                        onExport = { startExport(it, false) }
                    )

                    TranslationPanel(
                        targetLang = state.targetLang,
                        status = state.translateStatus,
                        translatedText = state.translatedText,
                        onTargetChange = { vm.setTargetLang(it) },
                        onTranslate = { vm.translate() },
                        onTranslatedChange = { vm.onTranslatedTextChanged(it) },
                        onCopy = { scope.launch { snackbar.showSnackbar("Translation copied") } },
                        onExport = { startExport(it, true) }
                    )
                }
            }
        }
    }
}

/* ---------------------------------------------------------------------------
 * Industrial building blocks
 * ------------------------------------------------------------------------- */

@Composable
private fun IndustrialHeader(showClear: Boolean, onClear: () -> Unit) {
    Column(Modifier.background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(width = 4.dp, height = 34.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "OCR-X",
                    fontWeight = FontWeight.Black,
                    fontSize = 22.sp,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    "// TEXT EXTRACTION UNIT",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (showClear) {
                OutlinedIconButton(onClick = onClear, icon = Icons.Filled.DeleteSweep, desc = "Clear all")
            }
        }
        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun OutlinedIconButton(onClick: () -> Unit, icon: ImageVector, desc: String) {
    Box(
        Modifier
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), ChipShape)
            .clickable { onClick() }
            .padding(8.dp)
    ) {
        Icon(icon, contentDescription = desc, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun IndustrialPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, PanelShape)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), PanelShape)
            .padding(14.dp),
        content = content
    )
}

@Composable
private fun SectionLabel(text: String, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .size(width = 3.dp, height = 13.dp)
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text.uppercase(),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            letterSpacing = 1.5.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            Text(
                trailing.uppercase(),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/* ---------------------------------------------------------------------------
 * Settings
 * ------------------------------------------------------------------------- */

@Composable
private fun SettingsPanel(
    engine: OcrEngineType,
    onEngineChange: (OcrEngineType) -> Unit,
    multiMode: Boolean,
    onMultiToggle: (Boolean) -> Unit,
    enhance: Boolean,
    onEnhanceToggle: (Boolean) -> Unit
) {
    IndustrialPanel {
        SectionLabel("Engine")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OcrEngineType.entries.forEach { type ->
                EngineChip(type = type, selected = type == engine, modifier = Modifier.weight(1f)) {
                    onEngineChange(type)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        if (engine == OcrEngineType.ML_KIT) {
            ToggleRow(Icons.Filled.AutoFixHigh, "Enhance image", "Orient · upscale · contrast", enhance, onEnhanceToggle)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
        ToggleRow(Icons.Filled.Layers, "Multi-capture", "Scan several · export as one", multiMode, onMultiToggle)
    }
}

@Composable
private fun EngineChip(
    type: OcrEngineType,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant
    Column(
        modifier
            .background(bg, ChipShape)
            .border(BorderStroke(if (selected) 1.5.dp else 1.dp, border), ChipShape)
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (selected) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                type.displayName,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            type.tagline,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
            Text(subtitle, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

/* ---------------------------------------------------------------------------
 * Capture sources
 * ------------------------------------------------------------------------- */

@Composable
private fun SourceButtons(
    multiMode: Boolean,
    hasPages: Boolean,
    onCamera: () -> Unit,
    onGallery: () -> Unit
) {
    val camera = if (multiMode && hasPages) "ADD PHOTO" else "CAMERA"
    val gallery = if (multiMode && hasPages) "ADD IMAGE" else "GALLERY"
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = onCamera,
            shape = ChipShape,
            modifier = Modifier.weight(1f).height(54.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(camera, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontSize = 13.sp)
        }
        OutlinedButton(
            onClick = onGallery,
            shape = ChipShape,
            modifier = Modifier.weight(1f).height(54.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground)
        ) {
            Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(gallery, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontSize = 13.sp)
        }
    }
}

@Composable
private fun WelcomePanel() {
    IndustrialPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.DocumentScanner, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text("READY", fontWeight = FontWeight.Black, letterSpacing = 2.sp, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    "Capture or import an image to extract text on-device.",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/* ---------------------------------------------------------------------------
 * Pages
 * ------------------------------------------------------------------------- */

@Composable
private fun PageCard(
    index: Int,
    showIndex: Boolean,
    page: Page,
    onTextChange: (String) -> Unit,
    onRemove: () -> Unit
) {
    IndustrialPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(64.dp)
                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), ChipShape)
            ) {
                AsyncImage(
                    model = page.imageUri,
                    contentDescription = "Scan $index",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (showIndex) {
                    Text("SCAN ${"%02d".format(index)}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onBackground)
                }
                when (val s = page.status) {
                    OcrStatus.Processing -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("RECOGNIZING…", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    OcrStatus.Done -> {
                        val lines = if (page.text.isBlank()) 0 else page.text.count { it == '\n' } + 1
                        Text("$lines LINES", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is OcrStatus.Error -> Text(s.message, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                }
            }
            OutlinedIconButton(onClick = onRemove, icon = Icons.Filled.Close, desc = "Remove scan")
        }
        if (page.status is OcrStatus.Done) {
            Spacer(Modifier.height(10.dp))
            DataTextField(page.text, onTextChange)
        }
    }
}

@Composable
private fun DataTextField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp, max = 220.dp),
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
        shape = ChipShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedTextColor = MaterialTheme.colorScheme.onBackground,
            unfocusedTextColor = MaterialTheme.colorScheme.onBackground
        )
    )
}

/* ---------------------------------------------------------------------------
 * Export
 * ------------------------------------------------------------------------- */

@Composable
private fun ExportSection(
    rows: List<List<String>>,
    pageCount: Int,
    multiMode: Boolean,
    combinedText: String,
    onCopy: () -> Unit,
    onExport: (ExportFormat) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val subtitle = if (multiMode) "$pageCount scans · ${rows.size} lines" else "${rows.size} lines"

    IndustrialPanel {
        SectionLabel("Table", subtitle)
        Spacer(Modifier.height(10.dp))
        TablePreview(rows)
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = {
                clipboard.setText(AnnotatedString(combinedText))
                onCopy()
            },
            shape = ChipShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("COPY TEXT", fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontSize = 12.sp)
        }
    }

    Spacer(Modifier.height(16.dp))

    IndustrialPanel {
        SectionLabel("Export")
        Spacer(Modifier.height(10.dp))
        ExportGrid(onExport)
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ExportGrid(onExport: (ExportFormat) -> Unit) {
    val icons = mapOf(
        ExportFormat.CSV to Icons.Filled.GridOn,
        ExportFormat.XLSX to Icons.Filled.TableView,
        ExportFormat.PDF to Icons.Filled.PictureAsPdf,
        ExportFormat.JSON to Icons.Filled.DataObject,
        ExportFormat.TXT to Icons.Filled.Description
    )
    ExportFormat.entries.chunked(3).forEach { rowFormats ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
        ) {
            rowFormats.forEach { fmt ->
                ExportButton(fmt.label, icons[fmt]!!, Modifier.weight(1f)) { onExport(fmt) }
            }
            repeat(3 - rowFormats.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun TranslationPanel(
    targetLang: Language,
    status: TranslateStatus,
    translatedText: String,
    onTargetChange: (Language) -> Unit,
    onTranslate: () -> Unit,
    onTranslatedChange: (String) -> Unit,
    onCopy: () -> Unit,
    onExport: (ExportFormat) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    Spacer(Modifier.height(16.dp))
    IndustrialPanel {
        SectionLabel("Translate", "on-device")
        Spacer(Modifier.height(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LanguageSelector(targetLang, onTargetChange, Modifier.weight(1f))
            Button(
                onClick = onTranslate,
                shape = ChipShape,
                enabled = status !is TranslateStatus.Running,
                modifier = Modifier.height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(Icons.Filled.Translate, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("TRANSLATE", fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontSize = 12.sp)
            }
        }

        when (status) {
            is TranslateStatus.Running -> {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(status.stage.uppercase(), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
            is TranslateStatus.Error -> {
                Spacer(Modifier.height(12.dp))
                Text(status.message, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }
            TranslateStatus.Done -> {
                Spacer(Modifier.height(12.dp))
                DataTextField(translatedText, onTranslatedChange)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(translatedText))
                        onCopy()
                    },
                    shape = ChipShape,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("COPY TRANSLATION", fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontSize = 12.sp)
                }
                Spacer(Modifier.height(12.dp))
                Text("EXPORT TRANSLATION", fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                ExportGrid(onExport)
            }
            TranslateStatus.Idle -> Unit
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun LanguageSelector(
    selected: Language,
    onChange: (Language) -> Unit,
    modifier: Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, ChipShape)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), ChipShape)
                .clickable { expanded = true }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("TO", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(selected.name, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TranslationEngine.LANGUAGES.forEach { lang ->
                DropdownMenuItem(
                    text = { Text(lang.name) },
                    onClick = { onChange(lang); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun ExportButton(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .height(58.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, ChipShape)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), ChipShape)
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(label.uppercase(), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun TablePreview(rows: List<List<String>>) {
    val columnCount = rows.maxOfOrNull { it.size } ?: 0
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background, ChipShape)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), ChipShape)
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState()).padding(4.dp)) {
            rows.take(50).forEachIndexed { rowIndex, row ->
                Row {
                    for (c in 0 until columnCount) {
                        Text(
                            text = row.getOrElse(c) { "" },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.width(140.dp).padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                }
                if (rowIndex < rows.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
        if (rows.size > 50) {
            Text(
                "…and ${rows.size - 50} more rows",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}
