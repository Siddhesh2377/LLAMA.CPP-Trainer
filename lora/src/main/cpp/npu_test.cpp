#include <cstdio>
#include <dlfcn.h>
#include <cstring>
#include <jni.h>
#include <unistd.h>

// QNN headers
#include "QnnInterface.h"
#include "QnnTypes.h"

// Logging macros
#define LOG(fmt, ...) printf("[NPU_TEST] " fmt "\n", ##__VA_ARGS__); fflush(stdout)
#define LOG_INFO(fmt, ...) printf("[NPU_TEST INFO] " fmt "\n", ##__VA_ARGS__); fflush(stdout)
#define LOG_WARN(fmt, ...) printf("[NPU_TEST WARN] " fmt "\n", ##__VA_ARGS__); fflush(stdout)
#define LOG_ERROR(fmt, ...) fprintf(stderr, "[NPU_TEST ERROR] " fmt "\n", ##__VA_ARGS__); fflush(stderr)

const char* qnnErrorToString(Qnn_ErrorHandle_t error) {
    switch(error) {
        case QNN_SUCCESS: return "QNN_SUCCESS";
        case QNN_ERROR_GENERAL: return "QNN_ERROR_GENERAL";
        case QNN_ERROR_NOT_SUPPORTED: return "QNN_ERROR_NOT_SUPPORTED";
        case QNN_ERROR_INVALID_ARGUMENT: return "QNN_ERROR_INVALID_ARGUMENT";
        case QNN_ERROR_INVALID_HANDLE: return "QNN_ERROR_INVALID_HANDLE";
        case QNN_ERROR_MEM_ALLOC: return "QNN_ERROR_MEM_ALLOC";
        default: {
            static char buf[32];
            snprintf(buf, sizeof(buf), "ERROR_CODE_%d", error);
            return buf;
        }
    }
}

// FastRPC structures for Unsigned PD
#define CDSP_DOMAIN_ID 3
#define DSPRPC_CONTROL_UNSIGNED_MODULE 4

struct remote_rpc_control_unsigned_module {
    int enable;
    int domain;
};

// Function pointer type
typedef int (*remote_session_control_fn)(uint32_t req, void* data, uint32_t datalen);

bool requestUnsignedPD() {
    LOG_INFO("Attempting to request Unsigned Protection Domain...");

    // Load libcdsprpc.so (should already be loaded, just get handle)
    void* libcdsprpc = dlopen("libcdsprpc.so", RTLD_NOLOAD | RTLD_NOW);
    if (!libcdsprpc) {
        // Try loading it fresh
        libcdsprpc = dlopen("/vendor/lib64/libcdsprpc.so", RTLD_NOW | RTLD_GLOBAL);
        if (!libcdsprpc) {
            LOG_ERROR("Failed to get libcdsprpc handle: %s", dlerror());
            return false;
        }
    }

    // Get weak symbol remote_session_control
    remote_session_control_fn remote_session_control =
            (remote_session_control_fn)dlsym(libcdsprpc, "remote_session_control");

    if (!remote_session_control) {
        LOG_WARN("remote_session_control not available (may be older device)");
        LOG_WARN("Continuing without Unsigned PD - may fail on retail devices");
        return false;  // Not a fatal error
    }

    // Request unsigned PD
    struct remote_rpc_control_unsigned_module data;
    data.enable = 1;
    data.domain = CDSP_DOMAIN_ID;

    int ret = remote_session_control(DSPRPC_CONTROL_UNSIGNED_MODULE,
                                     (void*)&data, sizeof(data));

    if (ret == 0) {
        LOG_INFO("✅ Unsigned PD enabled successfully!");
        LOG_INFO("   Created user PD on domain %d", CDSP_DOMAIN_ID);
        return true;
    } else {
        LOG_ERROR("❌ Unsigned PD request failed with code: %d", ret);
        LOG_ERROR("   This may cause context creation to fail");
        return false;
    }
}

