# Third-party notices

## whisper.cpp

Whspr vendors a trimmed Android `arm64-v8a` CPU-only subset of `whisper.cpp` in `third_party/whisper.cpp`.

- Source: https://github.com/ggerganov/whisper.cpp
- License: MIT
- Local license file: `third_party/whisper.cpp/LICENSE`

## Whisper models

Whspr downloads Whisper GGML models from the `ggerganov/whisper.cpp` Hugging Face repository.

- Source: https://huggingface.co/ggerganov/whisper.cpp
- Catalog: `ggml-tiny-q5_1.bin`, `ggml-base-q5_1.bin`, `ggml-small-q5_1.bin` (see `ModelCatalog.kt`).
- Models are downloaded by the user, verified by SHA-256 and stored in the app's internal files directory.
- Models are not bundled in the APK.

## Lucide icons

Whspr's keyboard special-key vector drawables (`ic_key_shift`, `ic_key_shift_caps`,
`ic_key_backspace`, `ic_key_enter`, `ic_key_globe`, `ic_key_mic` in
`app/src/main/res/drawable/`) are derived from Lucide icon paths, converted to
Android `<vector>` XML and tinted at runtime from `WhsprColors`.

- Source: https://lucide.dev
- License: ISC

## In-app notices

The full license texts of whisper.cpp and Lucide ship inside the APK in
`app/src/main/res/raw/third_party_licenses.txt` and are shown under **Más ajustes →
Licencias de terceros**. Keep that file in sync with this one.
