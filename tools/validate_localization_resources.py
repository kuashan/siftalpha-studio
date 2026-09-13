#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res"
LOCALE_DIRS = {
    "zh-CN": RES / "values",
    "zh-TW": RES / "values-zh-rTW",
    "en": RES / "values-en",
    "ko": RES / "values-ko",
    "ja": RES / "values-ja",
}
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[a-zA-Z]")


def strings(directory: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for path in sorted(directory.glob("*.xml")):
        root = ET.parse(path).getroot()
        for node in root.findall("string"):
            name = node.attrib.get("name", "").strip()
            if not name:
                raise ValueError(f"{path}: string without name")
            if name in result:
                raise ValueError(f"{directory}: duplicate string {name} (including {path.name})")
            result[name] = "".join(node.itertext())
    return result


def placeholders(value: str) -> tuple[str, ...]:
    return tuple(PLACEHOLDER.findall(value.replace("%%", "")))


def main() -> int:
    parsed = {locale: strings(directory) for locale, directory in LOCALE_DIRS.items()}
    baseline = parsed["zh-CN"]
    failures: list[str] = []

    for locale, values in parsed.items():
        missing = sorted(set(baseline) - set(values))
        extra = sorted(set(values) - set(baseline))
        if missing:
            failures.append(f"{locale}: missing keys: {', '.join(missing)}")
        if extra:
            failures.append(f"{locale}: extra keys: {', '.join(extra)}")
        for key in sorted(set(baseline) & set(values)):
            if placeholders(baseline[key]) != placeholders(values[key]):
                failures.append(
                    f"{locale}: placeholder mismatch for {key}: "
                    f"{placeholders(baseline[key])} != {placeholders(values[key])}"
                )

    locale_config = ET.parse(RES / "xml" / "locales_config.xml").getroot()
    android_name = "{http://schemas.android.com/apk/res/android}name"
    configured = [node.attrib.get(android_name) for node in locale_config.findall("locale")]
    expected = list(LOCALE_DIRS)
    if configured != expected:
        failures.append(f"locale config mismatch: {configured} != {expected}")

    if failures:
        print("Localization validation FAILED")
        for failure in failures:
            print(f"- {failure}")
        return 1

    modules = sorted(path.name for path in LOCALE_DIRS["zh-CN"].glob("*.xml"))
    print(
        "Localization validation PASS: "
        f"{len(baseline)} keys x {len(LOCALE_DIRS)} locales across {len(modules)} resource modules"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
