/**
 * llm_jni.cpp: JNI bridge between LocalLLM.kt and llama.cpp.
 * Uses only the public llama.h API (no "common" library).
 *
 * Place at: app/src/main/cpp/llm_jni.cpp
 */

#include <jni.h>
#include <mutex>
#include <string>
#include <vector>
#include <android/log.h>

#include "llama.h"

#define TAG "SanyaLLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct LlamaHandle {
    llama_model       *model = nullptr;
    llama_context     *ctx   = nullptr;
    const llama_vocab *vocab = nullptr;
    std::mutex         lock;
};

// JNI's NewStringUTF needs valid (modified) UTF-8. Keep ASCII and valid 2/3-byte
// sequences; drop everything else (broken pieces, 4-byte emoji, NUL).
static std::string sanitize_utf8(const std::string &s) {
    std::string out;
    out.reserve(s.size());
    size_t i = 0, n = s.size();
    while (i < n) {
        unsigned char c = (unsigned char) s[i];
        if (c == 0) { i++; continue; }
        if (c < 0x80) { out.push_back((char) c); i++; continue; }
        if (c >= 0xC2 && c <= 0xDF && i + 1 < n && ((unsigned char) s[i + 1] & 0xC0) == 0x80) {
            out.append(s, i, 2); i += 2; continue;
        }
        if ((c & 0xF0) == 0xE0 && i + 2 < n &&
            ((unsigned char) s[i + 1] & 0xC0) == 0x80 &&
            ((unsigned char) s[i + 2] & 0xC0) == 0x80) {
            out.append(s, i, 3); i += 3; continue;
        }
        i++;
    }
    return out;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_sanya_assistant_LocalLLM_nativeLoad(
    JNIEnv *env, jobject, jstring jPath, jint ctxSize, jint nThreads) {

    const char *path = env->GetStringUTFChars(jPath, nullptr);
    std::string modelPath(path);
    env->ReleaseStringUTFChars(jPath, path);
    LOGI("Loading model: %s", modelPath.c_str());

    llama_backend_init();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;                       // CPU only
    llama_model *model = llama_model_load_from_file(modelPath.c_str(), mp);
    if (!model) { LOGE("model load failed"); return 0L; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = (uint32_t) ctxSize;
    cp.n_batch         = (uint32_t) ctxSize;
    cp.n_threads       = nThreads;
    cp.n_threads_batch = nThreads;

    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        LOGE("context creation failed");
        llama_model_free(model);
        return 0L;
    }

    auto *h  = new LlamaHandle();
    h->model = model;
    h->ctx   = ctx;
    h->vocab = llama_model_get_vocab(model);
    LOGI("Model ready");
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT jstring JNICALL
Java_com_sanya_assistant_LocalLLM_nativeInfer(
    JNIEnv *env, jobject, jlong jHandle, jstring jPrompt, jint maxTokens, jfloat temp) {

    auto *h = reinterpret_cast<LlamaHandle *>(jHandle);
    if (!h || !h->model || !h->ctx) return env->NewStringUTF("");

    const char *p = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(p);
    env->ReleaseStringUTFChars(jPrompt, p);

    std::lock_guard<std::mutex> guard(h->lock);   // one generation at a time

    // ---- tokenise ----
    int need = -llama_tokenize(h->vocab, prompt.c_str(), (int32_t) prompt.size(),
                               nullptr, 0, true, true);
    if (need <= 0) return env->NewStringUTF("");

    std::vector<llama_token> tokens(need);
    if (llama_tokenize(h->vocab, prompt.c_str(), (int32_t) prompt.size(),
                       tokens.data(), (int32_t) tokens.size(), true, true) < 0) {
        return env->NewStringUTF("");
    }

    const int nCtx = (int) llama_n_ctx(h->ctx);
    int budget = maxTokens;
    if ((int) tokens.size() + budget > nCtx) budget = nCtx - (int) tokens.size();
    if (budget < 16) { LOGE("prompt too long (%zu tokens)", tokens.size()); return env->NewStringUTF(""); }

    // Every call starts fresh; Kotlin puts the conversation history in the prompt itself.
    llama_memory_clear(llama_get_memory(h->ctx), true);

    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    if (llama_decode(h->ctx, batch) != 0) {
        LOGE("decode failed on prompt");
        return env->NewStringUTF("");
    }

    // ---- sampler ----
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(64, 1.15f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(42));

    // ---- generate ----
    std::string result;
    char piece[256];

    for (int i = 0; i < budget; i++) {
        llama_token tok = llama_sampler_sample(smpl, h->ctx, -1);
        if (llama_vocab_is_eog(h->vocab, tok)) break;

        int n = llama_token_to_piece(h->vocab, tok, piece, sizeof(piece), 0, false);
        if (n > 0) result.append(piece, (size_t) n);

        llama_batch next = llama_batch_get_one(&tok, 1);
        if (llama_decode(h->ctx, next) != 0) break;
    }

    llama_sampler_free(smpl);
    LOGI("generated %zu bytes", result.size());

    return env->NewStringUTF(sanitize_utf8(result).c_str());
}

JNIEXPORT void JNICALL
Java_com_sanya_assistant_LocalLLM_nativeFree(JNIEnv *, jobject, jlong jHandle) {
    auto *h = reinterpret_cast<LlamaHandle *>(jHandle);
    if (!h) return;
    {
        std::lock_guard<std::mutex> guard(h->lock);
        if (h->ctx)   llama_free(h->ctx);
        if (h->model) llama_model_free(h->model);
    }
    delete h;
}

} // extern "C"
