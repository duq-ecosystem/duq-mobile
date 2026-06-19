# JNI-мост к whisper.cpp для on-device STT.
# Линкуем статическую библиотеку whisper из сабмодуля (add_subdirectory даёт target `whisper`).
cmake_minimum_required(VERSION 3.22.1)
project("duqwhisper" CXX)

# Отключаем всё лишнее из whisper.cpp — нужна только библиотека.
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)   # статика → всё внутри libduqwhisper.so
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)   # на Android OpenMP не нужен

add_subdirectory(whisper.cpp ${CMAKE_BINARY_DIR}/whisper.cpp)

add_library(duqwhisper SHARED whisper_jni.cpp)

target_link_libraries(duqwhisper
    whisper
    android
    log)
