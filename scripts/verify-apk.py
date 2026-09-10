#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
import sys
import zipfile
from pathlib import Path

from common import ROOT, require_sdk, run


def assert_patterns(body: str, label: str, patterns: list[str]) -> None:
    for pattern in patterns:
        if not re.search(pattern, body, re.MULTILINE):
            raise RuntimeError(f"MISS {label} -> {pattern}")


def find_aapt2() -> Path:
    candidates = sorted((require_sdk() / "build-tools").glob("*/aapt2"), reverse=True)
    if not candidates:
        raise RuntimeError("aapt2 no encontrado en build-tools.")
    return candidates[0]


def dump(aapt2: Path, apk: Path, *args: str) -> str:
    return run([str(aapt2), "dump", *args, str(apk)], capture=True).stdout


def compiled_xml(resources: str, name: str) -> str:
    pattern = rf"resource\s+0x[0-9a-fA-F]+\s+xml/{re.escape(name)}\s*\n\s*\(\)\s*\(file\)\s*(res/[^ ]+\.xml)\s*type=XML"
    match = re.search(pattern, resources)
    if not match:
        raise RuntimeError(f"MISS APK resource -> xml/{name}")
    return match.group(1)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, default=ROOT / "app/build/outputs/apk/release/app-release.apk")
    args = parser.parse_args(argv)
    apk = args.apk.resolve()
    try:
        if not apk.is_file():
            raise RuntimeError(f"APK no encontrado en {apk}")
        aapt2 = find_aapt2()
        badging = dump(aapt2, apk, "badging")
        for expected in ["package: name='dev.jorgex.whspr'", "uses-permission: name='android.permission.INTERNET'", "uses-permission: name='android.permission.RECORD_AUDIO'", "application-label:'Whspr'", "native-code: 'arm64-v8a'"]:
            if expected not in badging:
                raise RuntimeError(f"MISS APK badging -> {expected}")
        manifest = dump(aapt2, apk, "xmltree", "--file", "AndroidManifest.xml")
        assert_patterns(manifest, "APK manifest", [r"minSdkVersion.*=28", r"targetSdkVersion.*=36", r"allowBackup.*=false", r"fullBackupContent.*=false", r"extractNativeLibs.*=false", r"dataExtractionRules", r'"dev\.jorgex\.whspr\.WhsprInputMethodService"', r'"android\.permission\.BIND_INPUT_METHOD"', r'"android\.view\.InputMethod"', r'"android\.view\.im"', r'"dev\.jorgex\.whspr\.WhsprRecognitionService"', r'"android\.speech\.RecognitionService"', r'"android\.speech"'])
        if "debuggable(0x0101000f)=true" in manifest:
            raise RuntimeError("MISS APK manifest -> release APK must not be debuggable")
        resources = dump(aapt2, apk, "resources")
        assert_patterns(resources, "APK resources", [r"string/recognition_service_name", r'"Whspr dictado"'])
        input_method = dump(aapt2, apk, "xmltree", "--file", compiled_xml(resources, "input_method"))
        assert_patterns(input_method, "APK input method XML", [r"E: input-method", r'settingsActivity.*"dev\.jorgex\.whspr\.MainActivity"', r"supportsSwitchingToNextInputMethod.*=true", r"E: subtype", r'imeSubtypeLocale.*"es_ES"', r'imeSubtypeMode.*"keyboard"', r'languageTag.*"es-ES"'])
        recognition = dump(aapt2, apk, "xmltree", "--file", compiled_xml(resources, "recognition_service"))
        assert_patterns(recognition, "APK recognition service XML", [r"E: recognition-service", r'settingsActivity.*"dev\.jorgex\.whspr\.MainActivity"', r"selectableAsDefault.*=true"])
        rules = dump(aapt2, apk, "xmltree", "--file", compiled_xml(resources, "data_extraction_rules"))
        assert_patterns(rules, "APK data extraction XML", [r"E: cloud-backup", r"E: device-transfer", r'A: domain="root"', r'A: domain="file"', r'A: domain="database"', r'A: domain="sharedpref"', r'A: domain="external"'])
        with zipfile.ZipFile(apk) as archive:
            entries = archive.namelist()
        bad_entries = [entry for entry in entries if entry.startswith("assets/") or (entry.startswith("lib/") and not entry.startswith("lib/arm64-v8a/")) or re.search(r"(^|/)ggml-.*\.bin$|(^|/)models?/|\.(pt|onnx|tflite)$", entry)]
        if bad_entries:
            raise RuntimeError("MISS APK content -> " + ", ".join(bad_entries))
        if "lib/arm64-v8a/libwhspr.so" not in entries:
            raise RuntimeError("MISS APK native lib -> lib/arm64-v8a/libwhspr.so")
        size = apk.stat().st_size
        if size > 4 * 1024 * 1024:
            raise RuntimeError(f"MISS APK size -> {size} bytes, expected <= 4 MB")
        print("OK   APK package/perms/native ABI")
        print("OK   APK manifest services/metadata/privacy")
        print("OK   APK XML resources")
        print("OK   APK has no embedded models/assets")
        print(f"OK   APK size -> {size / 1024 / 1024:.2f} MB")
        return 0
    except Exception as error:
        print(error)
        return 1


if __name__ == "__main__":
    sys.exit(main())
