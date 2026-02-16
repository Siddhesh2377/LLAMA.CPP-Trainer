package com.dark.trainer.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BaseModel(
    val id: String,
    val name: String,
    @SerialName("model_download_link") val modelDownloadLink: String? = null,  // GGUF base model download URL
    val version: String,
    @SerialName("size_mb") val sizeMb: Int?,
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class Adapter(
    val id: String,
    @SerialName("base_model_id") val baseModelId: String,
    val name: String,
    val domain: String,
    val version: String,
    @SerialName("storage_path") val storagePath: String,
    @SerialName("size_mb") val sizeMb: Float?,
    @SerialName("checksum_sha256") val checksumSha256: String?,
    @SerialName("training_epochs") val trainingEpochs: Int?,
    @SerialName("training_loss") val trainingLoss: Float?,
    val status: String,
    @SerialName("is_published") val isPublished: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class AdapterDeployment(
    val id: String,
    @SerialName("adapter_id") val adapterId: String,
    @SerialName("rollout_percentage") val rolloutPercentage: Int,
    @SerialName("min_app_version") val minAppVersion: String?,
    @SerialName("target_devices") val targetDevices: String?,
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("deployed_at") val deployedAt: String
)

@Serializable
data class UpdateLog(
    @SerialName("device_id") val deviceId: String,
    @SerialName("adapter_id") val adapterId: String,
    @SerialName("installation_status") val installationStatus: String,
    @SerialName("error_message") val errorMessage: String? = null
)

// ============================================
// Local storage models (not synced to Supabase)
// These are stored locally in SharedPreferences as JSON
// ============================================

/**
 * Represents a locally downloaded model/adapter
 */
@Serializable
data class LocalModel(
    val baseModelId: String,
    val modelName: String,
    val localPath: String,
    val downloadedAt: Long = System.currentTimeMillis(),
    val sizeBytes: Long = 0
)

@Serializable
data class LocalAdapter(
    val adapterId: String,
    val baseModelId: String,
    val adapterName: String,
    val domain: String,
    val localPath: String,
    val downloadedAt: Long = System.currentTimeMillis(),
    val sizeBytes: Long = 0
)

/**
 * Chat message for conversation
 */
data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: ChatRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

enum class ChatRole {
    USER,
    ASSISTANT,
    SYSTEM
}

/**
 * Chat conversation
 */
data class Conversation(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String = "New Chat",
    val messages: List<ChatMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val baseModelId: String? = null,
    val adapterId: String? = null
)