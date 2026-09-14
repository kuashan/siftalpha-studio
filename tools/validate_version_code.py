#!/usr/bin/env python3
"""Require a PR candidate to increase Android versionCode over its base branch."""

from __future__ import annotations

import argparse
import re
import subprocess
from pathlib import Path


VERSION_CODE = re.compile(r"^\s*versionCode\s*=\s*(\d+)\s*$", re.MULTILINE)
BUILD_FILE = "app/build.gradle.kts"


def read_version_code(text: str, source: str) -> int:
    matches = VERSION_CODE.findall(text)
    if len(matches) != 1:
        raise SystemExit(f"Expected exactly one versionCode in {source}, found {len(matches)}")
    return int(matches[0])


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-ref", required=True, help="Git ref containing the base build file")
    args = parser.parse_args()

    current = read_version_code(Path(BUILD_FILE).read_text(encoding="utf-8"), "working tree")
    try:
        base_text = subprocess.check_output(
            ["git", "show", f"{args.base_ref}:{BUILD_FILE}"],
            text=True,
        )
    except subprocess.CalledProcessError as error:
        raise SystemExit(f"Could not read {BUILD_FILE} from {args.base_ref}") from error

    base = read_version_code(base_text, args.base_ref)
    if current <= base:
        raise SystemExit(
            f"versionCode must increase over {args.base_ref}: current={current}, base={base}"
        )

    print(f"versionCode upgrade check passed: {base} -> {current}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
