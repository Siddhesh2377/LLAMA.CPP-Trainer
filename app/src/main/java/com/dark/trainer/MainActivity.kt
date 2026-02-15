package com.dark.trainer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.*
import com.dark.trainer.ui.components.AdapterUpdateDialog
import com.dark.trainer.ui.screens.MainScreen
import com.dark.trainer.ui.theme.TrainerTheme
import com.dark.trainer.viewmodel.AdapterUpdateViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: AdapterUpdateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            TrainerTheme {
                val updateState by viewModel.updateState.collectAsState()

                // Show update dialog if updates are available
                if (updateState.updatesAvailable.isNotEmpty()) {
                    AdapterUpdateDialog(
                        updates = updateState.updatesAvailable,
                        isDownloading = updateState.downloading,
                        downloadProgress = updateState.downloadProgress,
                        onDownload = { adapter ->
                            viewModel.downloadAndInstallAdapter(adapter)
                        },
                        onDismiss = {
                            // User dismissed - clear updates for now
                            viewModel.dismissUpdates()
                        }
                    )
                }

                // Main app screen
                MainScreen(
                    updateState = updateState,
                    onCheckForUpdates = { viewModel.checkForUpdates() }
                )
            }
        }
    }
}