from __future__ import annotations
import os
import re
from pathlib import Path
from typing import Iterator

CHUNK_LINES = 50
OVERLAP_LINES = 10

CODE_EXTENSIONS = {
    ".py", ".ts", ".tsx", ".js", ".jsx", ".go", ".rs", ".java", ".cpp", ".c",
    ".h", ".hpp", ".cs", ".rb", ".php", ".swift", ".kt", ".scala", ".r",
    ".sh", ".bash", ".zsh", ".fish", ".sql", ".tf", ".hcl", ".css", ".scss",
}

TEXT_EXTENSIONS = CODE_EXTENSIONS | {
    ".md", ".txt", ".rst", ".yaml", ".yml", ".toml", ".json", ".xml", ".html",
    ".ini", ".cfg", ".conf", ".env",
}


def _file_type(path: Path) -> str:
    return "code" if path.suffix.lower() in CODE_EXTENSIONS else "doc"

SKIP_DIRS = {
    ".git", "__pycache__", "node_modules", ".venv", "venv",
    "dist", "build", ".idea", ".vscode",
}

SKIP_EXTENSIONS = {".map", ".lock"}


def walk_files(root: Path) -> Iterator[Path]:
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [
            d for d in dirnames
            if d not in SKIP_DIRS and not d.startswith(".")
        ]
        for fname in filenames:
            p = Path(dirpath) / fname
            if p.suffix.lower() in SKIP_EXTENSIONS:
                continue
            if p.suffix.lower() in TEXT_EXTENSIONS or _is_text(p):
                yield p


def _is_text(path: Path) -> bool:
    try:
        with open(path, "rb") as f:
            return b"\x00" not in f.read(512)
    except OSError:
        return False


_HEADING_RE = re.compile(r"^#{1,6}\s")
_DOC_EXTENSIONS = {".md", ".rst"}


def _chunk_lines(lines: list[str], file_type: str) -> list[dict]:
    chunks = []
    step = CHUNK_LINES - OVERLAP_LINES
    start = 0
    while start < len(lines):
        end = min(start + CHUNK_LINES, len(lines))
        content = "\n".join(lines[start:end]).strip()
        if content:
            chunks.append({
                "content": content,
                "start_line": start + 1,
                "end_line": end,
                "embedding": None,
                "file_type": file_type,
            })
        if end >= len(lines):
            break
        start += step
    return chunks


def _chunk_headings(lines: list[str], file_type: str) -> list[dict]:
    chunks = []
    chunk_start = 0
    for i, line in enumerate(lines):
        if i > 0 and _HEADING_RE.match(line):
            content = "\n".join(lines[chunk_start:i]).strip()
            if content:
                chunks.append({
                    "content": content,
                    "start_line": chunk_start + 1,
                    "end_line": i,
                    "embedding": None,
                    "file_type": file_type,
                })
            chunk_start = i
    content = "\n".join(lines[chunk_start:]).strip()
    if content:
        chunks.append({
            "content": content,
            "start_line": chunk_start + 1,
            "end_line": len(lines),
            "embedding": None,
            "file_type": file_type,
        })
    return chunks


def chunk_file(path: Path) -> list[dict]:
    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return []

    if not lines:
        return []

    ft = _file_type(path)
    if path.suffix.lower() in _DOC_EXTENSIONS:
        return _chunk_headings(lines, ft)
    return _chunk_lines(lines, ft)
