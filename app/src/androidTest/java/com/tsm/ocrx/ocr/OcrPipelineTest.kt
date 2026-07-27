package com.tsm.ocrx.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end tests of the real on-device pipeline: rendered page → deskew → ONNX
 * detection + recognition → table reconstruction → post-OCR correction.
 *
 * The pages are drawn here with Canvas rather than shipped as image assets, so the
 * expected text is known exactly and the tests are self-contained and deterministic.
 * That makes this the check that the whole chain actually works on a device — the
 * JVM unit tests can only cover the pure logic either side of the model.
 *
 * Run with: `./gradlew :app:connectedDebugAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class OcrPipelineTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val TAG = "OcrPipeline"
        /** Synthetic, perfectly rendered text should be read very accurately. */
        const val MAX_CER_CLEAN = 0.10
        /** A tilted page loses a little more, but must not collapse. */
        const val MAX_CER_ROTATED = 0.20
    }

    private val receiptLines = listOf(
        "ACME HARDWARE LTD",
        "Invoice No: INV-2024-0091",
        "Date: 14/03/2025"
    )

    /** Item, qty, price — laid out in columns with wide gutters. */
    private val receiptRows = listOf(
        Triple("Bolt M8", "2", "24.00"),
        Triple("Nut M8", "4", "8.00"),
        Triple("Washer", "10", "3.50"),
        Triple("TOTAL", "", "35.50")
    )

    @Test
    fun readsARenderedReceiptAccurately() = runBlocking {
        val bitmap = renderReceipt()
        try {
            val result = recognize(bitmap)
            Log.i(TAG, "clean scan:\n${result.text}")

            val score = Accuracy.score(result.text, expectedText())
            Log.i(TAG, "clean CER=%.4f WER=%.4f".format(score.cer, score.wer))
            assertTrue(
                "CER %.4f exceeded %.2f. Got:\n%s".format(score.cer, MAX_CER_CLEAN, result.text),
                score.cer <= MAX_CER_CLEAN
            )
            assertTrue("confidence should be reported", result.confidence.overall > 0.5f)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun rebuildsTheColumnsOfARenderedTable() = runBlocking {
        val bitmap = renderReceipt()
        try {
            val table = OcrEngine.parse(recognize(bitmap).text)
            Log.i(TAG, "columns=${table.columnCount} rows=${table.rows.size}")

            // The three-column body must come back as three columns, not one blob.
            assertTrue(
                "expected a multi-column table, got ${table.columnCount}",
                table.columnCount >= 3
            )
            val totalRow = table.rows.firstOrNull { it.any { cell -> cell.contains("35.5") } }
            assertTrue("the total row should survive as its own row: ${table.rows}", totalRow != null)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun straighteningRecoversATiltedPage() = runBlocking {
        val upright = renderReceipt()
        val tilted = rotate(upright, 5f)
        try {
            val straightened = Deskew.straighten(tilted)
            try {
                val score = Accuracy.score(
                    PaddleEngine.recognize(context, straightened).text,
                    expectedText()
                )
                Log.i(TAG, "tilted+straightened CER=%.4f".format(score.cer))
                assertTrue(
                    "CER %.4f on a 5° tilt exceeded %.2f".format(score.cer, MAX_CER_ROTATED),
                    score.cer <= MAX_CER_ROTATED
                )
            } finally {
                if (straightened !== tilted) straightened.recycle()
            }
        } finally {
            tilted.recycle()
            upright.recycle()
        }
    }

    @Test
    fun extractsDocumentFieldsFromTheRecognizedText() = runBlocking {
        val bitmap = renderReceipt()
        try {
            val fields = FieldExtractor.extract(recognize(bitmap).text)
            Log.i(TAG, "fields=$fields")

            assertEquals("2025-03-14", fields.documentDate)
            assertTrue("vendor should be the header line: ${fields.vendor}", fields.vendor != null)
            assertEquals(35.50, fields.total ?: 0.0, 0.01)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun importsAPdfAndReadsItsPages() = runBlocking {
        val pdf = writeReceiptPdf(pages = 2)
        try {
            val imported = PdfImporter.importPages(context, android.net.Uri.fromFile(pdf))
            assertEquals(2, imported.totalPages)
            assertEquals(2, imported.uris.size)

            // The rendered page must still be readable after the PDF round trip.
            val text = OcrEngine.recognize(context, imported.uris.first()).text
            Log.i(TAG, "pdf page 1:\n$text")
            assertTrue(
                "expected the vendor line in the imported page, got:\n$text",
                text.contains("ACME", ignoreCase = true)
            )
        } finally {
            pdf.delete()
        }
    }

    /**
     * Mixed-script routing on a page carrying both scripts.
     *
     * Measured on this device: the Thai PP-OCRv5 dictionary already covers Latin and
     * digits, so the Thai model alone reads "Bolt M8 QTY 2" perfectly — Thai receipts
     * are full of Latin, and the dictionary reflects that. The routing therefore does
     * not have to *rescue* the Latin lines here, and what this test pins is that it
     * does not damage them either: adding a second recogniser must never make a page
     * that already worked worse.
     *
     * The case routing genuinely fixes is the opposite one, covered by
     * [mixedScriptRescuesThaiWhenTheLanguageIsLeftOnLatin].
     */
    @Test
    fun mixedScriptDoesNotDegradeAPageThatAlreadyWorked() = runBlocking {
        val thaiLines = listOf("ร้านข้าวแกงภูเก็ต", "หมูปิ้งสูตรโบราณ")
        val latinLines = listOf("Bolt M8 QTY 2", "TOTAL 145.50")
        val bitmap = renderLines(thaiLines + latinLines)
        try {
            val expected = (thaiLines + latinLines).joinToString("\n")
            val mixed = PaddleEngine.recognize(
                context, bitmap, OcrLanguage.THAI, mixedScript = true
            ).text
            val thaiOnly = PaddleEngine.recognize(
                context, bitmap, OcrLanguage.THAI, mixedScript = false
            ).text
            val mixedCer = Accuracy.score(mixed, expected).cer
            val singleCer = Accuracy.score(thaiOnly, expected).cer
            Log.i(TAG, "thai page — CER mixed=%.4f single=%.4f".format(mixedCer, singleCer))
            Log.i(TAG, "mixed script ON:\n$mixed")

            assertTrue(
                "mixed-script CER %.4f must not be worse than %.4f".format(mixedCer, singleCer),
                mixedCer <= singleCer + 1e-6
            )
            assertTrue("Latin should read, got:\n$mixed", mixed.contains("Bolt", ignoreCase = true))
            assertTrue("Thai should read, got:\n$mixed", mixed.contains("ข้าวแกง"))
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * The failure this feature exists for: the user leaves the language on the default
     * (English/Latin) and scans a Thai page. The Latin model has no Thai glyphs, so it
     * returns transliterated noise — the exact bug that made Thai unusable before v1.2.
     * With mixed script on, the Thai model is paired in and rescues those lines.
     */
    @Test
    fun mixedScriptRescuesThaiWhenTheLanguageIsLeftOnLatin() = runBlocking {
        val thaiLines = listOf("ร้านข้าวแกงภูเก็ต", "หมูปิ้งสูตรโบราณ")
        val latinLines = listOf("Bolt M8 QTY 2", "TOTAL 145.50")
        val bitmap = renderLines(thaiLines + latinLines)
        try {
            val expected = (thaiLines + latinLines).joinToString("\n")
            val mixed = PaddleEngine.recognize(
                context, bitmap, OcrLanguage.LATIN, mixedScript = true
            ).text
            val latinOnly = PaddleEngine.recognize(
                context, bitmap, OcrLanguage.LATIN, mixedScript = false
            ).text
            val mixedCer = Accuracy.score(mixed, expected).cer
            val singleCer = Accuracy.score(latinOnly, expected).cer
            Log.i(TAG, "latin-selected — CER mixed=%.4f single=%.4f".format(mixedCer, singleCer))
            Log.i(TAG, "latin only:\n$latinOnly")
            Log.i(TAG, "latin + mixed:\n$mixed")

            assertTrue(
                "mixed-script CER %.4f should beat Latin-only %.4f".format(mixedCer, singleCer),
                mixedCer < singleCer
            )
            assertTrue("Thai should be rescued, got:\n$mixed", mixed.contains("ข้าวแกง"))
            assertTrue("Latin must survive, got:\n$mixed", mixed.contains("Bolt", ignoreCase = true))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun correctsMisreadDigitsAndChecksTheColumnTotal() {
        // Exercises the correction pass on the exact grid shape this pipeline emits.
        val grid = receiptRows.joinToString("\n") { (item, qty, price) -> "$item\t$qty\t$price" }
        val corrupted = grid.replace("24.00", "24.OO").replace("8.00", "8.0O")

        val result = TextCorrector.correct(corrupted)

        assertTrue("should have repaired the misread digits", result.corrections.size >= 2)
        assertTrue("the repaired grid should match the original", result.text == grid)
        assertTrue("35.50 is the true total, so no mismatch", result.failedSums.isEmpty())
    }

    /* ---- helpers ---- */

    private suspend fun recognize(bitmap: Bitmap) = PaddleEngine.recognize(context, bitmap)

    private fun expectedText(): String =
        (receiptLines + receiptRows.map { (a, b, c) -> "$a $b $c" }).joinToString("\n")

    /**
     * Draws a receipt at roughly the resolution a scan produces. Columns are placed at
     * fixed x positions with wide gaps so the geometry is unambiguous, which is what
     * lets the column assertions be strict.
     */
    private fun renderReceipt(width: Int = 1000, height: Int = 720): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textSize = 34f
        }

        var y = 80f
        receiptLines.forEach { line ->
            canvas.drawText(line, 60f, y, paint)
            y += 56f
        }
        y += 40f
        receiptRows.forEach { (item, qty, price) ->
            canvas.drawText(item, 60f, y, paint)
            if (qty.isNotEmpty()) canvas.drawText(qty, 480f, y, paint)
            canvas.drawText(price, 760f, y, paint)
            y += 60f
        }
        return bitmap
    }

    /** Renders plain left-aligned lines, one per row — used for the script tests. */
    private fun renderLines(lines: List<String>, width: Int = 900): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textSize = 44f
        }
        val height = 80 + lines.size * 76
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        var y = 80f
        lines.forEach { line ->
            canvas.drawText(line, 50f, y, paint)
            y += 76f
        }
        return bitmap
    }

    private fun rotate(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** Writes a real PDF containing the receipt, for the import round trip. */
    private fun writeReceiptPdf(pages: Int): File {
        val document = PdfDocument()
        val source = renderReceipt()
        try {
            repeat(pages) { index ->
                val info = PdfDocument.PageInfo.Builder(595, 842, index + 1).create()
                val page = document.startPage(info)
                page.canvas.drawColor(Color.WHITE)
                val scale = 575f / source.width
                page.canvas.save()
                page.canvas.translate(10f, 10f)
                page.canvas.scale(scale, scale)
                page.canvas.drawBitmap(source, 0f, 0f, null)
                page.canvas.restore()
                document.finishPage(page)
            }
            val file = File(context.cacheDir, "pipeline-test.pdf")
            file.outputStream().use { document.writeTo(it) }
            return file
        } finally {
            document.close()
            source.recycle()
        }
    }
}
