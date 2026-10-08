#include <jni.h>
#include <whisper.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <sys/stat.h>
#include <thread>
#include <vector>

namespace {

// Contrato de audio con AudioRecorder: 16 kHz mono PCM16, como mucho ~70 s.
constexpr jsize MAX_SAMPLES = 16000 * 70;

std::string jstring_to_string(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return {};
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

int thread_count() {
    const auto hardware = std::thread::hardware_concurrency();
    if (hardware == 0) return 2;
    return static_cast<int>(std::min<unsigned int>(hardware, 4));
}

struct ModelFingerprint {
    std::string path;
    off_t size = 0;
    time_t modified = 0;
};

bool model_fingerprint(const std::string & path, ModelFingerprint & out) {
    struct stat info {};
    if (stat(path.c_str(), &info) != 0 || !S_ISREG(info.st_mode)) {
        return false;
    }
    out.path = path;
    out.size = info.st_size;
    out.modified = info.st_mtime;
    return true;
}

bool same_model(const ModelFingerprint & left, const ModelFingerprint & right) {
    return left.path == right.path &&
        left.size == right.size &&
        left.modified == right.modified;
}

// Contexto cacheado entre dictados; solo se toca con transcribe_mutex() tomado.
whisper_context * context = nullptr;
ModelFingerprint loaded_model;

void free_context() {
    if (context == nullptr) return;
    whisper_free(context);
    context = nullptr;
    loaded_model = {};
}

whisper_context * cached_context(const std::string & model_path) {
    ModelFingerprint requested_model;
    if (!model_fingerprint(model_path, requested_model)) {
        return nullptr;
    }

    if (context != nullptr && same_model(loaded_model, requested_model)) {
        return context;
    }

    free_context();

    whisper_context_params context_params = whisper_context_default_params();
    context_params.use_gpu = false;

    context = whisper_init_from_file_with_params(model_path.c_str(), context_params);
    if (context != nullptr) {
        loaded_model = requested_model;
    }
    return context;
}

std::mutex & transcribe_mutex() {
    static std::mutex mutex;
    return mutex;
}

// Cancelación por token: cada transcripción recibe un token creciente desde Kotlin y
// cancelar marca como cancelados los tokens hasta ese. Así una cancelación nunca se pierde aunque
// llegue mientras la transcripción aún espera el mutex, ni afecta a una posterior.
std::atomic<int64_t> cancelled_up_to{0};

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_dev_jorgex_whspr_NativeWhisper_cancelNative(JNIEnv *, jclass, jlong token) {
    int64_t current = cancelled_up_to.load();
    while (token > current && !cancelled_up_to.compare_exchange_weak(current, token)) {
    }
}

// Libera el modelo cacheado si no hay una transcripción en curso (try_lock: nunca
// bloquea al llamante, que es el hilo principal).
extern "C" JNIEXPORT void JNICALL
Java_dev_jorgex_whspr_NativeWhisper_releaseNative(JNIEnv *, jclass) {
    std::unique_lock<std::mutex> lock(transcribe_mutex(), std::try_to_lock);
    if (lock.owns_lock()) free_context();
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_jorgex_whspr_NativeWhisper_transcribeNative(
    JNIEnv * env,
    jclass,
    jshortArray samples,
    jstring model_path,
    jstring language,
    jlong token
) {
    std::lock_guard<std::mutex> lock(transcribe_mutex());
    if (token <= cancelled_up_to.load()) return nullptr;

    const std::string model = jstring_to_string(env, model_path);
    const std::string lang = jstring_to_string(env, language);
    if (samples == nullptr || model.empty()) {
        return nullptr;
    }

    const jsize sample_count = env->GetArrayLength(samples);
    if (sample_count <= 0 || sample_count > MAX_SAMPLES) {
        return nullptr;
    }
    std::vector<jshort> pcm16(static_cast<size_t>(sample_count));
    env->GetShortArrayRegion(samples, 0, sample_count, pcm16.data());
    std::vector<float> pcm(pcm16.size());
    std::transform(pcm16.begin(), pcm16.end(), pcm.begin(), [](jshort sample) {
        return static_cast<float>(sample) / 32768.0f;
    });

    whisper_context * context = cached_context(model);
    if (context == nullptr) {
        return nullptr;
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = thread_count();
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.no_timestamps = true;
    params.no_context = true;
    params.translate = false;
    // Sin tokens no verbales: reduce las etiquetas tipo "[MÚSICA]" en audio sin habla.
    params.suppress_nst = true;
    int64_t request_token = token;
    params.abort_callback = [](void * data) {
        return *static_cast<int64_t *>(data) <= cancelled_up_to.load();
    };
    params.abort_callback_user_data = &request_token;

    if (lang == "auto") {
        params.detect_language = true;
        params.language = "auto";
    } else {
        params.detect_language = false;
        params.language = lang.c_str();
    }

    std::string text;
    if (whisper_full(context, params, pcm.data(), static_cast<int>(pcm.size())) != 0) {
        return nullptr;
    }
    if (token <= cancelled_up_to.load()) return nullptr;
    const int segments = whisper_full_n_segments(context);
    for (int i = 0; i < segments; ++i) {
        const char * segment = whisper_full_get_segment_text(context, i);
        if (segment != nullptr) text += segment;
    }

    return env->NewStringUTF(text.c_str());
}
