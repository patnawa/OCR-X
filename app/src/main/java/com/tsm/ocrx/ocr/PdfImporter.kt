package com.tsm.ocrx.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * Turns a PDF into page images the scanner can read.
 *
 * Plenty of the documents worth extracting arrive as PDFs — emailed invoices,
 * statements, scans from an office copier — and re-photographing a screen to get them
 * into the app loses far more accuracy than rendering them directly. Rendering uses
 * the platform's own [PdfRenderer], so this costs no dependency and no APK size.
 *
 * Note this rasterises and then recognises rather than reading any embedded text
 * layer: it therefore works on scanned (image-only) PDFs, which are exactly the ones
 * users cannot already copy text out of.
 */
object PdfImporter {

    private const val IMPORT_DIR = "pdf-pages"
    /** Cap on pages taken from one document, to bound memory and scan time. */
    const val MAX_PAGES = 30

    /** A PDF page that failed to render is skipped, not fatal to the whole import. */
    data class Imported(
        val uris: List<Uri>,
        val totalPages: Int
    ) {
        /** Pages present in the file but not imported, because of [MAX_PAGES]. */
        val skipped: Int get() = (totalPages - uris.size).coerceAtLeast(0)
    }

    /**
     * Renders [uri]'s pages to cached JPEGs at roughly [targetLongEdge] pixels and
     * returns their URIs in page order.
     */
    suspend fun importPages(
        context: Context,
        uri: Uri,
        targetLongEdge: Int = 2048
    ): Imported = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, IMPORT_DIR).apply { mkdirs() }
        // Each import replaces the last, so a long session does not accumulate
        // hundreds of megabytes of rendered pages in the cache.
        dir.listFiles()?.forEach { it.delete() }

        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("Could not open that PDF.")
        val uris = mutableListOf<Uri>()
        var pageCount = 0
        descriptor.use { fd ->
            PdfRenderer(fd).use { renderer ->
                pageCount = renderer.pageCount
                if (pageCount == 0) throw IllegalStateException("That PDF has no pages.")
                for (index in 0 until minOf(pageCount, MAX_PAGES)) {
                    val file = File(dir, "page-%03d.jpg".format(index + 1))
                    if (renderPage(renderer, index, targetLongEdge, file)) {
                        uris.add(Uri.fromFile(file))
                    }
                }
            }
        }
        if (uris.isEmpty()) throw IllegalStateException("None of that PDF's pages could be rendered.")
        Imported(uris, pageCount)
    }

    private fun renderPage(
        renderer: PdfRenderer,
        index: Int,
        targetLongEdge: Int,
        target: File
    ): Boolean = try {
        renderer.openPage(index).use { page ->
            // PdfRenderer reports points (1/72"); scale so the long edge lands near
            // the scan resolution, which is where the recogniser performs best.
            val scale = targetLongEdge.toFloat() / max(page.width, page.height)
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                // PdfRenderer composites onto a transparent bitmap; without a white
                // fill, black text lands on black once the alpha is flattened to JPEG.
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            } finally {
                bitmap.recycle()
            }
        }
        true
    } catch (_: Throwable) {
        false
    }

    /** True when [uri] looks like a PDF, by MIME type or extension. */
    fun isPdf(context: Context, uri: Uri): Boolean {
        val type = try {
            context.contentResolver.getType(uri)
        } catch (_: Throwable) {
            null
        }
        return type == "application/pdf" ||
            uri.toString().substringBefore('?').endsWith(".pdf", ignoreCase = true)
    }

    /** Opens a PDF and reports its page count without rendering anything. */
    fun pageCount(context: Context, uri: Uri): Int = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { fd: ParcelFileDescriptor ->
            PdfRenderer(fd).use { it.pageCount }
        } ?: 0
    } catch (_: Throwable) {
        0
    }
}
