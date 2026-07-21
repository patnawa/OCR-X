# OCR-X

Android app that scans images, extracts text with **on-device OCR**, reconstructs
tables, and exports to **CSV, Excel (.xlsx), PDF, JSON, and TXT**. Fully offline —
no account, no cloud, no storage permissions.

Current version: **1.2** — see [CHANGELOG.md](CHANGELOG.md).

## Features

- **Scan from Camera or Gallery**, with an optional **crop-before-scan** step to
  select just the text area.
- **On-device OCR** via **PP-OCRv6** (PaddleOCR, ONNX Runtime + OpenCV) with two
  scan modes:
  - **Quality** — full detail, best accuracy.
  - **Fast** — ~2× faster at lower input resolution.
- **Multi-language recognition** — English/Latin, **Thai**, Chinese, Japanese,
  Korean. Pick the script in the scan panel; detection is shared and only the
  recognition model swaps. All languages run offline (models are bundled).
- **Geometric table reconstruction** — columns are detected from the page geometry
  (vertical whitespace gutters) and kept aligned across rows, with interior empty
  cells preserved. Free-form text is left untouched.
- **Confidence surfacing** — a per-scan confidence badge, amber highlighting of
  low-confidence rows, and a warning before exporting a low-confidence scan.
- **Multi-capture** — scan several images and export them all into **one file**.
- **Edit before export** — every recognized block is editable.
- **Translation (offline + online)** — translate recognized text into 15 languages
  (incl. Thai): **offline** via ML Kit (free, on-device after a one-time model
  download) or **online** via cloud (higher quality, auto source detection). The
  translated text can be exported too.
- **Scan history** — the last 50 sessions are saved and reloadable; the current
  session survives the app being killed in the background.
- **Export to CSV / Excel / PDF / JSON / TXT** — saved wherever you choose via the
  system file picker. The `.xlsx` and `.pdf` writers are dependency-free.

## Tech stack

- Kotlin + Jetpack Compose (Material 3), single-activity.
- **PP-OCRv6** detection + recognition via the `ppocr-sdk` module
  (ONNX Runtime 1.21.1 + OpenCV 4.11.0). Models bundled in
  `app/src/main/assets/models/`.
- **PP-OCRv5** script-specific recognition models for Thai / Chinese / Korean
  (Chinese also serves Japanese), pre-converted to ONNX.
- Google ML Kit `translate` + `language-id` for offline translation.
- Capture via `ActivityResultContracts.TakePicture` + FileProvider; crop via the
  CanHub image cropper.
- Storage Access Framework (`CreateDocument`) for exports — no storage permissions.
- Custom `XlsxWriter` (Open XML over `java.util.zip`, no Apache POI) and a
  `PdfExporter` built on `android.graphics.pdf`.

## How OCR works

Detection finds text boxes (script-agnostic, one shared model). Each box is
recognized by the model for the selected language; the recognizer's per-fragment
confidence drives the confidence UI. Boxes are grouped into reading order and
columns via `ocr/Layout.kt`, then parsed into a grid for preview and export.

Switching the recognition language rebuilds the engine with that language's model;
the detection model and the rest of the pipeline are unchanged.

## Project layout

```
app/src/main/java/com/tsm/ocrx/
  MainActivity.kt          Compose UI (scan panel, page cards, table, export)
  OcrViewModel.kt          State: pages, mode, language, translation
  SessionStore.kt          Persist session + settings across app kills
  HistoryStore.kt          Last-50 scan history
  ocr/OcrEngine.kt         Recognize + text→table parsing
  ocr/PaddleEngine.kt      PP-OCR engine, per-language model loading
  ocr/OcrLanguage.kt       Recognition languages and their model assets
  ocr/Layout.kt            Reading order + geometric column detection
  ocr/ImagePreprocessor.kt EXIF orient / resize
  translate/               Offline (ML Kit) + online translation
  export/Exporters.kt      CSV / JSON / TXT
  export/XlsxWriter.kt     Minimal .xlsx writer
  export/PdfExporter.kt    A4 paginated PDF writer
  model/OcrModels.kt       OcrResult grid + ScanConfidence
  ui/theme/Theme.kt        Industrial Material 3 theme
app/src/test/java/...      JVM unit tests (Layout)
ppocr-sdk/                 PaddleOCR Android SDK (ONNX Runtime + OpenCV)
app/src/main/assets/models/
  det/                     Shared text-detection model
  rec/                     Default (Latin) recognition model + inference.yml
  rec/{thai,chinese,korean}/  Per-language recognition models
```

## Build

Requires JDK 17+ (the Android Studio JBR works) and the Android SDK.

```bash
# Debug APK  → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleDebug

# Release APK (minified)
./gradlew assembleRelease

# Unit tests
./gradlew :app:testDebugUnitTest
```

Or open the folder in Android Studio and press Run.

The APK ships **arm64-v8a only** (`abiFilters`) — it covers essentially all modern
phones. The bundled OCR models make the APK large (~136 MB); to support 32-bit
devices or x86 emulators, add the ABIs back in `app/build.gradle.kts`.

## Install on a phone

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or copy `app-debug.apk` to the device, enable **Install unknown apps** for your
file manager, and tap it.

## Requirements

- Android 8.0 (API 26) or newer, arm64 device.
