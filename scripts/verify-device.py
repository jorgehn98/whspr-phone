#!/usr/bin/env python3
from __future__ import annotations

import argparse
import os
import re
import sys

from common import adb_target, connected_devices, find_adb, run, select_device


PACKAGE = "dev.jorgex.whspr"


def output(command: list[str], *, check: bool = True) -> str:
    return run(command, capture=True, check=check).stdout.strip()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    parser.add_argument("--require-microphone", action="store_true")
    args = parser.parse_args(argv)
    try:
        adb = find_adb()
        serial = select_device(args.serial, connected_devices(adb))
        target = adb_target(adb, serial)
        run([*target, "get-state"], capture=True)
        if serial:
            print(f"OK   Android device -> {serial}")
        api_text = output([*target, "shell", "getprop", "ro.build.version.sdk"])
        abis = output([*target, "shell", "getprop", "ro.product.cpu.abilist"])
        if not abis:
            abis = ",".join(filter(None, [output([*target, "shell", "getprop", "ro.product.cpu.abi"]), output([*target, "shell", "getprop", "ro.product.cpu.abi2"])]))
        try:
            api = int(api_text)
        except ValueError:
            raise RuntimeError("Android API inválida: no he podido leer ro.build.version.sdk")
        if api < 28:
            raise RuntimeError("Android API inválida: Whspr minSdk es 28")
        if "arm64-v8a" not in abis:
            raise RuntimeError("ABI incompatible: Whspr solo admite arm64-v8a")
        print(f"OK   Android API -> {api}")
        print(f"OK   Android ABIs -> {abis}")
        if not output([*target, "shell", "pm", "path", PACKAGE]):
            raise RuntimeError("Whspr no está instalado en el dispositivo.")
        print("OK   package installed")
        mic = output([*target, "shell", "appops", "get", PACKAGE, "RECORD_AUDIO"], check=False)
        if re.search(r"RECORD_AUDIO:\s*(allow|foreground)", mic):
            print("OK   microphone permission granted")
        elif args.require_microphone:
            raise RuntimeError("Permiso de micrófono no concedido: abre Whspr y permítelo")
        else:
            print("WARN microphone permission -> abre Whspr y permite el micrófono")
        package_pattern = re.escape(PACKAGE)
        ime = output([*target, "shell", "ime", "list", "-a"])
        if not re.search(rf"{package_pattern}/(?:\.|{package_pattern}\.)WhsprInputMethodService", ime):
            raise RuntimeError("IME no registrado")
        print("OK   IME registered")
        dump = output([*target, "shell", "pm", "dump", PACKAGE])
        if not re.search(rf"{package_pattern}/(?:\.|{package_pattern}\.)WhsprRecognitionService", dump):
            raise RuntimeError("RecognitionService no registrado")
        print("OK   RecognitionService registered")
        return 0
    except Exception as error:
        print(error)
        return 1


if __name__ == "__main__":
    sys.exit(main())
