package com.tsm.ocrx.ocr

/**
 * Character- and word-error rates between recognized text and a known-correct
 * reference.
 *
 * Every accuracy change in this app — a new recognition model, deskewing, mixed-script
 * routing, a correction pass — is otherwise a guess. CER is the standard OCR metric
 * precisely because it is unforgiving and comparable: it counts the single-character
 * edits needed to turn the output into the truth, divided by the truth's length, so
 * 0.0 is perfect and 1.0 is worthless.
 *
 * Used by the instrumented accuracy harness (`OcrAccuracyTest`) against a golden set
 * of images, and unit-tested directly on the metric itself.
 */
object Accuracy {

    /**
     * @param cer   character error rate, 0.0 = perfect. May exceed 1.0 when the
     *              output is much longer than the reference (runaway insertions).
     * @param wer   word error rate over whitespace-separated tokens.
     * @param referenceChars length of the reference, so scores can be pooled by
     *              weight across a corpus instead of naively averaged.
     */
    data class Score(
        val cer: Double,
        val wer: Double,
        val referenceChars: Int,
        val referenceWords: Int
    )

    /**
     * How text is normalized before comparison. OCR output legitimately differs from
     * a reference in ways that are not recognition errors — the layout engine emits
     * tabs where the reference has spaces, and line breaks depend on the crop.
     */
    data class Normalization(
        val collapseWhitespace: Boolean = true,
        val ignoreCase: Boolean = false
    )

    fun score(
        recognized: String,
        reference: String,
        normalization: Normalization = Normalization()
    ): Score {
        val actual = normalize(recognized, normalization)
        val expected = normalize(reference, normalization)

        val referenceChars = expected.length
        val cer = if (referenceChars == 0) {
            if (actual.isEmpty()) 0.0 else 1.0
        } else {
            levenshtein(actual.toList(), expected.toList()).toDouble() / referenceChars
        }

        val actualWords = actual.split(' ').filter { it.isNotEmpty() }
        val expectedWords = expected.split(' ').filter { it.isNotEmpty() }
        val wer = if (expectedWords.isEmpty()) {
            if (actualWords.isEmpty()) 0.0 else 1.0
        } else {
            levenshtein(actualWords, expectedWords).toDouble() / expectedWords.size
        }

        return Score(cer, wer, referenceChars, expectedWords.size)
    }

    /** Pools scores by reference length, which is the correct way to average CER. */
    fun pooled(scores: List<Score>): Score {
        val chars = scores.sumOf { it.referenceChars }
        val words = scores.sumOf { it.referenceWords }
        if (chars == 0) return Score(0.0, 0.0, 0, 0)
        val cer = scores.sumOf { it.cer * it.referenceChars } / chars
        val wer = if (words == 0) 0.0 else scores.sumOf { it.wer * it.referenceWords } / words
        return Score(cer, wer, chars, words)
    }

    private fun normalize(text: String, normalization: Normalization): String {
        var result = text
        if (normalization.collapseWhitespace) {
            result = result.replace(Regex("\\s+"), " ").trim()
        }
        if (normalization.ignoreCase) result = result.lowercase()
        return result
    }

    /**
     * Levenshtein distance over any token type, using two rolling rows rather than a
     * full matrix so a page of text does not allocate megabytes.
     */
    private fun <T> levenshtein(actual: List<T>, expected: List<T>): Int {
        if (actual.isEmpty()) return expected.size
        if (expected.isEmpty()) return actual.size

        var previous = IntArray(expected.size + 1) { it }
        var current = IntArray(expected.size + 1)
        for (i in 1..actual.size) {
            current[0] = i
            for (j in 1..expected.size) {
                val substitution = previous[j - 1] + if (actual[i - 1] == expected[j - 1]) 0 else 1
                val deletion = previous[j] + 1
                val insertion = current[j - 1] + 1
                current[j] = minOf(substitution, deletion, insertion)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[expected.size]
    }
}
