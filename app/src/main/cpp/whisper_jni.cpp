// JNI-мост к whisper.cpp. Контекст модели держим как long-указатель на стороне Kotlin.
// Аудио приходит как float32 PCM, 16 kHz, mono (тот же формат, что ждёт whisper_full).
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "whisper.h"

#define TAG "DuqWhisperJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)

extern "C" JNIEXPORT jlong JNICALL
Java_com_duq_android_audio_WhisperLocal_nativeInit(JNIEnv *env, jobject, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // CPU/NEON; на SD8Elite small-ru идёт быстрее реалтайма
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (ctx == nullptr) {
        LOGE("whisper_init failed for %s", path);
        return 0;
    }
    LOGI("whisper model loaded");
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_duq_android_audio_WhisperLocal_nativeTranscribe(
        JNIEnv *env, jobject, jlong ctxPtr, jfloatArray audio, jstring lang, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx == nullptr) return env->NewStringUTF("");

    const jsize n = env->GetArrayLength(audio);
    std::vector<float> pcm(n);
    env->GetFloatArrayRegion(audio, 0, n, pcm.data());

    const char *langC = env->GetStringUTFChars(lang, nullptr);

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.language               = langC;       // "ru"
    wparams.n_threads              = threads > 0 ? threads : 4;
    wparams.translate              = false;
    wparams.no_timestamps          = true;
    wparams.print_progress         = false;
    wparams.print_realtime         = false;
    wparams.print_special          = false;
    wparams.single_segment         = false;
    wparams.temperature            = 0.0f;
    wparams.no_context             = true;        // зеркалит серверный condition_on_previous_text=false

    std::string out;
    if (whisper_full(ctx, wparams, pcm.data(), (int) pcm.size()) == 0) {
        const int segs = whisper_full_n_segments(ctx);
        for (int i = 0; i < segs; ++i) {
            out += whisper_full_get_segment_text(ctx, i);
        }
    } else {
        LOGE("whisper_full failed");
    }
    env->ReleaseStringUTFChars(lang, langC);

    // trim ведущих пробелов (whisper часто начинает с пробела)
    size_t start = out.find_first_not_of(" \t\n");
    if (start != std::string::npos) out = out.substr(start);
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_duq_android_audio_WhisperLocal_nativeFree(JNIEnv *, jobject, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx != nullptr) whisper_free(ctx);
}
