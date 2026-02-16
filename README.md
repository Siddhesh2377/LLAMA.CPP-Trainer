# Trainer - On-Device LLM Chat with LoRA Adapters

Android app for running quantized LLMs locally with hot-swappable LoRA adapters. Downloads models and adapters from Supabase, runs inference on-device using llama.cpp.

## What It Does

1. Browse and download GGUF base models from the server
2. Browse and download LoRA adapters (trained via the [Adapter server](../Adapter))
3. Load a model + adapter and chat locally on your phone
4. All inference runs on-device (CPU, optionally Snapdragon NPU)

## Prerequisites

- **Android Studio** (latest stable)
- **Android SDK 36** (compileSdk)
- **NDK** (installed via SDK Manager)
- **CMake 3.31+** (installed via SDK Manager)
- **llama.cpp** repo cloned locally
- **Physical device** with arm64-v8a (or emulator, but GPU/NPU won't work)

### Optional (for Snapdragon NPU)

- Qualcomm QNN SDK
- Hexagon SDK v6

## Setup

### 1. Clone and open in Android Studio

```bash
git clone <this-repo>
```

Open `/Trainer` in Android Studio. Let Gradle sync.

### 2. Configure `local.properties`

Android Studio creates this file automatically. Add the required paths:

```properties
sdk.dir=/path/to/Android/Sdk

# Required
llama.cpp.dir=/path/to/llama.cpp

# Optional (NPU support)
qnn.sdk.dir=/path/to/qairt
hexagon.sdk.dir=/path/to/hexagon-sdk-v6
hexagon.tools.dir=/path/to/hexagon-sdk-v6/tools/HEXAGON_Tools/19.0.04
```

Only `sdk.dir` and `llama.cpp.dir` are required. The rest are for NPU acceleration on Snapdragon devices.

### 3. Build and run

```bash
./gradlew :app:assembleDebug
```

Or just hit **Run** in Android Studio with a device connected.

## How to Use

### First Launch

1. App opens to **Chat** screen -- shows "No model loaded"
2. Tap the model icon (top bar) to open **Model Library**

### Download a Model

1. Go to **Available Models** tab
2. Pick a base model (e.g. `Qwen-0.5B`)
3. Tap **Download** -- waits for the GGUF file to download
4. Once downloaded, it appears in the **Downloaded** tab

### Download an Adapter

1. From Available Models, tap **View Adapters** on a model
2. Go to **Available Adapters** tab
3. Pick an adapter (e.g. `medical-assistant`)
4. Tap **Download** -- downloads and extracts the adapter

### Start Chatting

1. Go to **Downloaded** tab
2. Tap **Load** on your model (optionally select an adapter)
3. Go back to **Chat** screen
4. Type a message and send

### Settings

Tap the gear icon for:
- **Temperature** (0.0 - 2.0)
- **Max tokens** (64 - 2048)
- **System prompt**
- **NPU toggle** (if QNN SDK is available)

## Project Structure

```
Trainer/
├── app/src/main/java/com/dark/trainer/
│   ├── MainActivity.kt                    # Entry point + navigation
│   ├── data/
│   │   └── SupabaseClient.kt              # Supabase connection
│   ├── models/
│   │   └── BaseModel.kt                   # Data classes
│   ├── repository/
│   │   ├── ModelRepository.kt             # Base model download/management
│   │   └── AdapterRepository.kt           # Adapter download/management
│   ├── viewmodel/
│   │   ├── ChatViewModel.kt               # Chat + inference logic
│   │   ├── ModelManagerViewModel.kt        # Model/adapter management
│   │   └── UpdateState.kt                 # UI state classes
│   └── ui/
│       ├── screens/
│       │   ├── ChatScreen.kt              # Chat interface
│       │   └── ModelLibraryScreen.kt       # Model browser (3 tabs)
│       ├── components/
│       │   └── SettingsDialog.kt           # Settings overlay
│       └── theme/                          # Material3 theme
├── lora/                                   # Native JNI module
│   ├── src/main/java/com/dark/lora/
│   │   └── LoraJNI.kt                     # JNI interface
│   └── src/main/cpp/
│       ├── CMakeLists.txt                  # C++ build config
│       ├── lora.cpp                        # JNI bindings
│       └── lora_inference.cpp              # llama.cpp inference wrapper
└── local.properties                        # SDK paths (not committed)
```

## Backend (Supabase)

The app connects to Supabase for:

| Table | Purpose |
|-------|---------|
| `base_models` | Available base models (name, download link, version, size) |
| `adapters` | Trained LoRA adapters (linked to base model, storage path, status) |
| `adapter_deployments` | OTA rollout config |
| `update_logs` | Device download tracking |

**Storage bucket:** `adapters` -- holds adapter `.tar.gz` archives

Base model GGUF files are downloaded from the `model_download_link` field (usually a HuggingFace URL).

## On-Device Storage

```
filesDir/models/
├── qwen-0.5b/
│   ├── model.gguf
│   └── adapters/
│       ├── medical/adapter.gguf
│       └── coding/adapter.gguf
```

Tracked via SharedPreferences.

## Tech Stack

| Component | Tech |
|-----------|------|
| UI | Jetpack Compose + Material3 |
| Backend | Supabase (Postgrest + Storage) |
| Networking | Ktor |
| Serialization | kotlinx.serialization |
| Inference | llama.cpp (C++ via JNI) |
| NPU | Qualcomm QNN SDK (optional) |
| Target | arm64-v8a, minSdk 30 |

## Troubleshooting

| Problem | Fix |
|---------|-----|
| Build fails on CMake | Make sure `llama.cpp.dir` is set in `local.properties` |
| "No model loaded" | Download and load a model from Model Library |
| Model download stuck | Check that `model_download_link` is set for the base model in Supabase |
| Crash on model load | Device may not have enough RAM. Try a smaller quantized model (Q4_K_M) |
| NPU not working | QNN SDK path must be correct in `local.properties`. Not all devices support it |
