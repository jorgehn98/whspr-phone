#!/usr/bin/env python3
import argparse
import sys

from install import install


parser = argparse.ArgumentParser()
parser.add_argument("--serial")
sys.exit(install("release", parser.parse_args().serial))
