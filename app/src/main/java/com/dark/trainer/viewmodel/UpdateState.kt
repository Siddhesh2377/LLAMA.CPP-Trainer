package com.dark.trainer.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dark.trainer.models.Adapter
import com.dark.trainer.repository.AdapterRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class UpdateState(
    val isChecking: Boolean = false,
    val updatesAvailable: List<Adapter> = emptyList(),
    val downloading: Boolean = false,
    val downloadProgress: Int = 0,
    val error: String? = null
)

class AdapterUpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AdapterRepository(application)

    private val _updateState = MutableStateFlow(UpdateState())
    val updateState: StateFlow<UpdateState> = _updateState

    @SuppressLint("HardwareIds")
    private val deviceId = Settings.Secure.getString(
        application.contentResolver,
        Settings.Secure.ANDROID_ID
    )

    /**
     * Check for updates on app launch
     */
    fun checkForUpdates() {
        viewModelScope.launch {
            _updateState.value = _updateState.value.copy(isChecking = true, error = null)

            try {
                val currentAdapters = repository.getInstalledAdapters()
                val updates = repository.checkForUpdates(currentAdapters)

                _updateState.value = _updateState.value.copy(
                    isChecking = false,
                    updatesAvailable = updates
                )

            } catch (e: Exception) {
                _updateState.value = _updateState.value.copy(
                    isChecking = false,
                    error = e.message
                )
            }
        }
    }

    /**
     * Download and install adapter
     */
    fun downloadAndInstallAdapter(adapter: Adapter) {
        viewModelScope.launch {
            _updateState.value = _updateState.value.copy(downloading = true, error = null)

            try {
                // Download
                val file = repository.downloadAdapter(adapter) { progress ->
                    _updateState.value = _updateState.value.copy(downloadProgress = progress)
                }

                Log.d("AdapterUpdateViewModel", "File downloaded: ${file.absolutePath}")

                // TODO: Extract and load adapter using your ML model loader
                // extractTarGz(file)
                // modelLoader.loadAdapter(extractedPath)

                // Save version
                repository.saveInstalledAdapter(adapter.domain, adapter.version)

                // Log success
                repository.logUpdate(deviceId, adapter.id, "success")

                _updateState.value = _updateState.value.copy(
                    downloading = false,
                    downloadProgress = 0,
                    updatesAvailable = _updateState.value.updatesAvailable.filter { it.id != adapter.id }
                )

            } catch (e: Exception) {
                repository.logUpdate(deviceId, adapter.id, "failed", e.message)

                _updateState.value = _updateState.value.copy(
                    downloading = false,
                    downloadProgress = 0,
                    error = e.message
                )
            }
        }
    }

    /**
     * Dismiss update notifications without installing
     * User can check again later manually
     */
    fun dismissUpdates() {
        _updateState.value = _updateState.value.copy(
            updatesAvailable = emptyList(),
            error = null,
            downloadProgress = 0
        )
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _updateState.value = _updateState.value.copy(error = null)
    }
}