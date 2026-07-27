package com.tsm.ocrx.ocr

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real on-device engine over a golden image set and reports CER/WER.
 *
 * This is the harness that turns accuracy work from opinion into measurement: run it
 * before and after a change (deskewing, mixed-script routing, a different model) and
 * compare the pooled CER. Without it every such change is a guess.
 *
 * **Adding cases.** Drop matched pairs into `app/src/androidTest/assets/golden/`:
 *
 * ```
 * golden/
 *   thai-receipt-01.jpg
 *   thai-receipt-01.txt      # the exact correct text
 *   thai-receipt-01.lang     # optional: LATIN | THAI | CHINESE | JAPANESE | KOREAN
 * ```
 *
 * With no images present the test skips rather than fails, so a clean checkout stays
 * green — the harness is infrastructure, and the corpus is data you supply.
 *
 * Run with: `./gradlew :app:connectedDebugAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class OcrAccuracyTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val TAG = "OcrAccuracy"
        const val GOLDEN_DIR = "golden"
        /**
         * Pooled CER the corpus must stay under. Deliberately loose: this guards
         * against a regression that breaks recognition outright, not against the
         * few-percent movements that tuning produces. Tighten it once a real corpus
         * establishes the baseline.
         */
        const val MAX_POOLED_CER = 0.35
    }

    @Test
    fun goldenSetStaysWithinErrorBudget() {
        val cases = goldenCases()
        assumeTrue(
            "No golden images in androidTest/assets/$GOLDEN_DIR — nothing to measure.",
            cases.isNotEmpty()
        )

        val scores = mutableListOf<Accuracy.Score>()
        for (case in cases) {
            val recognized = recognize(case)
            val score = Accuracy.score(recognized, case.expected)
            scores.add(score)
            Log.i(
                TAG,
                "%-28s lang=%-8s CER=%.4f WER=%.4f (%d chars)".format(
                    case.name, case.language.name, score.cer, score.wer, score.referenceChars
                )
            )
        }

        val pooled = Accuracy.pooled(scores)
        Log.i(
            TAG,
            "POOLED over ${scores.size} images: CER=%.4f WER=%.4f".format(pooled.cer, pooled.wer)
        )
        assertTrue(
            "Pooled CER %.4f exceeded the %.2f budget".format(pooled.cer, MAX_POOLED_CER),
            pooled.cer <= MAX_POOLED_CER
        )
    }

    private fun recognize(case: GoldenCase): String = runBlocking {
        val bytes = context.assets.open("$GOLDEN_DIR/${case.imageAsset}").use { it.readBytes() }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("Could not decode ${case.imageAsset}")
        val straightened = Deskew.straighten(bitmap)
        try {
            PaddleEngine.recognize(
                context = context,
                bitmap = straightened,
                language = case.language,
                mixedScript = case.language.mixedScriptPartner
                    ?.let { OcrModelStore.isInstalled(context, it) } == true
            ).text
        } finally {
            if (straightened !== bitmap) straightened.recycle()
            bitmap.recycle()
        }
    }

    private data class GoldenCase(
        val name: String,
        val imageAsset: String,
        val expected: String,
        val language: OcrLanguage
    )

    /** Pairs every image in the golden directory with its `.txt` reference. */
    private fun goldenCases(): List<GoldenCase> {
        val files = try {
            context.assets.list(GOLDEN_DIR)?.toList().orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
        val images = files.filter {
            it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) || it.endsWith(".png", true)
        }
        return images.mapNotNull { image ->
            val base = image.substringBeforeLast('.')
            val expected = readAsset("$GOLDEN_DIR/$base.txt") ?: run {
                Log.w(TAG, "Skipping $image — no matching $base.txt reference")
                return@mapNotNull null
            }
            // Models the device does not have installed cannot be measured here.
            val language = readAsset("$GOLDEN_DIR/$base.lang")
                ?.trim()
                ?.let { name -> OcrLanguage.entries.firstOrNull { it.name.equals(name, true) } }
                ?: OcrLanguage.LATIN
            if (!OcrModelStore.isInstalled(context, language.model)) {
                Log.w(TAG, "Skipping $image — ${language.model.displayName} model not installed")
                return@mapNotNull null
            }
            GoldenCase(base, image, expected.trim(), language)
        }
    }

    private fun readAsset(path: String): String? = try {
        context.assets.open(path).bufferedReader().use { it.readText() }
    } catch (_: Throwable) {
        null
    }
}
