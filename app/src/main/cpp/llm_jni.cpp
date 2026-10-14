/**
 * llm_jni.cpp
 *
 * JNI bridge between LocalLLM.kt and llama.cpp.
 *
 * Build setup:
 *   1. Add llama.cpp as a git submodule:
 *        git submodule add https://github.com/ggml-org/llama.cpp \
 *            app/src/main/cpp/llama.cpp
 *   2. Place this file at  app/src/main/cpp/llm_jni.cpp
 *   3. Place CMakeLists.txt at  app/src/main/cpp/CMakeLists.txt
 *   4. Add the cmake block to app/build.gradle (see build.gradle patch file)
 *
 * The resulting .so is named  libllama_android.so  and is loaded by LocalLLM.kt.
 */

#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#include "llama.cpp/include/llama.h"
#include "llama.cpp/common/common.h"

#define TAG "SanyaLLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// We pack model + context into one heap object so we only need one jlong handle.
struct LlamaHandle {
    llama_model   *model = nullptr;
    llama_context *ctx   = nullptr;
};

extern "C" {

// ------------------------------------------------------------------
// nativeLoad
// ------------------------------------------------------------------
JNIEXPORT jlong JNICALL
Java_com_sanya_assistant_LocalLLM_nativeLoad(
    JNIEnv *env,
    jobject /* this */,
    jstring jPath,
    jint    ctxSize,
    jint    nThreads
) {
    const char *path = env->GetStringUTFChars(jPath, nullptr);
    LOGI("Loading model from: %s", path);

    llama_backend_init();

    // ---- model ----
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;          // CPU only — works on every device
    llama_model *model = llama_load_model_from_file(path, mp);
    env->ReleaseStringUTFChars(jPath, path);

    if (!model) {
        LOGE("Failed to load model");
        return 0L;
    }

    // ---- context ----
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx        = (uint32_t) ctxSize;
    cp.n_threads    = (uint32_t) nThreads;
    cp.n_threads_batch = (uint32_t) nThreads;
    cp.flash_attn   = true;       // Faster on ARM where supported
    llama_context *ctx = llama_new_context_with_model(model, cp);

    if (!ctx) {
        LOGE("Failed to create context");
        llama_free_model(model);
        return 0L;
    }

    LOGI("Model loaded. Vocab: %d, Context: %d", llama_n_vocab(model), ctxSize);

    auto *h = new LlamaHandle{model, ctx};
    return reinterpret_cast<jlong>(h);
}

// ------------------------------------------------------------------
// nativeInfer
// ------------------------------------------------------------------
JNIEXPORT jstring JNICALL
Java_com_sanya_assistant_LocalLLM_nativeInfer(
    JNIEnv *env,
    jobject /* this */,
    jlong   jHandle,
    jstring jPrompt,
    jint    maxTokens,
    jfloat  temp
) {
    auto *h = reinterpret_cast<LlamaHandle *>(jHandle);
    if (!h || !h->model || !h->ctx) {
        return env->NewStringUTF("");
    }

    const char *promptCStr = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(promptCStr);
    env->ReleaseStringUTFChars(jPrompt, promptCStr);

    // ---- tokenise ----
    std::vector<llama_token> tokens = llama_tokenize(h->ctx, prompt, /*add_bos=*/true, /*special=*/true);
    if (tokens.empty()) return env->NewStringUTF("");

    // Clear KV cache so every call is independent (stateless on the C++ side;
    // conversation history is managed in Kotlin via the prompt string itself).
    llama_kv_cache_clear(h->ctx);

    // ---- evaluate prompt ----
    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    if (llama_decode(h->ctx, batch) != 0) {
        LOGE("llama_decode failed on prompt");
        return env->NewStringUTF("");
    }

    // ---- sampler chain ----
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(sparams);
    // Repetition penalty — reduces loops common in small models
    llama_sampler_chain_add(sampler,
        llama_sampler_init_penalties(
            llama_n_vocab(h->model),
            llama_token_eos(h->model),
            llama_token_nl(h->model),
            64,     // last_n
            1.15f,  // repeat penalty
            0.0f,   // freq penalty
            0.0f    // presence penalty
        )
    );
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(/*seed=*/42));

    // ---- generate ----
    std::string result;
    result.reserve(512);
    char piece[16];

    for (int i = 0; i < maxTokens; i++) {
        llama_token token = llama_sampler_sample(sampler, h->ctx, /*idx=*/-1);

        if (llama_token_is_eog(h->model, token)) break;

        int n = llama_token_to_piece(h->model, token, piece, sizeof(piece), /*lstrip=*/0, /*special=*/false);
        if (n > 0) result.append(piece, n);

        llama_batch next = llama_batch_get_one(&token, 1);
        if (llama_decode(h->ctx, next) != 0) break;
    }

    llama_sampler_free(sampler);

    LOGI("Generated %zu chars", result.size());
    return env->NewStringUTF(result.c_str());
}

// ------------------------------------------------------------------
// nativeFree
// ------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_sanya_assistant_LocalLLM_nativeFree(
    JNIEnv *env,
    jobject /* this */,
    jlong jHandle
) {
    auto *h = reinterpret_cast<LlamaHandle *>(jHandle);
    if (!h) return;
    LOGI("Freeing LLM");
    if (h->ctx)   llama_free(h->ctx);
    if (h->model) llama_free_model(h->model);
    llama_backend_free();
    delete h;
}

} // extern "C"
