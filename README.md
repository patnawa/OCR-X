# OCR-X

Android app that scans images, extracts text with on-device OCR, and exports it to **CSV, JSON, and Excel (.xlsx)**.

## Features

- **Scan from Camera or Gallery** — take a photo or pick an existing image.
- **Two on-device OCR engines** (pick in Settings, both fully offline):
  - **PP-OCRv6** (default) — PaddleOCR's latest model via the official Android SDK (ONNX Runtime + OpenCV). Highest accuracy; matches/beats large VLMs on OCR benchmarks.
  - **ML Kit** — Google's Latin recognizer. Faster, lighter, with an optional image-enhancement pass.
- **Multi-capture mode** — flip the toggle to scan several images and export them all into **one file**.
- **Translation (offline + online)** — translate recognized text into 15 languages (incl. Thai). **Offline** via ML Kit (free, on-device after a one-time model download) or **Online** via cloud (higher quality, auto-detects source). Export the translated text too.
- **Live/Realtime translation** — a camera mode that continuously recognizes text and shows its translation live (ML Kit OCR + on-device translation).
- **Edit before export** — every recognized block is editable; fix mistakes before saving.
- **Table detection** — lines are split into columns on wide gaps, producing a real spreadsheet grid.
- **Export to CSV / Excel / PDF / JSON / TXT** — saved wherever you choose via the system file picker. The `.xlsx` and `.pdf` writers are dependency-free.

## Tech stack

- Kotlin + Jetpack Compose (Material 3)
- **PP-OCRv6** via the official PaddleOCR `ppocr-sdk` module (ONNX Runtime 1.21.1 + OpenCV 4.5.3), models bundled in `app/src/main/assets/models/`
- Google ML Kit `text-recognition` (bundled model, offline)
- CameraX-free capture via `ActivityResultContracts.TakePicture` + FileProvider
- Storage Access Framework (`CreateDocument`) for exports — no storage permissions required
- Custom `XlsxWriter` (Open XML over `java.util.zip`, no Apache POI)

## OCR engines

| Engine | Runs | Accuracy | Notes |
|---|---|---|---|
| **PP-OCRv6** (default) | On-device | Highest | ~30 MB models + ONNX/OpenCV native libs; first scan has a one-time model-load delay |
| **ML Kit** | On-device | Good | Fast; optional Enhance pass (orient/upscale/contrast) |

Both are offline. The APK ships **arm64-v8a only** (`abiFilters`) to keep size down — it covers essentially all modern phones. To also support 32-bit devices or x86 emulators, add the ABIs back in `app/build.gradle.kts`.

## Project layout

```
app/src/main/java/com/tsm/ocrx/
  MainActivity.kt        Compose UI (source buttons, page cards, export)
  OcrViewModel.kt        State: pages, multi-mode, combined export text
  ocr/OcrEngine.kt       ML Kit wrapper + text→table parser
  export/Exporters.kt    CSV / JSON serialization
  export/XlsxWriter.kt   Minimal .xlsx writer
  model/OcrModels.kt     OcrResult table model
  ui/theme/Theme.kt      Material 3 theme
```

## Build

Requires JDK 17+ (the Android Studio JBR works) and the Android SDK.

```bash
# Debug APK
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Release APK (minified)
./gradlew assembleRelease
```

Or open the folder in Android Studio and press Run.

## Install on a phone

1. Copy `app-debug.apk` to the device (USB, Drive, etc.).
2. On the phone, enable **Install unknown apps** for your file manager.
3. Tap the APK to install.

Or over USB with debugging enabled:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Requirements

- Android 8.0 (API 26) or newer, arm64 device.
