#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

from common import ROOT


failed = 0


def text(path: str | Path) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def check(label: str, condition: bool, detail: str | None = None) -> None:
    global failed
    if condition:
        print(f"OK   {label}")
    else:
        print(f"MISS {label}" + (f" -> {detail}" if detail else ""))
        failed += 1


def contains_all(body: str, patterns: list[str]) -> bool:
    return all(re.search(pattern, body, re.MULTILINE | re.DOTALL) for pattern in patterns)


def matches(path: str, pattern: str) -> list[str]:
    return re.findall(pattern, text(path), re.MULTILINE | re.DOTALL)


def main() -> int:
    global failed
    print("Whspr static checks\n")

    kotlin_files = sorted((ROOT / "app/src/main/java").rglob("*.kt"))
    cpp_path = ROOT / "app/src/main/cpp/native_whisper.cpp"
    cpp = cpp_path.read_text(encoding="utf-8")
    braces_ok = True
    for path in kotlin_files:
        body = path.read_text(encoding="utf-8")
        if body.count("{") != body.count("}"):
            print(f"MISS Kotlin braces -> {path}: {body.count('{')} vs {body.count('}')}")
            failed += 1
            braces_ok = False
    if braces_ok:
        print("OK   Kotlin brace balance")
    check("C++ brace balance", cpp.count("{") == cpp.count("}"))

    xml_paths = [ROOT / "app/src/main/AndroidManifest.xml", *(ROOT / "app/src/main/res").rglob("*.xml")]
    xml_ok = True
    for path in xml_paths:
        try:
            ET.parse(path)
        except ET.ParseError as error:
            print(f"MISS XML parse -> {path}: {error}")
            failed += 1
            xml_ok = False
    if xml_ok:
        print("OK   XML parse")

    scripts = {path.name: path.read_text(encoding="utf-8") for path in (ROOT / "scripts").glob("*.py")}
    installer = scripts.get("install.py", "")
    check(
        "adb scripts",
        "--serial" in scripts.get("install-debug.py", "")
        and "--serial" in scripts.get("install-release.py", "")
        and contains_all(installer, [r"connected_devices", r"install", r"RECORD_AUDIO", r"MainActivity", r"verify-device\.py"])
        and contains_all(scripts.get("verify-device.py", ""), [r"--serial", r"get-state", r"ro\.build\.version\.sdk", r"pm.*path", r"ime.*list", r"pm.*dump", r"appops.*RECORD_AUDIO"])
        and "verify-apk.py" in scripts.get("build-release.py", "")
        and contains_all(scripts.get("verify-apk.py", ""), [r"aapt2", r"lib/arm64-v8a/libwhspr\.so", r"input_method", r"recognition_service", r"data_extraction_rules", r"imeSubtypeMode", r"selectableAsDefault", r"WhsprRecognitionService", r"WhsprInputMethodService"]),
        "expected serial targeting, verification and explicit launch",
    )

    strings_xml = text("app/src/main/res/values/strings.xml")
    names = set(re.findall(r'<string\s+name="([A-Za-z0-9_]+)"', strings_xml))
    missing_strings: list[tuple[Path, str]] = []
    for path in (ROOT / "app/src/main").rglob("*"):
        if path.suffix not in {".xml", ".kt"}:
            continue
        for first, second in re.findall(r"@string/([A-Za-z0-9_]+)|R\.string\.([A-Za-z0-9_]+)", path.read_text(encoding="utf-8")):
            name = first or second
            if name not in names:
                missing_strings.append((path, name))
    for path, name in missing_strings:
        print(f"MISS string ref -> {path}: {name}")
    failed += len(missing_strings)
    if not missing_strings:
        print("OK   string refs")

    resources = {"style/AppTheme"}
    for path in (ROOT / "app/src/main/res").rglob("*"):
        if path.is_file():
            resources.add(f"{path.parent.name.split('-')[0]}/{path.stem}")
    missing_resources: list[tuple[Path, str]] = []
    for path in (ROOT / "app/src/main").rglob("*.xml"):
        for kind, name in re.findall(r"@(xml|mipmap|drawable|style)/([A-Za-z0-9_]+)", path.read_text(encoding="utf-8")):
            key = f"{kind}/{name}"
            if key not in resources:
                missing_resources.append((path, key))
    for path, key in missing_resources:
        print(f"MISS resource ref -> {path}: {key}")
    failed += len(missing_resources)
    if not missing_resources:
        print("OK   resource refs")

    manifest = text("app/src/main/AndroidManifest.xml")
    components = re.findall(r'android:name="(\.[A-Za-z0-9_]+|dev\.jorgex\.whspr\.[A-Za-z0-9_]+)"', manifest)
    component_paths = [ROOT / "app/src/main/java/dev/jorgex/whspr" / f"{name.rsplit('.', 1)[-1]}.kt" for name in components]
    check("manifest components", all(path.is_file() for path in component_paths))
    activities = re.findall(r'<activity[\s\S]*?android:name="([^"]+)"', manifest)
    services = re.findall(r'<service[\s\S]*?android:name="([^"]+)"', manifest)
    check("minimal manifest components", set(activities) == {".MainActivity", ".SettingsActivity"} and set(services) == {".WhsprInputMethodService", ".WhsprRecognitionService"})
    permissions = re.findall(r'<uses-permission\s+android:name="([^"]+)"', manifest)
    check("minimal manifest permissions", set(permissions) == {"android.permission.INTERNET", "android.permission.RECORD_AUDIO"})
    check("manifest backup disabled", contains_all(manifest, [r'android:allowBackup="false"', r'android:fullBackupContent="false"', r'android:dataExtractionRules="@xml/data_extraction_rules"']) and (ROOT / "app/src/main/res/xml/data_extraction_rules.xml").is_file())
    check("recognition service permission", "BIND_RECOGNITION_SERVICE" not in manifest)
    check("recognition service wiring", contains_all(manifest, [r'android:name="\.WhsprRecognitionService"', r'android:name="android\.speech"', r'android:resource="@xml/recognition_service"', r'android:name="android\.speech\.RecognitionService"']))

    input_method = text("app/src/main/res/xml/input_method.xml")
    check("input method keyboard subtype", 'android:imeSubtypeMode="keyboard"' in input_method and 'android:isAuxiliary="true"' not in input_method and 'android:languageTag="es-ES"' in input_method)
    recognition = text("app/src/main/java/dev/jorgex/whspr/WhsprRecognitionService.kt")
    recorder = text("app/src/main/java/dev/jorgex/whspr/AudioRecorder.kt")
    ime = text("app/src/main/java/dev/jorgex/whspr/WhsprInputMethodService.kt")
    check("recognition service attribution context", contains_all(recognition, [r"ContextParams\.Builder", r"setNextAttributionSource", r"callingAttributionSource"]))
    check("audio recorder attribution context", contains_all(recorder, [r"AudioRecord\.Builder", r"setContext\(context\)", r"Build\.VERSION_CODES\.S"]))
    check("audio recorder lifecycle guard", contains_all(recorder, [r"@Synchronized\s+fun start\(\): Boolean", r"@Synchronized\s+fun stop\(\): File\?", r"worker = runCatching"]))
    check("audio format contract", contains_all(recorder, [r"private const val SAMPLE_RATE = 16_000", r"private const val CHANNELS = 1", r"private const val BITS_PER_SAMPLE = 16", r"setAudioSource\(MediaRecorder\.AudioSource\.VOICE_RECOGNITION\)"]) and contains_all(cpp, [r"channels == 1", r"sample_rate == 16000", r"bits_per_sample == 16", r"MAX_WAV_PCM_BYTES"]))
    cleanup = r"finally\s*\{\s*runCatching\s*\{\s*audioFile\.delete\(\)\s*\}\s*\}"
    check("audio temp files", 'File.createTempFile("whspr-dictation-"' in recorder and re.search(cleanup, ime, re.DOTALL) is not None and re.search(cleanup, recognition, re.DOTALL) is not None)
    check("recognition service support check", contains_all(recognition, [r"onCheckRecognitionSupport", r"attributionSource: AttributionSource", r"RecognitionSupport\.Builder", r"onSupportResult"]))
    check("recognition service API guards", not re.search(r"@RequiresApi|androidx\.annotation\.RequiresApi|import android\.annotation\.RequiresApi", recognition) and contains_all(recognition, [r"Build\.VERSION\.SDK_INT >= Build\.VERSION_CODES\.S", r"onCheckRecognitionSupport", r"onTriggerModelDownload"]))
    check("recognition service model-download callback", contains_all(recognition, [r"onTriggerModelDownload", r"ModelDownloadListener", r"listener\.onSuccess", r"listener\.onScheduled", r"scheduleModelDownload", r"ERROR_NETWORK"]))

    sync_bad = []
    for path in kotlin_files:
        for block in re.findall(r"synchronized\s*\([^)]*\)\s*\{.*?\}", path.read_text(encoding="utf-8"), re.DOTALL):
            if re.search(r"\breturn\b", block) and "return@" not in block:
                sync_bad.append(path)
    check("no non-local synchronized returns", not sync_bad)

    catalog = text("app/src/main/java/dev/jorgex/whspr/ModelCatalog.kt")
    ids = re.findall(r'id\s*=\s*"([^"]+)"', catalog)
    files = re.findall(r'fileName\s*=\s*"([^"]+)"', catalog)
    hashes = re.findall(r'sha256\s*=\s*"([^"]+)"', catalog)
    urls = re.findall(r'url\s*=\s*"([^"]+)"', catalog)
    check("model catalog", bool(ids) and len(ids) == len(set(ids)) and len(files) == len(set(files)) and len(ids) == len(files) == len(hashes) == len(urls) and all(re.fullmatch(r"[a-f0-9]{64}", value) for value in hashes) and all(value.startswith("https://") for value in urls))

    model_store = text("app/src/main/java/dev/jorgex/whspr/ModelStore.kt")
    native = cpp
    runtime_model = "\n".join(text(path) for path in ["app/src/main/java/dev/jorgex/whspr/MainActivity.kt", "app/src/main/java/dev/jorgex/whspr/WhsprInputMethodService.kt", "app/src/main/java/dev/jorgex/whspr/WhsprRecognitionService.kt"])
    check("model readiness", contains_all(model_store, [r"fun isReady\(model: SpeechModel\): Boolean", r"hasExpectedSha256\(model\)"]) and contains_all(runtime_model, [r"hasExpectedSha256\(model\)", r"transcriber\.transcribe"]))
    transcriber = text("app/src/main/java/dev/jorgex/whspr/LocalTranscriber.kt")
    check("transcription errors", contains_all(transcriber, [r"fun transcribe\(audioFile: File, modelFile: File, language: String\): String\?", r"private external fun transcribeNative\(audioPath: String, modelPath: String, language: String\): String\?", r'System\.loadLibrary\("whspr"\)', r"if \(!available\) return null", r"\?\.trim\(\)"]) and "return nullptr" in native and contains_all(recognition, [r"ERROR_NO_MATCH", r"ERROR_CLIENT"]))
    check("non-verbal tag filter", "stripNonVerbalTags" in ime and "R.string.error_no_match" not in ime and 'name="error_no_match"' not in strings_xml)
    check("model session guard", contains_all(runtime_model, [r"sessionModelId", r"settings\.modelId != sessionModelId"]) and not re.search(r"if \(modelOk && settings\.modelId == sessionModelId\)", runtime_model) and contains_all(ime, [r"if \(session != inputSession\) return@post"]) and contains_all(recognition, [r"session == recognitionSession", r"currentCallback === listener"]))
    check("IME session cleanup", "dictationModelId = null" in ime)
    check("secure input guard", contains_all(ime, [r"isSecureInput = attribute\?\.let \{ isPasswordInput\(it\.inputType\) \} \?: false", r"if \(isSecureInput\) \{\s*showMessage\(R\.string\.error_secure_input\)", r"private fun isPasswordInput\(inputType: Int\): Boolean"]))
    teardown = re.search(r"private fun teardown\(\): ByteArray\?\s*\{(.*?)\n    \}", recorder, re.DOTALL)
    check("audio recorder teardown", bool(teardown and "onLevel = null" in teardown.group(1)))
    wave = text("app/src/main/java/dev/jorgex/whspr/VoiceWaveView.kt")
    set_level = re.search(r"fun setLevel\([^)]*\)\s*\{(.*?)\n    \}", wave, re.DOTALL)
    check("VoiceWaveView setLevel thread discipline", bool(set_level and "invalidate()" not in set_level.group(1)))
    keyboard = text("app/src/main/java/dev/jorgex/whspr/KeyboardLayout.kt")
    es = re.search(r"private fun lettersEs\([^)]*\) = KeyboardLayout\((.*?)\n    \)\n", keyboard, re.DOTALL)
    en = re.search(r"private fun lettersEn\([^)]*\) = KeyboardLayout\((.*?)\n    \)\n", keyboard, re.DOTALL)
    base_rows = re.compile(r'listOf\(((?:"[^"]+",?\s*)+)\)\.map')
    es_keys = ",".join(base_rows.findall(es.group(1))) if es else ""
    en_keys = ",".join(base_rows.findall(en.group(1))) if en else ""
    check("keyboard layout language layers", '"ñ"' in es_keys and '"ñ"' not in en_keys)
    check("recognition service UI decoupling", "onLevel" not in recognition)

    root_build = text("build.gradle.kts")
    app_build = text("app/build.gradle.kts")
    build = root_build + "\n" + app_build
    dependencies_ok = not re.search(r"androidx\.|compose|implementation\s*\(", build)
    check("arm64-only build", 'abiFilters.add("arm64-v8a")' in build)
    cmake = text("app/src/main/cpp/CMakeLists.txt")
    cpu_only = contains_all(cmake, [r'set\(GGML_CPU_ARM_ARCH "armv8-a"', r"set\(GGML_ACCELERATE OFF", r"set\(GGML_OPENMP OFF"])
    check("CPU-only native flags", cpu_only)
    check("minimal dependencies", dependencies_ok and cpu_only and 'abiFilters.add("arm64-v8a")' in build)
    proguard = ROOT / "app/proguard-rules.pro"
    check("signed minified release build", contains_all(app_build, [r"release\s*\{", r'signingConfig = signingConfigs\.getByName\("debug"\)', r"isMinifyEnabled = true", r"isShrinkResources = true", r"proguard-rules\.pro"]) and proguard.is_file() and "NativeWhisper" in proguard.read_text(encoding="utf-8"))
    wrapper = text("gradle/wrapper/gradle-wrapper.properties")
    expected_versions = ['compileSdk = 36', 'targetSdk = 36', 'minSdk = 28', 'ndkVersion = "28.2.13676358"', 'id("com.android.application") version "9.2.0"', 'sourceCompatibility = JavaVersion.VERSION_17', 'targetCompatibility = JavaVersion.VERSION_17', 'gradle-9.4.1-bin.zip', 'distributionSha256Sum=2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb']
    check("pinned build versions", all(value in build + wrapper for value in expected_versions))
    check("AGP 9 built-in Kotlin", "org.jetbrains.kotlin.android" not in build)
    gitignore = text(".gitignore")
    check("gitignore Android outputs", all(value in gitignore for value in [".gradle/", "build/", "app/build/", "app/.cxx/", "local.properties", ".idea/"]))
    direct_network = re.compile(r"OkHttp|Retrofit|HttpURLConnection|java\.net\.URL|java\.net\.Socket|DatagramSocket|Firebase|Analytics|Crashlytics|Telemetry")
    check("no direct network or telemetry clients", all(not direct_network.search(path.read_text(encoding="utf-8")) for path in kotlin_files))
    package = "dev.jorgex.whspr"
    identity_ok = re.search(rf'namespace\s*=\s*"{re.escape(package)}"', app_build) and re.search(rf'applicationId\s*=\s*"{re.escape(package)}"', app_build) and all(f"package {package}" in path.read_text(encoding="utf-8") for path in (ROOT / "app/src/main/java/dev/jorgex/whspr").glob("*.kt")) and "Java_dev_jorgex_whspr_NativeWhisper_transcribeNative" in native
    check("native JNI exception guard", "ExceptionCheck()" in native and "ExceptionClear()" in native)
    check("native model cache", contains_all(native, [r"#include <sys/stat\.h>", r"struct ModelFingerprint", r"st_size", r"st_mtime", r"same_model", r"std::lock_guard<std::mutex> lock\(transcribe_mutex\(\)\);"]))
    check("app identity", bool(identity_ok))

    marker = re.compile(r"\bTODO\b|\bFIXME\b|\bplaceholder\b|\bold package\b|dev\.example", re.IGNORECASE)
    marker_files = [path for base in [ROOT / "app/src", ROOT / "scripts"] for path in base.rglob("*") if path.is_file() and path.suffix != ".pyc"]
    check("no stale markers", all(not marker.search(path.read_text(encoding="utf-8", errors="ignore")) for path in marker_files))
    vendor_pattern = re.compile(r"arch/(x86|powerpc|riscv|s390|wasm|loongarch)|ggml-cpu/(cmake|kleidiai|llamafile|spacemit|amx)|coreml/|openvino/|bindings/javascript|add_subdirectory\((tests|examples)")
    vendor_files = [path for path in (ROOT / "third_party/whisper.cpp").rglob("*") if path.is_file() and (path.name == "CMakeLists.txt" or path.suffix == ".cmake")]
    check("no stale vendor CMake refs", all(not vendor_pattern.search(path.read_text(encoding="utf-8", errors="ignore")) for path in vendor_files))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
