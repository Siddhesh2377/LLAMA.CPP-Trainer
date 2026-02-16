#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstring>
#include <unistd.h>
#include <mutex>
#include <chrono>

#include "llama.h"
#include "common.h"
#include "ggml-backend.h"

#define LOG_TAG "LORA_INFERENCE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// JNI callbacks

static JavaVM    * g_jvm           = nullptr;
static jobject     g_log_callback  = nullptr;
static jmethodID   g_on_log        = nullptr;
static std::mutex  g_log_mutex;

// Streaming callback
static jobject     g_stream_callback = nullptr;
static jmethodID   g_on_token        = nullptr;
static jmethodID   g_on_complete     = nullptr;
static jmethodID   g_on_error        = nullptr;
static std::mutex  g_stream_mutex;

// Helper: Trim trailing incomplete UTF-8 multi-byte sequence (for streaming tokens).
static int utf8_complete_len(const char * buf, int len) {
    if (len <= 0) return 0;
    int i = len - 1;
    while (i >= 0 && ((unsigned char)buf[i] & 0xC0) == 0x80) i--;
    if (i < 0) return 0;
    unsigned char lead = (unsigned char)buf[i];
    int expected;
    if ((lead & 0x80) == 0)         expected = 1;
    else if ((lead & 0xE0) == 0xC0) expected = 2;
    else if ((lead & 0xF0) == 0xE0) expected = 3;
    else if ((lead & 0xF8) == 0xF0) expected = 4;
    else return i;
    if ((len - i) >= expected) return len;
    return i;
}

