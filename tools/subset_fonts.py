#!/usr/bin/env python
"""Subset MiSans to the character set the app can actually display.

Why subset: the three full MiSans weights are ~22 MB of the APK, and the app only
ever renders UI strings (a few hundred distinct characters) plus whatever the user
pastes into the pairing field. Shipping the whole CJK range for that is wasteful.

What is kept:
  * printable ASCII
  * the app's own UI strings (read from the Kotlin/XML sources, so a new string
    can never silently render as tofu)
  * GB2312 level 1+2 (6763 hanzi) — covers essentially all common Chinese text,
    so a pasted URL/note or a future string still renders
  * common CJK punctuation and fullwidth forms

Run from the repo root:  python tools/subset_fonts.py
"""
from __future__ import annotations

import pathlib
import sys

from fontTools import subset
from fontTools.ttLib import TTFont

ROOT = pathlib.Path(__file__).resolve().parent.parent
FONT_DIR = ROOT / "app" / "src" / "main" / "res" / "font"
SRC_DIR = ROOT / "app" / "src" / "main"


def app_characters() -> set[str]:
    """Every character appearing in the app's own UI source files."""
    chars: set[str] = set()
    for pattern in ("**/*.kt", "**/*.xml"):
        for path in SRC_DIR.glob(pattern):
            if "font" in path.parts:
                continue
            try:
                chars.update(path.read_text(encoding="utf-8"))
            except (UnicodeDecodeError, OSError):
                continue
    return chars


def gb2312_characters() -> set[str]:
    """All GB2312 level 1 and 2 hanzi, by decoding the two byte ranges."""
    chars: set[str] = set()
    for hi in range(0xB0, 0xF8):
        for lo in range(0xA1, 0xFF):
            try:
                chars.add(bytes([hi, lo]).decode("gb2312"))
            except UnicodeDecodeError:
                continue
    # Symbol/fullwidth rows.
    for hi in range(0xA1, 0xAA):
        for lo in range(0xA1, 0xFF):
            try:
                chars.add(bytes([hi, lo]).decode("gb2312"))
            except UnicodeDecodeError:
                continue
    return chars


def base_characters() -> set[str]:
    chars = {chr(c) for c in range(0x20, 0x7F)}
    chars |= set("　、。〃々〈〉《》「」『』【】〔〕〖〗〝〞！＂＃＄％＆＇（）＊＋，－．／：；＜＝＞？＠［＼］＾＿｀｛｜｝～")
    chars |= set("·—…‘’“”※←→↑↓■□●○◆◇★☆")
    return chars


def main() -> int:
    if not FONT_DIR.is_dir():
        print(f"font dir not found: {FONT_DIR}", file=sys.stderr)
        return 1

    keep = base_characters() | gb2312_characters() | app_characters()
    # Drop control characters that would break the subsetter's text input.
    keep = {c for c in keep if c.isprintable() or c == " "}
    text = "".join(sorted(keep))
    print(f"keeping {len(keep)} distinct characters")

    for ttf in sorted(FONT_DIR.glob("*.ttf")):
        before = ttf.stat().st_size
        options = subset.Options()
        options.layout_features = ["*"]
        options.name_IDs = ["*"]
        options.name_legacy = True
        options.notdef_outline = True
        options.recalc_bounds = True
        options.drop_tables += ["DSIG"]
        # Keep hinting: HyperOS renders small UI text with it.
        options.hinting = True

        font = TTFont(str(ttf))
        subsetter = subset.Subsetter(options=options)
        subsetter.populate(text=text)
        subsetter.subset(font)
        font.save(str(ttf))
        font.close()

        after = ttf.stat().st_size
        print(f"{ttf.name}: {before/1e6:.2f} MB -> {after/1e6:.2f} MB")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
