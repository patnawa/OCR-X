# Changelog

All notable changes to OCR-X are documented here. Versions follow the app's
`versionName` (with `versionCode` in parentheses).

## [1.4] (versionCode 5) — 2026-07-27

### Changed
- **Recognition runs in batches.** Text lines were recognized one per inference, so a
  40-line receipt paid the ONNX Runtime call overhead 40 times. Lines are now grouped
  into batches of up to 8.
- **Batches are grouped by line shape, not reading order.** A batch is one tensor, so
  every crop in it is padded out to the widest crop present — batching a receipt in
  reading order pairs a full-width description with a four-character amount and makes
  the amount cost as much as the description. Lines are now ordered by width before
  batching, and a batch is capped by total padded width as well as by count, so a page
  of unusually wide lines cannot allocate an outsized tensor. Recognition order is
  internal: results are returned in reading order as before.
- **The second script's model only re-reads uncertain lines.** With mixed script on,
  every line was recognized twice, doubling recognition time. A line the primary model
  read confidently cannot realistically be taken from it — the secondary has to beat it
  by `SECONDARY_MARGIN` — so only lines below `recSecondaryMaxConf` (0.90) are re-read.
  Set it to 1.0 to restore the previous always-re-read behaviour.

## [1.3] (versionCode 4) — 2026-07-25

### Added
- **Mixed-script recognition.** Detection runs once and every detected line is read
  by both the selected script's model and its complement, keeping whichever reading
  scored higher. Non-Latin scripts pair with Latin; Latin pairs back with Thai.
  Toggle under **Accuracy → Mixed script**; it costs one extra recognition pass and
  no extra detection.

  The case this fixes, measured on device: leaving the picker on the default
  English/Latin and scanning a Thai page used to yield transliterated noise
  (`ร้านข้าวแกงภูเก็ต` → `suuvann`), a **0.54 character error rate**. With mixed
  script on it reads **exactly right — CER 0.00** — and the Latin lines on the same
  page are unaffected. Note that script models like Thai's already carry Latin
  letters and digits in their dictionaries, so selecting Thai explicitly already
  handled both halves; it is the forgot-to-switch direction that needed help.
- **Row inspector.** Tap any row in the table preview to see the exact slice of the
  source image it came from, blown up, with an editable copy of the text beside it.
  Flagged low-confidence rows can now be checked and fixed without hunting through
  the original photo.
- **Auto-straighten.** Angled and tilted photos are flattened before recognition —
  a page outline is perspective-corrected when one is found, otherwise the dominant
  text angle is levelled. This also keeps column detection honest, since a few
  degrees of tilt used to smear the whitespace gutters until columns merged.
- **Ruled-line table detection.** When a document draws its own grid, the printed
  rules define the columns instead of inferred whitespace. Borderless tables still
  use the gutter heuristic.
- **On-demand language models.** Chinese/Japanese and Korean recognition models are
  now downloaded on first use instead of shipping in the APK, which removes ~30 MB
  from the install. Downloads are checksum-verified and can be removed again from
  **Recognition models**. English/Latin and Thai remain bundled and always offline.
- **Post-OCR correction.** Letters misread as digits (`O`→`0`, `l`→`1`, `S`→`5`) are
  repaired inside numeric columns only, and only when the cell is not already a valid
  number and becomes one. Every change is reported, never silent. Numeric columns are
  also cross-checked against a declared total, warning when they disagree.
- **Document fields.** Vendor, date, document number, subtotal, tax and total are
  extracted from receipts and invoices, with English and Thai anchor words and
  Buddhist-era year conversion. Tap a value to copy it.
- **Multi-image and PDF import.** Select several images at once, or import a
  multi-page PDF (up to 30 pages) — both append as pages in one session.
- **Copy cells & share.** *Copy cells* puts the grid on the clipboard as
  tab-separated values, which Excel, Sheets and Numbers paste straight into cells.
  Every export format can now also be sent through the system share sheet.
- **Accuracy harness.** CER/WER metrics plus an instrumented test suite that runs the
  real engine on a device — against rendered pages with known text, and against any
  golden image set dropped into `app/src/androidTest/assets/golden/`.

### Changed
- The engine is now built in the background when the app opens, so the first scan no
  longer pays the model load.
- NNAPI can be enabled under **Accuracy → Hardware acceleration** (off by default,
  since vendor driver quality varies). XNNPACK support is present but disabled:
  enabling it segfaulted ONNX Runtime on the test device, so it stays off until it
  can be validated more widely.
- Columns are now inferred only from rows containing more than one fragment. A
  full-width header line — a title, an address, `Invoice No: …` — used to span the
  gutter between columns and collapse the table underneath it.
- Rows are grouped by vertical centre rather than top edge, so a row mixing a large
  heading with small print stays a single row.
- Scan settings are restored even when the previous session had no recognized text.

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
