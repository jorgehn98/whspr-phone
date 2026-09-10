from __future__ import annotations

import os
import sys
from pathlib import Path

from common import ROOT, adb_target, connected_devices, find_adb, run, select_device


PACKAGE = "dev.jorgex.whspr"


def install(variant: str, serial: str | None) -> int:
    try:
        run([f"scripts/build-{variant}.py"], cwd=ROOT)
        adb = find_adb()
        serial = select_device(serial or os.environ.get("ANDROID_SERIAL"), connected_devices(adb))
        target = adb_target(adb, serial)
        run([*target, "get-state"], capture=True)
        if serial:
            print(f"OK   Android device -> {serial}")
        apk = ROOT / f"app/build/outputs/apk/{variant}/app-{variant}.apk"
        if not apk.is_file():
            raise RuntimeError(f"APK no encontrado en {apk}")
        print(f"\nInstalling Whspr {variant} APK...")
        run([*target, "install", "-r", str(apk)])
        print("\nGranting microphone permission...")
        grant = run([*target, "shell", "pm", "grant", PACKAGE, "android.permission.RECORD_AUDIO"], check=False)
        microphone = grant.returncode == 0
        if microphone:
            print("OK   microphone permission granted")
        else:
            print("No he podido conceder el micrófono por adb. Se podrá permitir manualmente al abrir Whspr.")
        verify = ["scripts/verify-device.py"]
        if serial:
            verify.extend(["--serial", serial])
        if microphone:
            verify.append("--require-microphone")
        run(verify, cwd=ROOT)
        print("\nOpening Whspr...")
        launch = run([*target, "shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity"], capture=True)
        print(launch.stdout, end="")
        if any(marker in launch.stdout for marker in ["Error", "Exception", "Status: timeout"]):
            return 1
        return 0
    except Exception as error:
        print(error)
        return 1
