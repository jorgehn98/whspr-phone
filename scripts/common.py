from __future__ import annotations

import os
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
REQUIRED_NDK = "28.2.13676358"
REQUIRED_SDK = "36"


def run(command: list[str], *, cwd: Path = ROOT, capture: bool = False, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, cwd=cwd, text=True, capture_output=capture, check=check)


def read_local_sdk(root: Path = ROOT) -> Path | None:
    properties = root / "local.properties"
    if not properties.is_file():
        return None
    for line in properties.read_text(encoding="utf-8").splitlines():
        if line.startswith("sdk.dir="):
            value = line.removeprefix("sdk.dir=").replace(r"\ ", " ").replace(r"\:", ":").replace("\\\\", "\\")
            return Path(value)
    return None


def find_sdk(root: Path = ROOT) -> Path | None:
    configured = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if configured:
        return Path(configured).expanduser()
    local = read_local_sdk(root)
    if local:
        return local
    default = Path.home() / "Android" / "Sdk"
    return default if default.is_dir() else None


def require_sdk(root: Path = ROOT) -> Path:
    sdk = find_sdk(root)
    if not sdk:
        raise RuntimeError("Android SDK no encontrado. Configura ANDROID_HOME o ejecuta scripts/write-local-properties.py.")
    return sdk


def find_adb(root: Path = ROOT) -> Path:
    adb = require_sdk(root) / "platform-tools" / "adb"
    if not adb.is_file():
        raise RuntimeError(f"adb no encontrado en {adb}")
    return adb


def connected_devices(adb: Path) -> list[str]:
    result = run([str(adb), "devices"], capture=True)
    return [line.split()[0] for line in result.stdout.splitlines()[1:] if line.rstrip().endswith("\tdevice")]


def select_device(serial: str | None, devices: list[str]) -> str | None:
    if serial:
        return serial
    if len(devices) == 1:
        return devices[0]
    if len(devices) > 1:
        raise RuntimeError("Hay varios Android conectados. Usa --serial o ANDROID_SERIAL.")
    return None


def adb_target(adb: Path, serial: str | None) -> list[str]:
    return [str(adb), *(["-s", serial] if serial else [])]
