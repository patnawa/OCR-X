# Changelog

All notable changes to OCR-X are documented here. Versions follow the app's
`versionName` (with `versionCode` in parentheses).

## [1.2] (versionCode 3) — 2026-07-21

### Added
- **Multi-language recognition.** A "Recognition language" picker in the scan
  panel: **English/Latin, Thai, Chinese, Japanese, Korean**. Text detection is
  script-agnostic and shared; only the recognition model and its character
  dictionary swap per language. Thai and CJK models (PP-OCRv5) are bundled and
  run fully offline. Previously Thai text came out as Latin garbage; it now reads
  correctly (verified on device).

### Changed
- Translation errors caused by no connectivity (DNS / `UnknownHostException`) now
  show a clear one-time-internet hint instead of a raw "unable to resolve host",
  and note that scanning and export always work offline.

## [1.1] (versionCode 2) — 2026-07-21

### Added
- **Geometric table structure.** Columns are now detected once for the whole page
  from the text geometry (vertical whitespace gutters) instead of per line, so
  columns stay aligned row to row and interior empty cells are preserved. Free-form
  text is left as-is. Backed by JVM unit tests.
- **OCR confidence surfacing.** Each scan shows a per-scan confidence badge,
  highlights low-confidence rows amber in the table preview, and warns before
  export when overall confidence is low. Confidence comes from the recognizer's
  per-fragment scores (a scan-time signal, not persisted).

## [1.0] (versionCode 1) — 2026-07-20

Initial release.

### Added
- On-device OCR with **PP-OCRv6** (PaddleOCR via ONNX Runtime + OpenCV), with
  **Quality** and **Fast** scan modes.
- Capture from **Camera or Gallery**, with an optional **crop-before-scan** step.
- **Multi-capture** mode — scan several images and export them as one file.
- Editable recognized text before export.
- Export to **CSV, Excel (.xlsx), PDF, JSON, TXT** via the system file picker
  (dependency-free `.xlsx` and `.pdf` writers).
- **Translation** of recognized text into 15 languages — offline (ML Kit) or
  online (cloud), with translated export.
- **Scan history** (last 50 sessions) and session persistence across app kills.
- Industrial dark UI and an adaptive launcher icon.
- Memory/stability hardening for aggressive-OEM background kills.
