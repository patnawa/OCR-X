# OCR-X

Android app that scans images, extracts text with **on-device OCR**, reconstructs
tables, and exports to **CSV, Excel (.xlsx), PDF, JSON, and TXT**. Fully offline —
no account, no cloud, no storage permissions.

Current version: **1.4** — see [CHANGELOG.md](CHANGELOG.md).

## Features

- **Scan from Camera or Gallery**, with an optional **crop-before-scan** step to
  select just the text area.
- **On-device OCR** via **PP-OCRv6** (PaddleOCR, ONNX Runtime + OpenCV) with two
  scan modes:
  - **Quality** — full detail, best accuracy.
  - **Fast** — ~2× faster at lower input resolution.
- **Multi-language recognition** — English/Latin, **Thai**, Chinese, Japanese,
  Korean. Pick the script in the scan panel; detection is shared and only the
  recognition model swaps. Latin and Thai are bundled; the CJK models download on
  first use (checksum-verified) and then run offline.
- **Mixed-script pages** — every detected line is recognized by both the selected
  script's model and its complement (non-Latin pairs with Latin, Latin pairs back
  with Thai), and the higher-scoring reading wins. One extra recognition pass, no
  extra detection. Measured on device: a Thai page scanned with the picker left on
  English/Latin goes from a 0.54 character error rate to 0.00.
- **Auto-straighten** — angled photos are perspective-corrected against the detected
  page outline, or levelled by the dominant text angle when no outline is found.
- **Geometric table reconstruction** — columns come from the page's own drawn ruling
  lines where it has them, otherwise from vertical whitespace gutters. Either way
  they are found once for the whole page and stay aligned across rows, with interior
  empty cells preserved. Free-form text is left untouched.
- **Confidence surfacing** — a per-scan confidence badge, amber highlighting of
  low-confidence rows, and a warning before exporting a low-confidence scan.
- **Row inspector** — tap a row to see the exact slice of the source image it came
  from, enlarged, next to an editable copy of its text.
- **Post-OCR correction** — letters misread as digits (O to 0, l to 1, S to 5) are
  repaired inside numeric columns only, and only when the cell is not already a valid
  number and becomes one. Changes are always reported. Numeric columns are also
  checked against a declared total.
- **Document fields** — vendor, date, document number, subtotal, tax and total are
  extracted from receipts and invoices (English and Thai anchors, Buddhist-era year
  conversion). A field is either found with confidence or left blank, never guessed.
- **Multi-capture** — scan several images and export them all into **one file**.
- **Multi-image and PDF import** — select many images at once, or import a
  multi-page PDF (up to 30 pages); both append as pages.
- **Edit before export** — every recognized block is editable.
- **Copy cells** — puts the grid on the clipboard as tab-separated values, which
  Excel, Sheets and Numbers paste straight into cells.
- **Translation (offline + online)** — translate recognized text into 15 languages
  (incl. Thai): **offline** via ML Kit (free, on-device after a one-time model
  download) or **online** via cloud (higher quality, auto source detection). The
  translated text can be exported too.
- **Scan history** — the last 50 sessions are saved and reloadable; the current
  session survives the app being killed in the background.
- **Export to CSV / Excel / PDF / JSON / TXT** — saved wherever you choose via the
  system file picker, or sent straight to another app via the share sheet. The
  `.xlsx` and `.pdf` writers are dependency-free.

## Tech stack

- Kotlin + Jetpack Compose (Material 3), single-activity.
- **PP-OCRv6** detection + recognition via the `ppocr-sdk` module
  (ONNX Runtime 1.21.1 + OpenCV 4.11.0), with XNNPACK where available and optional
  NNAPI. Bundled models live in `app/src/main/assets/models/`; downloaded ones in
  the app's private storage.
- **PP-OCRv5** script-specific recognition models for Thai / Chinese / Korean
  (Chinese also serves Japanese), pre-converted to ONNX.
- OpenCV is used directly for page straightening and ruled-line table detection.
- Google ML Kit `translate` + `language-id` for offline translation.
- Capture via `ActivityResultContracts.TakePicture` + FileProvider; crop via the
  CanHub image cropper.
- Storage Access Framework (`CreateDocument`) for exports — no storage permissions.
- Custom `XlsxWriter` (Open XML over `java.util.zip`, no Apache POI) and a
  `PdfExporter` built on `android.graphics.pdf`.

## How OCR works

