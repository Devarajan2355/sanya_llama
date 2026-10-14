cmake_minimum_required(VERSION 3.22)
project(sanya_llm)

# -----------------------------------------------------------------------
# llama.cpp — added as a git submodule at app/src/main/cpp/llama.cpp
#
#   git submodule add https://github.com/ggml-org/llama.cpp \
#       app/src/main/cpp/llama.cpp
#   git submodule update --init --recursive
# -----------------------------------------------------------------------

# Disable everything we don't need to keep the .so small and build fast.
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_METAL           OFF CACHE BOOL "" FORCE)  # iOS only
set(GGML_CUDA            OFF CACHE BOOL "" FORCE)  # no CUDA on Android
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)  # simplifies linking
set(GGML_NEON            ON  CACHE BOOL "" FORCE)  # ARM NEON SIMD — fast on phones
set(GGML_FP16_VA         ON  CACHE BOOL "" FORCE)  # ARM FP16 vector arithmetic

add_subdirectory(llama.cpp)

# -----------------------------------------------------------------------
# Our JNI bridge
# -----------------------------------------------------------------------
add_library(llama_android SHARED llm_jni.cpp)

target_include_directories(llama_android PRIVATE
    llama.cpp/include
    llama.cpp/common
    llama.cpp/ggml/include
)

target_link_libraries(llama_android
    llama          # llama.cpp core
    common         # tokenizer helpers
    android        # Android NDK log
    log
)

# Strip release builds to save ~2 MB from the final APK.
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    add_custom_command(TARGET llama_android POST_BUILD
        COMMAND ${CMAKE_STRIP} --strip-unneeded
            $<TARGET_FILE:llama_android>
    )
endif()
