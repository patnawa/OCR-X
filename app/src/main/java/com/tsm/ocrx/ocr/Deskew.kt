package com.tsm.ocrx.ocr

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Straightens a photographed page before detection.
 *
 * Two corrections, tried in order:
 *
 * 1. **Perspective** — if a convincing quadrilateral page outline is found, the page
 *    is warped to a flat rectangle. This is the dominant failure mode of hand-held
 *    document photos: a receipt shot from an angle has converging edges, so its text
 *    lines are neither horizontal nor parallel.
 * 2. **Rotation** — otherwise the dominant text angle is estimated and the whole
 *    image is rotated flat.
 *
 * Both matter beyond raw recognition: [Layout] finds columns from vertical whitespace
 * gutters, and even a couple of degrees of tilt smears those gutters until the columns
 * merge. Every step is conservative — anything that does not look like a clear win is
 * skipped and the original bitmap is returned unchanged.
 */
object Deskew {

    /** Below this the tilt is not worth a resample. */
    private const val MIN_ROTATION_DEGREES = 0.35
    /** Above this we are almost certainly measuring something other than text lines. */
    private const val MAX_ROTATION_DEGREES = 20.0
    /** A page outline must cover at least this fraction of the frame to be believed. */
    private const val MIN_PAGE_AREA_RATIO = 0.35
    /** ...and no more than this, or it is just the image border. */
    private const val MAX_PAGE_AREA_RATIO = 0.99
    /** Longest edge used for contour/angle analysis; full resolution buys nothing. */
    private const val ANALYSIS_EDGE = 1024

