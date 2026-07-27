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

import android.content.Context
import android.graphics.Bitmap
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.model.ModelConfig
import com.paddle.ocr.model.OCRBox
import com.paddle.ocr.model.OCRError
import com.paddle.ocr.model.OCRResult
import com.paddle.ocr.postprocess.BoxSorter
import com.paddle.ocr.postprocess.QuadTextCrop
import com.paddle.ocr.preprocess.RecPreprocessor
import com.paddle.ocr.util.BitmapUtils
import kotlin.math.hypot
import kotlin.math.max

class OCREngine(
    context: Context,
    private val config: PaddleOCRConfig,
    engineConfig: EngineConfig,
    detModelAsset: String = "models/det/inference.onnx",
    recModelAsset: String = "models/rec/inference.onnx",
    recConfigAsset: String = "models/rec/inference.yml",
    recModelAsset2: String? = null,
    recConfigAsset2: String? = null,
) {
    private val ortManager = ORTSessionManager(context, engineConfig)
    private val detectionEngine: DetectionEngine
    private val recognitionEngine: RecognitionEngine
    /** Optional second script's recognizer, sharing the same detection pass. */
    private val recognitionEngine2: RecognitionEngine?
    val coldLoadTimeMs: Long get() = ortManager.coldLoadTimeMs

    init {
        val configured = try {
            ortManager.loadModels(detModelAsset, recModelAsset)
            val recModelConfig = ModelConfig.parse(context, recConfigAsset)
            recModelConfig
        } catch (t: Throwable) {
            ortManager.release()
            throw t
        }
        val configured2 = if (recModelAsset2 != null && recConfigAsset2 != null) {
            try {
                ortManager.loadSecondaryRecognition(recModelAsset2)
                ModelConfig.parse(context, recConfigAsset2)
            } catch (t: Throwable) {
                ortManager.release()
                throw t
            }
        } else null
        detectionEngine = DetectionEngine(ortManager, config)
        recognitionEngine = RecognitionEngine(ortManager, configured.characterList)
        recognitionEngine2 = configured2?.let {
            RecognitionEngine(ortManager, it.characterList, secondary = true)
        }
    }

    fun run(bitmap: Bitmap): OCREngineResult {
        val srcMat = BitmapUtils.bitmapToBGRMat(bitmap)
        return runWithOwnedMat(srcMat)
    }

    fun run(imageBytes: ByteArray): OCREngineResult {
        val srcMat = BitmapUtils.imdecodeBGR(imageBytes)
        if (srcMat.empty()) {
            srcMat.release()
            throw OCRError.InvalidImage()
        }
        return runWithOwnedMat(srcMat)
    }

    private fun runWithOwnedMat(srcMat: org.opencv.core.Mat): OCREngineResult {
        return try {
            run(srcMat)
        } finally {
            srcMat.release()
        }
    }

    private fun run(srcMat: org.opencv.core.Mat): OCREngineResult {
        val totalStart = System.currentTimeMillis()
        val detResult = detectionEngine.detect(srcMat)
        val boxes = detResult.boxes

        if (boxes.isEmpty()) {
            val elapsed = System.currentTimeMillis() - totalStart
            return OCREngineResult(
                results = emptyList(),
                detectionTimeMs = detResult.timeMs,
                recognitionTimeMs = 0,
                totalTimeMs = elapsed,
                lineCount = 0,
                detPreprocessMs = detResult.preprocessMs,
                detInferenceMs = detResult.inferenceMs,
                detPostprocessMs = detResult.postprocessMs,
                detInputShape = detResult.inputShape,
                coldLoadTimeMs = ortManager.coldLoadTimeMs,
            )
        }

        // 2. Sort boxes
        val sortedBoxes = BoxSorter.sortInReadingOrder(boxes)

        // 3. Crop and recognize text regions
        var totalRecPreMs = 0L
        var totalRecInfMs = 0L
        var totalRecPostMs = 0L
        var totalRecMs = 0L
        val recInputShapes = mutableListOf<List<Int>>()
        val batchSize = config.recBatchSize.coerceAtLeast(1)

        // Batch by crop shape rather than by reading order — see [RecBatchPlanner].
        // The widths are predicted from the box geometry instead of the crops, so only
        // one batch of crops is ever resident; a box whose predicted width is slightly
        // off just lands in a neighbouring batch, which costs a little padding and
        // nothing else.
        val plan = RecBatchPlanner.plan(
            inputWidths = sortedBoxes.map { RecPreprocessor.inputWidthFor(croppedAspectRatio(it)) },
            maxBatchSize = batchSize,
            maxPaddedWidth = config.recMaxBatchPaddedWidth,
        )

        // Recognition order is an internal detail: readings go back into reading-order
        // slots so the returned list stays in the order the caller expects.
        val slots = arrayOfNulls<OCRResult>(sortedBoxes.size)
        val slotRecMs = arrayOfNulls<Long>(sortedBoxes.size)

        for (batch in plan) {
            val batchCrops = mutableListOf<org.opencv.core.Mat>()
            val batchBoxIndices = mutableListOf<Int>()
            for (boxIdx in batch) {
                val crop = QuadTextCrop.crop(srcMat, sortedBoxes[boxIdx])
                if (crop.rows() > 0 && crop.cols() > 0) {
                    batchCrops.add(crop)
                    batchBoxIndices.add(boxIdx)
                } else {
                    crop.release()
                }
            }
            if (batchCrops.isEmpty()) continue

            try {
                val batchResult = recognitionEngine.recognize(batchCrops)
                totalRecPreMs += batchResult.preprocessMs
                totalRecInfMs += batchResult.inferenceMs
                totalRecPostMs += batchResult.postprocessMs
                totalRecMs += batchResult.timeMs
                recInputShapes.add(batchResult.inputShape)
                if (batchSize == 1) {
                    batchBoxIndices.firstOrNull()?.let { slotRecMs[it] = batchResult.timeMs }
                }

                // Second script's reading of the very same crops. Detection is
                // script-agnostic, so only recognition is repeated — and only for the
                // lines the primary model was unsure of, which is where a script
                // mismatch actually shows up.
                val uncertain = batchResult.texts.indices.filter {
                    batchResult.texts[it].second < config.recSecondaryMaxConf
                }
                val alternates = HashMap<Int, Pair<String, Float>>()
                if (recognitionEngine2 != null && uncertain.isNotEmpty()) {
                    val altResult = recognitionEngine2.recognize(uncertain.map { batchCrops[it] })
                    totalRecPreMs += altResult.preprocessMs
                    totalRecInfMs += altResult.inferenceMs
                    totalRecPostMs += altResult.postprocessMs
                    totalRecMs += altResult.timeMs
                    uncertain.forEachIndexed { position, j ->
                        altResult.texts.getOrNull(position)?.let { alternates[j] = it }
                    }
                }

                for (j in batchResult.texts.indices) {
                    val boxIdx = batchBoxIndices[j]
                    val (text, confidence) = batchResult.texts[j]
                    val alt = alternates[j]
                    // A model whose dictionary lacks the script on this line
                    // decodes it as low-confidence noise, so the higher CTC score
                    // identifies the right script. SECONDARY_MARGIN keeps the
                    // primary model's reading unless the other one is clearly
                    // better, since scores from two models are only loosely
                    // comparable and near-ties should not flap.
                    val useAlt = alt != null &&
                        alt.first.isNotBlank() &&
                        alt.second > confidence + SECONDARY_MARGIN
                    val bestText = if (useAlt) alt!!.first else text
                    val bestConf = if (useAlt) alt!!.second else confidence
                    if (bestConf >= config.recScoreThresh && bestText.isNotBlank()) {
                        slots[boxIdx] = OCRResult(
                            box = sortedBoxes[boxIdx],
                            text = bestText,
                            confidence = bestConf,
                            fromSecondary = useAlt,
                        )
                    }
                }
            } finally {
                batchCrops.forEach { it.release() }
            }
        }

        val allResults = slots.filterNotNull()
        val perLineRecMs = slotRecMs.filterNotNull()

        val totalElapsed = System.currentTimeMillis() - totalStart
        val pipelineOverhead = totalElapsed - detResult.timeMs - totalRecMs

        return OCREngineResult(
            results = allResults,
            detectionTimeMs = detResult.timeMs,
            recognitionTimeMs = totalRecMs,
            totalTimeMs = totalElapsed,
            lineCount = allResults.size,
            detPreprocessMs = detResult.preprocessMs,
            detInferenceMs = detResult.inferenceMs,
            detPostprocessMs = detResult.postprocessMs,
            recPreprocessMs = totalRecPreMs,
            recInferenceMs = totalRecInfMs,
            recPostprocessMs = totalRecPostMs,
            pipelineOverheadMs = pipelineOverhead,
            coldLoadTimeMs = ortManager.coldLoadTimeMs,
            detInputShape = detResult.inputShape,
            recInputShapes = recInputShapes,
            perLineRecMs = perLineRecMs,
        )
    }

    fun release() {
        ortManager.release()
    }

    /**
     * Width-to-height ratio of the crop [box] will produce, which is what decides how
     * much of a recognition batch it occupies. A box taller than it is wide is rotated
     * upright by [QuadTextCrop], so its ratio inverts along with it.
     */
    private fun croppedAspectRatio(box: OCRBox): Double {
        val p = box.points
        val width = max(
            hypot(p[0].x - p[1].x, p[0].y - p[1].y),
            hypot(p[2].x - p[3].x, p[2].y - p[3].y),
        ).toDouble()
        val height = max(
            hypot(p[0].x - p[3].x, p[0].y - p[3].y),
            hypot(p[1].x - p[2].x, p[1].y - p[2].y),
        ).toDouble()
        if (width <= 0.0 || height <= 0.0) return 1.0
        return if (height / width >= QuadTextCrop.VERTICAL_CROP_RATIO) height / width
        else width / height
    }

    private companion object {
        /** How much better the secondary model must score to win a box. */
        const val SECONDARY_MARGIN = 0.05f
    }
}