int main(int argc, char** argv) {
    LOG("========================================");
    LOG("=== Standalone NPU Test with Unsigned PD ===");
    LOG("========================================");
    LOG("Process PID: %d, UID: %d", getpid(), getuid());
    LOG("");

    // ===================================================================
    // STEP 0: Request Unsigned PD FIRST (before loading QNN libraries)
    // ===================================================================
    LOG("[Step 0/6] Requesting Unsigned Protection Domain...");
    bool unsignedPdSuccess = requestUnsignedPD();
    if (!unsignedPdSuccess) {
        LOG_WARN("Unsigned PD not available - proceeding anyway");
        LOG_WARN("Note: May fail on retail devices with strict security");
    }
    LOG("");

    // ===================================================================
    // STEP 1: Load HTP stub (device-specific)
    // ===================================================================
    LOG("[Step 1/6] Loading libQnnHtpV73Stub.so...");
    void* htp_stub = dlopen("libQnnHtpV73Stub.so", RTLD_NOW | RTLD_GLOBAL);
    if (!htp_stub) {
        LOG_WARN("Failed to load HTP V73 stub: %s", dlerror());
        LOG_WARN("Continuing anyway - stub may not be required");
    } else {
        LOG("✓ libQnnHtpV73Stub.so loaded");
    }
    LOG("");

    // ===================================================================
    // STEP 2: Load vendor DSP library
    // ===================================================================
    LOG("[Step 2/6] Loading /vendor/lib64/libcdsprpc.so...");
    void* cdsprpc = dlopen("/vendor/lib64/libcdsprpc.so", RTLD_NOW | RTLD_GLOBAL);
    if (!cdsprpc) {
        LOG_ERROR("Failed to load libcdsprpc.so: %s", dlerror());
        if (htp_stub) dlclose(htp_stub);
        return 1;
    }
    LOG("✓ libcdsprpc.so loaded");
    LOG("");

    // ===================================================================
    // STEP 3: Load QNN System library
    // ===================================================================
    LOG("[Step 3/6] Loading libQnnSystem.so...");
    void* qnn_system = dlopen("libQnnSystem.so", RTLD_NOW | RTLD_GLOBAL);
    if (!qnn_system) {
        LOG_ERROR("Failed to load libQnnSystem.so: %s", dlerror());
        dlclose(cdsprpc);
        if (htp_stub) dlclose(htp_stub);
        return 1;
    }
    LOG("✓ libQnnSystem.so loaded");
    LOG("");

    // ===================================================================
    // STEP 4: Load QNN HTP library
    // ===================================================================
    LOG("[Step 4/6] Loading libQnnHtp.so...");
    void* qnn_lib = dlopen("libQnnHtp.so", RTLD_NOW | RTLD_LOCAL);
    if (!qnn_lib) {
        LOG_ERROR("Failed to load libQnnHtp.so: %s", dlerror());
        dlclose(qnn_system);
        dlclose(cdsprpc);
        if (htp_stub) dlclose(htp_stub);
        return 1;
    }
    LOG("✓ libQnnHtp.so loaded");
    LOG("");

    // ===================================================================
    // STEP 5: Get QNN interface
    // ===================================================================
    LOG("[Step 5/6] Getting QNN interface...");
    typedef Qnn_ErrorHandle_t (*GetProvidersFn)(const QnnInterface_t***, uint32_t*);
    auto getProviders = (GetProvidersFn)dlsym(qnn_lib, "QnnInterface_getProviders");

    if (!getProviders) {
        LOG_ERROR("Cannot find QnnInterface_getProviders: %s", dlerror());
        dlclose(qnn_lib);
        dlclose(qnn_system);
        dlclose(cdsprpc);
        if (htp_stub) dlclose(htp_stub);
        return 1;
    }

    const QnnInterface_t** providers = nullptr;
    uint32_t num_providers = 0;

    Qnn_ErrorHandle_t result = getProviders(&providers, &num_providers);
    if (result != QNN_SUCCESS || num_providers == 0) {
        LOG_ERROR("Failed to get providers (error: %s, count: %d)",
                  qnnErrorToString(result), num_providers);
        dlclose(qnn_lib);
        dlclose(qnn_system);
        dlclose(cdsprpc);
        if (htp_stub) dlclose(htp_stub);
        return 1;
    }

    LOG("✓ Found %d provider(s)", num_providers);
    LOG("  API Version: %u.%u.%u",
        providers[0]->apiVersion.coreApiVersion.major,
        providers[0]->apiVersion.coreApiVersion.minor,
        providers[0]->apiVersion.coreApiVersion.patch);
    LOG("");

    // ===================================================================
    // STEP 6: Create backend and context
    // ===================================================================
    const QNN_INTERFACE_VER_TYPE& qnn = providers[0]->QNN_INTERFACE_VER_NAME;

    Qnn_BackendHandle_t backend = nullptr;

    // Set up backend config with logging
    QnnBackend_Config_t logConfig = QNN_BACKEND_CONFIG_INIT;
    logConfig.option = QNN_BACKEND_CONFIG_OPTION_LOG_LEVEL;
    logConfig.logLevelConfig.logLevel = QNN_LOG_LEVEL_INFO;

    const QnnBackend_Config_t* backendConfigs[] = {&logConfig, nullptr};

    LOG("[Step 6/6] Creating backend...");
    result = qnn.backendCreate(nullptr, backendConfigs, &backend);
    if (result != QNN_SUCCESS) {
        LOG_ERROR("Failed to create backend (error: %s)", qnnErrorToString(result));
        dlclose(qnn_lib);
        dlclose(qnn_system);
        dlclose(cdsprpc);
        if (htp_stub) dlclose(htp_stub);
        return 1;
    }
    LOG("✓ Backend created (handle: %p)", backend);

    // Create context
    Qnn_ContextHandle_t context = nullptr;

    LOG("  Creating context...");
    result = qnn.contextCreate(backend, nullptr, nullptr, &context);

    if (result != QNN_SUCCESS) {
        LOG_ERROR("❌ Context creation FAILED!");
        LOG_ERROR("   Error: %s (code: %d)", qnnErrorToString(result), result);
        LOG_ERROR("");
        LOG_ERROR("Possible causes:");
        if (!unsignedPdSuccess) {
            LOG_ERROR("  - Unsigned PD was not enabled (see warning above)");
        }
        LOG_ERROR("  - SELinux restrictions blocking DSP access");
        LOG_ERROR("  - Insufficient permissions for /vendor/dsp/ firmware");
        LOG_ERROR("  - Device may require root/system privileges");
        LOG_ERROR("");
        LOG_ERROR("Workarounds:");
        LOG_ERROR("  1. Try running on a device with unlocked bootloader");
        LOG_ERROR("  2. Use QNN CPU backend instead (libQnnCpu.so)");
        LOG_ERROR("  3. Test on Samsung/Xiaomi device (more permissive than Pixel)");
        LOG_ERROR("  4. Check: adb shell getenforce (should show 'Permissive')");

        qnn.backendFree(backend);
        dlclose(qnn_lib);
        dlclose(qnn_system);
        dlclose(cdsprpc);
        if (htp_stub) dlclose(htp_stub);
        return 1;
    }

    LOG("✓ Context created (handle: %p)", context);
    LOG("");
    LOG("========================================");
    LOG("🎉🎉🎉 SUCCESS: NPU INITIALIZED! 🎉🎉🎉");
    LOG("========================================");
    LOG("Backend:      Qualcomm Hexagon HTP");
    LOG("Device:       Snapdragon 7s Gen 3");
    LOG("QNN API:      %u.%u.%u",
        providers[0]->apiVersion.coreApiVersion.major,
        providers[0]->apiVersion.coreApiVersion.minor,
        providers[0]->apiVersion.coreApiVersion.patch);
    LOG("Unsigned PD:  %s", unsignedPdSuccess ? "ENABLED ✓" : "NOT AVAILABLE");
    LOG("Status:       READY FOR INFERENCE");
    LOG("========================================");
    LOG("");

    // Cleanup
    LOG("Cleaning up...");
    qnn.contextFree(context, nullptr);
    qnn.backendFree(backend);
    dlclose(qnn_lib);
    dlclose(qnn_system);
    dlclose(cdsprpc);
    if (htp_stub) dlclose(htp_stub);

    LOG("✓ Cleanup complete");
    LOG("");
    LOG("Test completed successfully!");

    return 0;
}