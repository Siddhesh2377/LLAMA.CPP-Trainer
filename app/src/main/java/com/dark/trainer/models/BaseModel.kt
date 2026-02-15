package com.dark.trainer.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BaseModel(
    val id: String,
    val name: String,
    @SerialName("huggingface_id") val huggingfaceId: String,
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