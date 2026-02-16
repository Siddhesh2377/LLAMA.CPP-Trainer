package com.dark.trainer.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dark.lora.LoraJNI
import com.dark.trainer.models.ChatMessage
import com.dark.trainer.models.ChatRole
import com.dark.trainer.models.Conversation
import com.dark.trainer.repository.ModelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatState(
    val currentConversation: Conversation? = null,
    val isGenerating: Boolean = false,
    val error: String? = null,
    val isModelLoaded: Boolean = false,
    val loadedModelId: String? = null,
    val loadedAdapterId: String? = null,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 512,
    val systemPrompt: String = "You are a helpful AI assistant."
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val loraJNI = LoraJNI()
    private val modelRepository = ModelRepository(application)

    private val _chatState = MutableStateFlow(ChatState())
    val chatState: StateFlow<ChatState> = _chatState
    private val streamingBuffer = StringBuilder()

    companion object {
        private const val TAG = "ChatViewModel"
    }

    init {
        // Set up log callback
        loraJNI.setLogCallback(object : LoraJNI.LogCallback {
            override fun onLog(message: String) {
                Log.d(TAG, message)
            }
        })

        // Set up stream callback
        loraJNI.setStreamCallback(object : LoraJNI.StreamCallback {
            override fun onToken(token: String) {
                streamingBuffer.append(token)
                val content = streamingBuffer.toString()

                _chatState.value.currentConversation?.let { conv ->
                    val messages = conv.messages.toMutableList()
                    if (messages.isNotEmpty() && messages.last().role == ChatRole.ASSISTANT) {
                        messages[messages.size - 1] = messages.last().copy(content = content)
                    } else {
                        messages.add(ChatMessage(role = ChatRole.ASSISTANT, content = content))
                    }
                    _chatState.value = _chatState.value.copy(
                        currentConversation = conv.copy(
                            messages = messages,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            }

            override fun onComplete() {
                _chatState.value = _chatState.value.copy(isGenerating = false)
                Log.i(TAG, "Streaming generation complete")
            }

            override fun onError(error: String) {
                _chatState.value = _chatState.value.copy(
                    isGenerating = false,
                    error = error
                )
                Log.e(TAG, "Streaming error: $error")
            }
        })

        // Initialize backend
        viewModelScope.launch {
            try {
                val nativeLibDir = application.applicationInfo.nativeLibraryDir
                loraJNI.initLlamaBackend(nativeLibDir)
                Log.i(TAG, "llama.cpp backend initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize backend: ${e.message}", e)
                _chatState.value = _chatState.value.copy(
                    error = "Failed to initialize: ${e.message}"
                )
            }
        }
    }

    /**
     * Load a model for chat
     */
    fun loadModel(baseModelId: String, adapterId: String? = null, nThreads: Int = 0, nCtx: Int = 2048) {
        viewModelScope.launch {
            _chatState.value = _chatState.value.copy(
                isGenerating = true,
                error = null
            )

            try {
                // Get model path
                val modelPath = modelRepository.getModelPath(baseModelId)
                    ?: throw IllegalStateException("Model not downloaded: $baseModelId")

                // Load base model
                val result = withContext(Dispatchers.IO) {
                    loraJNI.loadModel(modelPath, nThreads, nCtx)
                }

                if (result.startsWith("ERROR")) {
                    throw IllegalStateException(result)
                }

                Log.i(TAG, "Model loaded: $result")

                // Load adapter if specified
                if (adapterId != null) {
                    val adapterPath = modelRepository.getAdapterPath(adapterId)
                        ?: throw IllegalStateException("Adapter not downloaded: $adapterId")

                    val adapterResult = withContext(Dispatchers.IO) {
                        loraJNI.loadLoraAdapter(adapterPath)
                    }

                    if (adapterResult.startsWith("ERROR")) {
                        throw IllegalStateException(adapterResult)
                    }

                    Log.i(TAG, "Adapter loaded: $adapterResult")
                }

                _chatState.value = _chatState.value.copy(
                    isGenerating = false,
                    isModelLoaded = true,
                    loadedModelId = baseModelId,
                    loadedAdapterId = adapterId,
                    currentConversation = Conversation(
                        baseModelId = baseModelId,
                        adapterId = adapterId
                    )
                )

            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model: ${e.message}", e)
                _chatState.value = _chatState.value.copy(
                    isGenerating = false,
                    isModelLoaded = false,
                    error = "Failed to load model: ${e.message}"
                )
            }
        }
    }

    /**
     * Unload current model
     */
    fun unloadModel() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    loraJNI.cleanupLlama()
                }
                _chatState.value = _chatState.value.copy(
                    isModelLoaded = false,
                    loadedModelId = null,
                    loadedAdapterId = null,
                    currentConversation = null
                )
                Log.i(TAG, "Model unloaded")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unload model: ${e.message}", e)
            }
        }
    }

    /**
     * Send a message and get response
     */
    fun sendMessage(content: String) {
        if (!_chatState.value.isModelLoaded) {
            _chatState.value = _chatState.value.copy(
                error = "No model loaded. Please load a model first."
            )
            return
        }

        viewModelScope.launch {
            _chatState.value = _chatState.value.copy(
                isGenerating = true,
                error = null
            )

            try {
                val currentConv = _chatState.value.currentConversation
                    ?: throw IllegalStateException("No conversation")

                // Add user message
                val userMessage = ChatMessage(
                    role = ChatRole.USER,
                    content = content
                )

                val updatedMessages = currentConv.messages + userMessage

                _chatState.value = _chatState.value.copy(
                    currentConversation = currentConv.copy(
                        messages = updatedMessages,
                        updatedAt = System.currentTimeMillis()
                    )
                )

                // Build prompt with conversation history
                val prompt = buildPrompt(
                    systemPrompt = _chatState.value.systemPrompt,
                    messages = updatedMessages
                )

                // Generate response using streaming
                streamingBuffer.clear()
                withContext(Dispatchers.IO) {
                    loraJNI.generateStreaming(
                        prompt,
                        _chatState.value.maxTokens,
                        _chatState.value.temperature
                    )
                }
                // Note: Response is handled by StreamCallback
                // isGenerating will be set to false in onComplete()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate response: ${e.message}", e)
                _chatState.value = _chatState.value.copy(
                    isGenerating = false,
                    error = "Failed to generate response: ${e.message}"
                )
            }
        }
    }

    /**
     * Build prompt from conversation history
     */
    private fun buildPrompt(systemPrompt: String, messages: List<ChatMessage>): String {
        val builder = StringBuilder()

        // Add system prompt
        builder.append("<|im_start|>system\n")
        builder.append(systemPrompt)
        builder.append("<|im_end|>\n")

        // Add conversation history
        for (message in messages) {
            builder.append("<|im_start|>${message.role.name.lowercase()}\n")
            builder.append(message.content)
            builder.append("<|im_end|>\n")
        }

        // Add assistant prefix for generation
        builder.append("<|im_start|>assistant\n")

        return builder.toString()
    }

    /**
     * Clear current conversation
     */
    fun clearConversation() {
        val current = _chatState.value.currentConversation ?: return
        _chatState.value = _chatState.value.copy(
            currentConversation = current.copy(
                messages = emptyList(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * Update generation settings
     */
    fun updateSettings(temperature: Float? = null, maxTokens: Int? = null, systemPrompt: String? = null) {
        _chatState.value = _chatState.value.copy(
            temperature = temperature ?: _chatState.value.temperature,
            maxTokens = maxTokens ?: _chatState.value.maxTokens,
            systemPrompt = systemPrompt ?: _chatState.value.systemPrompt
        )
    }

    /**
     * Clear error
     */
    fun clearError() {
        _chatState.value = _chatState.value.copy(error = null)
    }

    override fun onCleared() {
        super.onCleared()
        // Cleanup when ViewModel is destroyed
        loraJNI.cleanupLlama()
    }
}
