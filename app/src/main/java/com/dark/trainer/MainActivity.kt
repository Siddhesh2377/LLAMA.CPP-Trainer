package com.dark.trainer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.dark.trainer.ui.components.SettingsDialog
import com.dark.trainer.ui.screens.ChatScreen
import com.dark.trainer.ui.screens.ModelLibraryScreen
import com.dark.trainer.ui.theme.TrainerTheme
import com.dark.trainer.viewmodel.ChatViewModel
import com.dark.trainer.viewmodel.ModelManagerViewModel

class MainActivity : ComponentActivity() {

    private val chatViewModel: ChatViewModel by viewModels()
    private val modelManagerViewModel: ModelManagerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            TrainerTheme {
                AppContent(
                    chatViewModel = chatViewModel,
                    modelManagerViewModel = modelManagerViewModel
                )
            }
        }
    }
}

@Composable
fun AppContent(
    chatViewModel: ChatViewModel,
    modelManagerViewModel: ModelManagerViewModel
) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Chat) }
    var showSettings by remember { mutableStateOf(false) }

    val chatState by chatViewModel.chatState.collectAsState()
    val modelManagerState by modelManagerViewModel.state.collectAsState()

    when (currentScreen) {
        Screen.Chat -> {
            ChatScreen(
                chatState = chatState,
                onSendMessage = { message ->
                    chatViewModel.sendMessage(message)
                },
                onClearChat = {
                    chatViewModel.clearConversation()
                },
                onOpenSettings = {
                    showSettings = true
                },
                onOpenModelLibrary = {
                    currentScreen = Screen.ModelLibrary
                }
            )
        }

        Screen.ModelLibrary -> {
            ModelLibraryScreen(
                state = modelManagerState,
                onRefresh = {
                    modelManagerViewModel.fetchAvailableModels()
                    modelManagerViewModel.refreshLocalData()
                },
                onDownloadModel = { model ->
                    modelManagerViewModel.downloadModel(model)
                },
                onDownloadAdapter = { adapter ->
                    modelManagerViewModel.downloadAdapter(adapter)
                },
                onUpdateAdapter = { localAdapterId, remoteAdapter ->
                    modelManagerViewModel.updateAdapter(localAdapterId, remoteAdapter)
                },
                onDeleteModel = { modelId ->
                    modelManagerViewModel.deleteModel(modelId)
                },
                onDeleteAdapter = { adapterId ->
                    modelManagerViewModel.deleteAdapter(adapterId)
                },
                onLoadModel = { modelId, adapterId ->
                    chatViewModel.loadModel(modelId, adapterId)
                    currentScreen = Screen.Chat
                },
                onSelectModel = { modelId ->
                    modelManagerViewModel.fetchAdaptersForModel(modelId)
                },
                onBack = {
                    currentScreen = Screen.Chat
                }
            )
        }
    }

    // Settings Dialog
    if (showSettings) {
        SettingsDialog(
            temperature = chatState.temperature,
            maxTokens = chatState.maxTokens,
            systemPrompt = chatState.systemPrompt,
            npuEnabled = chatState.npuEnabled,
            onTemperatureChange = { temp ->
                chatViewModel.updateSettings(temperature = temp)
            },
            onMaxTokensChange = { tokens ->
                chatViewModel.updateSettings(maxTokens = tokens)
            },
            onSystemPromptChange = { prompt ->
                chatViewModel.updateSettings(systemPrompt = prompt)
            },
            onNpuEnabledChange = { enabled ->
                chatViewModel.updateSettings(npuEnabled = enabled)
            },
            onDismiss = {
                showSettings = false
            },
            onSave = {
                showSettings = false
            }
        )
    }
}

sealed class Screen {
    data object Chat : Screen()
    data object ModelLibrary : Screen()
}