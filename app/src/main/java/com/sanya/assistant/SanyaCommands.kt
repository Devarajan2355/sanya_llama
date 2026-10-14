cmake_minimum_required(VERSION 3.22)
project(sanya_llm)

# llama.cpp is a git submodule at app/src/main/cpp/llama.cpp
#   git submodule add https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp
#   git submodule update --init --recursive

set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)   # static llama/ggml inside our one .so
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)   # we do not use "common"
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)   # default ON needs libcurl: fails on Android
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)   # no host-CPU detection when cross-compiling

add_subdirectory(llama.cpp)

add_library(llama_android SHARED llm_jni.cpp)

target_include_directories(llama_android PRIVATE
    llama.cpp/include
    llama.cpp/ggml/include
)

target_link_libraries(llama_android
    llama
    android
    log
)

if(CMAKE_BUILD_TYPE STREQUAL "Release")
    add_custom_command(TARGET llama_android POST_BUILD
        COMMAND ${CMAKE_STRIP} --strip-unneeded $<TARGET_FILE:llama_android>)
endif()
