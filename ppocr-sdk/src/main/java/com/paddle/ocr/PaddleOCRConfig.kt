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

package com.paddle.ocr

/**
 * @param recMaxBatchPaddedWidth ceiling on `batch size × widest crop` for one
 *                               recognition batch, bounding the tensor a page of
 *                               unusually wide lines can allocate.
 * @param recSecondaryMaxConf    only lines the primary model read below this
 *                               confidence are re-read by the secondary script's
 *                               model. A line the primary is already sure of cannot
 *                               realistically be taken from it (see
 *                               `OCREngine.SECONDARY_MARGIN`), so re-reading it is
 *                               inference spent for nothing. Set to 1.0 to re-read
 *                               every line.
 */
data class PaddleOCRConfig(
    val detImgMode: String = "BGR",
    val detLimitSideLen: Int = 64,
    val detLimitType: String = "min",
    val detMaxSideLimit: Int = 4000,
    val detThresh: Float = 0.3f,
    val detBoxThresh: Float = 0.6f,
    val detUnclipRatio: Float = 1.5f,
    val detMaxCandidates: Int = 3000,
    val detUseDilation: Boolean = false,
    val detScoreMode: String = "fast",
    val detBoxType: String = "quad",
    val recScoreThresh: Float = 0.0f,
    val recBatchSize: Int = 1,
    val recMaxBatchPaddedWidth: Int = 5120,
    val recSecondaryMaxConf: Float = 0.90f,
)