The page is straightened first (`ocr/Deskew.kt`). This matters twice over, because
column detection reads vertical whitespace and even a couple of degrees of tilt
smears those gutters until columns merge.

Detection then finds text boxes. It is script-agnostic, so one shared model serves
every language and it runs **once**. Each box is recognized by the selected script's
model and, in mixed-script mode, by the Latin model as well; the higher-confidence
reading wins per box. That is why a page can mix Thai and English and still come out
right, and it is also what makes the extra accuracy cheap — only recognition repeats.

Boxes are grouped into rows by vertical centre and into columns by drawn ruling lines
(`ocr/TableRules.kt`) or whitespace gutters (`ocr/Layout.kt`), emitted tab-delimited,
then repaired by `ocr/TextCorrector.kt` and parsed into a grid for preview and export.

Switching the recognition language rebuilds the engine with that language's model;
the detection model and the rest of the pipeline are unchanged.

## Project layout

```
app/src/main/java/com/tsm/ocrx/
  MainActivity.kt          Compose UI (scan panel, page cards, table, export)
  OcrViewModel.kt          State: pages, mode, language, translation
  SessionStore.kt          Persist session + settings across app kills
  HistoryStore.kt          Last-50 scan history
  ScanUi.kt                Row inspector, fields, accuracy + model panels
  ocr/OcrEngine.kt         Recognize + text→table parsing + TSV
  ocr/PaddleEngine.kt      PP-OCR engine, mixed-script routing, warm-up
  ocr/OcrLanguage.kt       Recognition languages and their models
  ocr/OcrModelStore.kt     Bundled + downloaded model resolution & fetching
  ocr/Layout.kt            Reading order + geometric column detection
  ocr/TableRules.kt        Drawn ruling-line detection (OpenCV)
  ocr/Deskew.kt            Perspective + rotation correction (OpenCV)
  ocr/TextCorrector.kt     Numeric-column repair + column sum checks
  ocr/FieldExtractor.kt    Vendor / date / total extraction
  ocr/PdfImporter.kt       PDF to page images
  ocr/Accuracy.kt          CER / WER metrics
  ocr/ImagePreprocessor.kt EXIF orient / resize
  translate/               Offline (ML Kit) + online translation
  export/Exporters.kt      CSV / JSON / TXT
  export/XlsxWriter.kt     Minimal .xlsx writer
  export/PdfExporter.kt    A4 paginated PDF writer
  model/OcrModels.kt       OcrResult grid + ScanConfidence + ScanGeometry
  ui/theme/Theme.kt        Industrial Material 3 theme
app/src/test/java/...      JVM unit tests (layout, correction, fields, metrics)
app/src/androidTest/...    On-device pipeline + accuracy harness
ppocr-sdk/                 PaddleOCR Android SDK (ONNX Runtime + OpenCV)
app/src/main/assets/models/
  det/                     Shared text-detection model
  rec/                     Default (Latin) recognition model + inference.yml
  rec/thai/                Thai recognition model
                           (Chinese/Japanese + Korean download on demand)
```

## Build

Requires JDK 17+ (the Android Studio JBR works) and the Android SDK.

```bash
# Debug APK  → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleDebug

# Release APK (minified)
./gradlew assembleRelease

# Unit tests (pure logic: layout, correction, field extraction, CER/WER)
./gradlew :app:testDebugUnitTest

# On-device tests (real ONNX engine: pipeline, deskew, PDF import, accuracy)
./gradlew :app:connectedDebugAndroidTest
```

To measure accuracy against your own documents, drop matched pairs into
`app/src/androidTest/assets/golden/`: `name.jpg` plus `name.txt` (the correct text)
and an optional `name.lang`. `OcrAccuracyTest` reports per-image and pooled CER/WER
under the `OcrAccuracy` log tag. With no images present it skips.

On Xiaomi/MIUI devices, installing the test APK needs **Install via USB** enabled in
Developer options.

Or open the folder in Android Studio and press Run.

The APK ships **arm64-v8a only** (`abiFilters`) — it covers essentially all modern
phones. The bundled models and native libraries still make it large; the CJK
recognition models (~30 MB) were moved out of the APK and are fetched on demand. To
support 32-bit devices or x86 emulators, add the ABIs back in
`app/build.gradle.kts`.

## Install on a phone

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or copy `app-debug.apk` to the device, enable **Install unknown apps** for your
file manager, and tap it.

## Requirements

- Android 8.0 (API 26) or newer, arm64 device.
