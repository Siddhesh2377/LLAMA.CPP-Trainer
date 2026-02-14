cmake_minimum_required(VERSION 3.22.1)
project("lora")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ============================================
# READ PATHS FROM CMAKE ARGUMENTS
# ============================================
if(NOT DEFINED QNN_SDK_DIR)
    message(FATAL_ERROR "QNN_SDK_DIR not defined!")
endif()

if(NOT EXISTS ${QNN_SDK_DIR})
    message(FATAL_ERROR "QNN_SDK_DIR path does not exist: ${QNN_SDK_DIR}")
endif()

if(NOT DEFINED LLAMA_CPP_DIR)
    message(FATAL_ERROR "LLAMA_CPP_DIR not defined!")
endif()

if(NOT EXISTS ${LLAMA_CPP_DIR})
    message(FATAL_ERROR "LLAMA_CPP_DIR path does not exist: ${LLAMA_CPP_DIR}")
endif()

message(STATUS "QNN SDK found at: ${QNN_SDK_DIR}")
message(STATUS "llama.cpp found at: ${LLAMA_CPP_DIR}")

# ============================================
# DEFINE OUTPUT DIRECTORIES
# ============================================
set(ASSETS_DIR ${CMAKE_SOURCE_DIR}/../assets/qnnlibs)
message(STATUS "Assets directory: ${ASSETS_DIR}")

# ============================================
# QNN INCLUDE DIRECTORIES
# ============================================
include_directories(
    ${QNN_SDK_DIR}/include/QNN
)

# ============================================
# QNN LIBRARIES - DIRECT PATH
# ============================================
set(QNN_LIB_DIR ${QNN_SDK_DIR}/lib/aarch64-android)

if(NOT EXISTS ${QNN_LIB_DIR})
    message(FATAL_ERROR "QNN lib directory not found: ${QNN_LIB_DIR}")
endif()

message(STATUS "QNN libraries directory: ${QNN_LIB_DIR}")

# Set library paths directly
set(QNN_HTP_LIB ${QNN_LIB_DIR}/libQnnHtp.so)
set(QNN_SYSTEM_LIB ${QNN_LIB_DIR}/libQnnSystem.so)
set(QNN_CPU_LIB ${QNN_LIB_DIR}/libQnnCpu.so)
set(QNN_HTP_V73_STUB ${QNN_LIB_DIR}/libQnnHtpV73Stub.so)

# Verify they exist
if(NOT EXISTS ${QNN_HTP_LIB})
    message(FATAL_ERROR "QNN HTP library not found: ${QNN_HTP_LIB}")
endif()

if(NOT EXISTS ${QNN_SYSTEM_LIB})
    message(FATAL_ERROR "QNN System library not found: ${QNN_SYSTEM_LIB}")
endif()

if(NOT EXISTS ${QNN_CPU_LIB})
    message(WARNING "QNN CPU library not found: ${QNN_CPU_LIB}")
endif()

message(STATUS "QNN HTP Library: ${QNN_HTP_LIB}")
message(STATUS "QNN System Library: ${QNN_SYSTEM_LIB}")
message(STATUS "QNN CPU Library: ${QNN_CPU_LIB}")

# ============================================
# LLAMA.CPP - BUILD AS SUBDIRECTORY
# ============================================
# Vulkan GPU: builds and loads on Adreno but crashes during training backward pass
# (ErrorDeviceLost — Adreno driver can't handle backward graph ops)
# Keep OFF for training. Can enable for inference-only use later.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_HEXAGON OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)
set(GGML_SYCL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

# CPU optimizations for ARM64
set(GGML_OPENMP ON CACHE BOOL "" FORCE)            # Parallel threading via NDK's libomp
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)         # Conflicts with dotprod/i8mm
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)      # Requires CMake 3.24+ for FetchContent URL fix
set(GGML_CPU_REPACK OFF CACHE BOOL "" FORCE)        # Incompatible with training backward pass
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)  # NEON + dotprod (most modern phones)

# Don't build examples/tests/tools - just the library
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_HTTPLIB OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_CPP_DIR} ${CMAKE_BINARY_DIR}/llama.cpp)

message(STATUS "llama.cpp configured as subdirectory")

# ============================================
# NPU TEST EXECUTABLE (packaged as .so)
# ============================================
add_executable(npu_test npu_test.cpp)

# CRITICAL: Android 5.0+ requires PIE executables
set_target_properties(npu_test PROPERTIES
    POSITION_INDEPENDENT_CODE ON
    LINK_FLAGS "-fPIE -pie -static-libstdc++"
    OUTPUT_NAME "npu_test_exec"
    PREFIX "lib"
    SUFFIX ".so"
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}"
)

target_link_libraries(npu_test
    ${QNN_HTP_LIB}
    ${QNN_SYSTEM_LIB}
    android
    log
    dl
    -static-libstdc++
)

# ============================================
# JNI LIBRARY - QNN (existing)
# ============================================
add_library(${CMAKE_PROJECT_NAME} SHARED
    lora.cpp
    lora_graph_builder.cpp
    lora_train.cpp
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${LLAMA_CPP_DIR}/include
    ${LLAMA_CPP_DIR}/common
    ${LLAMA_CPP_DIR}/ggml/include
    ${LLAMA_CPP_DIR}/src
)

target_link_libraries(${CMAKE_PROJECT_NAME}
    # llama.cpp libraries
    llama
    common
    # QNN libraries
    ${QNN_HTP_LIB}
    ${QNN_SYSTEM_LIB}
    ${QNN_CPU_LIB}
    # Android libraries
    android
    log
)

# ============================================
# COPY QNN LIBRARIES TO ASSETS ONLY
# ============================================

# Create assets directory
add_custom_command(TARGET ${CMAKE_PROJECT_NAME} POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory ${ASSETS_DIR}
    COMMENT "Creating assets directory: ${ASSETS_DIR}"
)

# Copy QNN libraries to assets for runtime loading
add_custom_command(TARGET ${CMAKE_PROJECT_NAME} POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        ${QNN_HTP_LIB}
        ${QNN_SYSTEM_LIB}
        ${QNN_CPU_LIB}
        ${QNN_HTP_V73_STUB}
        ${ASSETS_DIR}/
    COMMENT "Copying QNN libraries to assets/qnnlibs for runtime loading"
)

message(STATUS "Build configured successfully")
