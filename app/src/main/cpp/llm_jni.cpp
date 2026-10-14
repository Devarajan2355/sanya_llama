/**
 * llm_jni.cpp
 *
 * JNI bridge between LocalLLM.kt and llama.cpp (current API: vocab-based,
 * llama_model_load_from_file / llama_init_from_model / llama_memory_*).
 * Does NOT use llama.cpp's "common" library, so nothing else needs to be built.
 *
 * The resulting .so is  libllama_android.so  and is loaded by LocalLLM.kt.
 */

#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#include "llama.cpp/include/llama.h"

#define TAG "SanyaLLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct LlamaHandle {
    llama_model   *model = nullptr;
    llama_context *ctx   = nullptr;
    int            nCtx  = 2048;
};

// NewStringUTF aborts on invalid UTF-8 (a token can end mid-character), so drop bad bytes.
static std::string sanitizeUtf8(const std::string &s) {
    std::string out;
    out.reserve(s.size());
    size_t i = 0;
    while (i < s.size()) {
        unsigned char c = (unsigned char) s[i];
        size_t len = 0;
        if (c < 0x80) len = 1;
        else if ((c >> 5) == 0x6) len = 2;
        else if ((c >> 4) == 0xE) len = 3;
        else if ((c >> 3) == 0x1E) len = 4;
        if (len == 0 || i + len > s.size()) { i++; continue; }
        bool ok = true;
        for (size_t k = 1; k < len; k++) {
            if (((unsigned char) s[i + k] >> 6) != 0x2) { ok = false; break; }
        }
        if (ok) out.append(s, i, len);
        i += ok ? len : 1;
    }
    return out;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_sanya_assistant_LocalLLM_nativeLoad(
    JNIEnv *env, jobject /* this */, jstring jPath, jint ctxSize, jint nThreads)
{
    const char *path = env->GetStringUTFChars(jPath, nullptr);
    LOGI("Loading model from: %s", path);

    llama_backend_init();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;   // CPU only
    llama_model *model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jPath, path);

    if (!model) {
        LOGE("Failed to load model");
        return 0L;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = (uint32_t) ctxSize;
    cp.n_batch         = (uint32_t) ctxSize;
    cp.n_threads       = nThreads;
    cp.n_threads_batch = nThreads;

    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        LOGE("Failed to create context");
        llama_model_free(model);
        return 0L;
    }

    const llama_vocab *vocab = llama_model_get_vocab(model);
    LOGI("Model loaded. Vocab: %d, Context: %d", llama_vocab_n_tokens(vocab), ctxSize);

    auto *h = new LlamaHandle();
    h->model = model;
    h->ctx   = ctx;
    h->nCtx  = ctxSize;
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT jstring JNICALL
Java_com_sanya_assistant_LocalLLM_nativeInfer(
    JNIEnv *env, jobject /* this */, jlong jHandle, jstring jPrompt, jint maxTokens, jfloat temp)
{
    auto *h = reinterpret_cast<LlamaHandle *>(jHandle);
    if (!h || !h->model || !h->ctx) return env->NewStringUTF("");

    const char *promptCStr = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(promptCStr);
    env->ReleaseStringUTFChars(jPrompt, promptCStr);

    const llama_vocab *vocab = llama_model_get_vocab(h->model);

    // ---- tokenise (first call sizes the buffer) ----
    int n = -llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(),
                            nullptr, 0, /*add_special=*/true, /*parse_special=*/true);
    if (n <= 0) return env->NewStringUTF("");

    std::vector<llama_token> tokens(n);
    if (llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(),
                       tokens.data(), (int32_t) tokens.size(), true, true) < 0) {
        LOGE("tokenize failed");
        return env->NewStringUTF("");
    }

    // Keep room for the reply: if the prompt is too long, keep its tail.
    int room = h->nCtx - (int) maxTokens - 1;
    if (room < 16) room = 16;
    if ((int) tokens.size() > room) {
        tokens.erase(tokens.begin(), tokens.end() - room);
    }

    // Every call is independent: clear the KV cache (history lives in the Kotlin prompt).
    llama_memory_clear(llama_get_memory(h->ctx), true);

    // ---- evaluate prompt ----
    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    if (llama_decode(h->ctx, batch) != 0) {
        LOGE("llama_decode failed on prompt");
        return env->NewStringUTF("");
    }

    // ---- sampler chain ----
    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(
        /*penalty_last_n=*/64, /*penalty_repeat=*/1.15f, /*penalty_freq=*/0.0f, /*penalty_present=*/0.0f));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // ---- generate ----
    std::string result;
    result.reserve(512);

    for (int i = 0; i < maxTokens; i++) {
        llama_token token = llama_sampler_sample(sampler, h->ctx, -1);

        if (llama_vocab_is_eog(vocab, token)) break;

        std::string piece(32, '\0');
        int len = llama_token_to_piece(vocab, token, &piece[0], (int32_t) piece.size(), 0, false);
        if (len < 0) {
            piece.assign((size_t) -len, '\0');
            len = llama_token_to_piece(vocab, token, &piece[0], (int32_t) piece.size(), 0, false);
        }
        if (len > 0) result.append(piece, 0, (size_t) len);

        llama_batch next = llama_batch_get_one(&token, 1);
        if (llama_decode(h->ctx, next) != 0) break;
    }

    llama_sampler_free(sampler);

    LOGI("Generated %zu chars", result.size());
    return env->NewStringUTF(sanitizeUtf8(result).c_str());
}

JNIEXPORT void JNICALL
Java_com_sanya_assistant_LocalLLM_nativeFree(JNIEnv * /*env*/, jobject /* this */, jlong jHandle)
{
    auto *h = reinterpret_cast<LlamaHandle *>(jHandle);
    if (!h) return;
    LOGI("Freeing LLM");
    if (h->ctx)   llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    llama_backend_free();
    delete h;
}

} // extern "C"
