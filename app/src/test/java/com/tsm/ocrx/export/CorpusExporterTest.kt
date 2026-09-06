package com.tsm.ocrx.export

import com.tsm.ocrx.ocr.OcrLanguage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Pins the contract between the in-app corpus export and the instrumented accuracy
 * harness: every case is a `.jpg` / `.txt` / `.lang` triple under `golden/` in exactly
 * the form `OcrAccuracyTest` reads, with the text verbatim and no BOM.
 */
class CorpusExporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)

    private fun case(
        index: Int,
        text: String,
        ocrText: String?,
        language: OcrLanguage = OcrLanguage.THAI
    ): CorpusCase {
        val image = tmp.newFile("page-$index.jpg").apply { writeBytes(jpegBytes) }
        return CorpusCase(
            index = index,
            name = CorpusExporter.caseName(SESSION, index),
            image = image,
            text = text,
            language = language,
            ocrText = ocrText
        )
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        return entries
    }

    @Test
    fun `writes each case as the jpg, txt and lang triple the harness reads`() {
        val corrected = "ร้านข้าวแกงภูเก็ต\nรวมทั้งสิ้น\t42.80"
        val raw = "ร้านข้าวแกงภูเก็ต\nรวมทั้งสิ้น\t42.8O"
        val out = ByteArrayOutputStream()

        CorpusExporter.write(listOf(case(1, corrected, raw)), out)
        val entries = unzip(out.toByteArray())

        val stem = "golden/20260906-134512-p01"
        assertArrayEquals(jpegBytes, entries["$stem.jpg"])
        // Verbatim text, trailing newline only, no BOM — the harness trims and compares.
        assertEquals("$corrected\n", entries["$stem.txt"]!!.toString(Charsets.UTF_8))
        assertEquals("THAI\n", entries["$stem.lang"]!!.toString(Charsets.UTF_8))
        assertTrue(entries.containsKey(CorpusExporter.README))
    }

    @Test
    fun `manifest records whether each case was corrected and the raw scan's error rate`() {
        val out = ByteArrayOutputStream()
        CorpusExporter.write(
            listOf(
                case(1, "TOTAL\t10.00", "TOTAL\t1O.00", OcrLanguage.LATIN),   // one char fixed
                case(2, "Nut M8\t8.00", "Nut M8\t8.00", OcrLanguage.LATIN),   // untouched
                case(3, "restored page", null, OcrLanguage.LATIN)              // raw unknown
            ),
            out
        )
        val manifest = unzip(out.toByteArray())[CorpusExporter.MANIFEST]!!
            .toString(Charsets.UTF_8).trim().lines()

        assertEquals("case\tlanguage\tlines\tedited\traw_cer\tchars", manifest[0])
        val rows = manifest.drop(1).map { it.split('\t') }
        assertEquals(3, rows.size)

        assertEquals("20260906-134512-p01", rows[0][0])
        assertEquals("LATIN", rows[0][1])
        assertEquals("yes", rows[0][3])
        // "TOTAL 10.00" is 11 chars after whitespace collapse; one substitution.
        assertEquals("%.4f".format(java.util.Locale.US, 1.0 / 11), rows[0][4])

        assertEquals("no", rows[1][3])
        assertEquals("0.0000", rows[1][4])

        assertEquals("no", rows[2][3])
        assertEquals("", rows[2][4])
    }

    @Test
    fun `a case is edited only when the text differs from the raw recognition`() {
        assertTrue(case(1, "fixed", "fixxed").edited)
        assertFalse(case(2, "same", "same").edited)
        val restored = case(3, "unknown origin", null)
        assertFalse(restored.edited)
        assertNull(restored.rawCer)
    }

    @Test
    fun `case and archive names carry the session time and page number`() {
        assertEquals("20260906-134512-p07", CorpusExporter.caseName(SESSION, 7))
        assertEquals("ocr-x-corpus-20260906-134512.zip", CorpusExporter.defaultFileName(SESSION))
    }

    private companion object {
        /** 2026-09-06 13:45:12 local time. */
        val SESSION: Long = java.util.GregorianCalendar(2026, 8, 6, 13, 45, 12).timeInMillis
    }
}
