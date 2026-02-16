package com.dark.trainer.repository

import android.content.Context
import android.util.Log
import com.dark.trainer.data.SupabaseClient
import com.dark.trainer.models.Adapter
import com.dark.trainer.models.AdapterDeployment
import com.dark.trainer.models.LocalAdapter
import com.dark.trainer.models.UpdateLog
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest
import androidx.core.content.edit
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

class AdapterRepository(private val context: Context) {

    private val supabase = SupabaseClient.client
    private val modelRepository = ModelRepository(context)

    /**
     * Fetch available adapters for a specific base model from Supabase
     */
    suspend fun fetchAdaptersForModel(baseModelId: String): List<Adapter> = withContext(Dispatchers.IO) {
        try {
            supabase.from("adapters").select {
                filter {
                    eq("base_model_id", baseModelId)
                    eq("is_published", true)
                }
            }.decodeList<Adapter>()
        } catch (e: Exception) {
            Log.e("AdapterRepository", "Failed to fetch adapters: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Fetch all available adapters from Supabase
     */
    suspend fun fetchAllAdapters(): List<Adapter> = withContext(Dispatchers.IO) {
        try {
            supabase.from("adapters").select {
                filter {
                    eq("is_published", true)
                }
            }.decodeList<Adapter>()
        } catch (e: Exception) {
            Log.e("AdapterRepository", "Failed to fetch adapters: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Check for available adapter updates based on locally installed adapters.
     * Returns a map of localAdapterId -> newer remote Adapter.
     */
    suspend fun checkForUpdates(
        localAdapters: List<LocalAdapter>
    ): Map<String, Adapter> = withContext(Dispatchers.IO) {
        if (localAdapters.isEmpty()) return@withContext emptyMap()

        // 1. Fetch all active deployments
        val deployments = supabase.from("adapter_deployments").select {
                filter {
                    eq("is_active", true)
                }
            }.decodeList<AdapterDeployment>()

        val adapterIds = deployments.map { it.adapterId }
        if (adapterIds.isEmpty()) return@withContext emptyMap()

        // 2. Fetch corresponding adapters
        val remoteAdapters = supabase.from("adapters").select {
                filter {
                    isIn("id", adapterIds)
                    eq("is_published", true)
                }
            }.decodeList<Adapter>()

        // 3. For each local adapter, find if a newer version exists
        val updates = mutableMapOf<String, Adapter>()
        for (local in localAdapters) {
            val newer = remoteAdapters
                .filter { it.baseModelId == local.baseModelId && it.domain == local.domain }
                .filter { isNewerVersion(it.version, local.version) }
                .maxByOrNull { it.version }
            if (newer != null) {
                updates[local.adapterId] = newer
            }
        }
        updates
    }

    /**
     * Compare semantic versions. Returns true if remote > local.
     */
    private fun isNewerVersion(remote: String, local: String): Boolean {
        if (local.isBlank()) return true
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val l = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    /**
     * Download adapter from Supabase Storage and extract it
     */
    suspend fun downloadAdapter(
        adapter: Adapter,
        onProgress: (Int) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {

        val bucket = supabase.storage.from("adapters")

        // Get public URL
        val downloadUrl = bucket.publicUrl(adapter.storagePath)

        // Download to app's cache directory first
        val localFile = File(
            context.cacheDir, "adapters/${adapter.domain}/${adapter.version}.tar.gz"
        )
        localFile.parentFile?.mkdirs()

        // Download file
        URL(downloadUrl).openStream().use { input ->
            FileOutputStream(localFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytes = 0L
                val totalSize = adapter.sizeMb?.times(1024 * 1024)?.toLong() ?: 0L

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

        // Verify checksum
        if (adapter.checksumSha256 != null) {
            val fileChecksum = calculateSHA256(localFile)
            if (fileChecksum != adapter.checksumSha256) {
                localFile.delete()
                throw SecurityException("Checksum mismatch! File corrupted.")
            }
        }

        // Extract tar.gz to proper location
        val localModel = modelRepository.getLocalModels()
            .find { it.baseModelId == adapter.baseModelId }
            ?: throw IllegalStateException("Base model not downloaded for adapter: ${adapter.name}")

        val modelDir = File(localModel.localPath).parentFile
            ?: throw IllegalStateException("Invalid model path")

        val extractDir = File(modelDir, "adapters/${adapter.domain}")
        extractDir.mkdirs()

        extractTarGz(localFile, extractDir)

        // Clean up downloaded tar.gz
        localFile.delete()

        // Save to local tracking
        modelRepository.saveLocalAdapter(
            LocalAdapter(
                adapterId = adapter.id,
                baseModelId = adapter.baseModelId,
                adapterName = adapter.name,
                domain = adapter.domain,
                version = adapter.version,
                localPath = extractDir.absolutePath,
                sizeBytes = extractDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
            )
        )

        extractDir
    }

    /**
     * Extract tar.gz archive
     */
    private fun extractTarGz(tarGzFile: File, outputDir: File) {
        GZIPInputStream(FileInputStream(tarGzFile)).use { gzipIn ->
            TarArchiveInputStream(gzipIn).use { tarIn ->
                var entry = tarIn.nextEntry
                while (entry != null) {
                    if (!tarIn.canReadEntryData(entry)) {
                        Log.w("AdapterRepository", "Cannot read entry: ${entry.name}")
                        entry = tarIn.nextEntry
                        continue
                    }

                    val outputFile = File(outputDir, entry.name)
                    if (entry.isDirectory) {
                        outputFile.mkdirs()
                    } else {
                        outputFile.parentFile?.mkdirs()
                        FileOutputStream(outputFile).use { output ->
                            tarIn.copyTo(output)
                        }
                    }
                    entry = tarIn.nextEntry
                }
            }
        }
    }

    /**
     * Log update status to Supabase
     */
    suspend fun logUpdate(
        deviceId: String,
        adapterId: String,
        status: String,
        errorMessage: String? = null
    ) = withContext(Dispatchers.IO) {

        val log = UpdateLog(
            deviceId = deviceId,
            adapterId = adapterId,
            installationStatus = status,
            errorMessage = errorMessage
        )

        try {
            supabase.from("update_logs").insert(log)
            Log.d("AdapterRepository", "Update logged successfully")
        } catch (e: Exception) {
            Log.e("AdapterRepository", "Failed to log update: ${e.message}")
            // Don't crash the app if logging fails
        }
    }

    /**
     * Get current installed adapters from local storage
     */
    fun getInstalledAdapters(): Map<String, String> {
        val prefs = context.getSharedPreferences("adapters", Context.MODE_PRIVATE)
        return prefs.all.mapValues { it.value.toString() }
    }

    /**
     * Save installed adapter version
     */
    fun saveInstalledAdapter(domain: String, version: String) {
        context.getSharedPreferences("adapters", Context.MODE_PRIVATE).edit {
            putString(domain, version)
        }
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