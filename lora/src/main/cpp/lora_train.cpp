#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstring>
#include <unistd.h>
#include <mutex>

#include "llama.h"
#include "common.h"
#include "ggml-opt.h"
#include "ggml-backend.h"

#define LOG_TAG "LORA_TRAIN"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// ============================================
// JNI callback to pipe logs to Kotlin UI
// ============================================
static JavaVM    * g_jvm      = nullptr;
static jobject     g_callback = nullptr;
static jmethodID   g_on_log   = nullptr;
static std::mutex  g_log_mutex;

// Send a log message to Kotlin UI (thread-safe)
static void ui_log(const char * fmt, ...) {
    char buf[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);

    // Always log to logcat
    LOGI("%s", buf);

    // Forward to Kotlin callback if available
    std::lock_guard<std::mutex> lock(g_log_mutex);
    if (!g_jvm || !g_callback || !g_on_log) return;

    JNIEnv * env = nullptr;
    bool attached = false;
    int status = g_jvm->GetEnv((void **)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        g_jvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    if (env) {
        jstring jmsg = env->NewStringUTF(buf);
        env->CallVoidMethod(g_callback, g_on_log, jmsg);
        env->DeleteLocalRef(jmsg);
    }
    if (attached) {
        g_jvm->DetachCurrentThread();
    }
}

// llama.cpp log callback — forwards ALL messages to both logcat and UI
static void log_callback(enum ggml_log_level level, const char * text, void * /* user_data */) {
    // Skip empty/whitespace-only messages
    if (!text || text[0] == '\0' || (text[0] == '\n' && text[1] == '\0')) return;

    // Strip trailing newline for cleaner UI display
    std::string msg(text);
    while (!msg.empty() && msg.back() == '\n') msg.pop_back();
    if (msg.empty()) return;

    // Forward ALL messages to UI — no filtering
    ui_log("[llama] %s", msg.c_str());
}

// Training progress callback — called after EVERY batch
static void train_progress_callback(
        bool               train,
        ggml_opt_context_t /* opt_ctx */,
        ggml_opt_dataset_t /* dataset */,
        ggml_opt_result_t  result,
        int64_t            ibatch,
        int64_t            ibatch_max,
        int64_t            t_start_us) {

    double loss = 0.0;
    ggml_opt_result_loss(result, &loss, nullptr);

    double elapsed_s = (double)(ggml_time_us() - t_start_us) / 1e6;
    double batches_per_sec = (ibatch + 1) / (elapsed_s > 0 ? elapsed_s : 1.0);

    const char * phase = train ? "TRAIN" : "EVAL";
    ui_log("[%s] batch %lld/%lld | loss: %.4f | %.2f batch/s | %.1fs elapsed",
           phase, (long long)(ibatch + 1), (long long)ibatch_max,
           loss, batches_per_sec, elapsed_s);
}

// ============================================
// Global training state
// ============================================
static llama_model                * g_model   = nullptr;
static llama_context              * g_context = nullptr;
static llama_adapter_lora         * g_adapter = nullptr;
static ggml_opt_dataset_t           g_dataset = nullptr;
static struct lr_opt                g_lr;
static bool                         g_backend_initialized = false;

// Helper: convert jstring to std::string
static std::string jstring_to_string(JNIEnv * env, jstring jstr) {
    if (!jstr) return "";
    const char * chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

// ============================================
// JNI: Register log callback
// ============================================
extern "C" JNIEXPORT void JNICALL
Java_com_dark_lora_LoraJNI_setLogCallback(
        JNIEnv * env, jobject /* this */,
        jobject callback) {
    std::lock_guard<std::mutex> lock(g_log_mutex);

    env->GetJavaVM(&g_jvm);

    // Release old callback
    if (g_callback) {
        env->DeleteGlobalRef(g_callback);
        g_callback = nullptr;
    }

    if (callback) {
        g_callback = env->NewGlobalRef(callback);
        jclass cls = env->GetObjectClass(callback);
        g_on_log = env->GetMethodID(cls, "onLog", "(Ljava/lang/String;)V");
    }
}

// ============================================
// JNI: Init Backend
// ============================================
extern "C" JNIEXPORT jboolean JNICALL
Java_com_dark_lora_LoraJNI_initLlamaBackend(
        JNIEnv * env, jobject /* this */,
        jstring jNativeLibDir) {
    ui_log("Initializing llama.cpp backend...");

    llama_log_set(log_callback, nullptr);

    std::string native_lib_dir = jstring_to_string(env, jNativeLibDir);
    ui_log("Loading backends from: %s", native_lib_dir.c_str());

    ggml_backend_load_all_from_path(native_lib_dir.c_str());
    llama_backend_init();

    g_backend_initialized = true;
    ui_log("Backend initialized (CPU)");
    return JNI_TRUE;
}

// ============================================
// JNI: Load Model
// ============================================
extern "C" JNIEXPORT jstring JNICALL
Java_com_dark_lora_LoraJNI_loadModel(
        JNIEnv * env, jobject /* this */,
        jstring jModelPath,
        jint nThreads,
        jint nCtx) {
    if (!g_backend_initialized) {
        return env->NewStringUTF("ERROR: Backend not initialized");
    }

    // Free previous
    if (g_context) { llama_free(g_context); g_context = nullptr; }
    if (g_model)   { llama_model_free(g_model); g_model = nullptr; }

    std::string model_path = jstring_to_string(env, jModelPath);
    ui_log("Loading model: %s", model_path.c_str());

    llama_model_params model_params = llama_model_default_params();
    model_params.use_mmap = false;
    ui_log("use_mmap=false (required for training)");

    g_model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (!g_model) {
        return env->NewStringUTF("ERROR: Failed to load model");
    }

    int n_cpus = (int) sysconf(_SC_NPROCESSORS_ONLN);
    int n_threads_actual = (nThreads > 0) ? nThreads :
        std::max(2, n_cpus - 2);
    int n_ctx_actual = (nCtx > 0) ? nCtx : 512;

    ui_log("CPU cores: %d, using %d threads", n_cpus, n_threads_actual);
    ui_log("Context size: %d, F32 KV cache, flash_attn=off", n_ctx_actual);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx        = n_ctx_actual;
    ctx_params.n_batch      = n_ctx_actual;
    ctx_params.n_ubatch     = n_ctx_actual;
    ctx_params.n_threads    = n_threads_actual;
    ctx_params.n_threads_batch = n_threads_actual;
    ctx_params.type_k = GGML_TYPE_F32;
    ctx_params.type_v = GGML_TYPE_F32;
    ctx_params.flash_attn_type = static_cast<llama_flash_attn_type>(0);

    g_context = llama_init_from_model(g_model, ctx_params);
    if (!g_context) {
        llama_model_free(g_model);
        g_model = nullptr;
        return env->NewStringUTF("ERROR: Failed to create context");
    }

    char model_desc[256];
    llama_model_desc(g_model, model_desc, sizeof(model_desc));
    double model_size_gb = (double) llama_model_size(g_model) / 1024.0 / 1024.0 / 1024.0;

    std::string result = "Model loaded: " + std::string(model_desc);
    result += " (" + std::to_string(model_size_gb).substr(0, 4) + " GB)";
    result += "\nThreads: " + std::to_string(n_threads_actual);
    result += " | Context: " + std::to_string(n_ctx_actual);

    ui_log("Model: %s (%.2f GB)", model_desc, model_size_gb);
    return env->NewStringUTF(result.c_str());
}

// ============================================
// JNI: Create LoRA Adapter
// ============================================
extern "C" JNIEXPORT jstring JNICALL
Java_com_dark_lora_LoraJNI_createLoraAdapter(
        JNIEnv * env, jobject /* this */,
        jint rank,
        jfloat alpha,
        jint nLayersSkip) {
    if (!g_model || !g_context) {
        return env->NewStringUTF("ERROR: Model not loaded");
    }

    ui_log("Creating LoRA adapter (rank=%d, alpha=%.1f, skip_layers=%d)...", rank, (double) alpha, nLayersSkip);

    // Free previous adapter
    if (g_adapter) {
        llama_rm_adapter_lora(g_context, g_adapter);
        llama_adapter_lora_free(g_adapter);
        g_adapter = nullptr;
    }

    g_adapter = llama_adapter_lora_create(g_model, rank, alpha, nullptr, nLayersSkip);
    if (!g_adapter) {
        return env->NewStringUTF("ERROR: Failed to create LoRA adapter");
    }

    int32_t ret = llama_set_adapter_lora(g_context, g_adapter, 1.0f);
    if (ret != 0) {
        llama_adapter_lora_free(g_adapter);
        g_adapter = nullptr;
        return env->NewStringUTF("ERROR: Failed to apply LoRA adapter");
    }

    ui_log("LoRA adapter applied to context");

    std::string result = "LoRA adapter created (rank=" + std::to_string(rank);
    result += ", alpha=" + std::to_string(alpha);
    result += ", skip=" + std::to_string(nLayersSkip) + ")";
    return env->NewStringUTF(result.c_str());
}

// ============================================
// JNI: Load existing LoRA adapter
// ============================================
extern "C" JNIEXPORT jstring JNICALL
Java_com_dark_lora_LoraJNI_loadLoraAdapter(
        JNIEnv * env, jobject /* this */,
        jstring jLoraPath) {
    if (!g_model || !g_context) {
        return env->NewStringUTF("ERROR: Model not loaded");
    }

    if (g_adapter) {
        llama_rm_adapter_lora(g_context, g_adapter);
        llama_adapter_lora_free(g_adapter);
        g_adapter = nullptr;
    }

    std::string lora_path = jstring_to_string(env, jLoraPath);
    ui_log("Loading LoRA adapter from: %s", lora_path.c_str());

    g_adapter = llama_adapter_lora_init(g_model, lora_path.c_str());
    if (!g_adapter) {
        return env->NewStringUTF("ERROR: Failed to load LoRA adapter");
    }

    int32_t ret = llama_set_adapter_lora(g_context, g_adapter, 1.0f);
    if (ret != 0) {
        llama_adapter_lora_free(g_adapter);
        g_adapter = nullptr;
        return env->NewStringUTF("ERROR: Failed to apply loaded LoRA adapter");
    }

    ui_log("LoRA adapter loaded and applied");
    return env->NewStringUTF(("LoRA loaded from: " + lora_path).c_str());
}

// ============================================
// JNI: Set Training Data
// ============================================
extern "C" JNIEXPORT jstring JNICALL
Java_com_dark_lora_LoraJNI_setTrainingData(
        JNIEnv * env, jobject /* this */,
        jstring jTrainingText) {
    if (!g_context) {
        return env->NewStringUTF("ERROR: Context not initialized");
    }

    if (g_dataset) { ggml_opt_dataset_free(g_dataset); g_dataset = nullptr; }

    std::string training_text = jstring_to_string(env, jTrainingText);
    ui_log("Training text: %zu chars", training_text.length());

    std::vector<llama_token> tokens = common_tokenize(g_context, training_text, true);
    ui_log("Tokenized: %zu tokens", tokens.size());

    int n_ctx = llama_n_ctx(g_context);

    if ((int) tokens.size() < 2) {
        return env->NewStringUTF("ERROR: Training text too short");
    }

    int64_t stride = std::max((int64_t) 1, (int64_t) n_ctx / 2);
    size_t original_size = tokens.size();
    size_t min_tokens = (size_t)(n_ctx + 1 + stride);

    if (tokens.size() < min_tokens) {
        std::vector<llama_token> original(tokens);
        while (tokens.size() < min_tokens) {
            tokens.insert(tokens.end(), original.begin(), original.end());
        }
        ui_log("Padded tokens: %zu -> %zu (min needed: %zu)", original_size, tokens.size(), min_tokens);
    }

    g_dataset = common_opt_dataset_init(g_context, tokens, stride);
    int64_t ndata = ggml_opt_dataset_ndata(g_dataset);

    ui_log("Dataset: %lld data points, stride=%lld, ctx=%d", (long long) ndata, (long long) stride, n_ctx);

    std::string result = "Data: " + std::to_string(original_size) + " tokens";
    result += " -> " + std::to_string(ndata) + " data points";
    return env->NewStringUTF(result.c_str());
}

// ============================================
// JNI: Init Training
// ============================================
extern "C" JNIEXPORT jstring JNICALL
Java_com_dark_lora_LoraJNI_initTraining(
        JNIEnv * env, jobject /* this */,
        jfloat learningRate,
        jint epochs) {
    if (!g_context || !g_model || !g_adapter) {
        return env->NewStringUTF("ERROR: Model, context, or adapter not ready");
    }

    ui_log("Initializing AdamW optimizer (lr=%.6f, epochs=%d)...", (double) learningRate, epochs);

    g_lr.lr0    = learningRate;
    g_lr.lr_min = learningRate * 0.1f;
    g_lr.epochs = (unsigned) epochs;
    g_lr.wd     = 0.0f;
    g_lr.decay_epochs = -1;
    g_lr.init();

    struct llama_opt_params lopt_params {
        /*n_ctx_train     =*/ 0,
        /*param_filter    =*/ llama_opt_param_filter_lora,
        /*param_filter_ud =*/ nullptr,
        /*get_opt_pars    =*/ common_opt_lr_pars,
        /*get_opt_pars_ud =*/ &g_lr,
        /*optimizer_type  =*/ GGML_OPT_OPTIMIZER_TYPE_ADAMW,
    };
    llama_opt_init(g_context, g_model, lopt_params);

    ui_log("Optimizer ready. Training only LoRA A/B tensors (base model frozen).");

    std::string result = "Optimizer: AdamW | LR: " + std::to_string(learningRate);
    return env->NewStringUTF(result.c_str());
}

// ============================================
// JNI: Train Epoch
// ============================================
extern "C" JNIEXPORT jstring JNICALL
Java_com_dark_lora_LoraJNI_trainEpoch(
        JNIEnv * env, jobject /* this */,
        jint epochIndex) {
    if (!g_context || !g_dataset) {
        return env->NewStringUTF("ERROR: Training not initialized");
    }

    g_lr.epoch = (unsigned) epochIndex;

    int64_t ndata = ggml_opt_dataset_ndata(g_dataset);
    // Reserve at least 1 data point for eval, but only if we have enough data
    int64_t idata_split;
    bool has_eval;
    if (ndata >= 2) {
        idata_split = std::max((int64_t) 1, ndata * 95 / 100);
        // Ensure at least 1 eval point
        if (idata_split >= ndata) idata_split = ndata - 1;
        has_eval = true;
    } else {
        // Only 1 data point — use all for training, no eval
        idata_split = ndata;
        has_eval = false;
    }

    ui_log("========================================");
    ui_log("=== EPOCH %d START ===", epochIndex + 1);
    ui_log("========================================");
    ui_log("Total data points: %lld", (long long) ndata);
    ui_log("Train split: %lld data points", (long long) idata_split);
    ui_log("Eval split: %lld data points%s", (long long)(ndata - idata_split),
           has_eval ? "" : " (skipped)");
    ui_log("Learning rate: %.6f", g_lr.get_lr());
    ui_log("Building computation graph (forward + backward)...");

    int64_t t_epoch_start = ggml_time_us();

    ggml_opt_result_t result_train = ggml_opt_result_init();
    ggml_opt_result_t result_eval  = has_eval ? ggml_opt_result_init() : nullptr;

    ui_log("--- Training phase ---");
    llama_opt_epoch(g_context, g_dataset,
                    result_train, result_eval, idata_split,
                    train_progress_callback, has_eval ? train_progress_callback : nullptr);

    double train_loss = 0.0, eval_loss = 0.0;
    ggml_opt_result_loss(result_train, &train_loss, nullptr);
    if (has_eval && result_eval) {
        ggml_opt_result_loss(result_eval, &eval_loss, nullptr);
    }

    double epoch_time_s = (double)(ggml_time_us() - t_epoch_start) / 1e6;

    ggml_opt_result_free(result_train);
    if (result_eval) ggml_opt_result_free(result_eval);

    ui_log("========================================");
    ui_log("=== EPOCH %d COMPLETE ===", epochIndex + 1);
    ui_log("  Train loss: %.4f", train_loss);
    if (has_eval) {
        ui_log("  Eval loss:  %.4f", eval_loss);
    } else {
        ui_log("  Eval loss:  (skipped — not enough data)");
    }
    ui_log("  LR:         %.6f", g_lr.get_lr());
    ui_log("  Time:       %.1fs", epoch_time_s);
    ui_log("========================================");

    std::string result = "Epoch " + std::to_string(epochIndex + 1);
    result += " | Train loss: " + std::to_string(train_loss);
    if (has_eval) {
        result += " | Eval loss: " + std::to_string(eval_loss);
    }
    result += " | Time: " + std::to_string((int) epoch_time_s) + "s";
    return env->NewStringUTF(result.c_str());
}

// ============================================
// JNI: Save LoRA Adapter
// ============================================
extern "C" JNIEXPORT jstring JNICALL
Java_com_dark_lora_LoraJNI_saveLoraAdapter(
        JNIEnv * env, jobject /* this */,
        jstring jOutputPath) {
    if (!g_adapter) {
        return env->NewStringUTF("ERROR: No adapter to save");
    }

    std::string output_path = jstring_to_string(env, jOutputPath);
    ui_log("Saving LoRA adapter to: %s", output_path.c_str());

    int32_t ret = llama_lora_save_adapter(g_adapter, output_path.c_str());
    if (ret != 0) {
        return env->NewStringUTF("ERROR: Failed to save adapter");
    }

    ui_log("Adapter saved successfully!");
    return env->NewStringUTF(("Saved: " + output_path).c_str());
}

// ============================================
// JNI: Generate text (inference)
// ============================================
extern "C" JNIEXPORT jstring JNICALL
Java_com_dark_lora_LoraJNI_generate(
        JNIEnv * env, jobject /* this */,
        jstring jPrompt,
        jint maxTokens,
        jfloat temperature) {
    if (!g_model || !g_context) {
        return env->NewStringUTF("ERROR: Model not loaded");
    }

    std::string prompt = jstring_to_string(env, jPrompt);
    ui_log("Generating: prompt=%zu chars, max_tokens=%d, temp=%.2f",
           prompt.length(), maxTokens, (double) temperature);

    // Clear KV cache for fresh generation
    llama_memory_clear(llama_get_memory(g_context), true);

    // Tokenize prompt
    std::vector<llama_token> tokens = common_tokenize(g_context, prompt, true);
    ui_log("Prompt tokens: %zu", tokens.size());

    if (tokens.empty()) {
        return env->NewStringUTF("ERROR: Empty prompt after tokenization");
    }

    int n_ctx = llama_n_ctx(g_context);
    if ((int) tokens.size() >= n_ctx) {
        return env->NewStringUTF("ERROR: Prompt too long for context");
    }

    // Create sampler
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(0));
    }

    // Process prompt
    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    if (llama_decode(g_context, batch) != 0) {
        llama_sampler_free(smpl);
        return env->NewStringUTF("ERROR: Failed to decode prompt");
    }

    // Generate tokens
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    std::string result;
    int n_generated = 0;
    int max_gen = (maxTokens > 0) ? maxTokens : 128;

    for (int i = 0; i < max_gen; i++) {
        llama_token new_token = llama_sampler_sample(smpl, g_context, -1);

        if (llama_vocab_is_eog(vocab, new_token)) {
            ui_log("EOS at token %d", i + 1);
            break;
        }

        // Convert token to text
        char piece[256];
        int n = llama_token_to_piece(vocab, new_token, piece, sizeof(piece), 0, true);
        if (n > 0) {
            result.append(piece, n);
        }

        // Decode single token
        batch = llama_batch_get_one(&new_token, 1);
        if (llama_decode(g_context, batch) != 0) {
            ui_log("Decode failed at token %d", i + 1);
            break;
        }
        n_generated++;
    }

    llama_sampler_free(smpl);
    ui_log("Generated %d tokens", n_generated);

    return env->NewStringUTF(result.c_str());
}

