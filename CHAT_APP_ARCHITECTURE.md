# Chat App Architecture - Trainer

## Overview

The Trainer app has been refactored from a LoRA training application to a **client-side chat application** with dynamic model and adapter management. Training now happens server-side, and the app downloads and uses pre-trained adapters.

## Architecture Summary

```
┌─────────────────────────────────────────────────────────┐
│                    Android App (Kotlin)                  │
│                                                          │
│  ┌────────────────────────────────────────────────────┐ │
│  │           UI Layer (Jetpack Compose)                │ │
│  │  • ChatScreen - Chat interface                      │ │
│  │  • ModelLibraryScreen - Model/adapter management    │ │
│  │  • SettingsDialog - Generation settings             │ │
│  └────────────────────────────────────────────────────┘ │
│                         ↕                                │
│  ┌────────────────────────────────────────────────────┐ │
│  │          ViewModels (State Management)              │ │
│  │  • ChatViewModel - Chat state & generation          │ │
│  │  • ModelManagerViewModel - Model/adapter downloads  │ │
│  └────────────────────────────────────────────────────┘ │
│                         ↕                                │
│  ┌────────────────────────────────────────────────────┐ │
│  │            Repositories (Data Layer)                │ │
│  │  • ModelRepository - Base model management          │ │
│  │  • AdapterRepository - Adapter management           │ │
│  └────────────────────────────────────────────────────┘ │
│                         ↕                                │
│  ┌────────────────────────────────────────────────────┐ │
│  │         Native Layer (JNI - llama.cpp)              │ │
│  │  • LoraJNI - Inference-only interface               │ │
│  │  • lora_inference.cpp - Native implementation       │ │
│  └────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────┘
                         ↕
           ┌─────────────────────────────┐
           │   External Data Sources      │
           │  • Supabase - Model metadata │
           │  • HuggingFace - GGUF files  │
           │  • Supabase Storage - Adapters│
           └─────────────────────────────┘
```

## Key Changes

### 1. **Removed Training Code**
- **Old**: `lora_train.cpp` with full training pipeline
- **New**: `lora_inference.cpp` - inference only
- Removed JNI functions:
  - `createLoraAdapter()`
  - `setTrainingData()`
  - `initTraining()`
  - `trainEpoch()`
  - `saveLoraAdapter()`
- Kept inference functions:
  - `initLlamaBackend()`
  - `loadModel()`
  - `loadLoraAdapter()`
  - `generate()`
  - `cleanupLlama()`

### 2. **Updated Data Models**

**BaseModel** (was using HuggingFace ID, now uses direct download link):
```kotlin
data class BaseModel(
    val id: String,
    val name: String,
    val modelDownloadLink: String,  // Direct GGUF download URL
    val architecture: String?,       // e.g., "Qwen2.5", "Llama-3.2"
    val parameterCount: String?,     // e.g., "1.5B", "350M"
    val quantization: String?,       // e.g., "Q4_K_M", "Q8_0"
    val contextLength: Int?,
    val sizeMb: Int?,
    val checksumSha256: String?
)
```

**New Models**:
- `ChatMessage` - Individual chat message
- `Conversation` - Chat conversation with history
- `LocalModel` - Tracks downloaded models
- `LocalAdapter` - Tracks downloaded adapters

### 3. **File Storage Structure**

```
filesDir/
└── models/
    ├── qwen2.5-1.5b-q4/
    │   ├── model.gguf
    │   └── adapters/
    │       ├── medical/
    │       │   └── adapter.gguf
    │       └── legal/
    │           └── adapter.gguf
    └── llama-3.2-1b-q8/
        ├── model.gguf
        └── adapters/
            └── code/
                └── adapter.gguf
```

### 4. **New Repositories**

**ModelRepository**:
- `fetchAvailableModels()` - Get models from Supabase
- `downloadModel()` - Download GGUF from HuggingFace
- `getLocalModels()` - Get downloaded models
- `deleteModel()` - Delete model + its adapters
- Uses SharedPreferences for local tracking

**AdapterRepository** (Updated):
- `fetchAdaptersForModel(baseModelId)` - Get adapters for a model
- `downloadAdapter()` - Download & extract tar.gz from Supabase
- `extractTarGz()` - Extract adapter files
- Filters adapters by `base_model_id`

### 5. **New ViewModels**

**ChatViewModel**:
- Manages chat state and conversation
- Handles LLM generation via JNI
- Supports settings: temperature, max_tokens, system_prompt
- Builds prompts with conversation history (ChatML format)

**ModelManagerViewModel**:
- Manages model/adapter downloads
- Tracks download progress
- Manages local storage

### 6. **UI Screens**

