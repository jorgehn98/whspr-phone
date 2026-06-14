cmake_minimum_required(VERSION 3.22.1)

project(whspr)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "" FORCE)
set(GGML_ACCELERATE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

set(WHISPER_CPP_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/whisper.cpp")
add_subdirectory("${WHISPER_CPP_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/whisper.cpp")

add_library(whspr SHARED native_whisper.cpp)

target_link_libraries(
    whspr
    PRIVATE
        whisper
        android
        log
)
