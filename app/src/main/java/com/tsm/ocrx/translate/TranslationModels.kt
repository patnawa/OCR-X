package com.tsm.ocrx.translate

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Manages the on-device translation model files so the user can pre-download
 * languages (e.g. over Wi-Fi) and use them offline afterwards.
 */
object TranslationModels {

    private val manager = RemoteModelManager.getInstance()

    /** BCP-47 codes of the models currently downloaded on the device. */
    suspend fun downloaded(): Set<String> {
        val models = manager.getDownloadedModels(TranslateRemoteModel::class.java).await()
        return models.map { it.language }.toSet()
    }

    suspend fun download(code: String, wifiOnly: Boolean) {
        val model = TranslateRemoteModel.Builder(code).build()
        val conditions = DownloadConditions.Builder()
            .apply { if (wifiOnly) requireWifi() }
            .build()
        manager.download(model, conditions).await()
    }

    suspend fun delete(code: String) {
        val model = TranslateRemoteModel.Builder(code).build()
        manager.deleteDownloadedModel(model).await()
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { e -> cont.resumeWithException(e) }
    }
}
