Golden accuracy corpus

Drop matched files here, one set per document:

  <name>.jpg   the page image
  <name>.txt   the exact correct text
  <name>.lang  optional: LATIN | THAI | CHINESE | JAPANESE | KOREAN (default LATIN)

Then run:  ./gradlew :app:connectedDebugAndroidTest

OcrAccuracyTest logs per-image and pooled CER/WER under the tag OcrAccuracy.
With no images present the test skips, so a clean checkout stays green.