// Helper: Sanitize a string in-place for JNI NewStringUTF.
// Replaces invalid/incomplete UTF-8 sequences with '?' anywhere in the string.
static void utf8_sanitize(char * buf) {
    unsigned char * p = (unsigned char *)buf;
    while (*p) {
        if (*p < 0x80) { p++; continue; } // ASCII
        int expected;
        if ((*p & 0xE0) == 0xC0)      expected = 2;
        else if ((*p & 0xF0) == 0xE0) expected = 3;
        else if ((*p & 0xF8) == 0xF0) expected = 4;
        else { *p = '?'; p++; continue; } // invalid lead byte
        // Check continuation bytes
        bool valid = true;
        for (int j = 1; j < expected; j++) {
            if ((p[j] & 0xC0) != 0x80) { valid = false; break; }
        }
        if (valid) { p += expected; }
        else { *p = '?'; p++; } // replace bad lead, re-scan from next byte
    }
}

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
    if (!g_jvm || !g_log_callback || !g_on_log) return;

    JNIEnv * env = nullptr;
    bool attached = false;
    int status = g_jvm->GetEnv((void **)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        g_jvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    if (env) {
        // Sanitize: llama.cpp can truncate strings mid-UTF-8 character (e.g. tokenizer merges)
        utf8_sanitize(buf);
        jstring jmsg = env->NewStringUTF(buf);
        env->CallVoidMethod(g_log_callback, g_on_log, jmsg);
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

    // Forward to UI
    ui_log("[llama] %s", msg.c_str());
}

// Global inference state

static llama_model                * g_model   = nullptr;
static llama_context              * g_context = nullptr;
static llama_adapter_lora         * g_adapter = nullptr;
static bool                         g_backend_initialized = false;

// Helper: convert jstring to std::string
static std::string jstring_to_string(JNIEnv * env, jstring jstr) {
    if (!jstr) return "";
    const char * chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

// JNI: Register log callback

extern "C" JNIEXPORT void JNICALL
Java_com_dark_lora_LoraJNI_setLogCallback(
        JNIEnv * env, jobject /* this */,
        jobject callback) {
    std::lock_guard<std::mutex> lock(g_log_mutex);

    env->GetJavaVM(&g_jvm);

    // Release old callback
    if (g_log_callback) {
        env->DeleteGlobalRef(g_log_callback);
        g_log_callback = nullptr;
    }

    if (callback) {
        g_log_callback = env->NewGlobalRef(callback);
        jclass cls = env->GetObjectClass(callback);
        g_on_log = env->GetMethodID(cls, "onLog", "(Ljava/lang/String;)V");
    }
}

// JNI: Register stream callback

extern "C" JNIEXPORT void JNICALL
Java_com_dark_lora_LoraJNI_setStreamCallback(
        JNIEnv * env, jobject /* this */,
        jobject callback) {
    std::lock_guard<std::mutex> lock(g_stream_mutex);

    if (!g_jvm) env->GetJavaVM(&g_jvm);

    // Release old callback
    if (g_stream_callback) {
        env->DeleteGlobalRef(g_stream_callback);
        g_stream_callback = nullptr;
    }

    if (callback) {
        g_stream_callback = env->NewGlobalRef(callback);
        jclass cls = env->GetObjectClass(callback);
        g_on_token = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
        g_on_complete = env->GetMethodID(cls, "onComplete", "()V");
        g_on_error = env->GetMethodID(cls, "onError", "(Ljava/lang/String;)V");
    }
}

// JNI: Init Backend

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dark_lora_LoraJNI_initLlamaBackend(
        JNIEnv * env, jobject /* this */,
        jstring jNativeLibDir) {
    ui_log("Initializing llama.cpp backend...");

    llama_log_set(log_callback, nullptr);

    std::string native_lib_dir = jstring_to_string(env, jNativeLibDir);
    ui_log("Loading backends from: %s", native_lib_dir.c_str());

    // Set ADSP_LIBRARY_PATH so FastRPC can find HTP skel libraries (libggml-htp-vXX.so)
    // Must be set BEFORE backend initialization
    setenv("ADSP_LIBRARY_PATH", native_lib_dir.c_str(), 1);
    ui_log("ADSP_LIBRARY_PATH set to: %s", native_lib_dir.c_str());

    // Enable Hexagon experimental ops (flash attention on HTP)
    setenv("GGML_HEXAGON_EXPERIMENTAL", "1", 1);

    ggml_backend_load_all_from_path(native_lib_dir.c_str());
    llama_backend_init();

    g_backend_initialized = true;
    ui_log("Backend initialized");
    return JNI_TRUE;
}

// JNI: Load Model

extern "C" JNIEXPORT jstring JNICALL
Java_com_dark_lora_LoraJNI_loadModel(
        JNIEnv * env, jobject /* this */,
        jstring jModelPath,
        jint nThreads,
        jint nCtx,
        jint nGpuLayers) {
    if (!g_backend_initialized) {
        return env->NewStringUTF("ERROR: Backend not initialized");
    }

    // Free previous
    if (g_context) { llama_free(g_context); g_context = nullptr; }
    if (g_model)   { llama_model_free(g_model); g_model = nullptr; }

    std::string model_path = jstring_to_string(env, jModelPath);
    ui_log("Loading model: %s", model_path.c_str());

    llama_model_params model_params = llama_model_default_params();
    model_params.use_mmap = false; // Load into RAM — avoids mmap page-fault stalls on Android

    // Offload layers to NPU (Hexagon HTP) if available
    int n_gpu = (nGpuLayers >= 0) ? nGpuLayers : 99;
    model_params.n_gpu_layers = n_gpu;
    ui_log("use_mmap=false, n_gpu_layers=%d", n_gpu);

    g_model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (!g_model) {
        return env->NewStringUTF("ERROR: Failed to load model");
    }

    int n_cpus = (int) sysconf(_SC_NPROCESSORS_ONLN);
    int n_threads_actual = (nThreads > 0) ? nThreads :
        std::max(2, n_cpus - 2);
    int n_ctx_actual = (nCtx > 0) ? nCtx : 2048;

    ui_log("CPU cores: %d, using %d threads", n_cpus, n_threads_actual);
    ui_log("Context size: %d", n_ctx_actual);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx        = n_ctx_actual;
    ctx_params.n_batch      = 512;
    ctx_params.n_ubatch     = 256;
    ctx_params.n_threads       = n_threads_actual;
    ctx_params.n_threads_batch = n_threads_actual;
    // Only enable flash attention when using HTP (reduces graph splits on NPU)
    // For CPU-only, the standard attention path is faster
    if (n_gpu > 0) {
        ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    }

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

// JNI: Load LoRA adapter

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
        return env->NewStringUTF("ERROR: Failed to apply LoRA adapter");
    }

    ui_log("LoRA adapter loaded and applied");
    return env->NewStringUTF(("LoRA loaded from: " + lora_path).c_str());
}

// JNI: Generate text (inference)

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

    // Stop strings for text-based detection (catches multi-token BPE sequences)
    const char * stop_strs[] = {
        "<|im_end|>", "<|im_start|>",           // ChatML
        "<|eot_id|>", "<|start_header_id|>",    // Llama 3
        "<end_of_turn>", "<start_of_turn>",      // Gemma
        "<|end|>", "<|user|>", "<|assistant|>",  // Phi
        nullptr
    };

    // Generate tokens
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    std::string result;
    int n_generated = 0;
    int max_gen = (maxTokens > 0) ? maxTokens : 128;

    auto t_start = std::chrono::steady_clock::now();

    for (int i = 0; i < max_gen; i++) {
        llama_token new_token = llama_sampler_sample(smpl, g_context, -1);

        if (llama_vocab_is_eog(vocab, new_token)) {
            ui_log("EOG at token %d", i + 1);
            break;
        }

        // Convert token to text (special=false: don't render control tokens)
        char piece[256];
        int n = llama_token_to_piece(vocab, new_token, piece, sizeof(piece), 0, false);
        if (n > 0) {
            result.append(piece, n);
        }

        // Text-based stop sequence detection
        bool is_stop = false;
        for (int s = 0; stop_strs[s]; s++) {
            size_t stop_len = strlen(stop_strs[s]);
            if (result.size() >= stop_len &&
                result.compare(result.size() - stop_len, stop_len, stop_strs[s]) == 0) {
                result.resize(result.size() - stop_len);
                ui_log("Stop string '%s' at token %d", stop_strs[s], i + 1);
                is_stop = true;
                break;
            }
        }
        if (is_stop) break;

        // Decode single token
        batch = llama_batch_get_one(&new_token, 1);
        if (llama_decode(g_context, batch) != 0) {
            ui_log("Decode failed at token %d", i + 1);
            break;
        }
        n_generated++;
    }

    llama_sampler_free(smpl);

    auto t_end = std::chrono::steady_clock::now();
    double elapsed = std::chrono::duration<double>(t_end - t_start).count();
    ui_log("Generated %d tokens in %.2fs (%.1f tok/s)", n_generated, elapsed,
           elapsed > 0 ? n_generated / elapsed : 0.0);

    return env->NewStringUTF(result.c_str());
}

