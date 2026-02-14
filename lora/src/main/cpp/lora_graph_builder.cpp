#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <android/dlext.h>  // NEW: For android_dlopen_ext
#include <string>
#include <fstream>

#include "QnnInterface.h"
#include "QnnTypes.h"

#define LOG_TAG "LORA_GRAPH"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Helper to load vendor libraries bypassing namespace restrictions
void* loadVendorLibrary(const char* name) {
    // Method 1: Try android_dlopen_ext with RTLD_GLOBAL in default namespace
    android_dlextinfo extinfo;
    extinfo.flags = ANDROID_DLEXT_USE_NAMESPACE;

    // Try loading from system namespace
    void* handle = android_dlopen_ext(name, RTLD_NOW | RTLD_GLOBAL, &extinfo);

    if (!handle) {
        // Method 2: Try direct dlopen (may work on some devices)
        handle = dlopen(name, RTLD_NOW | RTLD_GLOBAL);
    }

    if (!handle) {
        // Method 3: Try absolute paths
        const char* paths[] = {
                "/vendor/lib64/libcdsprpc.so",
                "/system/lib64/libcdsprpc.so",
                "/apex/com.android.vndk.v33/lib64/libcdsprpc.so",
                nullptr
        };

        for (int i = 0; paths[i] != nullptr; i++) {
            handle = dlopen(paths[i], RTLD_NOW | RTLD_GLOBAL);
            if (handle) {
                LOGI("Loaded from: %s", paths[i]);
                break;
            }
        }
    }

    return handle;
}

// Helper to create tensor
Qnn_Tensor_t createTensor(const char* name, Qnn_DataType_t dataType,
                          uint32_t* dims, uint32_t rank, void* data = nullptr, size_t dataSize = 0) {
    Qnn_Tensor_t tensor = QNN_TENSOR_INIT;
    tensor.version = QNN_TENSOR_VERSION_2;

    tensor.v2.id = 0;
    tensor.v2.name = name;
    tensor.v2.type = data ? QNN_TENSOR_TYPE_STATIC : QNN_TENSOR_TYPE_APP_WRITE;
    tensor.v2.dataFormat = QNN_TENSOR_DATA_FORMAT_DENSE;
    tensor.v2.dataType = dataType;
    tensor.v2.quantizeParams = {QNN_DEFINITION_UNDEFINED};
    tensor.v2.rank = rank;
    tensor.v2.dimensions = dims;
    tensor.v2.isDynamicDimensions = nullptr;
    tensor.v2.sparseParams = QNN_SPARSE_PARAMS_INIT;
    tensor.v2.isProduced = 0;
    tensor.v2.memType = QNN_TENSORMEMTYPE_RAW;

    if (data) {
        tensor.v2.clientBuf.data = data;
        tensor.v2.clientBuf.dataSize = dataSize;
    }

    return tensor;
}


