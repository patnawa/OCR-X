package com.tsm.ocrx.export

import com.tsm.ocrx.model.OcrResult
import java.io.OutputStream

/** Supported export formats and their file/MIME metadata. */
enum class ExportFormat(
    val label: String,
    val extension: String,
    val mimeType: String
) {
    CSV("CSV", "csv", "text/csv"),
    XLSX("Excel", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    PDF("PDF", "pdf", "application/pdf"),
    JSON("JSON", "json", "application/json"),
    TXT("Text", "txt", "text/plain");

    fun defaultFileName(): String = "ocr-x-export.$extension"
}

/** Serializes an [OcrResult] into the requested [ExportFormat]. */
object Exporters {

    fun write(format: ExportFormat, result: OcrResult, out: OutputStream) {
        when (format) {
            ExportFormat.CSV -> writeCsv(result, out)
            ExportFormat.JSON -> writeJson(result, out)
            ExportFormat.XLSX -> XlsxWriter.write(out, "OCR-X", padded(result))
            ExportFormat.TXT -> writeTxt(result, out)
            ExportFormat.PDF -> PdfExporter.write(result, out)
        }
    }

    private fun writeTxt(result: OcrResult, out: OutputStream) {
        out.write(0xEF); out.write(0xBB); out.write(0xBF) // UTF-8 BOM
        out.write(result.rawText.toByteArray(Charsets.UTF_8))
    }

    /** Pads every row to the widest column count for a rectangular grid. */
    private fun padded(result: OcrResult): List<List<String>> {
        val width = result.columnCount
        return result.rows.map { row ->
            if (row.size == width) row else row + List(width - row.size) { "" }
        }
    }

    private fun writeCsv(result: OcrResult, out: OutputStream) {
        val sb = StringBuilder()
        for (row in padded(result)) {
            sb.append(row.joinToString(",") { csvCell(it) })
            sb.append("\r\n")
        }
        // UTF-8 BOM so Excel detects encoding and shows non-ASCII correctly.
        out.write(0xEF); out.write(0xBB); out.write(0xBF)
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun csvCell(value: String): String {
        val needsQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuote) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }

    private fun writeJson(result: OcrResult, out: OutputStream) {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"generatedBy\": \"OCR-X\",\n")
        sb.append("  \"columnCount\": ").append(result.columnCount).append(",\n")
        sb.append("  \"rowCount\": ").append(result.rows.size).append(",\n")
        sb.append("  \"rows\": [\n")
        result.rows.forEachIndexed { i, row ->
            sb.append("    [")
            sb.append(row.joinToString(", ") { jsonString(it) })
            sb.append("]")
            if (i != result.rows.lastIndex) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ],\n")
        sb.append("  \"rawText\": ").append(jsonString(result.rawText)).append("\n")
        sb.append("}\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        for (ch in value) when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
        }
        append('"')
    }
}