// Helper: Send error to stream callback (thread-safe, for early returns)

static void stream_error(const char * error_msg) {
    std::lock_guard<std::mutex> lock(g_stream_mutex);
    if (!g_jvm || !g_stream_callback || !g_on_error) return;

    JNIEnv * env = nullptr;
    bool attached = false;
    int status = g_jvm->GetEnv((void **)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        g_jvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    if (env) {
        // Sanitize in case error message contains truncated UTF-8
        std::string safe_msg(error_msg);
        utf8_sanitize(&safe_msg[0]);
        jstring jerror = env->NewStringUTF(safe_msg.c_str());
        env->CallVoidMethod(g_stream_callback, g_on_error, jerror);
        env->DeleteLocalRef(jerror);
    }
    if (attached) {
        g_jvm->DetachCurrentThread();
    }
}

// JNI: Generate text (streaming)

extern "C" JNIEXPORT void JNICALL
Java_com_dark_lora_LoraJNI_generateStreaming(
        JNIEnv * env, jobject /* this */,
        jstring jPrompt,
        jint maxTokens,
        jfloat temperature) {
    if (!g_model || !g_context) {
        stream_error("ERROR: Model not loaded");
        return;
    }

    std::string prompt = jstring_to_string(env, jPrompt);
    ui_log("Streaming generation: prompt=%zu chars, max_tokens=%d, temp=%.2f",
           prompt.length(), maxTokens, (double) temperature);

    // Clear KV cache for fresh generation
    llama_memory_clear(llama_get_memory(g_context), true);

    // Tokenize prompt
    std::vector<llama_token> tokens = common_tokenize(g_context, prompt, true);
    ui_log("Prompt tokens: %zu", tokens.size());

    if (tokens.empty()) {
        stream_error("ERROR: Empty prompt after tokenization");
        return;
    }

    int n_ctx = llama_n_ctx(g_context);
    if ((int) tokens.size() >= n_ctx) {
        stream_error("ERROR: Prompt too long for context");
        return;
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

    // Batched prefill — process prompt in n_batch chunks, logits only on last token
    auto t_prefill_start = std::chrono::steady_clock::now();
    {
        const int n_batch_size = llama_n_batch(g_context);
        llama_batch batch = llama_batch_init(n_batch_size, 0, 1);
        size_t idx = 0;
        int32_t pos = 0;

        while (idx < tokens.size()) {
            const int32_t take = std::min<int32_t>(
                n_batch_size, (int32_t)(tokens.size() - idx));

            batch.n_tokens = take;
            for (int i = 0; i < take; ++i) {
                batch.token[i]     = tokens[idx + i];
                batch.pos[i]       = pos + i;
                batch.n_seq_id[i]  = 1;
                batch.seq_id[i][0] = 0;
                batch.logits[i]    = (idx + (size_t)i + 1 == tokens.size());
            }

            if (llama_decode(g_context, batch) != 0) {
                llama_batch_free(batch);
                llama_sampler_free(smpl);
                stream_error("ERROR: Failed to decode prompt");
                return;
            }

            pos += take;
            idx += (size_t)take;
        }
        llama_batch_free(batch);
    }

    auto t_prefill_end = std::chrono::steady_clock::now();
    double prefill_s = std::chrono::duration<double>(t_prefill_end - t_prefill_start).count();
    ui_log("Prefill done: %zu tokens in %.2fs (%.1f tok/s)", tokens.size(), prefill_s,
           prefill_s > 0 ? tokens.size() / prefill_s : 0.0);

    // Stop strings for text-based detection (catches multi-token BPE sequences)
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    const char * stop_strs[] = {
        "<|im_end|>", "<|im_start|>",           // ChatML
        "<|eot_id|>", "<|start_header_id|>",    // Llama 3
        "<end_of_turn>", "<start_of_turn>",      // Gemma
        "<|end|>", "<|user|>", "<|assistant|>",  // Phi
        nullptr
    };

    // Generate tokens — stream directly via env (no mutex / GetEnv overhead)
    std::string accumulated;  // Full generated text for stop detection
    int n_generated = 0;
    int max_gen = (maxTokens > 0) ? maxTokens : 128;
    int n_streamed_chars = 0;  // Characters already sent to UI

    auto t_gen_start = std::chrono::steady_clock::now();

    for (int i = 0; i < max_gen; i++) {
        llama_token new_token = llama_sampler_sample(smpl, g_context, -1);

        // Check EOG (single-token stop — catches proper special tokens)
        if (llama_vocab_is_eog(vocab, new_token)) {
            ui_log("EOG at token %d", i + 1);
            break;
        }

        // Convert token to text (special=false: don't render control tokens)
        char piece[256];
        int n = llama_token_to_piece(vocab, new_token, piece, sizeof(piece), 0, false);
        if (n > 0) {
            accumulated.append(piece, n);
        }

        // Text-based stop sequence detection (catches multi-token BPE sequences)
        bool is_stop = false;
        for (int s = 0; stop_strs[s]; s++) {
            size_t stop_len = strlen(stop_strs[s]);
            if (accumulated.size() >= stop_len &&
                accumulated.compare(accumulated.size() - stop_len, stop_len, stop_strs[s]) == 0) {
                accumulated.resize(accumulated.size() - stop_len);
                ui_log("Stop string '%s' at token %d", stop_strs[s], i + 1);
                is_stop = true;
                break;
            }
        }
        if (is_stop) {
            // Flush any remaining un-streamed text before the stop string
            if ((int) accumulated.size() > n_streamed_chars) {
                std::string remaining = accumulated.substr(n_streamed_chars);
                int safe_len = utf8_complete_len(remaining.c_str(), (int) remaining.size());
                if (safe_len > 0) {
                    jstring jtoken = env->NewStringUTF(remaining.substr(0, safe_len).c_str());
                    if (jtoken) {
                        env->CallVoidMethod(g_stream_callback, g_on_token, jtoken);
                        env->DeleteLocalRef(jtoken);
                    }
                }
            }
            break;
        }

        // Stream new characters to Kotlin (only the delta since last stream)
        if ((int) accumulated.size() > n_streamed_chars) {
            // Hold back characters that could be the start of a stop string
            int max_stop_len = 0;
            for (int s = 0; stop_strs[s]; s++) {
                int slen = (int) strlen(stop_strs[s]);
                if (slen > max_stop_len) max_stop_len = slen;
            }

            int safe_end = (int) accumulated.size() - max_stop_len;
            if (safe_end > n_streamed_chars) {
                // Ensure we don't split a multi-byte UTF-8 character
                int chunk_len = safe_end - n_streamed_chars;
                int safe_len = utf8_complete_len(accumulated.c_str() + n_streamed_chars, chunk_len);
                if (safe_len > 0) {
                    std::string chunk = accumulated.substr(n_streamed_chars, safe_len);
                    jstring jtoken = env->NewStringUTF(chunk.c_str());
                    if (jtoken) {
                        env->CallVoidMethod(g_stream_callback, g_on_token, jtoken);
                        env->DeleteLocalRef(jtoken);
                    }
                    n_streamed_chars += safe_len;
                }
            }
        }

        // Decode single token
        llama_batch gen_batch = llama_batch_get_one(&new_token, 1);
        if (llama_decode(g_context, gen_batch) != 0) {
            ui_log("Decode failed at token %d", i + 1);
            jstring jerr = env->NewStringUTF("Decode failed");
            if (jerr) {
                env->CallVoidMethod(g_stream_callback, g_on_error, jerr);
                env->DeleteLocalRef(jerr);
            }
            llama_sampler_free(smpl);
            return;
        }
        n_generated++;
    }

    // Flush any remaining buffered text
    if ((int) accumulated.size() > n_streamed_chars) {
        std::string remaining = accumulated.substr(n_streamed_chars);
        int safe_len = utf8_complete_len(remaining.c_str(), (int) remaining.size());
        if (safe_len > 0) {
            jstring jtoken = env->NewStringUTF(remaining.substr(0, safe_len).c_str());
            if (jtoken) {
                env->CallVoidMethod(g_stream_callback, g_on_token, jtoken);
                env->DeleteLocalRef(jtoken);
            }
        }
    }

    llama_sampler_free(smpl);

    auto t_gen_end = std::chrono::steady_clock::now();
    double gen_s = std::chrono::duration<double>(t_gen_end - t_gen_start).count();
    ui_log("Streamed %d tokens in %.2fs (%.1f tok/s)", n_generated, gen_s,
           gen_s > 0 ? n_generated / gen_s : 0.0);

    env->CallVoidMethod(g_stream_callback, g_on_complete);
}

// JNI: Remove LoRA adapter

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

// JNI: Check if adapter is loaded

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dark_lora_LoraJNI_hasAdapter(
        JNIEnv * /* env */, jobject /* this */) {
    return g_adapter != nullptr ? JNI_TRUE : JNI_FALSE;
}

// JNI: Check if model is loaded

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dark_lora_LoraJNI_hasModel(
        JNIEnv * /* env */, jobject /* this */) {
    return (g_model != nullptr && g_context != nullptr) ? JNI_TRUE : JNI_FALSE;
}

// JNI: Cleanup

extern "C" JNIEXPORT void JNICALL
Java_com_dark_lora_LoraJNI_cleanupLlama(
        JNIEnv * env, jobject /* this */) {
    ui_log("Cleaning up...");

    if (g_adapter && g_context) { llama_rm_adapter_lora(g_context, g_adapter); }
    if (g_adapter) { llama_adapter_lora_free(g_adapter); g_adapter = nullptr; }
    if (g_context) { llama_free(g_context); g_context = nullptr; }
    if (g_model)   { llama_model_free(g_model); g_model = nullptr; }
    if (g_backend_initialized) { llama_backend_free(); g_backend_initialized = false; }

    // Release callbacks
    {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        if (g_log_callback && env) {
            env->DeleteGlobalRef(g_log_callback);
            g_log_callback = nullptr;
        }
    }
    {
        std::lock_guard<std::mutex> lock(g_stream_mutex);
        if (g_stream_callback && env) {
            env->DeleteGlobalRef(g_stream_callback);
            g_stream_callback = nullptr;
        }
    }

    LOGI("Cleanup complete");
}