// ============================================
// JNI: Remove LoRA adapter (for comparison)
// ============================================
extern "C" JNIEXPORT void JNICALL
Java_com_dark_lora_LoraJNI_removeLoraAdapter(
        JNIEnv * /* env */, jobject /* this */) {
    if (g_adapter && g_context) {
        llama_rm_adapter_lora(g_context, g_adapter);
        llama_adapter_lora_free(g_adapter);
        g_adapter = nullptr;
        ui_log("LoRA adapter removed");
    }
}

// ============================================
// JNI: Check if adapter is loaded
// ============================================
extern "C" JNIEXPORT jboolean JNICALL
Java_com_dark_lora_LoraJNI_hasAdapter(
        JNIEnv * /* env */, jobject /* this */) {
    return g_adapter != nullptr ? JNI_TRUE : JNI_FALSE;
}

// ============================================
// JNI: Check if model is loaded
// ============================================
extern "C" JNIEXPORT jboolean JNICALL
Java_com_dark_lora_LoraJNI_hasModel(
        JNIEnv * /* env */, jobject /* this */) {
    return (g_model != nullptr && g_context != nullptr) ? JNI_TRUE : JNI_FALSE;
}

// ============================================
// JNI: Cleanup
// ============================================
extern "C" JNIEXPORT void JNICALL
Java_com_dark_lora_LoraJNI_cleanupLlama(
        JNIEnv * env, jobject /* this */) {
    ui_log("Cleaning up...");

    if (g_dataset) { ggml_opt_dataset_free(g_dataset); g_dataset = nullptr; }
    if (g_adapter && g_context) { llama_rm_adapter_lora(g_context, g_adapter); }
    if (g_adapter) { llama_adapter_lora_free(g_adapter); g_adapter = nullptr; }
    if (g_context) { llama_free(g_context); g_context = nullptr; }
    if (g_model)   { llama_model_free(g_model); g_model = nullptr; }
    if (g_backend_initialized) { llama_backend_free(); g_backend_initialized = false; }

    // Release callback
    {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        if (g_callback && env) {
            env->DeleteGlobalRef(g_callback);
            g_callback = nullptr;
        }
    }

    LOGI("Cleanup complete");
}
