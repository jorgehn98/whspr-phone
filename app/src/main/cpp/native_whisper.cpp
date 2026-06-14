#include <jni.h>
#include <whisper.h>

#include <algorithm>
#include <cstdint>
#include <fstream>
#include <mutex>
#include <string>
#include <sys/stat.h>
#include <thread>
#include <vector>

namespace {

constexpr uint32_t MAX_WAV_PCM_BYTES = 16000 * 2 * 70;

template <typename T>
bool read_value(std::ifstream & file, T & out) {
    file.read(reinterpret_cast<char *>(&out), sizeof(T));
    return file.good();
}

bool read_wav_mono_16k(const std::string & path, std::vector<float> & pcm) {
    std::ifstream file(path, std::ios::binary);
    if (!file) return false;

    char riff[4];
    uint32_t riff_size = 0;
    char wave[4];
    if (!file.read(riff, 4)) return false;
    if (!read_value(file, riff_size)) return false;
    if (!file.read(wave, 4)) return false;

    if (std::string(riff, 4) != "RIFF" || std::string(wave, 4) != "WAVE") {
        return false;
    }

    bool valid_format = false;
    std::vector<int16_t> samples;

    while (file && !file.eof()) {
        char chunk_id_raw[4];
        uint32_t chunk_size = 0;
        file.read(chunk_id_raw, 4);
        if (!file) break;
        if (!read_value(file, chunk_size)) break;

        const std::string chunk_id(chunk_id_raw, 4);
        const auto next_chunk = static_cast<std::streamoff>(file.tellg()) + chunk_size + (chunk_size % 2);

        if (chunk_id == "fmt ") {
            uint16_t audio_format = 0;
            uint16_t channels = 0;
            uint32_t sample_rate = 0;
            uint32_t byte_rate = 0;
            uint16_t block_align = 0;
            uint16_t bits_per_sample = 0;

            if (!read_value(file, audio_format)) return false;
            if (!read_value(file, channels)) return false;
            if (!read_value(file, sample_rate)) return false;
            if (!read_value(file, byte_rate)) return false;
            if (!read_value(file, block_align)) return false;
            if (!read_value(file, bits_per_sample)) return false;

            valid_format = audio_format == 1 &&
                channels == 1 &&
                sample_rate == 16000 &&
                bits_per_sample == 16;
        } else if (chunk_id == "data") {
            if (!valid_format) return false;
            if (chunk_size % sizeof(int16_t) != 0) return false;
            if (chunk_size == 0 || chunk_size > MAX_WAV_PCM_BYTES) return false;
            samples.resize(chunk_size / sizeof(int16_t));
            if (!file.read(reinterpret_cast<char *>(samples.data()), chunk_size)) return false;
        }

        file.seekg(next_chunk);
    }

    if (!valid_format || samples.empty()) return false;

    pcm.resize(samples.size());
    std::transform(samples.begin(), samples.end(), pcm.begin(), [](int16_t sample) {
        return static_cast<float>(sample) / 32768.0f;
    });
    return true;
}

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

whisper_context * cached_context(const std::string & model_path) {
    static whisper_context * context = nullptr;
    static ModelFingerprint loaded_model;

    ModelFingerprint requested_model;
    if (!model_fingerprint(model_path, requested_model)) {
        return nullptr;
    }

    if (context != nullptr && same_model(loaded_model, requested_model)) {
        return context;
    }

    if (context != nullptr) {
        whisper_free(context);
        context = nullptr;
        loaded_model = {};
    }

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

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_dev_jorgex_whspr_NativeWhisper_transcribeNative(
    JNIEnv * env,
    jclass,
    jstring audio_path,
    jstring model_path,
    jstring language
) {
    std::lock_guard<std::mutex> lock(transcribe_mutex());

    const std::string audio = jstring_to_string(env, audio_path);
    const std::string model = jstring_to_string(env, model_path);
    const std::string lang = jstring_to_string(env, language);

    if (audio.empty() || model.empty()) {
        return nullptr;
    }

    std::vector<float> pcm;
    if (!read_wav_mono_16k(audio, pcm)) {
        return nullptr;
    }

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
    params.single_segment = true;
    params.translate = false;

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
    const int segments = whisper_full_n_segments(context);
    for (int i = 0; i < segments; ++i) {
        const char * segment = whisper_full_get_segment_text(context, i);
        if (segment != nullptr) text += segment;
    }

    return env->NewStringUTF(text.c_str());
}
