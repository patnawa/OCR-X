package com.tsm.ocrx.ocr

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Where a recognition model's two files live, in a form the engine can open. */
data class ModelPaths(
    val model: String,
    val config: String
)

/** Outcome of a download attempt, so callers can show a specific message. */
sealed interface DownloadResult {
    data object Success : DownloadResult
    data class Failed(val message: String) : DownloadResult
}

/**
 * Resolves PP-OCR recognition models from either the APK assets or the app's private
 * storage, and downloads the ones that are not bundled.
 *
 * Only the Latin and Thai models ship inside the APK. Chinese/Japanese and Korean are
 * fetched on first use, which keeps ~30 MB out of the install. Every download is
 * verified against a SHA-256 recorded in [RecModel] and staged through a temporary
 * file, so an interrupted transfer can never leave a half-written model that the ONNX
 * runtime would fail on later.
 */
object OcrModelStore {

    private const val MODELS_DIR = "ocr-models"
    private const val MODEL_FILE = "inference.onnx"
    private const val CONFIG_FILE = "inference.yml"
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 60_000

    private fun dirFor(context: Context, model: RecModel): File =
        File(File(context.filesDir, MODELS_DIR), model.key)

    /** True when the model can be loaded right now, bundled or already downloaded. */
    fun isInstalled(context: Context, model: RecModel): Boolean = resolve(context, model) != null

    /** True when the user has downloaded this model (as opposed to it being bundled). */
    fun isDownloaded(context: Context, model: RecModel): Boolean {
        val dir = dirFor(context, model)
        return File(dir, MODEL_FILE).isFile && File(dir, CONFIG_FILE).isFile
    }

    /** Bytes this model occupies in app storage, or 0 when it is not downloaded. */
    fun downloadedBytes(context: Context, model: RecModel): Long {
        val dir = dirFor(context, model)
        return File(dir, MODEL_FILE).length() + File(dir, CONFIG_FILE).length()
    }

    /**
     * Paths for [model], or null when it still needs downloading. A downloaded model
     * yields absolute filesystem paths and a bundled one yields asset paths; the
     * engine distinguishes them by the leading slash.
     */
    fun resolve(context: Context, model: RecModel): ModelPaths? {
        if (isDownloaded(context, model)) {
            val dir = dirFor(context, model)
            return ModelPaths(File(dir, MODEL_FILE).absolutePath, File(dir, CONFIG_FILE).absolutePath)
        }
        val assetDir = model.bundledAssetDir ?: return null
        return ModelPaths("$assetDir/$MODEL_FILE", "$assetDir/$CONFIG_FILE")
    }

    /** Deletes a downloaded model. Bundled models are left alone. */
    fun delete(context: Context, model: RecModel): Boolean {
        val dir = dirFor(context, model)
        val ok = File(dir, MODEL_FILE).delete() or File(dir, CONFIG_FILE).delete()
        dir.delete()
        return ok
    }

    /**
     * Downloads [model]'s two files, reporting 0..1 progress weighted by their sizes.
     * Both files are fetched to `.part` siblings, checksum-verified, and only then
     * moved into place, so the model directory is never partially valid.
     */
    suspend fun download(
        context: Context,
        model: RecModel,
        onProgress: (Float) -> Unit = {}
    ): DownloadResult = withContext(Dispatchers.IO) {
        val dir = dirFor(context, model)
        if (!dir.isDirectory && !dir.mkdirs()) {
            return@withContext DownloadResult.Failed("Could not create the model folder.")
        }
        // The dictionary is a rounding error next to the graph, so weight progress by
        // the model file alone rather than showing a bar that jumps at the very end.
        val modelPart = File(dir, "$MODEL_FILE.part")
        val configPart = File(dir, "$CONFIG_FILE.part")
        try {
            fetch(model.modelUrl, modelPart, model.modelSha256) { onProgress(it * 0.98f) }
            fetch(model.configUrl, configPart, model.configSha256) { onProgress(0.98f + it * 0.02f) }

            if (!modelPart.renameTo(File(dir, MODEL_FILE))) error("Could not save the model file.")
            if (!configPart.renameTo(File(dir, CONFIG_FILE))) error("Could not save the dictionary.")
            onProgress(1f)
            DownloadResult.Success
        } catch (t: Throwable) {
            modelPart.delete()
            configPart.delete()
            DownloadResult.Failed(describe(t))
        }
    }

    /** Streams [url] into [target], hashing as it goes, and verifies [expectedSha256]. */
    private fun fetch(
        url: String,
        target: File,
        expectedSha256: String,
        onProgress: (Float) -> Unit
    ) {
        // HttpURLConnection silently stops following a redirect that switches
        // protocol (http <-> https), which these CDNs do, so redirects are followed
        // by hand. Each hop resolves against the URL that issued it, not the
        // original, so a relative Location lands on the right host.
        var current = URL(url)
        var connection = current.openConnection() as HttpURLConnection
        var redirects = 0
        while (true) {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "*/*")
            val code = connection.responseCode
            if (code !in listOf(301, 302, 303, 307, 308)) break
            val location = connection.getHeaderField("Location")
                ?: error("The download server sent an incomplete redirect.")
            connection.disconnect()
            if (++redirects > 5) error("The download server redirected too many times.")
            current = URL(current, location)
            connection = current.openConnection() as HttpURLConnection
        }

        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                error("The download server returned ${connection.responseCode}.")
            }
            val total = connection.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        if (total > 0) onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                error("The downloaded file did not match its checksum.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun describe(t: Throwable): String = when (t) {
        is java.net.UnknownHostException ->
            "No internet connection. Connect once to download this language, then it works offline."
        is java.net.SocketTimeoutException -> "The download timed out. Check your connection and retry."
        else -> t.message ?: "The download failed."
    }
}
