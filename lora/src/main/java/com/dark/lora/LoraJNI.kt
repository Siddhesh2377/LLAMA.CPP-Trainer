package com.dark.lora

class LoraJNI {
    // ============================================
    // Callback interfaces
    // ============================================
    interface LogCallback {
        fun onLog(message: String)
    }

    interface StreamCallback {
        fun onToken(token: String)
        fun onComplete()
        fun onError(error: String)
    }

    /** Register a callback to receive real-time log messages from native code */
    external fun setLogCallback(callback: LogCallback?)

    /** Register a callback to receive streaming tokens */
    external fun setStreamCallback(callback: StreamCallback?)

    // ============================================
    // llama.cpp Inference functions
    // ============================================

    /** Initialize llama.cpp backend with CPU support */
    external fun initLlamaBackend(nativeLibDir: String): Boolean

    /**
     * Load a GGUF model from file
     * @param modelPath Absolute path to .gguf model file
     * @param nThreads Number of threads (0 = auto)
     * @param nCtx Context size (0 = default 2048)
     * @return Success message or error
     */
    external fun loadModel(modelPath: String, nThreads: Int, nCtx: Int): String

    /**
     * Load a LoRA adapter and apply it to the current model
     * @param loraPath Absolute path to LoRA adapter file (.gguf)
     * @return Success message or error
     */
    external fun loadLoraAdapter(loraPath: String): String

    /**
     * Generate text from a prompt (non-streaming)
     * @param prompt Input text prompt
     * @param maxTokens Maximum tokens to generate
     * @param temperature Sampling temperature (0.0 = greedy, 0.7 = balanced, 1.0+ = creative)
     * @return Generated text
     */
    external fun generate(prompt: String, maxTokens: Int, temperature: Float): String

    /**
     * Generate text from a prompt with streaming (token-by-token callback)
     * Tokens are delivered via StreamCallback.onToken()
     * @param prompt Input text prompt
     * @param maxTokens Maximum tokens to generate
     * @param temperature Sampling temperature
     */
    external fun generateStreaming(prompt: String, maxTokens: Int, temperature: Float)

    /** Remove currently loaded LoRA adapter (reverts to base model) */
    external fun removeLoraAdapter()

    /** Check if a LoRA adapter is currently loaded */
    external fun hasAdapter(): Boolean

    /** Check if a model is currently loaded */
    external fun hasModel(): Boolean

    /** Cleanup and free all resources */
    external fun cleanupLlama()

    companion object {
        init {
            System.loadLibrary("lora")
        }
    }
}
