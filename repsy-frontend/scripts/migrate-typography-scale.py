#!/usr/bin/env python3
"""Dial back oversized typography: 16px root, tiered text-sm for secondary UI."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GLOBS = ("**/*.html",)

# Longer / more specific patterns first.
REPLACEMENTS: list[tuple[str, str]] = [
    # Dropdown / menu items
    (
        "px-4 py-2 text-base text-base-content hover:bg-base-300",
        "px-4 py-2 text-sm text-base-content hover:bg-base-300",
    ),
    (
        "px-4 py-2 text-base text-error hover:bg-base-300",
        "px-4 py-2 text-sm text-error hover:bg-base-300",
    ),
    (
        "flex w-full gap-2 px-4 py-2 text-base text-error hover:bg-base-300",
        "flex w-full gap-2 px-4 py-2 text-sm text-error hover:bg-base-300",
    ),
    (
        "rounded-t-lg px-4 py-2 text-base text-base-content hover:bg-base-300",
        "rounded-t-lg px-4 py-2 text-sm text-base-content hover:bg-base-300",
    ),
    # Sort / selector triggers (not nav)
    (
        "px-4 text-base text-base-content hover:border-primary",
        "px-4 text-sm text-base-content hover:border-primary",
    ),
    # Sidebar section label
    (
        "text-base font-medium text-base-content/70 uppercase",
        "text-sm font-medium text-base-content/70 uppercase",
    ),
    # Pagination
    (
        "p-[6px] text-base font-medium hover:bg-neutral-300",
        "p-[6px] text-sm font-medium hover:bg-neutral-300",
    ),
    (
        "p-[6px] text-base font-medium disabled:opacity-50",
        "p-[6px] text-sm font-medium disabled:opacity-50",
    ),
    (
        "p-[6px] text-base font-medium select-none",
        "p-[6px] text-sm font-medium select-none",
    ),
    # Footer
    ("text-center text-base font-bold text-neutral-200", "text-center text-sm font-bold text-neutral-200"),
    # Breadcrumb
    ('nav class="mb-4 text-base"', 'nav class="mb-4 text-sm"'),
    # Page subtitles (profile, user mgmt)
    ('<h2 class="text-base text-base-content/70">', '<h2 class="text-sm text-base-content/70">'),
    ('<h2 class="text-lg font-medium text-base-content/70">', '<h2 class="text-sm font-medium text-base-content/70">'),
    # Recent activity
    ('<h1 class="mb-6 text-base text-base-content">', '<h1 class="mb-6 text-lg font-medium text-base-content">'),
    (
        "py-4 text-base hover:bg-base-200 lg:grid-cols-[2fr_1fr_1fr]",
        "py-4 text-sm hover:bg-base-200 lg:grid-cols-[2fr_1fr_1fr]",
    ),
    ("text-base text-base-content/70 lg:hidden", "text-sm text-base-content/70 lg:hidden"),
    # Table / list meta labels
    ("text-base text-base-content/70", "text-sm text-base-content/70"),
    ("mb-4 text-base font-medium text-base-content/70", "mb-4 text-sm font-medium text-base-content/70"),
    ("mb-4 text-base text-neutral-200", "mb-4 text-sm text-neutral-200"),
    ("text-start text-base font-medium break-all text-neutral-200", "text-start text-sm font-medium break-all text-neutral-200"),
    # Table row primary cells (data density)
    ("flex gap-2 text-base text-base-content", "flex gap-2 text-sm text-base-content"),
    ('class="text-base text-base-content"', 'class="text-sm text-base-content"'),
    ('class="text-right text-base break-all text-base-content"', 'class="text-right text-sm break-all text-base-content"'),
    ('class="text-right text-base text-base-content"', 'class="text-right text-sm text-base-content"'),
    ("group text-secondary-500 flex w-max gap-2 text-base", "group text-secondary-500 flex w-max gap-2 text-sm"),
    ("group flex w-max gap-2 text-base text-base-content", "group flex w-max gap-2 text-sm text-base-content"),
    ("group flex min-w-0 gap-2 text-base text-base-content", "group flex min-w-0 gap-2 text-sm text-base-content"),
    ("group flex items-start gap-2 text-base", "group flex items-start gap-2 text-sm"),
    ("group text-secondary-500 flex gap-2 text-base", "group text-secondary-500 flex gap-2 text-sm"),
    (
        "inline-flex gap-2 rounded-[14px] bg-linear-to-b from-base-300 to-base-200 px-[16px] py-[6px] text-base text-base-content",
        "inline-flex gap-2 rounded-[14px] bg-linear-to-b from-base-300 to-base-200 px-[16px] py-[6px] text-sm text-base-content",
    ),
    # Welcome card
    ('<span class="text-base leading-normal text-base-content/65">Welcome,', '<span class="text-sm leading-normal text-base-content/65">Welcome,'),
    ('<p class="mt-4 text-base leading-normal text-base-content/65">', '<p class="mt-4 text-sm leading-normal text-base-content/65">'),
    (
        'class="font-sans mt-6 text-[32px] leading-[40px] text-base-content"',
        'class="font-sans mt-6 text-2xl leading-tight text-base-content"',
    ),
    # Dashboard stats — modest display scale at 16px root
    ('class="mt-2 text-[32px] leading-none font-bold text-base-content"', 'class="mt-2 text-2xl leading-none font-bold text-base-content"'),
    ('class="mt-1 text-center text-[24px] font-semibold text-base-content"', 'class="mt-1 text-center text-xl font-semibold text-base-content"'),
    # Modal / page headings
    ('class="text-[24px] leading-6 text-base-content"', 'class="text-xl leading-6 text-base-content"'),
    ('class="text-[28px] text-base-content/70"', 'class="text-2xl text-base-content/70"'),
    ('class="mt-2 text-[20px] text-base-content/70"', 'class="mt-2 text-lg text-base-content/70"'),
    # Form field labels
    ('class="mb-1 block text-[16px] text-base-content/70"', 'class="mb-1 block text-sm text-base-content/70"'),
    ('class="mb-2 text-[16px] text-base-content/70"', 'class="mb-2 text-sm text-base-content/70"'),
    # Detail stat values
    ('class="mt-1 text-[18px] text-base-content"', 'class="mt-1 text-base font-medium text-base-content"'),
    # Repo settings section body copy stays readable; dial secondary blocks
    ('class="mb-8 flex flex-col gap-2 text-base"', 'class="mb-8 flex flex-col gap-2 text-sm"'),
    # Repository page toolbar
    ('class="mb-4 flex items-center gap-2 text-base"', 'class="mb-4 flex items-center gap-2 text-sm"'),
]


def migrate_file(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    original = text
    for old, new in REPLACEMENTS:
        text = text.replace(old, new)
    if text != original:
        path.write_text(text, encoding="utf-8")
        return True
    return False


def main() -> None:
    changed = 0
    for pattern in GLOBS:
        for path in ROOT.glob(pattern):
            if "node_modules" in path.parts:
                continue
            if migrate_file(path):
                changed += 1
                print(f"updated: {path.relative_to(ROOT)}")
    print(f"done ({changed} files)")


if __name__ == "__main__":
    main()
