#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <string>

// QNN headers
#include "QnnInterface.h"
#include "QnnTypes.h"
#include "HTP/QnnHtpDevice.h"

#define LOG_TAG "LORA_QNN"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global QNN state
static void* g_cdsprpc_handle = nullptr;
static void* g_qnn_lib_handle = nullptr;
static const QnnInterface_t* g_qnn_interface = nullptr;
static Qnn_BackendHandle_t g_qnn_backend = nullptr;
static Qnn_ContextHandle_t g_qnn_context = nullptr;

extern "C" JNIEXPORT jstring JNICALL
Java_com_dark_lora_LoraJNI_testQNN(JNIEnv* env, jobject /* this */) {
    LOGI("=== QNN Test Started ===");

    // Step 0: Load system DSP library (REQUIRED for HTP)
    LOGI("Loading system DSP library...");

    // Try multiple possible locations
    const char* cdsprpc_paths[] = {
            "libcdsprpc.so",                    // Try system path first
            "/vendor/lib64/libcdsprpc.so",      // Vendor path
            "/system/lib64/libcdsprpc.so"       // System path
    };

    for (const char* path : cdsprpc_paths) {
        g_cdsprpc_handle = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
        if (g_cdsprpc_handle) {
            LOGI("✓ Loaded DSP library from: %s", path);
            break;
        }
    }

    if (!g_cdsprpc_handle) {
        std::string error = "Failed to load libcdsprpc.so (DSP communication library)\n";
        error += "This is a Qualcomm system library required for NPU access.\n";
        error += "Error: ";
        error += dlerror();
        LOGE("%s", error.c_str());
        return env->NewStringUTF(error.c_str());
    }

    // Step 1: Load QNN HTP library
    LOGI("Loading QNN HTP library...");
    g_qnn_lib_handle = dlopen("libQnnHtp.so", RTLD_NOW | RTLD_LOCAL);

    if (!g_qnn_lib_handle) {
        std::string error = "Failed to load libQnnHtp.so: ";
        error += dlerror();
        LOGE("%s", error.c_str());
        return env->NewStringUTF(error.c_str());
    }

    LOGI("✓ QNN HTP library loaded successfully");

    // Step 2: Get QNN interface
    LOGI("Getting QNN interface...");

    typedef Qnn_ErrorHandle_t (*QnnInterfaceGetProvidersFn_t)(
            const QnnInterface_t*** providerList,
            uint32_t* numProviders);

    auto getProviders = reinterpret_cast<QnnInterfaceGetProvidersFn_t>(
            dlsym(g_qnn_lib_handle, "QnnInterface_getProviders"));

    if (!getProviders) {
        std::string error = "Failed to find QnnInterface_getProviders";
        LOGE("%s", error.c_str());
        return env->NewStringUTF(error.c_str());
    }

    const QnnInterface_t** providers = nullptr;
    uint32_t num_providers = 0;

    Qnn_ErrorHandle_t result = getProviders(&providers, &num_providers);
    if (result != QNN_SUCCESS || num_providers == 0) {
        std::string error = "Failed to get QNN providers";
        LOGE("%s", error.c_str());
        return env->NewStringUTF(error.c_str());
    }

    LOGI("✓ Found %d QNN provider(s)", num_providers);

    // Store pointer to interface
    g_qnn_interface = providers[0];

    // Access the implementation via the union member
    const QNN_INTERFACE_VER_TYPE& qnn_impl = g_qnn_interface->QNN_INTERFACE_VER_NAME;

    LOGI("QNN API Version: %u.%u.%u",
         g_qnn_interface->apiVersion.coreApiVersion.major,
         g_qnn_interface->apiVersion.coreApiVersion.minor,
         g_qnn_interface->apiVersion.coreApiVersion.patch);

    // Step 3: Create Backend (REQUIRED before context)
    LOGI("Creating QNN backend...");

    result = qnn_impl.backendCreate(
            nullptr,  // log handle
            nullptr,  // config
            &g_qnn_backend);

    if (result != QNN_SUCCESS) {
        std::string error = "Failed to create QNN backend: " + std::to_string(result);
        LOGE("%s", error.c_str());
        return env->NewStringUTF(error.c_str());
    }

    LOGI("✓ QNN backend created successfully");

    // Step 4: Create QNN context
    LOGI("Creating QNN context...");

    QnnContext_Config_t context_config = QNN_CONTEXT_CONFIG_INIT;

    result = qnn_impl.contextCreate(
            g_qnn_backend,  // backend handle
            nullptr,        // device handle
            reinterpret_cast<const QnnContext_Config_t **>(&context_config),
            &g_qnn_context);

    if (result != QNN_SUCCESS) {
        std::string error = "Failed to create QNN context: " + std::to_string(result);
        LOGE("%s", error.c_str());
        return env->NewStringUTF(error.c_str());
    }

    LOGI("✓ QNN context created successfully");

    // Success message
    std::string success = "✅ QNN NPU INITIALIZED SUCCESSFULLY!\n\n";
    success += "🔥 Backend: Hexagon HTP (NPU)\n";
    success += "📊 API Version: " +
               std::to_string(g_qnn_interface->apiVersion.coreApiVersion.major) + "." +
               std::to_string(g_qnn_interface->apiVersion.coreApiVersion.minor) + "." +
               std::to_string(g_qnn_interface->apiVersion.coreApiVersion.patch) + "\n";
    success += "✅ Status: NPU ONLINE\n";
    success += "📱 Device: Snapdragon 7s Gen 3\n\n";
    success += "🚀 Ready for matrix operations on NPU!\n";
    success += "💪 LoRA training pipeline ready!";

    LOGI("=== QNN Test Completed Successfully ===");

    return env->NewStringUTF(success.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_dark_lora_LoraJNI_cleanupQNN(JNIEnv* env, jobject /* this */) {
    LOGI("Cleaning up QNN...");

    if (g_qnn_interface) {
        const QNN_INTERFACE_VER_TYPE& qnn_impl = g_qnn_interface->QNN_INTERFACE_VER_NAME;

        if (g_qnn_context) {
            qnn_impl.contextFree(g_qnn_context, nullptr);
            g_qnn_context = nullptr;
            LOGI("✓ QNN context freed");
        }

        if (g_qnn_backend) {
            qnn_impl.backendFree(g_qnn_backend);
            g_qnn_backend = nullptr;
            LOGI("✓ QNN backend freed");
        }
    }

    if (g_qnn_lib_handle) {
        dlclose(g_qnn_lib_handle);
        g_qnn_lib_handle = nullptr;
        g_qnn_interface = nullptr;
        LOGI("✓ QNN library unloaded");
    }

    if (g_cdsprpc_handle) {
        dlclose(g_cdsprpc_handle);
        g_cdsprpc_handle = nullptr;
        LOGI("✓ DSP library unloaded");
    }

    LOGI("QNN cleanup complete");
}