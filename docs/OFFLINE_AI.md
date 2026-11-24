# Offline AI: Whisper listening (step 5) and SmolVLM2 describer (step 6)

Third Eye listens with Android's own speech recogniser whenever it can, because that
handles Sinhala (`si-LK`) and Tamil (`ta-LK`) far better than a small Whisper model.
Whisper (English only) is the fallback when the phone has no recogniser app or no internet.

Without the native library or the model the app works exactly as before: it uses Android's
recogniser, and if there is none it says so out loud.

## Language setting

Say **"speak Sinhala"**, **"speak Tamil"** or **"speak English"** (also "සිංහලෙන් කතා කරන්න",
"தமிழில் பேசு"). The choice is remembered. It sets the recogniser language and the speech voice.
If the phone has no Sinhala or Tamil voice, Third Eye keeps speaking English and says how to
add one (Settings > Text-to-speech > Install voice data). Replies are still English sentences;
translating them is a separate piece of work.

## 1. Install the NDK and CMake (Android Studio)

Android Studio > Settings > Languages & Frameworks > Android SDK > **SDK Tools** tab >
tick **Show Package Details**, then tick:

- **NDK (Side by side) 26.3.11579264**
- **CMake 3.22.1**

Apply. They land in `%LOCALAPPDATA%\Android\Sdk\ndk\26.3.11579264` and
`%LOCALAPPDATA%\Android\Sdk\cmake\3.22.1`. (Neither was installed when this was written.)

## 2. Get whisper.cpp and llama.cpp

From the repository root (`third_party/` is git-ignored):

```powershell
git clone --depth 1 --branch v1.8.2 https://github.com/ggml-org/whisper.cpp third_party/whisper.cpp
```

```powershell
git clone --depth 1 --branch b7100 https://github.com/ggml-org/llama.cpp third_party/llama.cpp
```

Either one alone also works (only that feature is built).

**One ggml.** Both projects carry a copy of ggml; linking two copies fails with duplicate
symbols. `app/src/main/cpp/CMakeLists.txt` therefore builds ggml once, from llama.cpp when it
is present (else from whisper.cpp), and compiles `whisper.cpp/src/whisper.cpp` and llama.cpp's
`tools/mtmd` against that single copy. That only works when the two checkouts use the same
ggml API, so pick tags released close together: whisper.cpp regularly syncs ggml from
llama.cpp, and its release notes / commit log ("sync : ggml") show which llama.cpp build it
matches. The tags above are suggestions and were **not** compiled here (no NDK on this PC);
if whisper.cpp fails to compile against llama.cpp's ggml, move one of the tags until they
match. The JNI code uses these APIs: `whisper_init_from_file_with_params`, `whisper_full`;
`llama_model_load_from_file`, `llama_init_from_model`, `llama_memory_clear`,
`mtmd_init_from_file`, `mtmd_tokenize`, `mtmd_helper_eval_chunks` (llama.cpp from mid-2025 on).

## 3. Build with the native library switched on

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
./gradlew :app:assemblePhoneDebug -PthirdeyeNativeAi=true
```

Or add `thirdeyeNativeAi=true` to `gradle.properties` (or your user `~/.gradle/gradle.properties`)
to make it permanent. Leave it off and both variants build without the NDK.

## 4. Put the Whisper model on the phone

Recommended: `ggml-base.en-q5_1.bin` (~57 MB). Smaller and less accurate:
`ggml-tiny.en-q5_1.bin` (~31 MB). The app also accepts the unquantised
`ggml-base.en.bin` (~148 MB) and `ggml-tiny.en.bin` (~78 MB). It picks the first it finds in
that order.

```powershell
New-Item -ItemType Directory -Force models | Out-Null
Invoke-WebRequest -Uri "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin" -OutFile "models/ggml-base.en-q5_1.bin"
```

Copy it to the phone (install the app once first so the folder exists):

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb shell mkdir -p /sdcard/Android/data/com.sliit.thirdeye/files/models
& $adb push models/ggml-base.en-q5_1.bin /sdcard/Android/data/com.sliit.thirdeye/files/models/
```

The app looks in `files/models/` inside its private folder first, then in
`/sdcard/Android/data/com.sliit.thirdeye/files/models/`.

## 5. Put the SmolVLM2 files on the phone (optional, step 6)

"What is this?" without internet uses SmolVLM2 when its two files (model + mmproj) are present
and the phone has the memory; otherwise it keeps using the ML Kit labels and the object
detector. Any error, or no answer within 20 seconds, also falls back to the labels.

| Option | Files | Download | Phone RAM |
|---|---|---|---|
| 500M (preferred) | `SmolVLM2-500M-Video-Instruct-Q8_0.gguf` + `mmproj-SmolVLM2-500M-Video-Instruct-Q8_0.gguf` | ~440 MB + ~105 MB | 4 GB phone, ~1.1 GB free |
| 256M (low memory) | `SmolVLM2-256M-Video-Instruct-Q8_0.gguf` + `mmproj-SmolVLM2-256M-Video-Instruct-Q8_0.gguf` | ~175 MB + ~105 MB | 3 GB phone, ~600 MB free |

(The `f16` mmproj files are accepted too.) Sizes are approximate; check the Hugging Face page.

```powershell
$hf = "https://huggingface.co/ggml-org/SmolVLM2-500M-Video-Instruct-GGUF/resolve/main"
Invoke-WebRequest -Uri "$hf/SmolVLM2-500M-Video-Instruct-Q8_0.gguf" -OutFile "models/SmolVLM2-500M-Video-Instruct-Q8_0.gguf"
Invoke-WebRequest -Uri "$hf/mmproj-SmolVLM2-500M-Video-Instruct-Q8_0.gguf" -OutFile "models/mmproj-SmolVLM2-500M-Video-Instruct-Q8_0.gguf"
& $adb push models/SmolVLM2-500M-Video-Instruct-Q8_0.gguf /sdcard/Android/data/com.sliit.thirdeye/files/models/
& $adb push models/mmproj-SmolVLM2-500M-Video-Instruct-Q8_0.gguf /sdcard/Android/data/com.sliit.thirdeye/files/models/
```

For the 256M model, use `ggml-org/SmolVLM2-256M-Video-Instruct-GGUF` and swap `500M` for
`256M` in the file names. With both present the app picks 500M when memory allows, else 256M.

## 6. Try it on a phone

1. Turn on airplane mode, open Third Eye, tap the big button.
2. The first time it says "No internet, so I'm listening on the phone. Speak after the beep."
   and asks for microphone permission.
3. After the beep, say "what is this". It should open the camera screen.
4. Still offline, tap the screen to take a photo. With SmolVLM2 installed you should hear one
   or two sentences (first photo may fall back to labels while the model loads). `adb logcat
   -s LocalDescriber SmolVlmDescriber ThirdEyeVlm` shows which engine was picked.
