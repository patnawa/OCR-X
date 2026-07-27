package com.tsm.ocrx.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the CER/WER metric itself. The instrumented harness reports numbers
 * derived from this, so an error here would silently invalidate every accuracy claim
 * made about the engine.
 */
class AccuracyTest {

    @Test
    fun `identical text scores zero error`() {
        val score = Accuracy.score("INVOICE 12345", "INVOICE 12345")

        assertEquals(0.0, score.cer, 1e-9)
        assertEquals(0.0, score.wer, 1e-9)
    }

    @Test
    fun `one wrong character in ten is a tenth of a character error rate`() {
        val score = Accuracy.score("0123456788", "0123456789")

        assertEquals(0.1, score.cer, 1e-9)
        assertEquals(1.0, score.wer, 1e-9)   // the single token is wrong
    }

    @Test
    fun `counts insertions and deletions, not just substitutions`() {
        assertEquals(0.25, Accuracy.score("ABCDE", "ABCD").cer, 1e-9)   // 1 insertion / 4
        assertEquals(0.25, Accuracy.score("ABC", "ABCD").cer, 1e-9)     // 1 deletion / 4
    }

    @Test
    fun `whitespace differences are normalized away by default`() {
        // The layout engine emits tabs where a reference has single spaces; that is a
        // formatting difference, not a recognition error.
        val score = Accuracy.score("Bolt\t12.00", "Bolt 12.00")

        assertEquals(0.0, score.cer, 1e-9)
    }

    @Test
    fun `case sensitivity is configurable`() {
        val sensitive = Accuracy.score("acme", "ACME")
        val insensitive = Accuracy.score(
            "acme", "ACME", Accuracy.Normalization(ignoreCase = true)
        )

        assertTrue(sensitive.cer > 0.0)
        assertEquals(0.0, insensitive.cer, 1e-9)
    }

    @Test
    fun `empty output against a real reference is total failure`() {
        assertEquals(1.0, Accuracy.score("", "ABCD").cer, 1e-9)
    }

    @Test
    fun `pooling weights each document by its reference length`() {
        // A 1-char document at 100% error must not outweigh a 99-char one at 0%.
        val short = Accuracy.score("x", "y")                       // cer 1.0 over 1 char
        val long = Accuracy.score("a".repeat(99), "a".repeat(99))  // cer 0.0 over 99 chars

        val pooled = Accuracy.pooled(listOf(short, long))

        assertEquals(0.01, pooled.cer, 1e-9)
        assertEquals(100, pooled.referenceChars)
    }
}