    /**
     * Returns a straightened copy of [bitmap], or [bitmap] itself when no correction
     * is warranted. The caller keeps ownership of the input and must recycle the
     * result only when it is a different instance.
     */
    fun straighten(bitmap: Bitmap): Bitmap {
        // Straightening runs before the recognition engine is built, so it cannot
        // rely on engine construction having loaded OpenCV.
        if (!OpenCvLoader.isAvailable()) return bitmap
        val src = Mat()
        return try {
            Utils.bitmapToMat(bitmap, src)
            Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2BGR)
            warpToPage(src, bitmap) ?: rotateFlat(src, bitmap) ?: bitmap
        } catch (_: Throwable) {
            // Straightening is an optimisation, never a reason to fail a scan.
            bitmap
        } finally {
            src.release()
        }
    }

    // ---- 1. Perspective correction -----------------------------------------

    /** Finds a page-like quad and warps it flat, or null when none is convincing. */
    private fun warpToPage(src: Mat, original: Bitmap): Bitmap? {
        val quad = findPageQuad(src) ?: return null
        val ordered = orderCorners(quad)

        // Output size from the average of each pair of opposite sides, so the warp
        // does not squash the page toward whichever edge happened to be nearer.
        val widthTop = distance(ordered[0], ordered[1])
        val widthBottom = distance(ordered[3], ordered[2])
        val heightLeft = distance(ordered[0], ordered[3])
        val heightRight = distance(ordered[1], ordered[2])
        val width = ((widthTop + widthBottom) / 2).toInt()
        val height = ((heightLeft + heightRight) / 2).toInt()
        if (width < 32 || height < 32) return null

        val source = MatOfPoint2f(*ordered)
        val dest = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(width - 1.0, 0.0),
            Point(width - 1.0, height - 1.0),
            Point(0.0, height - 1.0)
        )
        val transform = Imgproc.getPerspectiveTransform(source, dest)
        val warped = Mat()
        return try {
            Imgproc.warpPerspective(
                src, warped, transform, Size(width.toDouble(), height.toDouble()),
                Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE, Scalar(255.0, 255.0, 255.0)
            )
            toBitmap(warped, original)
        } finally {
            source.release(); dest.release(); transform.release(); warped.release()
        }
    }

    private fun findPageQuad(src: Mat): Array<Point>? {
        val small = Mat()
        val gray = Mat()
        val edges = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        try {
            val scale = ANALYSIS_EDGE.toDouble() / maxOf(src.cols(), src.rows())
            if (scale < 1.0) {
                Imgproc.resize(src, small, Size(), scale, scale, Imgproc.INTER_AREA)
            } else {
                src.copyTo(small)
            }
            Imgproc.cvtColor(small, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(gray, edges, 60.0, 180.0)
            // Close small gaps so a page border broken by glare still forms one contour.
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)
            kernel.release()

            Imgproc.findContours(
                edges, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )
            val frameArea = (small.cols() * small.rows()).toDouble()
            var best: Array<Point>? = null
            var bestArea = 0.0
            for (contour in contours) {
                val curve = MatOfPoint2f(*contour.toArray())
                val approx = MatOfPoint2f()
                try {
                    val peri = Imgproc.arcLength(curve, true)
                    Imgproc.approxPolyDP(curve, approx, 0.02 * peri, true)
                    if (approx.total() != 4L) continue
                    val points = approx.toArray()
                    val area = abs(Imgproc.contourArea(approx))
                    val ratio = area / frameArea
                    if (ratio < MIN_PAGE_AREA_RATIO || ratio > MAX_PAGE_AREA_RATIO) continue
                    if (!Imgproc.isContourConvex(MatOfPoint(*points))) continue
                    if (area > bestArea) {
                        bestArea = area
                        best = points
                    }
                } finally {
                    curve.release(); approx.release()
                }
            }
            // Map back to full-resolution coordinates.
            val inverse = if (scale < 1.0) 1.0 / scale else 1.0
            return best?.map { Point(it.x * inverse, it.y * inverse) }?.toTypedArray()
        } finally {
            small.release(); gray.release(); edges.release(); hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    /** Orders four corners as top-left, top-right, bottom-right, bottom-left. */
    private fun orderCorners(points: Array<Point>): Array<Point> {
        // The sum x+y is smallest at the top-left and largest at the bottom-right;
        // the difference y-x separates the other two.
        val bySum = points.sortedBy { it.x + it.y }
        val byDiff = points.sortedBy { it.y - it.x }
        val topLeft = bySum.first()
        val bottomRight = bySum.last()
        val topRight = byDiff.first().takeIf { it != topLeft && it != bottomRight }
            ?: points.first { it != topLeft && it != bottomRight }
        val bottomLeft = points.first { it != topLeft && it != bottomRight && it != topRight }
        return arrayOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    private fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

    // ---- 2. Rotation correction --------------------------------------------

    /** Estimates the text tilt and rotates the image flat, or null when it is level. */
    private fun rotateFlat(src: Mat, original: Bitmap): Bitmap? {
        val angle = estimateSkewDegrees(src) ?: return null
        if (abs(angle) < MIN_ROTATION_DEGREES || abs(angle) > MAX_ROTATION_DEGREES) return null

        val centre = Point(src.cols() / 2.0, src.rows() / 2.0)
        val rotation = Imgproc.getRotationMatrix2D(centre, angle, 1.0)
        // Grow the canvas so rotation does not clip text near the corners.
        val radians = Math.toRadians(angle)
        val cosA = abs(cos(radians))
        val sinA = abs(sin(radians))
        val newWidth = (src.rows() * sinA + src.cols() * cosA).toInt()
        val newHeight = (src.rows() * cosA + src.cols() * sinA).toInt()
        rotation.put(0, 2, rotation.get(0, 2)[0] + (newWidth / 2.0 - centre.x))
        rotation.put(1, 2, rotation.get(1, 2)[0] + (newHeight / 2.0 - centre.y))

        val rotated = Mat()
        return try {
            Imgproc.warpAffine(
                src, rotated, rotation, Size(newWidth.toDouble(), newHeight.toDouble()),
                Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE, Scalar(255.0, 255.0, 255.0)
            )
            toBitmap(rotated, original)
        } finally {
            rotation.release(); rotated.release()
        }
    }

    /**
     * Dominant tilt of the text, in degrees, as the median angle of the text-line
     * blobs produced by a wide morphological close. The median resists the odd
     * vertical rule or logo that a mean would be dragged by.
     */
    private fun estimateSkewDegrees(src: Mat): Double? {
        val small = Mat()
        val gray = Mat()
        val binary = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        try {
            val scale = ANALYSIS_EDGE.toDouble() / maxOf(src.cols(), src.rows())
            if (scale < 1.0) {
                Imgproc.resize(src, small, Size(), scale, scale, Imgproc.INTER_AREA)
            } else {
                src.copyTo(small)
            }
            Imgproc.cvtColor(small, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.adaptiveThreshold(
                gray, binary, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV, 25, 12.0
            )
            // Smear characters together horizontally so each text line is one blob.
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(25.0, 3.0))
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel)
            kernel.release()

            Imgproc.findContours(
                binary, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )
            val angles = mutableListOf<Double>()
            for (contour in contours) {
                val points = MatOfPoint2f(*contour.toArray())
                try {
                    val rect = Imgproc.minAreaRect(points)
                    // Ignore blobs too small or too square to be a line of text.
                    val longSide = maxOf(rect.size.width, rect.size.height)
                    val shortSide = minOf(rect.size.width, rect.size.height)
                    if (longSide < 40 || shortSide < 1 || longSide / shortSide < 3) continue
                    val vertices = arrayOfNulls<Point>(4).also { rect.points(it) }
                    // Angle of the rectangle's long edge.
                    val a = vertices[0]!!
                    val b = vertices[1]!!
                    val c = vertices[2]!!
                    val (p, q) = if (distance(a, b) >= distance(b, c)) a to b else b to c
                    var degrees = Math.toDegrees(atan2(q.y - p.y, q.x - p.x))
                    while (degrees > 45) degrees -= 90
                    while (degrees < -45) degrees += 90
                    angles.add(degrees)
                } finally {
                    points.release()
                }
            }
            if (angles.size < 4) return null
            return angles.sorted()[angles.size / 2]
        } finally {
            small.release(); gray.release(); binary.release(); hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    // ---- shared -------------------------------------------------------------

    private fun toBitmap(bgr: Mat, template: Bitmap): Bitmap {
        val rgba = Mat()
        return try {
            Imgproc.cvtColor(bgr, rgba, Imgproc.COLOR_BGR2RGBA)
            val out = Bitmap.createBitmap(rgba.cols(), rgba.rows(), template.config ?: Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgba, out)
            out
        } finally {
            rgba.release()
        }
    }

    /** Kept for callers that want a plain grayscale Mat of a bitmap. */
    internal fun grayMatOf(bitmap: Bitmap): Mat {
        val rgba = Mat()
        val gray = Mat(bitmap.height, bitmap.width, CvType.CV_8UC1)
        return try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            gray
        } finally {
            rgba.release()
        }
    }
}
