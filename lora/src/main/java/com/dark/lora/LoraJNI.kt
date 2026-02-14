package com.dark.lora

class LoraJNI {
    // ============================================
    // QNN NPU functions (existing)
    // ============================================
    external fun testQNN(): String
    external fun cleanupQNN()
    external fun buildLoraGraph(outputPath: String): String

    // ============================================
    // Log callback interface
    // ============================================
    interface LogCallback {
        fun onLog(message: String)
    }

    /** Register a callback to receive real-time log messages from native code */
    external fun setLogCallback(callback: LogCallback?)

    // ============================================
    // llama.cpp LoRA Training functions
    // ============================================

    external fun initLlamaBackend(nativeLibDir: String): Boolean

    external fun loadModel(modelPath: String, nThreads: Int, nCtx: Int): String

    /** @param nLayersSkip skip first N layers (0 = train all) */
    external fun createLoraAdapter(rank: Int, alpha: Float, nLayersSkip: Int = 0): String

    external fun loadLoraAdapter(loraPath: String): String

    external fun setTrainingData(trainingText: String): String

    external fun initTraining(learningRate: Float, epochs: Int): String

    external fun trainEpoch(epochIndex: Int): String

    external fun saveLoraAdapter(outputPath: String): String

    // ============================================
    // Inference functions
    // ============================================

    external fun generate(prompt: String, maxTokens: Int, temperature: Float): String

    external fun removeLoraAdapter()

    external fun hasAdapter(): Boolean

    external fun hasModel(): Boolean

    external fun cleanupLlama()

    companion object {
        init {
            System.loadLibrary("lora")
        }
    }
}
