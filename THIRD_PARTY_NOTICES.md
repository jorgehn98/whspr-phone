# Third-party notices

## whisper.cpp

Whspr vendors a trimmed Android `arm64-v8a` CPU-only subset of `whisper.cpp` in `third_party/whisper.cpp`.

- Source: https://github.com/ggerganov/whisper.cpp
- License: MIT
- Local license file: `third_party/whisper.cpp/LICENSE`

## Whisper models

Whspr downloads Whisper GGML models from the `ggerganov/whisper.cpp` Hugging Face repository.

- Source: https://huggingface.co/ggerganov/whisper.cpp
- Models are downloaded by the user into the app's private external files directory.
- Models are not bundled in the APK.
