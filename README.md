# Trainer

On-device LLM chat app for Android. Downloads GGUF models + LoRA adapters from Supabase, runs inference locally via llama.cpp.

## Setup

1. Clone this repo and open in Android Studio
2. Edit `local.properties` and add your Supabase credentials:

```properties
supabase.url=YOUR_SUPABASE_URL
supabase.key=YOUR_SUPABASE_ANON_KEY
```

3. Connect an arm64 Android device and hit **Run**

That's it. All native libraries (llama.cpp, QNN, Hexagon) are prebuilt and shipped in the repo.

### Building native code from source (optional)

If you need to modify the C++ code, add these to `local.properties`:

```properties
llama.cpp.dir=/path/to/llama.cpp

# Optional: NPU acceleration (Snapdragon only)
qnn.sdk.dir=/path/to/qairt
hexagon.sdk.dir=/path/to/hexagon-sdk-v6
hexagon.tools.dir=/path/to/hexagon-sdk-v6/tools/HEXAGON_Tools/19.0.04
```

When `llama.cpp.dir` is set, the build compiles from source instead of using the prebuilt .so files.

## Usage

1. Tap the model icon -> **Model Library** -> download a model
2. Tap **Load** on the downloaded model
3. Chat

## Project Structure

```
app/    Android app (Jetpack Compose, Supabase client, chat UI)
lora/   Native JNI module (llama.cpp inference, LoRA adapter loading)
```
