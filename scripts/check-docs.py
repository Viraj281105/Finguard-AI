#!/usr/bin/env python3
"""Documentation checks (S0-02). Standard library only; run from anywhere:  python scripts/check-docs.py

1. Every row ID in docs/KNOWN_ISSUES.md is unique.
2. Every KI-nnn mentioned in a Markdown file is defined in KNOWN_ISSUES.md.
3. Phrases that were found to contradict the code are not reintroduced (STALE_PHRASES).
4. Relative Markdown links point at files that exist.

Exit code 0 = clean, 1 = problems (listed). Keep STALE_PHRASES small and specific: each entry is a past contradiction.
"""

from __future__ import annotations

import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KNOWN_ISSUES = ROOT / "docs" / "KNOWN_ISSUES.md"
SKIP_DIRS = {"node_modules", ".git", "target", ".venv", "dist", "coverage", ".staging", "__pycache__"}

# (regex, why it is wrong). Matched per line, case-sensitive, in every checked Markdown file except KNOWN_ISSUES/CHANGELOG
# history rows, which may legitimately quote old behaviour (see HISTORY_FILES).
STALE_PHRASES: list[tuple[str, str]] = [
    (r"Minimum length 6\b", "password minimum is 10 characters (RegisterRequest)"),
    (r"\*\*Not implemented\.\*\* \(KI-013\)", "rate limiting and security headers are implemented (KI-013)"),
    (r"\(not yet committed\)", "the working tree is committed; state the commit instead"),
    (r"24 h access / 7 d refresh", "access tokens live 15 minutes (jwt.expiration default 900000)"),
    (r"≈ 309 kB", "the measured production bundle is 488 kB (2026-10-28)"),
    (r"`simulation` \(v0\.9\.0\)", "there is no simulation module; scenarios live in forecasting"),
]
HISTORY_FILES = {"CHANGELOG.md", "DECISIONS.md", "AUDIT_E2E_2026-10-27.md", "RELEASE_PLAN.md"}

KI_ROW = re.compile(r"^\|\s*(KI-\d{3})\s*\|", re.MULTILINE)
KI_REF = re.compile(r"\bKI-(\d{3})\b")
LINK = re.compile(r"(?<!\!)\[[^\]]*\]\(([^)\s]+)\)")


def markdown_files() -> list[Path]:
    out = []
    for path in ROOT.rglob("*.md"):
        if SKIP_DIRS.intersection(path.relative_to(ROOT).parts):
            continue
        out.append(path)
    return sorted(out)


def main() -> int:
    problems: list[str] = []
    text = KNOWN_ISSUES.read_text(encoding="utf-8")

    ids = KI_ROW.findall(text)
    for ki, count in Counter(ids).items():
        if count > 1:
            problems.append(f"docs/KNOWN_ISSUES.md: {ki} is defined {count} times")
    defined = set(ids)

    for path in markdown_files():
        rel = path.relative_to(ROOT).as_posix()
        content = path.read_text(encoding="utf-8")
        lines = content.splitlines()

        for number in sorted(set(KI_REF.findall(content))):
            if f"KI-{number}" not in defined:
                problems.append(f"{rel}: refers to KI-{number}, which is not defined in docs/KNOWN_ISSUES.md")

        if path.name not in HISTORY_FILES and rel != "docs/KNOWN_ISSUES.md":
            for index, line in enumerate(lines, start=1):
                for pattern, why in STALE_PHRASES:
                    if re.search(pattern, line):
                        problems.append(f"{rel}:{index}: stale statement /{pattern}/ - {why}")

        in_fence = False
        for index, line in enumerate(lines, start=1):
            if line.lstrip().startswith("```"):
                in_fence = not in_fence
                continue
            if in_fence:
                continue
            for target in LINK.findall(line):
                if re.match(r"^(https?:|mailto:|#)", target):
                    continue
                file_part = target.split("#", 1)[0]
                if file_part and not (path.parent / file_part).resolve().exists():
                    problems.append(f"{rel}:{index}: broken link -> {target}")

    if problems:
        print(f"{len(problems)} documentation problem(s):")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    print(f"Documentation checks passed ({len(markdown_files())} Markdown files, {len(defined)} known issues).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
