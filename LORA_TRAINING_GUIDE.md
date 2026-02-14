# On-Device LoRA/QLoRA Training on Android — Complete Study Guide

## Table of Contents

1. [What is LoRA?](#1-what-is-lora)
2. [What is QLoRA?](#2-what-is-qlora)
3. [How Our System Works](#3-how-our-system-works)
4. [Architecture Deep Dive](#4-architecture-deep-dive)
5. [The Training Pipeline Step by Step](#5-the-training-pipeline-step-by-step)
6. [How LoRA Integrates into llama.cpp](#6-how-lora-integrates-into-llamacpp)
7. [The Backward Pass Problem](#7-the-backward-pass-problem)
8. [Errors We Faced and Solved](#8-errors-we-faced-and-solved)
9. [Error 7 (SOLVED): SET_ROWS View Assertion Crash](#9-error-7-solved-set_rows-view-assertion-crash)
10. [Error 8 (SOLVED): Graph Node Capacity Overflow](#error-8-solved-graph-node-capacity-overflow)
11. [Error 9 (SOLVED): n_lora_nodes Assertion on Adapter Free](#error-9-solved-n_lora_nodes-assertion-on-adapter-free)
12. [Speed Optimizations](#12-speed-optimizations)
13. [GPU/NPU Acceleration Attempts](#13-gpunpu-acceleration-attempts)
14. [Real-Time Logging System](#14-real-time-logging-system)
15. [File Reference](#15-file-reference)
16. [Memory Estimates](#16-memory-estimates)
17. [How to Debug Further](#17-how-to-debug-further)

---

## 1. What is LoRA?

**LoRA** = **Low-Rank Adaptation** (Hu et al., 2021)

### The Problem
Fine-tuning a large language model means updating ALL its weights. For a 1B parameter model, that's:
- 1 billion parameters × 4 bytes (F32) = **4 GB** just for the model
- Adam optimizer needs 2x the model size for momentum/variance = **8 GB more**
- Gradients = **4 GB more**
- Total: **~16 GB** — impossible on a phone

### The Solution
LoRA freezes the base model and adds **tiny trainable matrices** alongside each weight.

For a weight matrix `W` of shape `[d_in, d_out]` (e.g., `[1536, 1536]` for attention Q):
- Create matrix `A` of shape `[d_in, rank]` (e.g., `[1536, 4]`)
- Create matrix `B` of shape `[rank, d_out]` (e.g., `[4, 1536]`)
- During forward pass: `output = W @ x + (alpha/rank) * B @ A @ x`

### Why This Works
- `rank` is tiny (4-16) compared to dimensions (1536)
- A has `d_in × rank` params, B has `rank × d_out` params
- Total trainable params: `rank × (d_in + d_out)` instead of `d_in × d_out`
- For rank=4, dim=1536: **12,288** params instead of **2,359,296** — a **192× reduction**

### Initialization
- **A** gets random Kaiming initialization (small random values)
- **B** gets initialized to **zero**
- This means LoRA starts as identity (no change to base model) and gradually learns

### Target Modules
LoRA is typically applied to attention weight matrices:
- `attn_q` (query projection)
- `attn_k` (key projection)
- `attn_v` (value projection)
- `attn_output` (output projection)
- `ffn_gate`, `ffn_up`, `ffn_down` (feed-forward network)

**Important**: LoRA targets weight MATRICES, not bias vectors. Biases are 1D and don't benefit from low-rank decomposition.

---

## 2. What is QLoRA?

**QLoRA** = **Quantized LoRA** (Dettmers et al., 2023)

### How It Differs from Regular LoRA
- In standard LoRA, the base model is F16/F32
- In QLoRA, the base model is **quantized** (e.g., Q4_K_M = 4-bit)
- Only the LoRA A/B matrices are F32
- Gradients flow through the quantized weights during training (they're "dequantized on the fly")

### Why Our System Is QLoRA
Our system loads GGUF models that are already quantized (Q4_K_M, Q8_0, etc.). The base weights stay quantized. Only the F32 LoRA A/B matrices are trained. **This is QLoRA by default.**

### Memory Savings
For a 1.5B model (Qwen2.5-1.5B-Q4_K_M):
- Base model (Q4_K_M): **~900 MB** (stays quantized, not duplicated)
- LoRA A/B (rank 4, all layers): **~12 MB** (F32 trainable params)
- Adam optimizer states: **~24 MB** (2× LoRA size)
- KV cache (F32, ctx=128): **~50 MB**
- Activations/gradients: **~200 MB**
- **Total: ~1.2 GB** — fits on a phone!

---

## 3. How Our System Works

### High-Level Architecture

```
┌─────────────────────────────────────────────────┐
│              Android App (Kotlin/Compose)         │
│              MainActivity.kt                      │
│  ┌──────────────────────────────────────────────┐│
│  │  Step 1: Init Backend                         ││
│  │  Step 2: Load Model (GGUF)                    ││
│  │  Step 3: Create LoRA Adapter                  ││
│  │  Step 4: Set Training Data                    ││
│  │  Step 5: Init Training + Run Epochs           ││
│  │  Step 6: Save LoRA Adapter (GGUF)             ││
│  └──────────────────────────────────────────────┘│
│                      │ JNI                        │
│  ┌──────────────────────────────────────────────┐│
│  │           lora_train.cpp (C++ JNI)            ││
│  │   LoraJNI.kt  ←→  Native functions            ││
│  └──────────────────────────────────────────────┘│
│                      │                            │
│  ┌──────────────────────────────────────────────┐│
│  │             llama.cpp (C/C++)                  ││
│  │  ┌────────┐ ┌────────────┐ ┌──────────────┐  ││
│  │  │ Model  │ │  LoRA      │ │  Training    │  ││
│  │  │ Loading│ │  Adapter   │ │  (ggml_opt)  │  ││
│  │  └────────┘ └────────────┘ └──────────────┘  ││
│  │       ┌─────────────────┐                     ││
│  │       │   ggml engine   │ (forward + backward)││
│  │       └─────────────────┘                     ││
│  └──────────────────────────────────────────────┘│
└─────────────────────────────────────────────────┘
```

### The Three Layers

1. **Kotlin UI** (`MainActivity.kt`): Step-by-step training wizard. Each step is a button that calls a JNI function on a background thread.

2. **JNI Bridge** (`lora_train.cpp`): C++ code that translates between Java/Kotlin objects and llama.cpp C API. Manages global state (`g_model`, `g_context`, `g_adapter`, `g_dataset`).

3. **llama.cpp** (modified): The core engine. We added functions for:
   - Creating blank LoRA adapters (`llama_adapter_lora_create`)
   - Saving trained adapters (`llama_lora_save_adapter`)
   - Filtering trainable parameters to LoRA only (`llama_opt_param_filter_lora`)

---

## 4. Architecture Deep Dive

### Project Structure

```
Trainer/
├── app/                              # Android app
│   └── src/main/java/.../
│       └── MainActivity.kt           # Training UI
├── lora/                             # Native module
│   ├── src/main/java/.../
│   │   └── LoraJNI.kt               # Kotlin JNI declarations
│   └── src/main/cpp/
│       ├── CMakeLists.txt            # Build config (links llama.cpp)
│       ├── lora_train.cpp            # JNI training functions
│       ├── lora.cpp                  # QNN functions (legacy)
│       └── lora_graph_builder.cpp    # QNN graph builder (legacy)
└── local.properties                  # Contains llama.cpp.dir and qairt.dir paths

External:
├── /home/home/dev/AI/llama.cpp/      # Modified llama.cpp
│   ├── include/llama.h               # Added: llama_adapter_lora_create, etc.
│   ├── src/llama-adapter.cpp         # Added: create, save, target filtering
│   ├── src/llama-context.cpp         # Added: LoRA param registration, filter
│   └── ggml/src/ggml.c              # Modified: backward pass fixes
└── /home/home/dev/lib/qairt/         # QNN SDK (legacy, NPU blocked)
```

### CMake Integration

llama.cpp is built as a CMake subdirectory inside the `lora` module:

```cmake
# Key settings for Android NDK:
set(BUILD_SHARED_LIBS OFF)     # Static link into liblora.so
set(GGML_OPENMP OFF)           # NDK doesn't support OpenMP well
set(GGML_NATIVE OFF)           # Don't use host CPU features
set(GGML_CUDA OFF)             # No GPU backends on Android
set(GGML_VULKAN OFF)
set(GGML_METAL OFF)

add_subdirectory(${LLAMA_CPP_DIR} ${CMAKE_BINARY_DIR}/llama.cpp)
target_link_libraries(lora llama common android log)
```

The paths come from `local.properties`:
```properties
llama.cpp.dir=/home/home/dev/AI/llama.cpp
qairt.dir=/home/home/dev/lib/qairt
```

### Build Output
- Target: `arm64-v8a` only
- Output: `liblora.so` (~8 MB)
- Includes all of llama.cpp statically linked

---

## 5. The Training Pipeline Step by Step

### Step 1: Init Backend

```kotlin
loraJNI.initLlamaBackend(applicationInfo.nativeLibraryDir)
```

**What happens:**
1. Sets Android log callback (so llama.cpp messages appear in logcat)
2. `ggml_backend_load_all_from_path()` — scans for backend .so files
3. `llama_backend_init()` — initializes CPU backend
4. Only CPU backend works on retail Android (QNN/NPU blocked by linker namespace)

### Step 2: Load Model

```kotlin
loraJNI.loadModel(modelPath, 0, 128)
```

**What happens:**
1. `llama_model_load_from_file(path, params)` with `use_mmap=false`
   - **Why no mmap?** Training needs to write gradient accumulation buffers, which requires non-mmap memory
2. `llama_init_from_model(model, ctx_params)` creates context with:
   - `type_k = GGML_TYPE_F32` — KV cache must be F32 for training (backward pass)
   - `type_v = GGML_TYPE_F32`
   - `flash_attn_type = 0` — flash attention disabled (no backward implementation)
   - `n_ctx = n_batch = n_ubatch` — all equal for training

### Step 3: Create LoRA Adapter

```kotlin
loraJNI.createLoraAdapter(4, 8.0f)
```

**What happens in `llama_adapter_lora_create()`:**
1. Iterates all model tensors by name
2. `should_target_tensor()` checks if each tensor matches target modules:
   - Default targets: `attn_q`, `attn_k`, `attn_v`, `attn_output`, `ffn_gate`, `ffn_up`, `ffn_down`
   - Skips `.bias` tensors (LoRA only works on weight matrices)
   - Skips recurrent layers (SSM/shortconv ops have no backward pass)
3. For each target tensor `W[d_in, d_out]`:
   - Creates `A = ggml_new_tensor_2d(F32, d_in, rank)`
   - Creates `B = ggml_new_tensor_2d(F32, rank, d_out)`
   - Kaiming init for A, zero init for B
4. Allocates a single ggml buffer for all A/B tensors
5. Stores in `adapter->ab_map[tensor_name] = {a, b}`
6. Calls `llama_set_adapter_lora(ctx, adapter, 1.0)` to attach to context

**For Qwen2.5-1.5B (28 layers, 7 target modules per layer):**
- 28 × 7 = 196 weight tensor pairs (used to be 280 with biases)

**For LFM2-350M (16 layers, ~10 recurrent):**
- 6 attention layers × 7 targets = 42 weight tensor pairs (was 54 with more targets)

### Step 4: Set Training Data

```kotlin
loraJNI.setTrainingData("The quick brown fox...")
```

**What happens:**
1. Tokenizes text using the model's tokenizer
2. If tokens < `n_ctx + 1 + stride`, repeats them (padding for small datasets)
3. Creates `ggml_opt_dataset` with sliding window:
   - Window size: `n_ctx + 1` (input + 1 target token)
   - Stride: `n_ctx / 2` (50% overlap)
   - Each "data point" is a window of tokens

### Step 5: Training

```kotlin
loraJNI.initTraining(0.0001f, 3)
loraJNI.trainEpoch(0)
```

**Init training (`llama_opt_init`):**
1. Calls `opt_init()` on the context, which:
   - Iterates ALL model tensors and calls `param_filter` on each
   - `llama_opt_param_filter_lora()` returns `true` only for tensors with `.lora_a` or `.lora_b` in their name
   - These get `GGML_TENSOR_FLAG_PARAM` set (marks them as trainable)
   - Base model weights are NOT marked as parameters
2. Sets up AdamW optimizer with given learning rate

**Train epoch (`llama_opt_epoch`):**
1. Iterates over data points in the dataset
2. For each batch:
   a. Fills a `llama_batch` with tokens from the dataset window
   b. Calls `llama_decode()` which builds the forward computation graph:
      - Embedding → RMS Norm → Attention (with LoRA) → FFN (with LoRA) → ... → Output
      - `build_lora_mm(W, x)` generates: `result = W@x + scale * B@A@x`
   c. Calls `ggml_build_backward_expand()` to build the backward graph
      - Traverses forward graph in reverse
      - For each operation, adds gradient computation nodes
      - Only propagates gradients through nodes connected to PARAM tensors
   d. Runs forward + backward pass with `ggml_backend_graph_compute()`
   e. AdamW optimizer updates A/B matrices using computed gradients
3. Returns train loss and eval loss

### Step 6: Save Adapter

```kotlin
loraJNI.saveLoraAdapter(outputPath)
```

**What happens in `llama_lora_save_adapter()`:**
1. Creates a GGUF file with metadata:
   - `general.type = "adapter"`
   - `general.architecture` = model architecture name
   - `adapter.type = "lora"`
   - `adapter.lora.alpha` = alpha value
2. Writes each A/B tensor pair:
   - Tensor names: `blk.{i}.attn_q.weight.lora_a`, `blk.{i}.attn_q.weight.lora_b`
3. The saved file can be loaded back with `llama_adapter_lora_init()` for inference

---

## 6. How LoRA Integrates into llama.cpp

### Forward Pass: `build_lora_mm()`

**File:** `src/llama-graph.cpp:731`

```cpp
ggml_tensor * build_lora_mm(ggml_tensor * w, ggml_tensor * cur) {
    ggml_tensor * res = ggml_mul_mat(ctx0, w, cur);  // Base: W @ x

    for (auto & lora : *loras) {
        auto * lw = lora.first->get_weight(w);
        if (!lw) continue;

        float scale = lw->get_scale(lora.first->alpha, adapter_scale);
        // LoRA: scale * B @ A @ x
        ggml_tensor * ab = ggml_mul_mat(ctx0, lw->b,
                              ggml_mul_mat(ctx0, lw->a, cur));
        ab = ggml_scale(ctx0, ab, scale);
        res = ggml_add(ctx0, res, ab);
    }
    return res;
}
```

This generates the computation graph:
```
res = mul_mat(W, x) + scale(mul_mat(B, mul_mat(A, x)), alpha/rank)
```

### Backward Pass: Automatic Differentiation

ggml's `ggml_build_backward_expand()` in `ggml/src/ggml.c` automatically computes gradients:

1. **Gradient propagation**: Starts from the loss (cross-entropy), works backward through the graph
2. **Parameter filter**: Only tensors with `GGML_TENSOR_FLAG_PARAM` are endpoints for gradient accumulation
3. **Chain rule**: For `y = B @ A @ x`:
   - `dL/dA = B^T @ dL/dy @ x^T` (gradient for A matrix)
   - `dL/dB = dL/dy @ (A @ x)^T` (gradient for B matrix)
4. **Base weights skipped**: Since base weights don't have PARAM flag, gradients stop there

### What "Automatic Differentiation" Means

ggml's backward pass works by:
1. Walking the forward graph in reverse order
2. For each node, looking up its op type in a big `switch` statement
3. Generating the appropriate gradient computation as new graph nodes
4. These gradient nodes become part of an extended graph that includes both forward and backward passes

The key limitation: **every op in the forward graph that gradients need to flow through MUST have a backward implementation in the switch statement.** Missing ops cause `GGML_ABORT("unsupported ggml op for backward pass")`.

---

## 7. The Backward Pass Problem

### The Root Cause

llama.cpp's training support was originally built for a simple `finetune` example that used basic transformer ops. Modern LLM architectures use many more operations, and the backward pass hasn't kept up.

**GitHub Issue #18805** confirms: "llama-finetune is broken on modern transformers"

### Supported Ops (have backward implementations)

```
DUP, ADD, ADD1, ACC, SUB, MUL, DIV, SQR, SQRT, LOG, SIN, COS,
SUM, SUM_ROWS, MEAN, REPEAT, REPEAT_BACK, RMS_NORM, MUL_MAT,
SCALE, SET, CPY, CONT, RESHAPE, VIEW, PERMUTE, TRANSPOSE,
GET_ROWS, DIAG_MASK_INF, DIAG_MASK_ZERO, SOFT_MAX, ROPE,
IM2COL, POOL_2D, WIN_PART, WIN_UNPART,
UNARY (ABS, SGN, NEG, STEP, RELU, SILU, EXP, EXPM1, SOFTPLUS),
CROSS_ENTROPY_LOSS, GLU (SWIGLU split only), NONE
```

### Unsupported Ops (we added gradient-stopping stubs)

```
SSM_CONV, SSM_SCAN — Mamba/recurrent layer ops
CONCAT — used in shortconv block
FLASH_ATTN_EXT — flash attention (disabled instead)
```

### Potentially Unsupported Ops (still investigating)

- `GGML_UNARY_OP_TANH` — used in attention softcap (Grok, Gemma)
- `GGML_UNARY_OP_GELU` — used in some FFN variants
- KV cache view operations — may create view tensors that confuse backward pass
- `MUL_MAT_ID` — used in MoE (Mixture of Experts) layers

---

## 8. Errors We Faced and Solved

### Error 1: SSM_CONV/SSM_SCAN Backward Crash (LFM2 hybrid model)

**Symptom:** `GGML_ABORT: unsupported ggml op for backward pass: SSM_CONV`

**Why:** LFM2 has recurrent layers that use `ggml_ssm_conv()` and `ggml_ssm_scan()`. These ops don't have backward pass implementations.

**Solution (two parts):**
1. Added stub backward cases in `ggml.c`:
   ```c
   case GGML_OP_SSM_CONV:
   case GGML_OP_SSM_SCAN:
   case GGML_OP_CONCAT:
   case GGML_OP_FLASH_ATTN_EXT: {
       // Gradient stops here
   } break;
   ```
2. Added layer filtering in `should_target_tensor()` to skip recurrent layers:
   ```c
   if (hparams->is_recurrent(il)) return false;
   ```

### Error 2: Flash Attention Backward Crash

**Symptom:** Crash at `GGML_OP_FLASH_ATTN_EXT` backward

**Why:** Flash attention is an optimized fused operation that doesn't support gradient computation.

**Solution:** Disabled flash attention in context params:
```c
ctx_params.flash_attn_type = static_cast<llama_flash_attn_type>(0);
```
This forces the non-flash attention path (separate MUL_MAT → SCALE → SOFT_MAX → MUL_MAT), which uses ops that DO have backward implementations.

### Error 3: Training Data Too Short

**Symptom:** Crash/empty dataset when training text has fewer tokens than context window

**Why:** `common_opt_dataset_init()` needs at least `n_ctx + 1 + stride` tokens to create even one data point.

**Solution:** Token repetition in `setTrainingData()`:
```cpp
while (tokens.size() < min_tokens) {
    tokens.insert(tokens.end(), original.begin(), original.end());
}
```

### Error 4: Iterator Invalidation During Token Padding

**Symptom:** Crash during token repetition

**Why:** `tokens.insert(tokens.end(), tokens.begin(), tokens.end())` invalidates iterators when the vector reallocates.

**Solution:** Copy to separate vector first:
```cpp
std::vector<llama_token> original(tokens);
while (tokens.size() < min_tokens) {
    tokens.insert(tokens.end(), original.begin(), original.end());
}
```

### Error 5: Quantized Tensor Assertion in Gradient Propagation

**Symptom:** `GGML_ASSERT(node->src[j]->type == GGML_TYPE_F32 || node->src[j]->type == GGML_TYPE_F16)` at line ~6871

**Why:** In `ggml_build_backward_expand`, the gradient propagation code checks if source tensors need gradients. When a `MUL_MAT(quantized_weight, x)` node is encountered, the code checks if the quantized weight's type is F32/F16 — but it's Q4_K_M (a quantized type).

**Solution:** Changed the assertion to a `continue` — skip quantized tensors during gradient need propagation:
```c
if (node->src[j]->type != GGML_TYPE_F32 && node->src[j]->type != GGML_TYPE_F16) {
    continue;  // Skip quantized tensors — they can't be differentiated
}
```

### Error 6: LoRA Applied to Bias Tensors (Qwen)

**Symptom:** Qwen models create LoRA for bias tensors like `blk.0.attn_q.bias: A[1536,4] B[4,1]` — degenerate shapes.

**Why:** `should_target_tensor()` matched any tensor containing "attn_q" in its name, including `blk.0.attn_q.bias`.

**Solution:** Added bias filter:
```c
if (strstr(tensor_name, ".bias")) return false;
```

---

## 9. Error 7 (SOLVED): SET_ROWS View Assertion Crash

### The Problem

Both LFM2 and Qwen2.5 models crashed during `ggml_build_backward_expand` with:
```
GGML_ASSERT(!node->view_src || node->op == GGML_OP_CPY || node->op == GGML_OP_VIEW ||
    node->op == GGML_OP_RESHAPE || node->op == GGML_OP_PERMUTE || node->op == GGML_OP_TRANSPOSE) failed
```

### Root Cause: `ggml_set_rows` in KV Cache

The KV cache uses `ggml_set_rows()` to write K/V values into the cache. This function (in `ggml.c:3850`):
```c
struct ggml_tensor * result = ggml_view_tensor(ctx, a);  // creates a VIEW of the cache
result->op = GGML_OP_SET_ROWS;  // changes the op to SET_ROWS
```

So the result tensor has `view_src` set (it's a view of the KV cache buffer) but its op is `SET_ROWS`, not one of the allowed view ops. The backward pass assertion catches this.

### The Fix (Two Parts)

1. **Added `SET_ROWS` to the view_src assertion allowlist** (line ~6894):
   ```c
   GGML_ASSERT(!node->view_src || ... || node->op == GGML_OP_SET_ROWS);
   ```

2. **Added stub backward case for `SET_ROWS`** (gradient stops at KV cache writes):
   ```c
   case GGML_OP_SET_ROWS: {
       // Gradient stops here — KV cache write op
   } break;
   ```

### Why Gradient Stopping is OK Here

`SET_ROWS` writes computed K/V values into the KV cache. During training:
- The K/V values are computed from attention projections (which DO get LoRA gradients)
- The KV cache is just storage — writing to it doesn't need gradient propagation
- Gradients flow through the K/V computation path, not through the cache write path

---

## Error 8 (SOLVED): Graph Node Capacity Overflow

### The Problem

After fixing the SET_ROWS issue, both LFM2 and Qwen models crashed with:
```
GGML_ASSERT(cgraph->n_nodes < cgraph->size) failed
```
at `ggml_build_backward_expand+1788`. The computation graph ran out of space for nodes during backward pass expansion.

### Root Cause: Graph Sized for Inference Only

llama.cpp pre-allocates computation graphs with a fixed node capacity calculated in `llama_context::graph_max_nodes()`:
```c
uint32_t res = std::max<uint32_t>(1024u, 8u * model.n_tensors());
res += model.n_lora_nodes;
```

This is sized for **inference** — just the forward pass. During training, `ggml_build_backward_expand()` roughly **triples** the graph size by adding:
- Gradient computation nodes (one per differentiable forward node)
- Gradient accumulation nodes
- Optimizer state nodes (AdamW momentum/variance updates)

Additionally, the graphs (`gf_res_prev`, `gf_res_reserve`) are originally created during **context construction** (in the constructor), when `opt_ctx` is still `NULL`. So even if `graph_max_nodes()` checked for training mode, it would return the inference size because training hasn't been initialized yet.

### The Fix (Two Parts)

1. **Multiplied capacity by 4x in training mode** (`llama-context.cpp:~1960`):
   ```c
   uint32_t llama_context::graph_max_nodes(uint32_t n_tokens) const {
       // ... existing code ...
       uint32_t res = std::max<uint32_t>(1024u, 8u * model.n_tensors());
       res += model.n_lora_nodes;
       // Training needs ~4x graph capacity for backward pass + optimizer nodes
       if (opt_ctx) {
           res *= 4;
       }
       return res;
   }
   ```

2. **Recreated graph results in `opt_init()` after `opt_ctx` is set** (`llama-context.cpp:~2715`):
   ```c
   opt_ctx = ggml_opt_init(opt_params);

   // Recreate graph results with larger capacity for backward pass + optimizer
   {
       const uint32_t n_tokens = std::min(n_ctx(), n_ubatch);
       const size_t max_nodes = this->graph_max_nodes(n_tokens);
       LLAMA_LOG_INFO("%s: resizing graph for training: max_nodes = %zu\n", __func__, max_nodes);
       gf_res_prev.reset(new llm_graph_result(max_nodes));
       gf_res_reserve.reset(new llm_graph_result(max_nodes));
   }
   ```

### Why This Works

- The original graphs are created in the constructor with inference-sized capacity
- When `opt_init()` is called to start training, `opt_ctx` gets set
- We then immediately recreate the graphs — now `graph_max_nodes()` sees `opt_ctx != NULL` and returns 4x capacity
- The 4x multiplier gives enough headroom for forward + backward + optimizer nodes
- The log message confirms the new size in logcat: `resizing graph for training: max_nodes = <N>`

---

## Error 9 (SOLVED): n_lora_nodes Assertion on Adapter Free

### The Problem

When freeing a LoRA adapter (e.g., before creating a new one with different `skip_layers`), the app crashed with:
```
GGML_ASSERT(adapter->model.n_lora_nodes >= adapter->get_n_nodes()) failed
```
in `llama_adapter_lora_free()`.

### Root Cause

When `skip_layers` is used, fewer LoRA nodes are created than the model originally registered. The model's `n_lora_nodes` counter was incremented at creation time based on all possible targets, but when an adapter with skipped layers was freed, it tried to subtract more nodes than it contributed, causing an underflow.

### The Fix

Replaced the hard assertion with a safe underflow check in `llama-adapter.cpp`:
```c
void llama_adapter_lora_free(llama_adapter_lora * adapter) {
    uint32_t n_nodes = adapter->get_n_nodes();
    if (adapter->model.n_lora_nodes >= n_nodes) {
        adapter->model.n_lora_nodes -= n_nodes;
    } else {
        LLAMA_LOG_WARN("%s: n_lora_nodes underflow (%u < %u), resetting to 0\n",
                       __func__, adapter->model.n_lora_nodes, n_nodes);
        adapter->model.n_lora_nodes = 0;
    }
    delete adapter;
}
```

---

## 12. Speed Optimizations

Training on CPU is inherently slow. We applied several optimizations to make it practical on mobile:

### 1. Skip Early Layers (`n_layers_skip`)

Most model knowledge is encoded in early layers. Later layers handle task-specific behavior. By skipping early layers, we train far fewer parameters with minimal quality loss.

**Implementation:** Added `n_layers_skip` parameter to `llama_adapter_lora_create()`:
```c
// In should_target_tensor():
if (n_layers_skip > 0) {
    int il = get_layer_index(tensor_name);
    if (il >= 0 && il < n_layers_skip) {
        return false;  // Skip this layer
    }
}
```

**Impact for Qwen2.5-1.5B (28 layers):**
- `skip=0`: 196 LoRA tensor pairs (all layers)
- `skip=20`: 56 LoRA tensor pairs (last 8 layers only) — **3.5× fewer params**
- Fewer params = smaller backward graph = faster training + less memory

### 2. Maximize Thread Count

Changed from conservative `min(4, cores-2)` to aggressive `max(2, cores-2)`:
```c
int n_threads_actual = (nThreads > 0) ? nThreads : std::max(2, n_cpus - 2);
```

### 3. Reduce Context Length

Default reduced from 128 to 64. Smaller context = smaller KV cache, smaller attention matrices, fewer computation nodes per batch.

### 4. Recommended Settings for Speed

| Parameter | Fast (low quality) | Balanced | Thorough |
|-----------|-------------------|----------|----------|
| Context | 32 | 64 | 128 |
| Rank | 2 | 4 | 8 |
| Skip Layers | 24 (last 4) | 20 (last 8) | 0 (all) |
| Epochs | 1 | 3 | 5 |

---

## 13. GPU/NPU Acceleration Attempts

### QNN NPU — Not Viable for Training

**Why it doesn't work:**
1. QNN SDK is **inference-only** — it has no backward pass / gradient computation support
2. `libcdsprpc.so` (DSP runtime) is blocked by Android's linker namespace isolation on retail devices — the QNN HTP backend can't load
3. Even if it could load, NPU hardware is designed for inference (matrix multiply, activation functions) not gradient computation

**QNN CPU backend** works but offers no speed advantage over llama.cpp's CPU backend.

### Vulkan GPU — Crashes on Backward Pass

**What we tried:**
1. Enabled `GGML_VULKAN=ON` in CMakeLists.txt
2. Fixed build issues:
   - Android NDK lacks C++ Vulkan headers (`vulkan.hpp`) — copied from host system into NDK sysroot
   - Needed `vk_video/` headers too
   - Bumped minSdk from 27 to 30 for Vulkan 1.1 (`vkGetPhysicalDeviceFeatures2`)
3. Vulkan loaded successfully and inference worked

**Why it failed:** During training backward pass, the Adreno GPU driver crashed with:
```
vk::Queue::submit: ErrorDeviceLost
```
Adreno GPU drivers can't handle the backward graph operations (gradient computation involves ops the driver doesn't support or hits memory/shader limits).

**Conclusion:** Vulkan is set to OFF with this comment in CMakeLists.txt:
```cmake
# Vulkan GPU: builds and loads on Adreno but crashes during training backward pass
# (ErrorDeviceLost — Adreno driver can't handle backward graph ops)
# Keep OFF for training. Can enable for inference-only use later.
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
```

### Future Options

- **ggml-hexagon backend**: Exists in llama.cpp for QNN/NPU integration via the Hexagon DSP — may work on future devices or rooted phones that bypass linker isolation
- **Vulkan inference + CPU training**: Could load model on GPU for inference, switch to CPU for training epochs — would require significant refactoring
- **Newer Adreno drivers**: Future GPU driver updates may fix the ErrorDeviceLost crash

---

## 14. Real-Time Logging System

### The Problem

Training felt "freezy" — the UI showed no progress during long-running native operations. Users couldn't tell if training was working or stuck.

### Solution: JNI Callback Pipeline

We implemented a three-layer logging system that pipes llama.cpp internal logs and per-batch training progress to the Android UI in real-time.

### Architecture

```
llama.cpp internal logs ──→ log_callback() ──→ filters important msgs ──→ UI
                                                                          ↑
lora_train.cpp stages ────→ ui_log() ────────→ formats + sends ──────────┘
                                                                          ↑
ggml_opt per-batch ───────→ train_progress_callback() ──→ loss/speed ────┘
                                        ↓
                               JNI CallVoidMethod
                                        ↓
                          LoraJNI.LogCallback.onLog(msg)
                                        ↓
                             runOnUiThread { appendLog(msg) }
```

### Implementation Details

**1. JNI Callback Registration (`lora_train.cpp`)**
```c
static JavaVM    * g_jvm      = nullptr;
static jobject     g_callback = nullptr;  // Global ref to Kotlin callback
static jmethodID   g_on_log   = nullptr;  // LogCallback.onLog method ID
static std::mutex  g_log_mutex;
```
- `JavaVM*` stored to attach/detach threads as needed
- Global ref prevents garbage collection of the callback object
- Mutex protects concurrent access from training thread + llama.cpp threads

**2. `ui_log()` — Universal Log Function**
- Sends formatted messages to both logcat AND Kotlin UI
- Handles thread attachment (training runs on a background thread)
- Used by all JNI functions for stage logging

**3. `log_callback()` — llama.cpp Log Interceptor**
- Registered via `llama_log_set(log_callback, nullptr)`
- Intercepts ALL llama.cpp internal log messages
- Filters to forward only important messages to UI (model loading, LoRA, graph, memory, errors)
- Everything still goes to logcat for debugging

**4. `train_progress_callback()` — Per-Batch Training Progress**
- Matches `ggml_opt_epoch_callback` signature
- Called after each batch during `llama_opt_epoch()`
- Reports: batch N/M, loss, batches/sec, elapsed time
- Reports every batch for small datasets, every 5 batches for larger ones

**5. Kotlin Side (`LoraJNI.kt`)**
```kotlin
interface LogCallback {
    fun onLog(message: String)
}
external fun setLogCallback(callback: LogCallback?)
```

**6. UI Integration (`MainActivity.kt`)**
```kotlin
LaunchedEffect(Unit) {
    loraJNI.setLogCallback(object : LoraJNI.LogCallback {
        override fun onLog(message: String) {
            runOnUiThread { appendLog(message) }
        }
    })
}
```

### What the User Sees

During training, the log scrolls with messages like:
```
Initializing llama.cpp backend...
Loading backends from: /data/app/.../lib/arm64
Backend initialized (CPU)
Loading model: /storage/emulated/0/Download/qwen2.5-1.5b-q2_k.gguf
use_mmap=false (required for training)
CPU cores: 8, using 6 threads
Model: Qwen2.5 1.5B Q2_K (0.94 GB)
Creating LoRA adapter (rank=4, alpha=8.0, skip_layers=20)...
LoRA adapter applied to context
Training text: 450 chars
Tokenized: 98 tokens
Padded tokens: 98 -> 196 (min needed: 97)
Dataset: 3 data points, stride=32, ctx=64
Initializing AdamW optimizer (lr=0.000100, epochs=1)...
=== Epoch 1 (data: 2 train, 1 eval) ===
[train] batch 1/2 | loss: 4.2156 | 0.3 batch/s | 3.2s elapsed
[train] batch 2/2 | loss: 3.8921 | 0.3 batch/s | 6.1s elapsed
[eval] batch 1/1 | loss: 3.7544 | 0.5 batch/s | 1.9s elapsed
Epoch 1 complete: train_loss=4.0538, eval_loss=3.7544, lr=0.000100
Saving LoRA adapter to: /data/data/com.dark.trainer/files/lora_adapter.gguf
Adapter saved successfully!
```

---

## 15. File Reference

### Files WE Modified in llama.cpp

| File | What We Changed |
|------|----------------|
| `include/llama.h` | Added `llama_opt_param_filter_lora`, `llama_adapter_lora_create` (with `n_layers_skip`), `llama_lora_save_adapter` |
| `src/llama-adapter.cpp` | Added `should_target_tensor()`, `llama_adapter_lora_create()`, `llama_lora_save_adapter()`, bias/recurrent/layer-skip filtering, safe `llama_adapter_lora_free()` |
| `src/llama-context.cpp:~1960` | 4x graph capacity multiplier in `graph_max_nodes()` for training mode |
| `src/llama-context.cpp:~2715` | Recreate graph results in `opt_init()` after `opt_ctx` is set |
| `src/llama-context.cpp:~2735` | Register LoRA tensors as trainable params in `opt_init()` |
| `src/llama-context.cpp:~3635` | `llama_opt_param_filter_lora()` implementation |
| `ggml/src/ggml.c:~6712` | Added stub backward cases for SSM_CONV, SSM_SCAN, CONCAT, FLASH_ATTN_EXT, SET_ROWS |
| `ggml/src/ggml.c:~6871` | Changed quantized tensor assertion to skip/continue |
| `ggml/src/ggml.c:~6894` | Added SET_ROWS to `view_src` assertion allowlist |
| `ggml/src/ggml.c:~248` | Added `fflush(stderr)` + Android logging to `ggml_abort()` |

### Files in Trainer Project

| File | Purpose |
|------|---------|
| `lora/src/main/cpp/lora_train.cpp` | JNI bridge — all training functions + JNI log callback + training progress callback |
| `lora/src/main/cpp/CMakeLists.txt` | Build config — links llama.cpp as subdirectory, Vulkan OFF |
| `lora/src/main/java/com/dark/lora/LoraJNI.kt` | Kotlin JNI external declarations + LogCallback interface |
| `app/src/main/java/com/dark/trainer/MainActivity.kt` | Compose UI — single-button training with stage indicators and real-time log |

### Key llama.cpp Functions (Unmodified but Important)

| Function | File | What It Does |
|----------|------|-------------|
| `build_lora_mm()` | `src/llama-graph.cpp:731` | Injects LoRA into forward pass: `W@x + scale*B@A@x` |
| `build_attn_mha()` | `src/llama-graph.cpp:1467` | Non-flash attention path (MUL_MAT → SOFT_MAX → MUL_MAT) |
| `build_ffn()` | `src/llama-graph.cpp:820` | FFN with SwiGLU |
| `ggml_build_backward_expand()` | `ggml/src/ggml.c:~6810` | Builds backward computation graph |
| `opt_init()` | `src/llama-context.cpp:~2680` | Marks trainable parameters |

---

## 16. Memory Estimates

### Qwen2.5-1.5B-Q4_K_M, rank=4, ctx=128

| Component | Size |
|-----------|------|
| Base model (Q4_K_M) | ~900 MB |
| LoRA A/B (28 layers × 7 targets × rank 4 × 1536 dim × F32 × 2) | ~12 MB |
| Adam states (2× LoRA) | ~24 MB |
| KV cache (F32, ctx=128) | ~50 MB |
| Activations + gradients | ~200 MB |
| **Total** | **~1.2 GB** |

### LFM2-350M-Q4_K_M, rank=4, ctx=128

| Component | Size |
|-----------|------|
| Base model (Q4_K_M) | ~250 MB |
| LoRA A/B (6 attn layers × 7 targets × rank 4) | ~4 MB |
| Adam states | ~8 MB |
| KV cache (F32, ctx=128) | ~30 MB |
| Activations + gradients | ~100 MB |
| **Total** | **~400 MB** |

---

## 17. How to Debug Further

### Rebuild After Code Changes

```bash
# In Android Studio: Build → Clean Project, then Build → Make Project
# Or from command line:
cd /home/home/AndroidStudioProjects/Trainer
./gradlew :lora:assembleDebug
```

### Logcat Filtering

```bash
# Watch training logs
adb logcat -s LORA_TRAIN:I GGML:*

# Watch for the crash message (after our fix)
adb logcat | grep -E "GGML|LORA_TRAIN|SIGABRT|ggml_build_backward"
```

### What to Look For After the Fix

With the `__android_log_print` we added to `ggml_abort()`, when the crash happens you should see something like:
```
GGML: ggml.c:6722: ggml_build_backward_expand: unsupported ggml op for backward pass: <OP_NAME>
```

The `<OP_NAME>` tells you exactly what op needs a backward implementation.

### Adding a Backward Pass for a New Op

In `ggml/src/ggml.c`, find the backward pass switch (~line 6255) and add a case:

```c
case GGML_OP_YOUR_OP: {
    if (src0_needs_grads) {
        // Compute d(loss)/d(src0) and accumulate:
        ggml_add_or_set(ctx, cgraph, isrc0, <gradient expression>);
    }
    if (src1_needs_grads) {
        // Same for src1 if applicable
        ggml_add_or_set(ctx, cgraph, isrc1, <gradient expression>);
    }
} break;
```

Or if the op is not on the critical training path, add it as a stub (gradient stops):
```c
case GGML_OP_YOUR_OP: {
    // Gradient stops here — not on training path
} break;
```

### Common Gradient Formulas

| Op | Gradient for src0 | Gradient for src1 |
|----|-------------------|-------------------|
| `ADD(a, b)` | `grad` | `grad` |
| `MUL(a, b)` | `grad * b` | `grad * a` |
| `MUL_MAT(a, b)` | `out_prod(b, grad)` | `mul_mat(a^T, grad)` |
| `SCALE(a, s)` | `grad * s` | N/A |
| `SILU(a)` | `silu_back(grad, a)` | N/A |
| `TANH(a)` | `grad * (1 - tanh(a)^2)` | N/A |
| `RMS_NORM(a)` | `rms_norm_back(grad, a)` | N/A |

---

## Summary

We're building an Android app that fine-tunes quantized LLMs on-device using LoRA/QLoRA. The system works by:

1. Loading a quantized GGUF model via llama.cpp
2. Creating fresh F32 LoRA A/B matrices for attention + FFN weights
3. Using llama.cpp's automatic differentiation to compute gradients
4. Only updating LoRA matrices (base model frozen)
5. Saving trained adapters as GGUF files

**What works:** The full training pipeline — model loading, LoRA adapter creation, data preparation, training initialization, forward pass, backward pass, optimizer updates, and saving. Training runs successfully on Qwen2.5-1.5B-Q4_K_M on Android with real-time progress logging to the UI.

**Errors solved:** 9 distinct crashes fixed across the ggml backward pass, graph capacity, KV cache view assertions, bias filtering, recurrent layer handling, adapter free underflow, and Android logging.

**Speed optimizations:** Layer skipping (`n_layers_skip`), aggressive thread usage, reduced default context (64). GPU (Vulkan) and NPU (QNN) both investigated and confirmed non-viable for training on current Android hardware.

**Real-time logging:** JNI callback pipeline sends llama.cpp internal logs, stage progress, and per-batch training metrics (loss, speed, elapsed time) to the Compose UI in real-time.

**The fundamental challenge:** llama.cpp's training support (backward pass) hasn't kept up with the variety of ops used in modern LLM architectures. This is a known issue (GitHub #18805). Each new model architecture may introduce ops that need backward pass implementations added.
