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

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.model.OCRError
import java.io.File
import java.nio.FloatBuffer

class ORTSessionManager(
    private val context: Context,
    private val config: EngineConfig,
) {
    private var env: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var recSession2: OrtSession? = null
    private var detInputName: String = "x"
    private var recInputName: String = "x"
    private var recInputName2: String = "x"
    var coldLoadTimeMs: Long = 0
        private set

    fun loadModels(detAssetPath: String, recAssetPath: String) {
        val loadStart = System.currentTimeMillis()
        env = OrtEnvironment.getEnvironment()
        val opts = newSessionOptions()
        try {
            val ortEnv = env ?: throw OCRError.ModelLoadFailed("OCR", Exception("Environment not initialized"))
            val detBytes = readModel(detAssetPath)
            val recBytes = readModel(recAssetPath)
            try {
                detSession = ortEnv.createSession(detBytes, opts)
            } catch (t: Throwable) {
                throw OCRError.ModelLoadFailed("detection", t)
            }
            try {
                recSession = ortEnv.createSession(recBytes, opts)
            } catch (t: Throwable) {
                detSession?.close()
                detSession = null
                throw OCRError.ModelLoadFailed("recognition", t)
            }

            detInputName = try {
                detSession!!.inputNames.iterator().next()
            } catch (t: Throwable) {
                throw OCRError.ModelLoadFailed("detection", t)
            }
            recInputName = try {
                recSession!!.inputNames.iterator().next()
            } catch (t: Throwable) {
                throw OCRError.ModelLoadFailed("recognition", t)
            }
            coldLoadTimeMs = System.currentTimeMillis() - loadStart
        } finally {
            opts.close()
        }
    }

    /**
     * Loads an additional recognition model that shares this manager's detection
     * session. Used to recognize the same crops with a second script's model so the
     * caller can keep whichever reading scored higher.
     */
    fun loadSecondaryRecognition(recPath: String) {
        val ortEnv = env
            ?: throw OCRError.ModelLoadFailed("recognition", Exception("Environment not initialized"))
        val opts = newSessionOptions()
        try {
            val bytes = readModel(recPath)
            recSession2 = try {
                ortEnv.createSession(bytes, opts)
            } catch (t: Throwable) {
                throw OCRError.ModelLoadFailed("recognition (secondary)", t)
            }
            recInputName2 = try {
                recSession2!!.inputNames.iterator().next()
            } catch (t: Throwable) {
                throw OCRError.ModelLoadFailed("recognition (secondary)", t)
            }
        } finally {
            opts.close()
        }
    }

    private fun newSessionOptions(): OrtSession.SessionOptions =
        OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(config.numThreads)
            // Without these, ORT's arena allocator keeps its high-water mark
            // resident forever — the process balloons to 500+ MB after large
            // scans and becomes a prime target for OEM background killers.
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
            // Execution providers are best-effort: a runtime build without the
            // provider throws, and a session with no EP registered still runs on
            // plain CPU. Never let an accelerator turn into a hard scan failure.
            if (config.useNnapi) {
                try {
                    addNnapi()
                } catch (_: Throwable) {
                }
            }
            if (config.useXnnpack) {
                try {
                    addXnnpack(mapOf("intra_op_num_threads" to config.numThreads.toString()))
                } catch (_: Throwable) {
                }
            }
        }

    fun runDetection(input: FloatArray, shape: LongArray): Pair<FloatArray, LongArray> {
        val session = detSession
            ?: throw OCRError.ModelLoadFailed("detection", Exception("Session not initialized"))
        val ortEnv = env
            ?: throw OCRError.ModelLoadFailed("detection", Exception("Environment not initialized"))
        return runSession(ortEnv, session, detInputName, input, shape, "detection")
    }

    fun runRecognition(input: FloatArray, shape: LongArray): Pair<FloatArray, LongArray> {
        val session = recSession
            ?: throw OCRError.ModelLoadFailed("recognition", Exception("Session not initialized"))
        val ortEnv = env
            ?: throw OCRError.ModelLoadFailed("recognition", Exception("Environment not initialized"))
        return runSession(ortEnv, session, recInputName, input, shape, "recognition")
    }

    /** True when a secondary recognition model is loaded. */
    val hasSecondaryRecognition: Boolean get() = recSession2 != null

    fun runRecognitionSecondary(input: FloatArray, shape: LongArray): Pair<FloatArray, LongArray> {
        val session = recSession2
            ?: throw OCRError.ModelLoadFailed("recognition", Exception("Secondary session not initialized"))
        val ortEnv = env
            ?: throw OCRError.ModelLoadFailed("recognition", Exception("Environment not initialized"))
        return runSession(ortEnv, session, recInputName2, input, shape, "recognition (secondary)")
    }

    fun release() {
        try {
            detSession?.close()
        } finally {
            detSession = null
            try {
                recSession?.close()
            } finally {
                recSession = null
                try {
                    recSession2?.close()
                } finally {
                    recSession2 = null
                    env = null
                }
            }
        }
    }

    /**
     * Reads a model from either the APK assets or the filesystem. An absolute path
     * is treated as a file so downloaded models can be loaded without being copied
     * into assets; anything else is an asset path.
     */
    private fun readModel(path: String): ByteArray {
        return try {
            if (path.startsWith("/")) {
                val file = File(path)
                if (!file.isFile) throw OCRError.ModelNotFound(path, Exception("No such file"))
                file.readBytes()
            } else {
                context.assets.open(path).use { it.readBytes() }
            }
        } catch (t: OCRError) {
            throw t
        } catch (t: Throwable) {
            throw OCRError.ModelNotFound(path, t)
        }
    }

    private fun runSession(
        ortEnv: OrtEnvironment,
        session: OrtSession,
        inputName: String,
        input: FloatArray,
        shape: LongArray,
        modelName: String,
    ): Pair<FloatArray, LongArray> {
        val tensor = try {
            OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(input), shape)
        } catch (t: Throwable) {
            throw OCRError.InferenceFailed(modelName, t)
        }
        val result = try {
            try {
                session.run(mapOf(inputName to tensor))
            } catch (t: Throwable) {
                throw OCRError.InferenceFailed(modelName, t)
            }
        } finally {
            tensor.close()
        }

        return try {
            try {
                val outputName = session.outputNames.iterator().next()
                val ortValue = result.get(outputName)
                    .orElseThrow { Exception("No output tensor found") }
                val outputTensor = ortValue as? OnnxTensor
                    ?: throw Exception("Output is not an ONNX tensor")
                Pair(copyFloatBuffer(outputTensor.floatBuffer), outputTensor.info.shape)
            } catch (t: Throwable) {
                throw OCRError.InferenceFailed(modelName, t)
            }
        } finally {
            result.close()
        }
    }

    private fun copyFloatBuffer(buffer: FloatBuffer): FloatArray {
        val duplicate = buffer.duplicate()
        duplicate.rewind()
        val output = FloatArray(duplicate.remaining())
        duplicate.get(output)
        return output
    }
}
