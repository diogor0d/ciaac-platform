#!/usr/bin/env python3
"""Check local Markdown links and GitHub-style heading anchors."""

from __future__ import annotations

import argparse
import html
import os
import re
import sys
import tempfile
import unittest
from pathlib import Path
from urllib.parse import unquote, urlsplit

LINK = re.compile(r"!?(?:\[[^\]]*\])\(\s*(?:<([^>]+)>|([^\s)]+))(?:\s+[^)]*)?\)")
ATX = re.compile(r"^ {0,3}(#{1,6})\s+(.+?)\s*#*\s*$")
SETEXT = re.compile(r"^ {0,3}(=+|-+)\s*$")
BACKTICK = chr(96)
SKIP_DIRS = {
    ".git", ".cache", ".pytest_cache", ".mypy_cache", ".ruff_cache", "__pycache__",
    ".venv", "venv", "target", "node_modules", "build", "dist", "out", "coverage", "cache",
}


def _markdown_lines(path: Path) -> list[tuple[int, str]]:
    lines: list[tuple[int, str]] = []
    fenced = False
    fence_char = ""
    fence_size = 0
    in_comment = False
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if in_comment:
            if "-->" in line:
                line = line.split("-->", 1)[1]
                in_comment = False
            else:
                continue
        while "<!--" in line:
            before, after = line.split("<!--", 1)
            if "-->" in after:
                line = before + after.split("-->", 1)[1]
            else:
                line = before
                in_comment = True
                break
        marker = re.match("^ {0,3}(" + BACKTICK + "{3,}|~{3,})", line)
        if marker:
            token = marker.group(1)
            if not fenced:
                fenced, fence_char, fence_size = True, token[0], len(token)
            elif token[0] == fence_char and len(token) >= fence_size:
                fenced = False
            continue
        if not fenced:
            lines.append((line_number, line))
    return lines


def _slug(text: str) -> str:
    text = html.unescape(text)
    text = re.sub(r"<[^>]*>", "", text)
    text = re.sub(r"!?(?:\[([^\]]*)\])\([^)]*\)", r"\1", text)
    text = re.sub(BACKTICK + r"([^" + BACKTICK + r"]+)" + BACKTICK, r"\1", text)
    text = text.lower().strip()
    text = "".join(char for char in text if char.isalnum() or char in " -")
    return re.sub(r" +", "-", text)


def _anchors(path: Path) -> set[str]:
    lines = _markdown_lines(path)
    anchors: set[str] = set()
    counts: dict[str, int] = {}
    index = 0
    while index < len(lines):
        match = ATX.match(lines[index][1])
        heading = match.group(2) if match else None
        if heading is None and index + 1 < len(lines) and SETEXT.match(lines[index + 1][1]):
            heading = lines[index][1]
            index += 1
        if heading is not None:
            base = _slug(heading)
            suffix = counts.get(base, 0)
            anchors.add(base if suffix == 0 else f"{base}-{suffix}")
            counts[base] = suffix + 1
        index += 1
    return anchors


def markdown_files(root: Path) -> list[Path]:
    markdown: list[Path] = []
    for directory, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(name for name in dirnames if name not in SKIP_DIRS)
        markdown.extend(Path(directory) / name for name in filenames if name.lower().endswith(".md"))
    return sorted(markdown)


def check_tree(root: Path) -> list[str]:
    markdown = markdown_files(root)
    anchor_cache = {path.resolve(): _anchors(path) for path in markdown}
    issues: list[str] = []
    for source in markdown:
        for line_number, line in _markdown_lines(source):
            for match in LINK.finditer(line):
                destination = match.group(1) or match.group(2) or ""
                parts = urlsplit(destination)
                if parts.scheme or parts.netloc:
                    continue
                target_path = unquote(parts.path)
                fragment = unquote(parts.fragment)
                target = (source.parent / target_path).resolve() if target_path else source.resolve()
                if not target.exists():
                    issues.append(f"{source.relative_to(root)}:{line_number}: missing local path: {destination}")
                    continue
                if fragment and target.is_file() and target.suffix.lower() == ".md":
                    if fragment not in anchor_cache.get(target, set()):
                        issues.append(f"{source.relative_to(root)}:{line_number}: missing heading anchor: {destination}")
    return issues


class CheckerTests(unittest.TestCase):
    def test_valid_path_and_anchor(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "index.md").write_text("# Entrada\n\n[Guia](guide.md#secao)\n", encoding="utf-8")
            (root / "guide.md").write_text("# Secao\n", encoding="utf-8")
            self.assertEqual(check_tree(root), [])

    def test_reports_missing_path_and_anchor(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "index.md").write_text(
                "[Guia](missing.md) [Destino](guide.md#ausente)\n", encoding="utf-8"
            )
            (root / "guide.md").write_text("# Secao\n", encoding="utf-8")
            issues = check_tree(root)
            self.assertEqual(len(issues), 2)
            self.assertIn("missing local path", issues[0])
            self.assertIn("missing heading anchor", issues[1])

    def test_duplicate_heading_anchor_suffix(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            doc = root / "guide.md"
            doc.write_text("# Secao\n# Secao\n", encoding="utf-8")
            self.assertIn("secao-1", _anchors(doc))

    def test_preserves_source_line_numbers_and_skips_generated_trees(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            doc = root / "index.md"
            fence = BACKTICK * 3
            doc.write_text(f"{fence}text\nignored\n{fence}\n\n[Guia](missing.md)\n", encoding="utf-8")
            (root / "target").mkdir()
            (root / "target" / "generated.md").write_text("[Bad](missing.md)\n", encoding="utf-8")
            issues = check_tree(root)
            self.assertEqual(len(issues), 1)
            self.assertTrue(issues[0].startswith("index.md:5:"))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true", help="run checker unit tests")
    args = parser.parse_args()
    if args.self_test:
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(CheckerTests)
        result = unittest.TextTestRunner(verbosity=2).run(suite)
        return 0 if result.wasSuccessful() else 1
    root = Path(__file__).resolve().parents[1]
    issues = check_tree(root)
    if issues:
        print("\n".join(issues), file=sys.stderr)
        print(f"Documentation validation failed: {len(issues)} local link issue(s).", file=sys.stderr)
        return 1
    count = len(markdown_files(root))
    print(f"Documentation validation passed ({count} Markdown files checked).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
