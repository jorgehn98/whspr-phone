#!/usr/bin/env python3
from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

from common import REQUIRED_NDK, REQUIRED_SDK, ROOT, find_sdk


def main() -> int:
    print("Whspr Android build environment\n")
    missing = 0
    java = Path(os.environ["JAVA_HOME"]) / "bin/java" if os.environ.get("JAVA_HOME") else None
    if not java or not java.is_file():
        found = shutil.which("java")
        java = Path(found) if found else None
    if java:
        print(f"OK   java -> {java}")
        result = subprocess.run([str(java), "-version"], text=True, capture_output=True)
        match = re.search(r'version\s+"(?:1\.)?([0-9]+)', result.stderr + result.stdout)
        major = int(match.group(1)) if match else 0
        if major >= 17:
            print(f"OK   Java version -> {major}")
        else:
            print("MISS Java 17+")
            missing += 1
    else:
        print("MISS java")
        missing += 1
    if (ROOT / "gradlew").is_file() and os.access(ROOT / "gradlew", os.X_OK):
        print("OK   gradlew")
    else:
        print("MISS gradlew ejecutable")
        missing += 1

    print(f"\nANDROID_HOME={os.environ.get('ANDROID_HOME', '')}")
    print(f"ANDROID_SDK_ROOT={os.environ.get('ANDROID_SDK_ROOT', '')}\n")
    sdk = find_sdk()
    if not sdk:
        print("MISS Android SDK")
        print("Hint: configura ANDROID_HOME o crea local.properties con sdk.dir=/home/tu_usuario/Android/Sdk")
        missing += 1
    else:
        checks = [
            (sdk / f"platforms/android-{REQUIRED_SDK}", f"Android SDK platform {REQUIRED_SDK}"),
            (sdk / f"ndk/{REQUIRED_NDK}", f"NDK {REQUIRED_NDK}"),
            (sdk / "cmake", "Android SDK CMake"),
            (sdk / "platform-tools/adb", "adb"),
        ]
        build_tools = sdk / "build-tools"
        has_build_tools = build_tools.is_dir() and any(path.is_dir() and path.name.startswith(f"{REQUIRED_SDK}.") for path in build_tools.iterdir())
        for path, label in checks:
            if path.exists():
                print(f"OK   {label} -> {path}")
            else:
                print(f"MISS {label}")
                missing += 1
        if has_build_tools:
            print(f"OK   Android SDK Build Tools {REQUIRED_SDK}.x -> {build_tools}")
        else:
            print(f"MISS Android SDK Build Tools {REQUIRED_SDK}.x")
            missing += 1
    if missing:
        print(f"\nFaltan {missing} requisitos. Instálalos con Android Studio/SDK Manager y vuelve a ejecutar este script.")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
