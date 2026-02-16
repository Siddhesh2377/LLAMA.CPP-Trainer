package com.dark.trainer.repository

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.dark.trainer.data.SupabaseClient
import com.dark.trainer.models.BaseModel
import com.dark.trainer.models.LocalAdapter
import com.dark.trainer.models.LocalModel
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest

class ModelRepository(private val context: Context) {

    private val supabase = SupabaseClient.client
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "ModelRepository"
        private const val PREFS_NAME = "model_storage"
        private const val KEY_MODELS = "downloaded_models"
        private const val KEY_ADAPTERS = "downloaded_adapters"
    }

    /**
     * Fetch available base models from Supabase
     */
    suspend fun fetchAvailableModels(): List<BaseModel> = withContext(Dispatchers.IO) {
        try {
            supabase.from("base_models").select {
                filter {
                    eq("is_active", true)
                }
            }.decodeList<BaseModel>()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch models: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Download a base model from HuggingFace URL
     */
    suspend fun downloadModel(
        model: BaseModel,
        onProgress: (Int) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val modelDir = File(context.filesDir, "models/${model.name}")
        modelDir.mkdirs()

        val localFile = File(modelDir, "model.gguf")

        // Download file
        URL(model.modelDownloadLink).openStream().use { input ->
            FileOutputStream(localFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytes = 0L
                val totalSize = model.sizeMb?.times(1024L * 1024L) ?: 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytes += bytesRead

                    if (totalSize > 0) {
                        val progress = (totalBytes * 100 / totalSize).toInt()
                        onProgress(progress)
                    }
                }
            }
        }

        // Verify checksum if provided
        if (model.checksumSha256 != null) {
            val fileChecksum = calculateSHA256(localFile)
            if (fileChecksum != model.checksumSha256) {
                localFile.delete()
                throw SecurityException("Checksum mismatch! File corrupted.")
            }
        }

        // Save to local storage tracking
        saveLocalModel(
            LocalModel(
                baseModelId = model.id,
                modelName = model.name,
                localPath = localFile.absolutePath,
                sizeBytes = localFile.length()
            )
        )

        localFile
    }

    /**
     * Get locally downloaded models
     */
    fun getLocalModels(): List<LocalModel> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val modelsJson = prefs.getString(KEY_MODELS, "[]") ?: "[]"
        return try {
            json.decodeFromString<List<LocalModel>>(modelsJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse local models: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get locally downloaded adapters
     */
    fun getLocalAdapters(): List<LocalAdapter> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val adaptersJson = prefs.getString(KEY_ADAPTERS, "[]") ?: "[]"
        return try {
            json.decodeFromString<List<LocalAdapter>>(adaptersJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse local adapters: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get adapters for a specific base model
     */
    fun getAdaptersForModel(baseModelId: String): List<LocalAdapter> {
        return getLocalAdapters().filter { it.baseModelId == baseModelId }
    }

    /**
     * Save local model to tracking
     */
    fun saveLocalModel(model: LocalModel) {
        val models = getLocalModels().toMutableList()
        models.removeIf { it.baseModelId == model.baseModelId }
        models.add(model)

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_MODELS, json.encodeToString(models))
        }
    }

    /**
     * Save local adapter to tracking
     */
    fun saveLocalAdapter(adapter: LocalAdapter) {
        val adapters = getLocalAdapters().toMutableList()
        adapters.removeIf { it.adapterId == adapter.adapterId }
        adapters.add(adapter)

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_ADAPTERS, json.encodeToString(adapters))
        }
    }

    /**
     * Delete a local model and its adapters
     */
    suspend fun deleteModel(baseModelId: String) = withContext(Dispatchers.IO) {
        val models = getLocalModels().toMutableList()
        val modelToDelete = models.find { it.baseModelId == baseModelId } ?: return@withContext

        // Delete model file
        File(modelToDelete.localPath).parentFile?.deleteRecursively()

        // Remove from tracking
        models.removeIf { it.baseModelId == baseModelId }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_MODELS, json.encodeToString(models))
        }

        // Also delete associated adapters
        val adapters = getLocalAdapters().toMutableList()
        adapters.removeIf { it.baseModelId == baseModelId }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_ADAPTERS, json.encodeToString(adapters))
        }
    }

    /**
     * Delete a local adapter
     */
    suspend fun deleteAdapter(adapterId: String) = withContext(Dispatchers.IO) {
        val adapters = getLocalAdapters().toMutableList()
        val adapterToDelete = adapters.find { it.adapterId == adapterId } ?: return@withContext

        // Delete adapter directory
        File(adapterToDelete.localPath).deleteRecursively()

        // Remove from tracking
        adapters.removeIf { it.adapterId == adapterId }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_ADAPTERS, json.encodeToString(adapters))
        }
    }

    /**
     * Check if a model is downloaded
     */
    fun isModelDownloaded(baseModelId: String): Boolean {
        return getLocalModels().any { it.baseModelId == baseModelId }
    }

    /**
     * Check if an adapter is downloaded
     */
    fun isAdapterDownloaded(adapterId: String): Boolean {
        return getLocalAdapters().any { it.adapterId == adapterId }
    }

    /**
     * Get model file path
     */
    fun getModelPath(baseModelId: String): String? {
        return getLocalModels().find { it.baseModelId == baseModelId }?.localPath
    }

    /**
     * Get adapter file path (assuming adapter.gguf in adapter directory)
     */
    fun getAdapterPath(adapterId: String): String? {
        val adapter = getLocalAdapters().find { it.adapterId == adapterId } ?: return null
        // Look for .gguf file in adapter directory
        val adapterDir = File(adapter.localPath)
        val ggufFile = adapterDir.listFiles()?.find { it.extension == "gguf" }
        return ggufFile?.absolutePath
    }

    /**
     * Calculate SHA-256 checksum
     */
    private fun calculateSHA256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
