package com.paddle.ocr.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecBatchPlannerTest {

    private val noWidthLimit = Int.MAX_VALUE

    @Test
    fun `every line lands in exactly one batch`() {
        val widths = listOf(300, 1200, 90, 640, 90, 2400, 150)

        val plan = RecBatchPlanner.plan(widths, maxBatchSize = 3, maxPaddedWidth = noWidthLimit)

        assertEquals(widths.indices.toList(), plan.flatten().sorted())
    }

    @Test
    fun `lines of similar width are batched together`() {
        // Interleaved wide and narrow, the shape a receipt row has: a full-width
        // description next to a short amount.
        val widths = listOf(1200, 90, 1150, 100, 1300, 80)

        val plan = RecBatchPlanner.plan(widths, maxBatchSize = 3, maxPaddedWidth = noWidthLimit)

        assertEquals(listOf(listOf(5, 1, 3), listOf(2, 0, 4)), plan)
    }

    @Test
    fun `batching by width keeps padding far below reading order`() {
        val widths = listOf(1200, 90, 1150, 100, 1300, 80)
        fun cost(batches: List<List<Int>>) =
            batches.sumOf { batch -> batch.size * batch.maxOf { widths[it] } }

        val planned = RecBatchPlanner.plan(widths, maxBatchSize = 3, maxPaddedWidth = noWidthLimit)
        val readingOrder = widths.indices.chunked(3)

        // Reading order pairs a 1200px line with an 80px one and pads both to 1300.
        assertEquals(3 * 1200 + 3 * 1300, cost(readingOrder))
        // By width, the three short lines pad to 100 instead.
        assertEquals(3 * 100 + 3 * 1300, cost(planned))
    }

    @Test
    fun `no batch exceeds the size cap`() {
        val widths = List(20) { 100 + it }

        val plan = RecBatchPlanner.plan(widths, maxBatchSize = 6, maxPaddedWidth = noWidthLimit)

        assertTrue(plan.all { it.size <= 6 })
        assertEquals(20, plan.sumOf { it.size })
    }

    @Test
    fun `wide lines are split into smaller batches to respect the width budget`() {
        val widths = List(8) { 3000 }

        val plan = RecBatchPlanner.plan(widths, maxBatchSize = 8, maxPaddedWidth = 5120)

        // 2 * 3000 = 6000 blows the budget, so these can only go one at a time.
        assertTrue(plan.all { it.size == 1 })
        assertEquals(8, plan.size)
    }

    @Test
    fun `a line wider than the whole budget still runs alone`() {
        val plan = RecBatchPlanner.plan(listOf(9000), maxBatchSize = 8, maxPaddedWidth = 5120)

        assertEquals(listOf(listOf(0)), plan)
    }

    @Test
    fun `equal widths keep their original order`() {
        val widths = List(6) { 200 }

        val plan = RecBatchPlanner.plan(widths, maxBatchSize = 2, maxPaddedWidth = noWidthLimit)

        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4, 5)), plan)
    }

    @Test
    fun `a batch size of one gives one line per batch`() {
        val plan = RecBatchPlanner.plan(listOf(500, 100, 300), maxBatchSize = 1, maxPaddedWidth = noWidthLimit)

        assertEquals(listOf(listOf(1), listOf(2), listOf(0)), plan)
    }

    @Test
    fun `no boxes means no batches`() {
        assertTrue(RecBatchPlanner.plan(emptyList(), maxBatchSize = 8, maxPaddedWidth = 5120).isEmpty())
    }
}
