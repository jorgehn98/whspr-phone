#!/usr/bin/env python3
from __future__ import annotations

import sys

from common import ROOT, find_sdk


def main() -> int:
    sdk = find_sdk()
    if not sdk:
        print("Android SDK no encontrado. Instala Android Studio o configura ANDROID_HOME.")
        return 1
    escaped = str(sdk.resolve()).replace("\\", r"\\").replace(":", r"\:").replace(" ", r"\ ")
    (ROOT / "local.properties").write_text(f"sdk.dir={escaped}\n", encoding="utf-8")
    print(f"OK   local.properties -> sdk.dir={escaped}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
