package com.tsm.ocrx.ocr

import android.os.Build

/**
 * Loads OpenCV's native library, once, before anything touches a `Mat`.
 *
 * The PaddleOCR SDK never calls `System.loadLibrary` itself — the upstream demo app
 * does it from its Application class — so any code path that reaches OpenCV first is
 * responsible for it. That now includes [Deskew] and [TableRules], which run *before*
 * the recognition engine is built, so loading cannot be left to engine construction.
 */
object OpenCvLoader {

    @Volatile
    private var loaded = false
    private val lock = Any()

    /**
     * Ensures the library is loaded, throwing a diagnosable error if it is not
     * available for this device's ABI. Repeat calls are free.
     */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            try {
                System.loadLibrary("opencv_java4")
                loaded = true
            } catch (e: Throwable) {
                val abis = Build.SUPPORTED_ABIS.joinToString(", ")
                throw IllegalStateException(
                    "OpenCV failed to load for this device (ABIs: $abis). ${e.message}", e
                )
            }
        }
    }

    /**
     * Loads the library for an optional pass, reporting whether it is usable. Callers
     * that can degrade gracefully — straightening, ruled-line detection — use this so
     * a load failure skips the enhancement instead of failing the scan.
     */
    fun isAvailable(): Boolean = try {
        ensureLoaded()
        true
    } catch (_: Throwable) {
        false
    }
}
