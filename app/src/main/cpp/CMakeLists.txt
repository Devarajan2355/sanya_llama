
cmake_minimum_required(VERSION 3.22)

project(sanya_llm LANGUAGES C CXX)

# Android-specific build for Sanya's llama.cpp JNI library.
# Expected layout:
# app/src/main/cpp/
#   CMakeLists.txt
#   llm_jni.cpp
#   llama.cpp/CMakeLists.txt

if(NOT ANDROID)
    message(FATAL_ERROR "This CMakeLists.txt is intended for Android NDK builds.")
endif()

if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/CMakeLists.txt")
    message(FATAL_ERROR
        "llama.cpp is missing. Initialize the submodule at "
        "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp"
    )
endif()

if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/llm_jni.cpp")
    message(FATAL_ERROR
        "llm_jni.cpp is missing from app/src/main/cpp/"
    )
endif()

# Build a single JNI shared library containing the static engine libraries.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Disable optional engine tools that are unnecessary in the Android app.
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD OFF CACHE BOOL "" FORCE)

# Avoid optional host-only or network-dependent components.
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

# Add the llama.cpp engine.
add_subdirectory(
    "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp"
    "${CMAKE_CURRENT_BINARY_DIR}/llama-build"
)

# Sanya's JNI bridge.
add_library(llama_android SHARED
    llm_jni.cpp
)

target_compile_features(llama_android PRIVATE cxx_std_17)

target_include_directories(llama_android PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include"
    "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml/include"
)

find_library(ANDROID_LOG_LIBRARY log REQUIRED)
find_library(ANDROID_LIBRARY android REQUIRED)

target_link_libraries(llama_android PRIVATE
    llama
    "${ANDROID_LOG_LIBRARY}"
    "${ANDROID_LIBRARY}"
)

# Use the Android toolchain's normal stripping and packaging process.
