#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app" / "src" / "main" / "java" / "com" / "siftalpha" / "studio"
BASE_TARGETS = [
    SRC / "MainActivity.kt",
    SRC / "V04Activity.kt",
    SRC / "RuntimeStorageActivity.kt",
    SRC / "ProjectEditorActivity.kt",
    SRC / "V054TerminalActivity.kt",
    SRC / "CrashRecoveryActivity.kt",
    SRC / "SettingsActivity.kt",
]
TARGETS = BASE_TARGETS + sorted((SRC / "ui").rglob("*.kt"))

# Fixed Studio-owned CJK text belongs in Android resources. User/project/runtime output is not
# translated, but it should not require CJK literals in reachable Activity/Compose UI sources.
CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\u3040-\u30ff\uac00-\ud7af]")
STRING = re.compile(r'"(?:\\.|[^"\\])*"')
UI_CALL = re.compile(
    r"(?:text|button|smallButton|shortcutButton|section|emptyHint|hint|toast|setState|setTerminalState)\(\s*(\"(?:\\.|[^\"\\])*\")"
    r"|\.setTitle\(\s*(\"(?:\\.|[^\"\\])*\")"
    r"|\.setMessage\(\s*(\"(?:\\.|[^\"\\])*\")"
    r"|\bhint\s*=\s*(\"(?:\\.|[^\"\\])*\")"
    r"|\bText\(\s*(?:text\s*=\s*)?(\"(?:\\.|[^\"\\])*\")"
    r"|\bcontentDescription\s*=\s*(\"(?:\\.|[^\"\\])*\")"
)
DYNAMIC = re.compile(r"\$\{[^}]+\}|\$[A-Za-z_][A-Za-z0-9_]*")
IMMUTABLE_NAMES = ("SiftAlpha Studio", "GitHub")
LANGUAGE_PICKER_CALL = "StudioLanguage.showPicker("
LANGUAGE_PICKER_OWNER = SRC / "SettingsActivity.kt"


def source_without_line_comment(line: str) -> str:
    # Current targeted files do not use // inside user-facing string literals.
    return line.split("//", 1)[0]


def literal_body(token: str) -> str:
    return token[1:-1]


def is_symbol_or_dynamic_only(value: str) -> bool:
    value = DYNAMIC.sub("", value)
    for name in IMMUTABLE_NAMES:
        value = value.replace(name, "")
    # Direction keys, bullets, folder/file glyphs and punctuation are language-neutral.
    value = re.sub(r"[\s\u2190-\u21ff●📁📂📄▶·:：/\\()（）._+\-?]+", "", value)
    return not any(ch.isalnum() for ch in value)


def main() -> int:
    failures: list[str] = []
    for path in TARGETS:
        if not path.exists():
            failures.append(f"missing target: {path.relative_to(ROOT)}")
            continue

        source = path.read_text(encoding="utf-8")
        picker_count = source.count(LANGUAGE_PICKER_CALL)
        if path == LANGUAGE_PICKER_OWNER:
            if picker_count != 1:
                failures.append(
                    f"{path.relative_to(ROOT)}: expected exactly one in-app language picker in Settings, found {picker_count}"
                )
        elif picker_count != 0:
            failures.append(
                f"{path.relative_to(ROOT)}: in-app language picker is Settings-only, found {picker_count}"
            )

        for lineno, raw in enumerate(source.splitlines(), 1):
            line = source_without_line_comment(raw)
            for match in STRING.finditer(line):
                value = literal_body(match.group(0))
                if CJK.search(value):
                    failures.append(
                        f"{path.relative_to(ROOT)}:{lineno}: hardcoded CJK UI/string literal: {value[:100]}"
                    )
            for match in UI_CALL.finditer(line):
                token = next((group for group in match.groups() if group), None)
                if token is None:
                    continue
                value = literal_body(token)
                if value and not is_symbol_or_dynamic_only(value):
                    failures.append(
                        f"{path.relative_to(ROOT)}:{lineno}: fixed UI literal must use resources: {value[:100]}"
                    )

    if failures:
        print("Localized UI source validation FAILED")
        for failure in failures:
            print(f"- {failure}")
        return 1

    print(
        f"Localized UI source validation PASS: {len(TARGETS)} reachable Activity/Compose surfaces; "
        "Settings-only language picker"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
