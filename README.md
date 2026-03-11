# Tiny Aya Chat

A lightweight Android chat app that runs [Cohere's Aya](https://cohere.com/research/aya) multilingual language model entirely on-device using [llama.cpp](https://github.com/ggml-org/llama.cpp). No internet connection required — all inference happens locally on your phone.

Aya supports 23 languages including English, French, Spanish, Arabic, Hindi, Swahili, Japanese, and more.

## Quick Start

### 1. Install the APK

Download `app-debug.apk` from the [latest release](https://github.com/BakungaBronson/tiny-aya-chat/releases/latest) and install it on your Android device.

> Requires Android 8.0+ (API 26) and an arm64 device. Tested on Galaxy S24 Ultra.

### 2. Download the Model

Download the GGUF model file (~2.1 GB) from HuggingFace:

**[tiny-aya-global-q4_k_m.gguf](https://huggingface.co/CohereLabs/tiny-aya-global-GGUF/resolve/main/tiny-aya-global-q4_k_m.gguf)**

### 3. Transfer the Model to Your Device

**Option A: Downloads folder (easiest)**

Copy `tiny-aya-global-q4_k_m.gguf` to your phone's **Downloads** folder using any file transfer method (USB, Google Drive, etc.). The app automatically checks Downloads on startup.

**Option B: ADB push (developers)**

```bash
# Push directly to the app's private storage
adb push tiny-aya-global-q4_k_m.gguf /sdcard/Android/data/com.craneai.tinyaya/files/
```

The app scans these locations in order:
1. `/sdcard/Android/data/com.craneai.tinyaya/files/`
2. App internal storage (`filesDir`)
3. `/sdcard/Download/`
4. `/sdcard/`

### 4. Chat

Launch the app. It will detect and load the model automatically (takes ~10-15 seconds). Once you see "Ready", start chatting.

## Features

- **Fully offline** — no data leaves your device
- **Multilingual** — Aya supports 23 languages natively
- **Streaming** — tokens appear in real-time as they're generated
- **Performance metrics** — live tok/s display during generation
- **Stop generation** — tap Stop to interrupt at any time
- **Optimized inference** — flash attention, Q8 KV cache, big-core thread detection, GPU offloading, mlock

## Performance

On a Galaxy S24 Ultra (Snapdragon 8 Gen 3):

| Metric | Value |
|--------|-------|
| Model load time | ~10s |
| Generation speed | ~8-12 tok/s |
| RAM usage | ~2.5 GB |
| APK size | ~46 MB |

## Building from Source

### Prerequisites

- Android Studio or Android SDK with:
  - NDK 28.0.12916984
  - CMake (3.22.1+ via SDK, or system CMake via Homebrew/apt)
- ~10 GB disk space (llama.cpp native build)

### Build

```bash
# Clone with submodules
git clone --recurse-submodules https://github.com/BakungaBronson/tiny-aya-chat.git
cd tiny-aya-chat

# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug
```

If the llama.cpp submodule is empty:
```bash
git submodule update --init --depth=1
```

If CMake from the SDK is too old (FetchContent errors with KleidiAI), point to a system CMake by adding to `local.properties`:
```
cmake.dir=/opt/homebrew    # macOS with Homebrew
cmake.dir=/usr             # Linux
```

### Project Structure

```
tiny-aya-chat/
├── app/                    # Android app (MVVM, ViewBinding)
│   └── src/main/java/
│       ├── ChatViewModel.kt   # Model loading, chat logic, tok/s tracking
│       └── MainActivity.kt    # UI, message rendering
├── llama/                  # Native library module
│   └── src/main/
│       ├── cpp/
│       │   ├── ai_chat.cpp    # JNI bridge to llama.cpp
│       │   └── CMakeLists.txt # Native build config
│       └── java/
│           └── LlamaEngine.kt # Kotlin wrapper with Flow<String> streaming
└── llama.cpp/              # Git submodule (inference engine)
```

## Architecture

The app uses a `:llama` Android library module with JNI bindings adapted from the official [llama.android example](https://github.com/ggml-org/llama.cpp/tree/master/examples/llama.android). Key design decisions:

- **GGUF format** with Q4_K_M quantization for optimal size/quality on mobile
- **Dynamic backend loading** (`GGML_BACKEND_DL=ON`) — CPU variants (armv8.0 through armv9.2) are selected at runtime based on detected CPU features
- **KleidiAI** acceleration for ARM NEON/dotprod/i8mm operations
- **Big-core detection** — reads sysfs CPU frequencies to avoid scheduling on efficiency cores
- **Context shifting** — discards older tokens when the 2K context fills up, enabling unlimited conversation length

## License

MIT
