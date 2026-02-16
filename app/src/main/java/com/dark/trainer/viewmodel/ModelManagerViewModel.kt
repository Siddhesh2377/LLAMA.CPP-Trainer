package com.dark.trainer.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dark.trainer.models.Adapter
import com.dark.trainer.models.BaseModel
import com.dark.trainer.models.LocalAdapter
import com.dark.trainer.models.LocalModel
import com.dark.trainer.repository.AdapterRepository
import com.dark.trainer.repository.ModelRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ModelManagerState(
    val availableModels: List<BaseModel> = emptyList(),
    val availableAdapters: List<Adapter> = emptyList(),
    val localModels: List<LocalModel> = emptyList(),
    val localAdapters: List<LocalAdapter> = emptyList(),
    val adapterUpdates: Map<String, Adapter> = emptyMap(),
    val selectedModelId: String? = null,
    val isLoading: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Int = 0,
    val downloadingItemId: String? = null,
    val error: String? = null
)

class ModelManagerViewModel(application: Application) : AndroidViewModel(application) {

    private val modelRepository = ModelRepository(application)
    private val adapterRepository = AdapterRepository(application)

    private val _state = MutableStateFlow(ModelManagerState())
    val state: StateFlow<ModelManagerState> = _state

    companion object {
        private const val TAG = "ModelManagerViewModel"
    }

    init {
        refreshLocalData()
        fetchAvailableModels()
    }

    /**
     * Fetch available models from Supabase
     */
    fun fetchAvailableModels() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)

            try {
                val models = modelRepository.fetchAvailableModels()
                _state.value = _state.value.copy(
                    isLoading = false,
                    availableModels = models
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch models: ${e.message}", e)
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "Failed to fetch models: ${e.message}"
                )
            }
        }
    }

    /**
     * Fetch available adapters for a selected model
     */
    fun fetchAdaptersForModel(baseModelId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                isLoading = true,
                selectedModelId = baseModelId,
                error = null
            )

            try {
                val adapters = adapterRepository.fetchAdaptersForModel(baseModelId)
                _state.value = _state.value.copy(
                    isLoading = false,
                    availableAdapters = adapters
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch adapters: ${e.message}", e)
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "Failed to fetch adapters: ${e.message}"
                )
            }
        }
    }

    /**
     * Download a base model
     */
    fun downloadModel(model: BaseModel) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                isDownloading = true,
                downloadProgress = 0,
                downloadingItemId = model.id,
                error = null
            )

            try {
                modelRepository.downloadModel(model) { progress ->
                    _state.value = _state.value.copy(downloadProgress = progress)
                }

                // Refresh local data
                refreshLocalData()

                _state.value = _state.value.copy(
                    isDownloading = false,
                    downloadProgress = 0,
                    downloadingItemId = null
                )

                Log.i(TAG, "Model downloaded successfully: ${model.name}")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to download model: ${e.message}", e)
                _state.value = _state.value.copy(
                    isDownloading = false,
                    downloadProgress = 0,
                    downloadingItemId = null,
                    error = "Failed to download model: ${e.message}"
                )
            }
        }
    }

    /**
     * Download an adapter
     */
    fun downloadAdapter(adapter: Adapter) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                isDownloading = true,
                downloadProgress = 0,
                downloadingItemId = adapter.id,
                error = null
            )

            try {
                adapterRepository.downloadAdapter(adapter) { progress ->
                    _state.value = _state.value.copy(downloadProgress = progress)
                }

                // Refresh local data
                refreshLocalData()

                _state.value = _state.value.copy(
                    isDownloading = false,
                    downloadProgress = 0,
                    downloadingItemId = null
                )

                Log.i(TAG, "Adapter downloaded successfully: ${adapter.name}")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to download adapter: ${e.message}", e)
                _state.value = _state.value.copy(
                    isDownloading = false,
                    downloadProgress = 0,
                    downloadingItemId = null,
                    error = "Failed to download adapter: ${e.message}"
                )
            }
        }
    }

    /**
     * Delete a local model
     */
    fun deleteModel(baseModelId: String) {
        viewModelScope.launch {
            try {
                modelRepository.deleteModel(baseModelId)
                refreshLocalData()
                Log.i(TAG, "Model deleted: $baseModelId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete model: ${e.message}", e)
                _state.value = _state.value.copy(
                    error = "Failed to delete model: ${e.message}"
                )
            }
        }
    }

    /**
     * Delete a local adapter
     */
    fun deleteAdapter(adapterId: String) {
        viewModelScope.launch {
            try {
                modelRepository.deleteAdapter(adapterId)
                refreshLocalData()
                Log.i(TAG, "Adapter deleted: $adapterId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete adapter: ${e.message}", e)
                _state.value = _state.value.copy(
                    error = "Failed to delete adapter: ${e.message}"
                )
            }
        }
    }

    /**
     * Refresh local model/adapter data and check for updates
     */
    fun refreshLocalData() {
        val localModels = modelRepository.getLocalModels()
        val localAdapters = modelRepository.getLocalAdapters()

        _state.value = _state.value.copy(
            localModels = localModels,
            localAdapters = localAdapters
        )

        // Check for adapter updates in background
        checkForAdapterUpdates(localAdapters)
    }

    /**
     * Check if any local adapters have newer versions available on server
     */
    private fun checkForAdapterUpdates(localAdapters: List<LocalAdapter> = _state.value.localAdapters) {
        if (localAdapters.isEmpty()) return

        viewModelScope.launch {
            try {
                val updates = adapterRepository.checkForUpdates(localAdapters)
                _state.value = _state.value.copy(adapterUpdates = updates)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check for adapter updates: ${e.message}", e)
            }
        }
    }

    /**
     * Update a local adapter to a newer remote version
     */
    fun updateAdapter(localAdapterId: String, remoteAdapter: Adapter) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                isDownloading = true,
                downloadProgress = 0,
                downloadingItemId = localAdapterId,
                error = null
            )

            try {
                // Delete old adapter files
                modelRepository.deleteAdapter(localAdapterId)

                // Download the new version
                adapterRepository.downloadAdapter(remoteAdapter) { progress ->
                    _state.value = _state.value.copy(downloadProgress = progress)
                }

                // Refresh local data (also re-checks for updates)
                refreshLocalData()

                _state.value = _state.value.copy(
                    isDownloading = false,
                    downloadProgress = 0,
                    downloadingItemId = null
                )

                Log.i(TAG, "Adapter updated: ${remoteAdapter.name} v${remoteAdapter.version}")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to update adapter: ${e.message}", e)
                _state.value = _state.value.copy(
                    isDownloading = false,
                    downloadProgress = 0,
                    downloadingItemId = null,
                    error = "Failed to update adapter: ${e.message}"
                )
            }
        }
    }

    /**
     * Check if a model is downloaded
     */
    fun isModelDownloaded(baseModelId: String): Boolean {
        return modelRepository.isModelDownloaded(baseModelId)
    }

    /**
     * Check if an adapter is downloaded
     */
    fun isAdapterDownloaded(adapterId: String): Boolean {
        return modelRepository.isAdapterDownloaded(adapterId)
    }

    /**
     * Get adapters for a specific model
     */
    fun getAdaptersForLocalModel(baseModelId: String): List<LocalAdapter> {
        return modelRepository.getAdaptersForModel(baseModelId)
    }

    /**
     * Clear error
     */
    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