extern "C" JNIEXPORT jstring JNICALL
Java_com_dark_lora_LoraJNI_buildLoraGraph(
        JNIEnv* env,
        jobject /* this */,
        jstring output_path) {

    LOGI("=== Building LoRA Graph ===");

    const char* output_path_str = env->GetStringUTFChars(output_path, nullptr);

    // Load QNN libraries with namespace workaround
    LOGI("Loading DSP library...");

    // CRITICAL: Load cdsprpc FIRST with RTLD_GLOBAL
    void* cdsprpc = loadVendorLibrary("libcdsprpc.so");
    if (!cdsprpc) {
        std::string error = "❌ Failed to load DSP library\n\n";
        error += "This is a vendor library access issue.\n";
        error += "Error: " + std::string(dlerror()) + "\n\n";
        error += "WORKAROUND:\n";
        error += "1. Build as system app (requires root)\n";
        error += "2. OR use QNN CPU backend (slower)\n";
        error += "3. OR wait for device with relaxed SELinux";

        LOGE("%s", error.c_str());
        env->ReleaseStringUTFChars(output_path, output_path_str);
        return env->NewStringUTF(error.c_str());
    }

    LOGI("✓ DSP library loaded");

    // Now load QNN libraries (they need cdsprpc)
    void* qnn_system = dlopen("libQnnSystem.so", RTLD_NOW | RTLD_GLOBAL);
    void* qnn_lib = dlopen("libQnnHtp.so", RTLD_NOW | RTLD_LOCAL);

    if (!qnn_lib) {
        std::string error = "Failed to load QNN HTP: ";
        error += dlerror();
        LOGE("%s", error.c_str());
        env->ReleaseStringUTFChars(output_path, output_path_str);
        return env->NewStringUTF(error.c_str());
    }

    LOGI("✓ QNN libraries loaded");

    // Get QNN interface
    typedef Qnn_ErrorHandle_t (*GetProvidersFn)(const QnnInterface_t***, uint32_t*);
    auto getProviders = (GetProvidersFn)dlsym(qnn_lib, "QnnInterface_getProviders");

    const QnnInterface_t** providers = nullptr;
    uint32_t num_providers = 0;
    getProviders(&providers, &num_providers);

    if (num_providers == 0) {
        env->ReleaseStringUTFChars(output_path, output_path_str);
        return env->NewStringUTF("No QNN providers found");
    }

    const QNN_INTERFACE_VER_TYPE& qnn = providers[0]->QNN_INTERFACE_VER_NAME;

    LOGI("QNN API Version: %u.%u.%u",
         providers[0]->apiVersion.coreApiVersion.major,
         providers[0]->apiVersion.coreApiVersion.minor,
         providers[0]->apiVersion.coreApiVersion.patch);

    // Create backend
    Qnn_BackendHandle_t backend = nullptr;
    Qnn_ErrorHandle_t result = qnn.backendCreate(nullptr, nullptr, &backend);
    if (result != QNN_SUCCESS) {
        std::string error = "Backend creation failed: " + std::to_string(result);
        LOGE("%s", error.c_str());
        env->ReleaseStringUTFChars(output_path, output_path_str);
        return env->NewStringUTF(error.c_str());
    }
    LOGI("✓ Backend created");

    // Create context
    Qnn_ContextHandle_t context = nullptr;
    result = qnn.contextCreate(backend, nullptr, nullptr, &context);
    if (result != QNN_SUCCESS) {
        std::string error = "❌ Context creation failed: " + std::to_string(result) + "\n\n";
        error += "Error code 14001 = Transport layer failed\n";
        error += "This means DSP communication is blocked.\n\n";
        error += "SOLUTION: Switch to QNN CPU Backend\n";
        error += "Edit code to use libQnnCpu.so instead of libQnnHtp.so\n";
        error += "CPU backend works without vendor library access.";

        LOGE("%s", error.c_str());
        qnn.backendFree(backend);
        env->ReleaseStringUTFChars(output_path, output_path_str);
        return env->NewStringUTF(error.c_str());
    }
    LOGI("✓ Context created");

    // Create graph
    Qnn_GraphHandle_t graph = nullptr;
    result = qnn.graphCreate(context, "lora_graph", nullptr, &graph);
    if (result != QNN_SUCCESS) {
        std::string error = "Graph creation failed: " + std::to_string(result);
        LOGE("%s", error.c_str());
        qnn.contextFree(context, nullptr);
        qnn.backendFree(backend);
        env->ReleaseStringUTFChars(output_path, output_path_str);
        return env->NewStringUTF(error.c_str());
    }
    LOGI("✓ Graph created");

    // Build LoRA operations
    {
        const int d = 512;
        const int k = 512;
        const int r = 8;

        uint32_t input_dims[] = {1, d};
        uint32_t W_dims[] = {d, k};
        uint32_t A_dims[] = {d, r};
        uint32_t B_dims[] = {r, k};
        uint32_t AB_dims[] = {d, k};
        uint32_t output_dims[] = {1, k};
        uint32_t alpha_dims[] = {1};

        // Allocate and initialize weights
        float* W_data = new float[d * k];
        float* A_data = new float[d * r];
        float* B_data = new float[r * k];
        float alpha_val = 0.1f;

        for (int i = 0; i < d * k; i++) W_data[i] = 0.01f;
        for (int i = 0; i < d * r; i++) A_data[i] = 0.01f;
        for (int i = 0; i < r * k; i++) B_data[i] = 0.01f;

        // Create tensors
        Qnn_Tensor_t input = createTensor("input", QNN_DATATYPE_FLOAT_32, input_dims, 2);
        Qnn_Tensor_t W = createTensor("W", QNN_DATATYPE_FLOAT_32, W_dims, 2, W_data, d * k * sizeof(float));
        Qnn_Tensor_t A = createTensor("A", QNN_DATATYPE_FLOAT_32, A_dims, 2, A_data, d * r * sizeof(float));
        Qnn_Tensor_t B = createTensor("B", QNN_DATATYPE_FLOAT_32, B_dims, 2, B_data, r * k * sizeof(float));
        Qnn_Tensor_t alpha = createTensor("alpha", QNN_DATATYPE_FLOAT_32, alpha_dims, 1, &alpha_val, sizeof(float));

        Qnn_Tensor_t AB = createTensor("AB", QNN_DATATYPE_FLOAT_32, AB_dims, 2);
        Qnn_Tensor_t scaled_AB = createTensor("scaled_AB", QNN_DATATYPE_FLOAT_32, AB_dims, 2);
        Qnn_Tensor_t W_eff = createTensor("W_effective", QNN_DATATYPE_FLOAT_32, W_dims, 2);
        Qnn_Tensor_t output = createTensor("output", QNN_DATATYPE_FLOAT_32, output_dims, 2);

        // Operation 1: MatMul A @ B
        Qnn_Tensor_t matmul_AB_inputs[] = {A, B};
        Qnn_Tensor_t matmul_AB_outputs[] = {AB};

        Qnn_OpConfig_t matmul_AB_op = QNN_OPCONFIG_INIT;
        matmul_AB_op.version = QNN_OPCONFIG_VERSION_1;
        matmul_AB_op.v1.name = "lora_matmul_AB";
        matmul_AB_op.v1.packageName = "qti.aisw";
        matmul_AB_op.v1.typeName = "MatMul";
        matmul_AB_op.v1.numOfInputs = 2;
        matmul_AB_op.v1.inputTensors = matmul_AB_inputs;
        matmul_AB_op.v1.numOfOutputs = 1;
        matmul_AB_op.v1.outputTensors = matmul_AB_outputs;

        qnn.graphAddNode(graph, matmul_AB_op);
        LOGI("✓ Added MatMul(A, B)");

        // Operation 2: Scale
        Qnn_Tensor_t scale_inputs[] = {AB, alpha};
        Qnn_Tensor_t scale_outputs[] = {scaled_AB};

        Qnn_OpConfig_t scale_op = QNN_OPCONFIG_INIT;
        scale_op.version = QNN_OPCONFIG_VERSION_1;
        scale_op.v1.name = "lora_scale";
        scale_op.v1.packageName = "qti.aisw";
        scale_op.v1.typeName = "ElementWiseMultiply";
        scale_op.v1.numOfInputs = 2;
        scale_op.v1.inputTensors = scale_inputs;
        scale_op.v1.numOfOutputs = 1;
        scale_op.v1.outputTensors = scale_outputs;

        qnn.graphAddNode(graph, scale_op);
        LOGI("✓ Added Scale");

        // Operation 3: Add
        Qnn_Tensor_t add_inputs[] = {W, scaled_AB};
        Qnn_Tensor_t add_outputs[] = {W_eff};

        Qnn_OpConfig_t add_op = QNN_OPCONFIG_INIT;
        add_op.version = QNN_OPCONFIG_VERSION_1;
        add_op.v1.name = "lora_add";
        add_op.v1.packageName = "qti.aisw";
        add_op.v1.typeName = "ElementWiseAdd";
        add_op.v1.numOfInputs = 2;
        add_op.v1.inputTensors = add_inputs;
        add_op.v1.numOfOutputs = 1;
        add_op.v1.outputTensors = add_outputs;

        qnn.graphAddNode(graph, add_op);
        LOGI("✓ Added Add");

        // Operation 4: Final MatMul
        Qnn_Tensor_t final_inputs[] = {input, W_eff};
        Qnn_Tensor_t final_outputs[] = {output};

        Qnn_OpConfig_t final_op = QNN_OPCONFIG_INIT;
        final_op.version = QNN_OPCONFIG_VERSION_1;
        final_op.v1.name = "lora_forward";
        final_op.v1.packageName = "qti.aisw";
        final_op.v1.typeName = "MatMul";
        final_op.v1.numOfInputs = 2;
        final_op.v1.inputTensors = final_inputs;
        final_op.v1.numOfOutputs = 1;
        final_op.v1.outputTensors = final_outputs;

        qnn.graphAddNode(graph, final_op);
        LOGI("✓ Added Final MatMul");

        // Finalize graph
        qnn.graphFinalize(graph, nullptr, nullptr);
        LOGI("✓ Graph finalized");

        // Serialize to binary
        Qnn_ContextBinarySize_t binary_size = 0;
        qnn.contextGetBinarySize(context, &binary_size);

        void* binary_buffer = malloc(binary_size);
        Qnn_ContextBinarySize_t written_size = 0;
        qnn.contextGetBinary(context, binary_buffer, binary_size, &written_size);

        // Save to file
        std::ofstream file(output_path_str, std::ios::binary);
        file.write((char*)binary_buffer, written_size);
        file.close();
        free(binary_buffer);

        LOGI("✅ Saved binary: %s (%lu bytes)", output_path_str, (unsigned long)written_size);

        // Cleanup
        delete[] W_data;
        delete[] A_data;
        delete[] B_data;
    }

    // Cleanup
    qnn.contextFree(context, nullptr);
    qnn.backendFree(backend);
    dlclose(qnn_lib);
    if (qnn_system) dlclose(qnn_system);
    if (cdsprpc) dlclose(cdsprpc);

    std::string success = "✅ LoRA graph built successfully!\n";
    success += "Binary saved to: " + std::string(output_path_str);

    env->ReleaseStringUTFChars(output_path, output_path_str);
    return env->NewStringUTF(success.c_str());
}