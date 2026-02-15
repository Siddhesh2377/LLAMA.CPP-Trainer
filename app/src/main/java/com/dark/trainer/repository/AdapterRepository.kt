package com.dark.trainer.repository

import android.content.Context
import android.util.Log
import com.dark.trainer.data.SupabaseClient
import com.dark.trainer.models.Adapter
import com.dark.trainer.models.AdapterDeployment
import com.dark.trainer.models.UpdateLog
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest
import androidx.core.content.edit

class AdapterRepository(private val context: Context) {

    private val supabase = SupabaseClient.client

    /**
     * Check for available adapter updates
     */
    suspend fun checkForUpdates(
        currentAdapters: Map<String, String> // domain -> version
    ): List<Adapter> = withContext(Dispatchers.IO) {

        // 1. Fetch all active deployments
        val deployments = supabase.from("adapter_deployments").select {
                filter {
                    eq("is_active", true)
                }
            }.decodeList<AdapterDeployment>()

        val adapterIds = deployments.map { it.adapterId }

        // 2. Fetch corresponding adapters
        val adapters = supabase.from("adapters").select {
                filter {
                    isIn("id", adapterIds)
                    eq("is_published", true)
                }
            }.decodeList<Adapter>()

        // 3. Filter updates needed
        adapters.filter { adapter ->
            val currentVersion = currentAdapters[adapter.domain] ?: "0.0.0"
            adapter.version > currentVersion
        }
    }

    /**
     * Download adapter from Supabase Storage
     */
    suspend fun downloadAdapter(
        adapter: Adapter, onProgress: (Int) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {

        val bucket = supabase.storage.from("adapters")

        // Get public URL
        val downloadUrl = bucket.publicUrl(adapter.storagePath)

        // Download to app's cache directory
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

        localFile
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