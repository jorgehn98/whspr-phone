#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
import sys
import urllib.request
from pathlib import Path

from common import ROOT


def field(body: str, name: str) -> str:
    match = re.search(rf'^\s*{re.escape(name)}\s*=\s*"((?:\\.|[^"])*)"', body, re.MULTILINE)
    if not match:
        raise ValueError(f"No se puede leer '{name}' en ModelCatalog.kt.")
    return match.group(1)


def integer_expression(body: str, name: str) -> int:
    match = re.search(rf"^\s*{re.escape(name)}\s*=\s*([^,]+)", body, re.MULTILINE)
    if not match:
        raise ValueError(f"No se puede leer '{name}' en ModelCatalog.kt.")
    value = 1
    for token in match.group(1).split("*"):
        value *= int(token.strip().removesuffix("L"))
    return value


def remote_size(url: str, timeout: int) -> int:
    last_error: Exception | None = None
    for method, headers in [("HEAD", {}), ("GET", {"Range": "bytes=0-0"})]:
        try:
            request = urllib.request.Request(url, method=method, headers=headers)
            with urllib.request.urlopen(request, timeout=timeout) as response:
                content_range = response.headers.get("Content-Range", "")
                match = re.search(r"/(\d+)$", content_range)
                if match:
                    return int(match.group(1))
                length = response.headers.get("Content-Length")
                if length:
                    return int(length)
        except Exception as error:
            last_error = error
    raise RuntimeError(f"No se puede obtener el tamaño remoto. Último error: {last_error}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", type=Path, default=ROOT / "app/src/main/java/dev/jorgex/whspr/ModelCatalog.kt")
    parser.add_argument("--timeout", type=int, default=20)
    args = parser.parse_args(argv)
    try:
        catalog = args.catalog.read_text(encoding="utf-8")
        models = re.findall(r"SpeechModel\s*\((.*?)\)\s*,", catalog, re.DOTALL)
        if not models:
            raise RuntimeError(f"No hay modelos en {args.catalog}.")
        print(f"Verifying model catalog...\nArchivo: {args.catalog}\n")
        failures = 0
        for body in models:
            model_id, label, minimum, url = field(body, "id"), field(body, "label"), integer_expression(body, "minBytes"), field(body, "url")
            if not url.startswith("https://"):
                print(f"MISS {model_id} -> URL no HTTPS: {url}")
                failures += 1
                continue
            try:
                size = remote_size(url, args.timeout)
                if size < minimum:
                    raise RuntimeError(f"tamaño remoto {size}, mínimo {minimum}")
                print(f"OK   {model_id} -> {label} ({size} bytes)")
            except Exception as error:
                print(f"MISS {model_id} -> {label}\n  URL: {url}\n  Error: {error}")
                failures += 1
        if failures:
            print(f"\nModel catalog has errors: {failures}")
            return 1
        print("\nModel catalog OK")
        return 0
    except Exception as error:
        print(error)
        return 1


if __name__ == "__main__":
    sys.exit(main())
