package com.tsm.ocrx.export

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal, dependency-free writer for the Office Open XML (.xlsx) format.
 *
 * An .xlsx file is a ZIP archive of XML parts. We emit the smallest valid set
 * of parts and write every cell as an inline string, which sidesteps the shared
 * strings table entirely while remaining fully spec-compliant. Excel, Google
 * Sheets and LibreOffice all open the result.
 */
object XlsxWriter {

    fun write(out: OutputStream, sheetName: String, rows: List<List<String>>) {
        ZipOutputStream(out).use { zip ->
            zip.putPart("[Content_Types].xml", contentTypes())
            zip.putPart("_rels/.rels", rootRels())
            zip.putPart("xl/workbook.xml", workbook(sheetName))
            zip.putPart("xl/_rels/workbook.xml.rels", workbookRels())
            zip.putPart("xl/worksheets/sheet1.xml", sheet(rows))
        }
    }

    private fun ZipOutputStream.putPart(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun contentTypes() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
          <Default Extension="xml" ContentType="application/xml"/>
          <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
          <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
        </Types>
    """.trimIndent()

    private fun rootRels() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
        </Relationships>
    """.trimIndent()

    private fun workbook(sheetName: String) = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                  xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
          <sheets>
            <sheet name="${escape(sheetName.take(31))}" sheetId="1" r:id="rId1"/>
          </sheets>
        </workbook>
    """.trimIndent()

    private fun workbookRels() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
        </Relationships>
    """.trimIndent()

    private fun sheet(rows: List<List<String>>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        sb.append("<sheetData>")
        rows.forEachIndexed { rowIndex, cells ->
            val rowNum = rowIndex + 1
            sb.append("<row r=\"").append(rowNum).append("\">")
            cells.forEachIndexed { colIndex, value ->
                val ref = cellRef(colIndex, rowNum)
                sb.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                sb.append(escape(value))
                sb.append("</t></is></c>")
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    /** 0-based column + 1-based row -> A1-style reference, e.g. (0,1) -> "A1". */
    private fun cellRef(col: Int, row: Int): String {
        val name = StringBuilder()
        var c = col
        while (true) {
            name.insert(0, ('A' + (c % 26)))
            c = c / 26 - 1
            if (c < 0) break
        }
        return "$name$row"
    }

    private fun escape(s: String): String = buildString {
        for (ch in s) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> if (ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r') Unit else append(ch)
        }
    }
}
