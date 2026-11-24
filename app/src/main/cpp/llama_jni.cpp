// JNI bridge for com.sliit.thirdeye.SmolVlmDescriber: SmolVLM2 through llama.cpp's
// multimodal library (libmtmd). One photo in, one or two plain sentences out.
#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <string>
#include <vector>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "ThirdEyeVlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Raw UTF-8 bytes back to Java (decoded there): NewStringUTF aborts the app on byte
// sequences that aren't modified UTF-8, e.g. emoji or a character split across tokens.
static jbyteArray to_bytes(JNIEnv *env, const std::string &s) {
    jbyteArray arr = env->NewByteArray((jsize) s.size());
    if (arr != nullptr) env->SetByteArrayRegion(arr, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

namespace {

struct Vlm {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    mtmd_context *mctx = nullptr;
    const llama_vocab *vocab = nullptr;
    int n_batch = 512;
    std::atomic<bool> abort{false};
};

bool abort_cb(void *data) {
    return static_cast<Vlm *>(data)->abort.load();
}

void free_vlm(Vlm *v) {
    if (v == nullptr) return;
    if (v->mctx) mtmd_free(v->mctx);
    if (v->ctx) llama_free(v->ctx);
    if (v->model) llama_model_free(v->model);
    delete v;
}

// The model's own chat template around "<__media__>\n<prompt>", so SmolVLM sees the
// format it was trained on.
std::string chat_prompt(const llama_model *model, const std::string &user) {
    const char *tmpl = llama_model_chat_template(model, nullptr);
    llama_chat_message msg{"user", user.c_str()};
    std::vector<char> buf(user.size() * 2 + 512);
    int n = llama_chat_apply_template(tmpl, &msg, 1, true, buf.data(), (int32_t) buf.size());
    if (n > (int) buf.size()) {
        buf.resize(n + 1);
        n = llama_chat_apply_template(tmpl, &msg, 1, true, buf.data(), (int32_t) buf.size());
    }
    if (n < 0) return "User:" + user + "<end_of_utterance>\nAssistant:";   // SmolVLM format
    return std::string(buf.data(), n);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_sliit_thirdeye_SmolVlmDescriber_nativeLoad(JNIEnv *env, jclass, jstring jmodel,
                                                    jstring jmmproj, jint threads, jint n_ctx) {
    static bool backend_ready = false;
    if (!backend_ready) {
        llama_backend_init();
        backend_ready = true;
    }
    const char *model_path = env->GetStringUTFChars(jmodel, nullptr);
    const char *mmproj_path = env->GetStringUTFChars(jmmproj, nullptr);

    auto *v = new Vlm();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    v->model = llama_model_load_from_file(model_path, mp);
    if (v->model != nullptr) {
        v->vocab = llama_model_get_vocab(v->model);
        llama_context_params cp = llama_context_default_params();
        cp.n_ctx = (uint32_t) n_ctx;
        cp.n_batch = (uint32_t) v->n_batch;
        cp.n_threads = threads;
        cp.n_threads_batch = threads;
        cp.abort_callback = abort_cb;
        cp.abort_callback_data = v;
        v->ctx = llama_init_from_model(v->model, cp);
    }
    if (v->ctx != nullptr) {
        mtmd_context_params mtp = mtmd_context_params_default();
        mtp.use_gpu = false;
        mtp.n_threads = threads;
        mtp.print_timings = false;
        v->mctx = mtmd_init_from_file(mmproj_path, v->model, mtp);
    }
    if (v->mctx == nullptr) {
        LOGE("could not load %s / %s", model_path, mmproj_path);
        free_vlm(v);
        v = nullptr;
    }
    env->ReleaseStringUTFChars(jmodel, model_path);
    env->ReleaseStringUTFChars(jmmproj, mmproj_path);
    return reinterpret_cast<jlong>(v);
}

JNIEXPORT jbyteArray JNICALL
Java_com_sliit_thirdeye_SmolVlmDescriber_nativeDescribe(JNIEnv *env, jclass, jlong handle,
                                                        jbyteArray jrgb, jint width, jint height,
                                                        jstring jprompt, jint max_tokens) {
    auto *v = reinterpret_cast<Vlm *>(handle);
    if (v == nullptr) return nullptr;
    v->abort = false;

    const char *p = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt = chat_prompt(v->model, std::string(mtmd_default_marker()) + "\n" + p);
    env->ReleaseStringUTFChars(jprompt, p);

    jbyte *rgb = env->GetByteArrayElements(jrgb, nullptr);
    mtmd_bitmap *bitmap = mtmd_bitmap_init((uint32_t) width, (uint32_t) height,
                                           reinterpret_cast<const unsigned char *>(rgb));
    env->ReleaseByteArrayElements(jrgb, rgb, JNI_ABORT);
    if (bitmap == nullptr) return nullptr;

    // Each photo is a fresh conversation.
    llama_memory_clear(llama_get_memory(v->ctx), true);

    mtmd_input_text text{prompt.c_str(), /*add_special=*/true, /*parse_special=*/true};
    mtmd_input_chunks *chunks = mtmd_input_chunks_init();
    const mtmd_bitmap *bitmaps[] = {bitmap};
    int32_t rc = mtmd_tokenize(v->mctx, chunks, &text, bitmaps, 1);
    llama_pos n_past = 0;
    if (rc == 0) {
        rc = mtmd_helper_eval_chunks(v->mctx, v->ctx, chunks, 0, 0, v->n_batch, true, &n_past);
    }
    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(bitmap);
    if (rc != 0 || v->abort) {
        LOGE("prompt evaluation failed or was stopped: %d", rc);
        return nullptr;
    }

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());   // steady answers, no dice

    std::string out;
    char piece[256];
    for (int i = 0; i < max_tokens && !v->abort; ++i) {
        llama_token tok = llama_sampler_sample(smpl, v->ctx, -1);
        if (llama_vocab_is_eog(v->vocab, tok)) break;
        int n = llama_token_to_piece(v->vocab, tok, piece, sizeof(piece), 0, false);
        if (n > 0) out.append(piece, n);
        llama_batch batch = llama_batch_get_one(&tok, 1);
        if (llama_decode(v->ctx, batch) != 0) break;
    }
    llama_sampler_free(smpl);
    if (v->abort) return nullptr;
    return to_bytes(env, out);
}

JNIEXPORT void JNICALL
Java_com_sliit_thirdeye_SmolVlmDescriber_nativeAbort(JNIEnv *, jclass, jlong handle) {
    auto *v = reinterpret_cast<Vlm *>(handle);
    if (v != nullptr) v->abort = true;
}

JNIEXPORT void JNICALL
Java_com_sliit_thirdeye_SmolVlmDescriber_nativeFree(JNIEnv *, jclass, jlong handle) {
    free_vlm(reinterpret_cast<Vlm *>(handle));
}

}  // extern "C"
