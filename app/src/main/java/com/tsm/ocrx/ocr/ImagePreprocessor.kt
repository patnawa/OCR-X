package com.tsm.ocrx.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Prepares a photo for OCR. Two entry points:
 *
 *  - [decodeOriented]: full-colour, EXIF-corrected bitmap capped to a long edge.
 *    Used by PP-OCRv6, which does its own preprocessing and expects natural colour.
 *  - [processForMlKit]: additionally normalizes resolution and applies grayscale +
 *    contrast, which helps ML Kit on low-quality photos.
 */
object ImagePreprocessor {

    private const val ML_LONG_EDGE_MIN = 1400
    private const val ML_LONG_EDGE_MAX = 2600
    private const val CONTRAST = 1.35f

    /** Decoded, upright, colour bitmap with its long edge capped at [maxLongEdge]. */
    fun decodeOriented(context: Context, uri: Uri, maxLongEdge: Int = 4000): Bitmap {
        val decoded = decodeSampled(context, uri, maxLongEdge * 2)
        val upright = applyExifOrientation(context, uri, decoded)
        return capLongEdge(upright, maxLongEdge)
    }

    /** Enhanced bitmap tuned for ML Kit: oriented, resolution-normalized, grayscale+contrast. */
    fun processForMlKit(context: Context, uri: Uri): Bitmap {
        val oriented = decodeOriented(context, uri, 4000)
        val sized = normalizeRange(oriented, ML_LONG_EDGE_MIN, ML_LONG_EDGE_MAX)
        return enhance(sized)
    }

    private fun decodeSampled(context: Context, uri: Uri, ceiling: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        val longest = max(bounds.outWidth, bounds.outHeight)
        while (longest / sample > ceiling) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: throw IllegalStateException("Could not read image")
    }

    private fun applyExifOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** Shrinks (never enlarges) so the long edge is at most [maxLongEdge]. */
    private fun capLongEdge(bitmap: Bitmap, maxLongEdge: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxLongEdge) return bitmap
        val ratio = maxLongEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
            (bitmap.height * ratio).roundToInt().coerceAtLeast(1),
            true
        )
    }

    /** Scales so the long edge falls within [min]..[max] (upscales small, shrinks large). */
    private fun normalizeRange(bitmap: Bitmap, min: Int, max: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        val target = longest.coerceIn(min, max)
        if (target == longest) return bitmap
        val ratio = target.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
            (bitmap.height * ratio).roundToInt().coerceAtLeast(1),
            true
        )
    }

    private fun enhance(bitmap: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val grayscale = ColorMatrix().apply { setSaturation(0f) }
        val t = (-0.5f * CONTRAST + 0.5f) * 255f
        val contrast = ColorMatrix(
            floatArrayOf(
                CONTRAST, 0f, 0f, 0f, t,
                0f, CONTRAST, 0f, 0f, t,
                0f, 0f, CONTRAST, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
        grayscale.postConcat(contrast)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(grayscale)
        }
        Canvas(out).drawBitmap(bitmap, 0f, 0f, paint)
        return out
    }
}
