#!/usr/bin/env python3
"""Bump the tracked versionCode; leave the base versionName unchanged."""

import os
from pathlib import Path
import re
import sys
import tempfile

MAX_CODE = 2_100_000_000
CODE_LINE = re.compile(
    r"^(?P<prefix>[ \t]*versionCode[ \t]*(?:=[ \t]*|[ \t]+))"
    r"(?P<code>0|[1-9][0-9]*)(?P<suffix>[ \t]*(?://[^\r\n]*)?\r?)$",
    re.MULTILINE,
)


def bump(path):
    # Keep formatting, comments, permissions, and line endings intact. Refuse
    # expressions/ambiguous declarations rather than editing the wrong code.
    with path.open(newline="") as source:
        text = source.read()
    declarations = re.findall(r"^[ \t]*versionCode\b.*$", text, re.MULTILINE)
    matches = list(CODE_LINE.finditer(text))
    if len(declarations) != 1 or len(matches) != 1:
        raise ValueError("Expected exactly one literal versionCode declaration in app/build.gradle")
    match = matches[0]
    old = int(match["code"])
    if not 1 <= old < MAX_CODE:
        raise ValueError(f"versionCode must be between 1 and {MAX_CODE - 1} before bumping")
    new = old + 1
    updated = text[:match.start("code")] + str(new) + text[match.end("code"):]
    temp = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", newline="", dir=path.parent,
                                         prefix=".version-code-", delete=False) as output:
            temp = Path(output.name)
            os.chmod(temp, path.stat().st_mode & 0o777)
            output.write(updated)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temp, path)
    finally:
        if temp is not None and temp.exists():
            temp.unlink()
    return old, new


if __name__ == "__main__":
    try:
        path = Path(__file__).resolve().parents[1] / "app/build.gradle"
        old, new = bump(path)
        print(f"{path}: versionCode {old} -> {new}; base versionName unchanged")
    except (OSError, ValueError) as error:
        print(f"bump-version: {error}", file=sys.stderr)
        sys.exit(1)
