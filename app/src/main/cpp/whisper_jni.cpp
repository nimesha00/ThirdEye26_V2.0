// JNI bridge for com.sliit.thirdeye.WhisperRecognizer: offline English speech-to-text.
#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <string>
#include <thread>

#include "whisper.h"

#define TAG "ThirdEyeWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Raw UTF-8 bytes back to Java (decoded there): NewStringUTF aborts the app on byte
// sequences that aren't modified UTF-8, e.g. emoji or a character split across tokens.
static jbyteArray to_bytes(JNIEnv *env, const std::string &s) {
    jbyteArray arr = env->NewByteArray((jsize) s.size());
    if (arr != nullptr) env->SetByteArrayRegion(arr, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_sliit_thirdeye_WhisperRecognizer_nativeInit(JNIEnv *env, jclass, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    if (ctx == nullptr) LOGE("could not load %s", path);
    env->ReleaseStringUTFChars(jpath, path);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jbyteArray JNICALL
Java_com_sliit_thirdeye_WhisperRecognizer_nativeTranscribe(JNIEnv *env, jclass, jlong handle,
                                                           jfloatArray jsamples, jint threads,
                                                           jstring jlang) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) return nullptr;

    const char *lang = env->GetStringUTFChars(jlang, nullptr);
    std::string language(lang);
    env->ReleaseStringUTFChars(jlang, lang);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = std::max(1, (int) threads);
    p.language = language.c_str();
    p.translate = false;
    p.no_context = true;
    p.no_timestamps = true;
    p.single_segment = true;     // a voice command is one short sentence
    p.print_realtime = false;
    p.print_progress = false;
    p.print_timestamps = false;
    p.print_special = false;
    p.suppress_blank = true;

    jsize n = env->GetArrayLength(jsamples);
    jfloat *samples = env->GetFloatArrayElements(jsamples, nullptr);
    int rc = whisper_full(ctx, p, samples, n);
    env->ReleaseFloatArrayElements(jsamples, samples, JNI_ABORT);
    if (rc != 0) {
        LOGE("whisper_full failed: %d", rc);
        return nullptr;
    }

    std::string text;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) text += whisper_full_get_segment_text(ctx, i);
    return to_bytes(env, text);
}

JNIEXPORT void JNICALL
Java_com_sliit_thirdeye_WhisperRecognizer_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx != nullptr) whisper_free(ctx);
}

}  // extern "C"
