// Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.paddle.ocr.engine

/**
 * Decides which text lines are recognized together.
 *
 * A recognition batch is one tensor, so every crop in it is padded out to the widest
 * crop present. Batching lines in reading order therefore wastes most of the batch:
 * a receipt row pairs a full-width description with a four-character amount, and the
 * amount ends up costing as much to recognize as the description. Grouping by width
 * instead keeps the padding small, which is what makes batching worth doing at all.
 *
 * Batches are also capped by total padded width, not just by count. Width ordering
 * concentrates the widest lines into the same batch, and a batch of eight 3200px
 * crops is a far larger tensor than eight average ones — the cap keeps the peak
 * allocation bounded regardless of how the page is shaped.
 */
object RecBatchPlanner {

    /**
     * Groups indices into [inputWidths] into batches, widest-together, preserving the
     * caller's order among equal widths.
     *
     * @param inputWidths    each line's width in the batch tensor, in pixels at the
     *                       recognizer's fixed input height.
     * @param maxBatchSize   most lines in one batch.
     * @param maxPaddedWidth budget for `batch size × widest crop`. A line wider than
     *                       the whole budget still runs, alone.
     */
    fun plan(inputWidths: List<Int>, maxBatchSize: Int, maxPaddedWidth: Int): List<List<Int>> {
        val sizeCap = maxBatchSize.coerceAtLeast(1)
        val widthCap = maxPaddedWidth.coerceAtLeast(1)

        val batches = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        var widest = 0

        for (index in inputWidths.indices.sortedBy { inputWidths[it] }) {
            val width = inputWidths[index]
            val full = current.size >= sizeCap
            val overBudget = (current.size + 1) * maxOf(widest, width) > widthCap
            if (current.isNotEmpty() && (full || overBudget)) {
                batches.add(current)
                current = mutableListOf()
                widest = 0
            }
            current.add(index)
            widest = maxOf(widest, width)
        }
        if (current.isNotEmpty()) batches.add(current)
        return batches
    }
}
