package com.tsm.ocrx.export

import com.tsm.ocrx.ocr.Accuracy
import com.tsm.ocrx.ocr.OcrLanguage
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * One scanned page packaged as a golden test case: the straightened, scan-resolution
 * image the recognizer actually saw, and the text as the user corrected it.
 *
 * @param index    1-based page number in the session, for display only.
 * @param name     file stem shared by the case's `.jpg`, `.txt` and `.lang`.
 * @param image    the cached scan JPEG, already straightened.
 * @param text     the page's current (corrected) text, tab-delimited columns.
 * @param language recognition language the scan was made with.
 * @param ocrText  what the engine produced before any edit, or null if unknown.
 */
data class CorpusCase(
    val index: Int,
    val name: String,
    val image: File,
    val text: String,
    val language: OcrLanguage,
    val ocrText: String?
) {
    /** True when the user changed the recognized text — the case carries a correction. */
    val edited: Boolean get() = ocrText != null && ocrText != text

    val lineCount: Int get() = text.lines().count { it.isNotBlank() }

    /**
     * Character error rate of the raw recognition against the corrected text, or null
     * when the raw output is unknown. This is the page's real, on-device accuracy —
     * the number every threshold in the pipeline has so far been tuned without.
     */
    val rawCer: Double? get() = ocrText?.let { Accuracy.score(it, text).cer }
}

/**
 * Writes scanned pages and their corrected text as the golden corpus that the
 * instrumented accuracy harness (`OcrAccuracyTest`) reads.
 *
 * The app already has everything the harness needs — the exact bitmap handed to the
 * recognizer is cached for the row inspector, and every edit the user makes is a
 * correction of the engine's output. This turns those into `<case>.jpg`, `.txt` and
 * `.lang` triples, so the accuracy of every heuristic can be measured on real pages
 * instead of guessed.
 *
 * The archive holds the documents themselves. Export is explicit and goes wherever
 * the user chooses; nothing is collected or sent automatically.
 */
object CorpusExporter {

    /** Directory inside the archive that mirrors `androidTest/assets/golden/`. */
    const val GOLDEN_DIR = "golden"
    const val MANIFEST = "manifest.tsv"
    const val README = "README.txt"
    const val MIME_TYPE = "application/zip"

    private val STAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /** Stem for a page's files: the session's start time plus a number unique to the page. */
    fun caseName(sessionTime: Long, pageNumber: Int): String =
        "${STAMP.format(Date(sessionTime))}-p${"%02d".format(pageNumber)}"

    fun defaultFileName(sessionTime: Long): String =
        "ocr-x-corpus-${STAMP.format(Date(sessionTime))}.zip"

    fun write(cases: List<CorpusCase>, out: OutputStream, now: Long = System.currentTimeMillis()) {
        ZipOutputStream(out).use { zip ->
            zip.putText(README, readme(now))
            zip.putText(MANIFEST, manifest(cases))
            for (case in cases) {
                zip.putNextEntry(ZipEntry("$GOLDEN_DIR/${case.name}.jpg"))
                case.image.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                // No BOM: the harness reads the file verbatim as the expected text.
                zip.putText("$GOLDEN_DIR/${case.name}.txt", case.text.trim() + "\n")
                zip.putText("$GOLDEN_DIR/${case.name}.lang", case.language.name + "\n")
            }
        }
    }

    private fun ZipOutputStream.putText(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    /** One row per case: what it is, whether it was corrected, and how wrong the raw scan was. */
    private fun manifest(cases: List<CorpusCase>): String = buildString {
        appendLine("case\tlanguage\tlines\tedited\traw_cer\tchars")
        for (c in cases) {
            append(c.name).append('\t')
            append(c.language.name).append('\t')
            append(c.lineCount).append('\t')
            append(if (c.edited) "yes" else "no").append('\t')
            append(c.rawCer?.let { "%.4f".format(Locale.US, it) } ?: "").append('\t')
            append(c.text.trim().length)
            appendLine()
        }
    }

    private fun readme(now: Long): String = """
        OCR-X accuracy corpus — exported ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(now))}

        Each case is the straightened, scan-resolution image the recognizer actually
        saw, paired with the page text as corrected in the app:

          $GOLDEN_DIR/<case>.jpg   the image (JPEG; already straightened, so re-straightening is a no-op)
          $GOLDEN_DIR/<case>.txt   the correct text, one line per row, columns separated by tabs, UTF-8
          $GOLDEN_DIR/<case>.lang  the recognition language the scan was made with

        To measure the engine against these cases, copy the files in $GOLDEN_DIR/ into
        app/src/androidTest/assets/$GOLDEN_DIR/ and run:

          ./gradlew :app:connectedDebugAndroidTest

        OcrAccuracyTest logs per-case and pooled CER/WER, table structure and extracted
        fields under the log tag OcrAccuracy.

        $MANIFEST lists each case with whether its text was corrected in the app and
        the character error rate of the raw recognition against the corrected text.
        A case marked "no" was exported unchanged — include it only if you checked it.

        These files contain the scanned documents themselves. Share them only where
        the documents may be shared.
    """.trimIndent() + "\n"
}
