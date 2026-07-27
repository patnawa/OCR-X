package com.tsm.ocrx.ocr

import android.graphics.Bitmap
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/**
 * Finds a table's printed ruling lines.
 *
 * [Layout] normally infers columns from vertical whitespace gutters, which is the
 * right call for receipts and any table without borders. But when a document *does*
 * draw its grid — invoices, forms, spec sheets — the drawn lines are ground truth:
 * they survive cells whose text nearly touches the neighbouring column, and they
 * place a column boundary correctly even where every row happens to leave that cell
 * empty. So when clear ruling is present we use it, and otherwise fall back to
 * whitespace.
 */
object TableRules {

    /**
     * Ruling line positions in image pixels.
     *
     * @param verticalX   x of each vertical rule, left to right.
     * @param horizontalY y of each horizontal rule, top to bottom.
     */
    data class Rules(
        val verticalX: List<Int>,
        val horizontalY: List<Int>
    )

    /** A rule must span this fraction of the content extent to count. */
    private const val MIN_SPAN_RATIO = 0.45
    /** Fewest rules in each direction before we believe this is a ruled table. */
    private const val MIN_VERTICAL_RULES = 3
    private const val MIN_HORIZONTAL_RULES = 3
    /** Rules nearer than this (px) are the two sides of one drawn line. */
    private const val MERGE_DISTANCE = 12

    /**
     * Returns the ruling lines of [bitmap], or null when the page is not a ruled
     * table. Any OpenCV failure also yields null, so the caller silently falls back
     * to whitespace gutters rather than failing the scan.
     */
    fun detect(bitmap: Bitmap): Rules? {
        if (!OpenCvLoader.isAvailable()) return null
        val gray = Deskew.grayMatOf(bitmap)
        val binary = Mat()
        val horizontal = Mat()
        val vertical = Mat()
        return try {
            // Invert so ink is white, which is what the morphology below expects.
            Imgproc.adaptiveThreshold(
                gray, binary, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C,
                Imgproc.THRESH_BINARY_INV, 15, 10.0
            )
            val width = binary.cols()
            val height = binary.rows()

            // A long thin kernel erases everything except lines running its way:
            // characters are far shorter than a rule in the rule's own direction.
            val hLength = max(16, (width * MIN_SPAN_RATIO / 2).toInt())
            val vLength = max(16, (height * MIN_SPAN_RATIO / 2).toInt())
            openWith(binary, horizontal, Size(hLength.toDouble(), 1.0))
            openWith(binary, vertical, Size(1.0, vLength.toDouble()))

            val verticalX = peaks(projectColumns(vertical), height * MIN_SPAN_RATIO)
            val horizontalY = peaks(projectRows(horizontal), width * MIN_SPAN_RATIO)

            if (verticalX.size < MIN_VERTICAL_RULES || horizontalY.size < MIN_HORIZONTAL_RULES) {
                null
            } else {
                Rules(verticalX, horizontalY)
            }
        } catch (_: Throwable) {
            null
        } finally {
            gray.release(); binary.release(); horizontal.release(); vertical.release()
        }
    }

    private fun openWith(src: Mat, dst: Mat, kernelSize: Size) {
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, kernelSize)
        try {
            Imgproc.erode(src, dst, kernel)
            Imgproc.dilate(dst, dst, kernel)
        } finally {
            kernel.release()
        }
    }

    /** Ink count per x, i.e. how tall the vertical rule at that x is. */
    private fun projectColumns(mat: Mat): IntArray {
        val sums = Mat()
        return try {
            Core.reduce(mat, sums, 0, Core.REDUCE_SUM, org.opencv.core.CvType.CV_32S)
            val row = IntArray(sums.cols())
            sums.get(0, 0, row)
            IntArray(row.size) { row[it] / 255 }
        } finally {
            sums.release()
        }
    }

    /** Ink count per y, i.e. how wide the horizontal rule at that y is. */
    private fun projectRows(mat: Mat): IntArray {
        val sums = Mat()
        return try {
            Core.reduce(mat, sums, 1, Core.REDUCE_SUM, org.opencv.core.CvType.CV_32S)
            val column = IntArray(sums.rows())
            sums.get(0, 0, column)
            IntArray(column.size) { column[it] / 255 }
        } finally {
            sums.release()
        }
    }

    /**
     * Centres of the runs where [projection] exceeds [threshold]. A drawn line is a
     * few pixels thick and often doubled, so nearby runs are merged into one rule.
     */
    private fun peaks(projection: IntArray, threshold: Double): List<Int> {
        val centres = mutableListOf<Int>()
        var runStart = -1
        for (i in projection.indices) {
            val above = projection[i] >= threshold
            if (above && runStart < 0) runStart = i
            if (!above && runStart >= 0) {
                centres.add((runStart + i - 1) / 2)
                runStart = -1
            }
        }
        if (runStart >= 0) centres.add((runStart + projection.size - 1) / 2)

        val merged = mutableListOf<Int>()
        for (centre in centres) {
            val previous = merged.lastOrNull()
            if (previous != null && centre - previous <= MERGE_DISTANCE) {
                merged[merged.size - 1] = (previous + centre) / 2
            } else {
                merged.add(centre)
            }
        }
        return merged
    }
}
