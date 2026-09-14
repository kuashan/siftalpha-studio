#!/usr/bin/env python3
"""Validate that every launcher entry point resolves to the approved raster artwork."""

from __future__ import annotations

from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app"
ARTWORK = APP / "src" / "main" / "res" / "drawable-nodpi" / "siftalpha_launcher_art.jpg"
ARTWORK_REF = "@drawable/siftalpha_launcher_art"

MANIFEST = APP / "src" / "main" / "AndroidManifest.xml"
LAUNCHER_RESOURCES = [
    APP / "src" / "main" / "res" / "drawable" / "ic_launcher_foreground.xml",
    APP / "src" / "main" / "res" / "mipmap-anydpi" / "siftalpha_launcher.xml",
    APP / "src" / "main" / "res" / "mipmap-anydpi" / "siftalpha_launcher_round.xml",
    APP / "src" / "main" / "res" / "mipmap-anydpi-v26" / "siftalpha_launcher.xml",
    APP / "src" / "main" / "res" / "mipmap-anydpi-v26" / "siftalpha_launcher_round.xml",
    APP / "src" / "main" / "res" / "mipmap-anydpi-v26" / "ic_launcher.xml",
    APP / "src" / "main" / "res" / "mipmap-anydpi-v26" / "ic_launcher_round.xml",
]


def main() -> int:
    failures: list[str] = []

    if not ARTWORK.is_file():
        failures.append(f"missing artwork: {ARTWORK.relative_to(ROOT)}")
    else:
        data = ARTWORK.read_bytes()
        if not data.startswith(b"\xff\xd8\xff"):
            failures.append("approved launcher artwork is not a JPEG")
        if len(data) < 1024:
            failures.append("approved launcher artwork is unexpectedly small")

    if MANIFEST.is_file():
        manifest = MANIFEST.read_text(encoding="utf-8")
        if 'android:icon="@mipmap/siftalpha_launcher"' not in manifest:
            failures.append("manifest does not use siftalpha_launcher")
        if 'android:roundIcon="@mipmap/siftalpha_launcher_round"' not in manifest:
            failures.append("manifest does not use siftalpha_launcher_round")
    else:
        failures.append(f"missing manifest: {MANIFEST.relative_to(ROOT)}")

    for path in LAUNCHER_RESOURCES:
        if not path.is_file():
            failures.append(f"missing launcher resource: {path.relative_to(ROOT)}")
            continue
        content = path.read_text(encoding="utf-8")
        if ARTWORK_REF not in content:
            failures.append(f"{path.relative_to(ROOT)} does not reference {ARTWORK_REF}")
        if "siftalpha_app_icon_v2" in content or "ic_launcher_artwork" in content:
            failures.append(f"{path.relative_to(ROOT)} references a retired icon asset")

    if failures:
        print("Launcher icon validation FAILED")
        for failure in failures:
            print(f"- {failure}")
        return 1

    print(
        "Launcher icon validation PASS: manifest and legacy/API 26+ launcher resources "
        "resolve to the approved artwork"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
