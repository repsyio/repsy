#!/usr/bin/env python3
"""Replace legacy Repsy hex colors with OriginHub theme-aware Tailwind classes."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GLOBS = ("**/*.html", "**/*.css", "**/*.ts")

# Order matters: longer / more specific patterns first where overlap exists.
REPLACEMENTS: list[tuple[str, str]] = [
    # Tailwind arbitrary colors → DaisyUI theme utilities
    ("from-[rgba(17,16,16,0.6)] to-[#1D1C1C]", "from-base-200/60 to-base-200"),
    ("from-[#333333] to-[#111010]", "from-base-300 to-base-200"),
    ("bg-[rgba(17,16,16,0.6)]", "bg-base-200/60"),
    ("focus-within:border-[#FDFDFD]", "focus-within:border-primary"),
    ("group-hover:ring-[#9FA0A0]", "group-hover:ring-base-content/70"),
    ("[class.bg-[#404141]]", "[class.bg-base-300]"),
    ("border-[#404141]", "border-base-300"),
    ("border-[#101112]", "border-base-100"),
    ("bg-[#101112]", "bg-base-100"),
    ("bg-[#333333]", "bg-base-300"),
    ("hover:bg-[#404141]", "hover:bg-base-300"),
    ("hover:bg-[#2D2C2C]", "hover:bg-base-300"),
    ("hover:border-[#404141]", "hover:border-base-300"),
    ("routerLinkActive=\"bg-[#333333]\"", "routerLinkActive=\"bg-base-300\""),
    ("'bg-[#333333]'", "'bg-base-300'"),
    ("text-[#FDFDFD]", "text-base-content"),
    ("text-[#fdfdfd]", "text-base-content"),
    ("text-[#9FA0A0]", "text-base-content/70"),
    ("text-[#9C9C9D]", "text-base-content/65"),
    ("placeholder:text-[#9FA0A0]", "placeholder:text-base-content/70"),
    ("hover:border-white", "hover:border-primary"),
    # Plain text-white on dark surfaces → base-content (not primary-content buttons)
    ("text-white hover:", "text-base-content hover:"),
    ("text-white outline-none", "text-base-content outline-none"),
    ("text-[14px] text-white", "text-[14px] text-base-content"),
    ("text-sm break-all text-white", "text-sm break-all text-base-content"),
    ("leading-6 text-white", "leading-6 text-base-content"),
    ("px-4 py-2 text-[14px] text-white", "px-4 py-2 text-[14px] text-base-content"),
    ("group-hover:text-white", "group-hover:text-base-content"),
    ("hover:bg-[#1D1C1C]", "hover:bg-base-200"),
    ("text-[#FF6B6B]", "text-error"),
    ("bg-[#404141]", "bg-base-300"),
    ("hover:border-[#FDFDFD]", "hover:border-primary"),
    ("from-[#111010] to-[#333333]", "from-base-200 to-base-300"),
    ("hover:bg-[#2A2B2B]", "hover:bg-base-300"),
    ("border-[#2A2A2A]", "border-base-300"),
    ("bg-[#0C0C0C]", "bg-base-200"),
    ("bg-[#14161A]", "bg-base-200"),
    ('stroke="#FDFDFD"', 'stroke="currentColor"'),
    ('stroke="#9FA0A0"', 'stroke="currentColor"'),
    ('fill="#9FA0A0"', 'fill="currentColor"'),
    ("focus:border-[#FDFDFD]", "focus:border-primary"),
    ("border-b-[#404141]", "border-b-base-300"),
    ("text-[#00DC82]", "text-success"),
    ('fill="#E8546A"', 'fill="currentColor"'),
    ("border border-[#3A3A3A] bg-[#151617]", "border border-base-300 bg-base-200"),
    ("text-[#C9C9C9]", "text-base-content/80"),
    ("text-[#8A8A8A]", "text-base-content/60"),
    ("bg-[#080808]", "bg-base-200"),
    ("border-[#1A1A1A]", "border-base-300"),
    ("ring-[#18191A]/60", "ring-base-200/60"),
    ("from-[#FFF0F8] to-[#FF69B4]", "from-primary/20 to-primary"),
    ("text-[#07080A]", "text-primary-content"),
    ("border: 3px solid #9fa0a0;", "border: 3px solid var(--color-base-300);"),
    ("border-top-color: #ff69b4;", "border-top-color: var(--color-primary);"),
    # TS / inline SVG
    ("text-[#9FA0A0] hover:text-secondary-500", "text-base-content/70 hover:text-primary"),
    ('stroke="#69FFB4"', 'stroke="currentColor" class="text-primary"'),
    ("#69FFB4", "currentColor"),
    ("#69ffb4", "var(--color-primary)"),
    ("#e8546a", "var(--color-error)"),
    ("#ffffff", "var(--color-base-content)"),
    ("#FFFFFF", "var(--color-base-content)"),
    ("rgba(10, 10, 10, 0)", "transparent"),
    ("rgba(232, 84, 106, 0.1)", "color-mix(in oklch, var(--color-error) 10%, transparent)"),
]

CSS_ONLY: list[tuple[str, str]] = [
    ("background-color: #000000;", "background-color: var(--color-base-200);"),
    ("border: 4px solid #ff69b4;", "border: 4px solid var(--color-primary);"),
    (
        "background-color: #101d13;\n    border-color: #206a35;\n    color: #1eac52;",
        "background-color: color-mix(in oklch, var(--color-success) 12%, var(--color-base-100));\n"
        "    border-color: color-mix(in oklch, var(--color-success) 45%, var(--color-base-300));\n"
        "    color: var(--color-success);",
    ),
    ("stroke: #206a35;", "stroke: var(--color-success);"),
    (
        "background-color: #211011;\n    border-color: #952e2e;\n    color: #db3f3f;",
        "background-color: color-mix(in oklch, var(--color-error) 12%, var(--color-base-100));\n"
        "    border-color: color-mix(in oklch, var(--color-error) 45%, var(--color-base-300));\n"
        "    color: var(--color-error);",
    ),
    ("fill: #952e2e;\n    stroke: #952e2e;", "fill: var(--color-error);\n    stroke: var(--color-error);"),
    ("background-color: #7c3aed;", "background-color: color-mix(in oklch, var(--color-primary) 70%, var(--color-base-300));"),
    ("background-color: #3b82f6;", "background-color: color-mix(in oklch, var(--color-info) 55%, var(--color-base-300));"),
    ("background-color: #6b7280;", "background-color: var(--color-base-300);"),
    ("color: #ffffff;", "color: var(--color-primary-content);"),
    ("color: white;", "color: var(--color-base-content);"),
]


def migrate_file(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    original = text

    for old, new in REPLACEMENTS:
        text = text.replace(old, new)

    if path.suffix == ".css":
        for old, new in CSS_ONLY:
            text = text.replace(old, new)

    # Modal / divider gradient borders in component CSS
    if path.suffix == ".css" and "border-image-source" in text:
        text = re.sub(
            r"border-image-source:\s*linear-gradient\(\s*90deg,\s*transparent\s*0%,\s*var\(--color-(\w+)\)\s*50%,\s*transparent\s*100%\s*\);",
            r"border-image-source: linear-gradient(90deg, transparent 0%, var(--color-\1) 50%, transparent 100%);",
            text,
        )

    if text != original:
        path.write_text(text, encoding="utf-8")
        return True
    return False


def main() -> None:
    changed: list[str] = []
    for pattern in GLOBS:
        for path in ROOT.glob(pattern):
            if "node_modules" in path.parts or "dist" in path.parts or "scripts" in path.parts:
                continue
            if path.name == "migrate-theme-colors.py":
                continue
            if migrate_file(path):
                changed.append(str(path.relative_to(ROOT)))

    print(f"Updated {len(changed)} files")
    for p in sorted(changed):
        print(f"  {p}")


if __name__ == "__main__":
    main()
