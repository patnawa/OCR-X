Golden accuracy corpus

Drop matched files here, one set per document:

  <name>.jpg   the page image
  <name>.txt   the exact correct text (tab-separated columns, one line per row)
  <name>.lang  optional: LATIN | THAI | CHINESE | JAPANESE | KOREAN (default LATIN)

The easiest source is the app itself: scan a page, correct its text in the row
inspector or the page editor, then tap "Save as test cases" under Export. The
resulting zip holds a golden/ folder in exactly this format, plus a manifest with
the raw scan's error rate against your correction. Copy the golden/ files here.

Then run:  ./gradlew :app:connectedDebugAndroidTest

OcrAccuracyTest logs per-image and pooled CER/WER, whether the table structure
(row and column counts) matched, and whether vendor/date/total were extracted
correctly, all under the tag OcrAccuracy.
With no images present the test skips, so a clean checkout stays green.

Images and text here are scanned documents: only commit sets that may be shared.
