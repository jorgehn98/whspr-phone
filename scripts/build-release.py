#!/usr/bin/env python3
import subprocess
import sys

from common import ROOT, run


try:
    run(["scripts/check-static.py"], cwd=ROOT)
    print()
    run(["scripts/check-android-env.py"], cwd=ROOT)
    print("\nBuilding Whspr release APK...")
    run(["./gradlew", ":app:assembleRelease"], cwd=ROOT)
    print()
    run(["scripts/verify-apk.py"], cwd=ROOT)
except subprocess.CalledProcessError as error:
    sys.exit(error.returncode)
