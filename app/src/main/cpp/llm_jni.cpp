
#include <jni.h>
#include <android/log.h>

#include <cstdint>
#include <limits>
#include <string>
#include <vector>

#include "llama.cpp/include/llama.h"

#define TAG "SanyaLLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct LlamaHandle {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
};

extern "C" {

// ------------------------------------------------------------
// Load a GGUF model
// ------------------------------------------------------------
JNIEXPORT jlong JNICALL
Java_com_sanya_assistant_LocalLLM_nativeLoad(
    JNIEnv* env,
    jobject /* this */,
    jstring jPath,
    jint ctxSize,
    jint nThreads
) {
    if (jPath == nullptr || ctxSize <= 0 || nThreads <= 0) {
        LOGE("Invalid model path or context/thread settings");
        return 0;
    }

    const char* path = env->GetStringUTFChars(jPath, nullptr);
    if (path == nullptr) {
        return 0;
    }

    LOGI("Loading model: %s", path);

    llama_backend_init();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;  // CPU inference

    llama_model* model = llama_model_load_from_file(path, mp);

    env->ReleaseStringUTFChars(jPath, path);

    if (model == nullptr) {
        LOGE("Failed to load model");
        llama_backend_free();
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(ctxSize);
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;

    // Leave flash attention at the library default.
    // The newer API uses flash_attn_type, not the old flash_attn field.

    llama_context* ctx = llama_init_from_model(model, cp);

    if (ctx == nullptr) {
        LOGE("Failed to create context");
        llama_model_free(model);
        llama_backend_free();
        return 0;
    }

    const llama_vocab* vocab = llama_model_get_vocab(model);

    LOGI(
        "Model loaded. Vocabulary: %d, Context: %d",
        llama_vocab_n_tokens(vocab),
        ctxSize
    );

    auto* handle = new LlamaHandle{model, ctx};
    return reinterpret_cast<jlong>(handle);
}

// ------------------------------------------------------------
// Generate a response
// ------------------------------------------------------------
JNIEXPORT jstring JNICALL
Java_com_sanya_assistant_LocalLLM_nativeInfer(
    JNIEnv* env,
    jobject /* this */,
    jlong jHandle,
    jstring jPrompt,
    jint maxTokens,
    jfloat temp
) {
    auto* h = reinterpret_cast<LlamaHandle*>(jHandle);

    if (h == nullptr || h->model == nullptr || h->ctx == nullptr ||
        jPrompt == nullptr || maxTokens <= 0) {
        return env->NewStringUTF("");
    }

    const char* promptChars = env->GetStringUTFChars(jPrompt, nullptr);
    if (promptChars == nullptr) {
        return env->NewStringUTF("");
    }

    std::string prompt(promptChars);
    env->ReleaseStringUTFChars(jPrompt, promptChars);

    const llama_vocab* vocab = llama_model_get_vocab(h->model);
    const int32_t textLen = static_cast<int32_t>(prompt.size());

    // First determine the token buffer size.
    int32_t needed = llama_tokenize(
        vocab,
        prompt.c_str(),
        textLen,
        nullptr,
        0,
        true,
        true
    );

    if (needed == std::numeric_limits<int32_t>::min()) {
        LOGE("Token count overflow");
        return env->NewStringUTF("");
    }

    if (needed < 0) {
        needed = -needed;
    }

    if (needed <= 0) {
        LOGE("Prompt contains no tokens");
        return env->NewStringUTF("");
    }

    std::vector<llama_token> tokens(static_cast<size_t>(needed));

    int32_t tokenCount = llama_tokenize(
        vocab,
        prompt.c_str(),
        textLen,
        tokens.data(),
        static_cast<int32_t>(tokens.size()),
        true,
        true
    );

    // Retry if the required size changed.
    if (tokenCount < 0) {
        if (tokenCount == std::numeric_limits<int32_t>::min()) {
            return env->NewStringUTF("");
        }

        tokens.resize(static_cast<size_t>(-tokenCount));

        tokenCount = llama_tokenize(
            vocab,
            prompt.c_str(),
            textLen,
            tokens.data(),
            static_cast<int32_t>(tokens.size()),
            true,
            true
        );
    }

    if (tokenCount <= 0) {
        LOGE("Prompt tokenization failed");
        return env->NewStringUTF("");
    }

    tokens.resize(static_cast<size_t>(tokenCount));

    // Each inference starts with a clean context cache.
    llama_memory_clear(llama_get_memory(h->ctx), true);

    llama_batch batch = llama_batch_get_one(
        tokens.data(),
        static_cast<int32_t>(tokens.size())
    );

    if (llama_decode(h->ctx, batch) != 0) {
        LOGE("Failed to evaluate prompt");
        return env->NewStringUTF("");
    }

    // Build the sampler chain using the current API.
    llama_sampler_chain_params samplerParams =
        llama_sampler_chain_default_params();

    llama_sampler* sampler =
        llama_sampler_chain_init(samplerParams);

    if (sampler == nullptr) {
        LOGE("Failed to initialize sampler");
        return env->NewStringUTF("");
    }

    llama_sampler_chain_add(
        sampler,
        llama_sampler_init_penalties(
            llama_vocab_n_tokens(vocab),
            64,     // Repetition history
            1.15f,  // Repetition penalty
            0.0f,   // Frequency penalty
            0.0f    // Presence penalty
        )
    );

    llama_sampler_chain_add(
        sampler,
        llama_sampler_init_top_k(40)
    );

    llama_sampler_chain_add(
        sampler,
        llama_sampler_init_top_p(0.95f, 1)
    );

    llama_sampler_chain_add(
        sampler,
        llama_sampler_init_temp(temp)
    );

    llama_sampler_chain_add(
        sampler,
        llama_sampler_init_dist(42)
    );

    std::string result;
    result.reserve(512);

    std::vector<char> piece(256);

    for (int i = 0; i < maxTokens; ++i) {
        llama_token token =
            llama_sampler_sample(sampler, h->ctx, -1);

        // The current API takes a vocabulary, not a model.
        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }

        int32_t n = llama_token_to_piece(
            vocab,
            token,
            piece.data(),
            static_cast<int32_t>(piece.size()),
            0,
            false
        );

        // Increase the output buffer if the token needs more space.
        if (n < 0) {
            if (n == std::numeric_limits<int32_t>::min()) {
                LOGE("Token piece size overflow");
                break;
            }

            piece.resize(static_cast<size_t>(-n));

            n = llama_token_to_piece(
                vocab,
                token,
                piece.data(),
                static_cast<int32_t>(piece.size()),
                0,
                false
            );
        }

        if (n > 0) {
            result.append(piece.data(), static_cast<size_t>(n));
        }

        llama_batch next = llama_batch_get_one(&token, 1);

        if (llama_decode(h->ctx, next) != 0) {
            LOGE("Failed to evaluate generated token");
            break;
        }
    }

    llama_sampler_free(sampler);

    LOGI("Generated %zu characters", result.size());

    return env->NewStringUTF(result.c_str());
}

// ------------------------------------------------------------
// Free the model and context
// ------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_sanya_assistant_LocalLLM_nativeFree(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong jHandle
) {
    auto* h = reinterpret_cast<LlamaHandle*>(jHandle);

    if (h == nullptr) {
        return;
    }

    LOGI("Freeing LLM");

    if (h->ctx != nullptr) {
        llama_free(h->ctx);
    }

    if (h->model != nullptr) {
        llama_model_free(h->model);
    }

    llama_backend_free();
    delete h;
}

} // extern "C"