**ChatScreen**:
- Message list with user/assistant bubbles
- Text input with send button
- Settings button (temperature, max_tokens, system prompt)
- Model library button
- Clear chat button
- Empty states (no model, no messages)

**ModelLibraryScreen**:
- 3 tabs:
  1. **Downloaded** - Local models & adapters with load/delete actions
  2. **Available Models** - Browse & download base models
  3. **Available Adapters** - Browse & download adapters for selected model
- Download progress indicators
- Model metadata display (size, quantization, parameters)

**SettingsDialog**:
- Temperature slider (0.0 - 2.0)
- Max tokens slider (64 - 2048)
- System prompt text field

## Workflow

### First-Time User Flow:
1. User opens app → sees ChatScreen with "No model loaded" state
2. User taps "Open Model Library"
3. User switches to "Available Models" tab
4. User downloads a base model (e.g., Qwen2.5-1.5B-Q4_K_M)
5. User taps "View Adapters" for that model
6. User downloads an adapter (e.g., "Medical Assistant")
7. User goes to "Downloaded" tab
8. User taps "Load" on the adapter
9. App loads model + adapter, returns to ChatScreen
10. User starts chatting

### Chat Flow:
1. User types message → taps Send
2. ChatViewModel builds prompt with conversation history
3. Calls JNI `generate()` with prompt
4. llama.cpp generates response
5. Response added to conversation
6. UI auto-scrolls to bottom

### Settings Flow:
1. User taps Settings icon
2. Adjusts temperature / max_tokens / system prompt
3. Taps Save
4. Settings applied to next generation

## Supabase Schema

**base_models** table:
```sql
CREATE TABLE base_models (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT,
    model_download_link TEXT NOT NULL,  -- Direct HuggingFace GGUF URL
    architecture TEXT,
    parameter_count TEXT,
    quantization TEXT,
    context_length INTEGER,
    size_mb INTEGER,
    checksum_sha256 TEXT,
    version TEXT,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);
```

**adapters** table:
```sql
CREATE TABLE adapters (
    id UUID PRIMARY KEY,
    base_model_id UUID REFERENCES base_models(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    domain TEXT NOT NULL,
    version TEXT NOT NULL,
    storage_path TEXT NOT NULL,  -- Path in Supabase Storage
    size_mb FLOAT,
    checksum_sha256 TEXT,
    training_epochs INTEGER,
    training_loss FLOAT,
    status TEXT,
    is_published BOOLEAN DEFAULT false,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);
```

**Supabase Storage**:
- Bucket: `adapters`
- Files stored as: `{domain}/{version}.tar.gz`

## Dependencies Added

```kotlin
// Apache Commons Compress for tar.gz extraction
implementation("org.apache.commons:commons-compress:1.27.1")
```

## Build Configuration

**CMakeLists.txt changes**:
- Changed from `lora_train.cpp` → `lora_inference.cpp`
- Removed training-related comments
- Kept inference-optimized settings (mmap=true)

## Next Steps

### For Server-Side Training:
1. Set up training pipeline on server
2. Train LoRA adapters
3. Package as tar.gz with adapter.gguf
4. Upload to Supabase Storage
5. Create adapter record in Supabase
6. Set `is_published = true`

### For App Users:
1. Open Model Library
2. Download base model
3. Download adapter(s)
4. Load model + adapter
5. Start chatting!

## Testing Checklist

- [ ] Download base model from HuggingFace
- [ ] Download adapter from Supabase (tar.gz extraction)
- [ ] Load model successfully
- [ ] Load adapter successfully
- [ ] Send message and get response
- [ ] Conversation history maintained
- [ ] Settings (temperature, max_tokens) applied
- [ ] Delete model (removes adapters too)
- [ ] Delete individual adapter
- [ ] Progress indicators during download
- [ ] Error handling (network, storage, checksum)
- [ ] Empty states (no model, no messages)
- [ ] Navigation between Chat and Model Library

## Known Limitations

1. **No conversation persistence** - Conversations are lost on app restart (can be added with Room DB)
2. **Single conversation** - Only one active conversation (can be extended to multiple)
3. **No streaming** - Response appears all at once (llama.cpp supports streaming but not implemented)
4. **ChatML format hardcoded** - Prompt format is hardcoded (should be configurable per model)
5. **No background downloads** - Downloads block UI (can be improved with WorkManager)

## Future Enhancements

- [ ] Conversation persistence (Room DB)
- [ ] Multiple conversations
- [ ] Streaming responses
- [ ] Voice input/output
- [ ] Model-specific prompt templates
- [ ] Background downloads
- [ ] Adapter merging (use multiple adapters simultaneously)
- [ ] Model quantization on-device
- [ ] Share conversations
- [ ] Export chat history
