# codesearch — implementation plan & summary

## Goal

A CLI tool for fast, indexed search over large codebases. Faster than grep for repeated searches because files are indexed once and re-indexed only when changed.

## Architecture

```
codesearch/
├── store.py      — SQLite persistence (files + chunks + embeddings)
├── indexer.py    — file walker, chunker
├── embeddings.py — embedding providers (local, openai)
├── searcher.py   — fuzzy and semantic search logic
└── cli.py        — Click CLI (index / search / stats commands)
```

## Key decisions

**Storage:** SQLite at `~/.codesearch/index.db`. Each file's mtime and size are stored; on re-index, unchanged files are skipped. Embeddings are stored as raw float32 blobs alongside their text chunks.

**Chunking:** Fixed 50-line windows with 10-line overlap. Simple and language-agnostic; works for code, markdown, and config files alike.

**Fuzzy search:** `rapidfuzz.partial_ratio` scored against every chunk in the index. Configurable threshold (default 40/100).

**Semantic search:** Cosine similarity over stored embeddings using numpy. Embedding provider is pluggable — local (`sentence-transformers`, default model `all-MiniLM-L6-v2`) or OpenAI (`text-embedding-3-small`).

**Merge (--type both):** Results from both methods are deduplicated by (path, start_line), keeping the max score, then re-sorted.

**File types:** All text files — source code, markdown, config, anything without a null byte in the first 512 bytes.

**Skipped dirs:** `.git`, `__pycache__`, `node_modules`, `.venv`, `venv`, `dist`, `build`, `.idea`, `.vscode`.

## Dependencies

| Package | Role | Required |
|---|---|---|
| click | CLI | always |
| rich | output formatting | always |
| rapidfuzz | fuzzy matching | always |
| numpy | embedding math | always |
| sentence-transformers | local embeddings | optional (`[local]`) |
| openai | OpenAI embeddings | optional (`[openai]`) |

## What's not implemented (possible next steps)

- FAISS/chromadb for sub-linear vector search on very large codebases
- SQLite FTS5 for pre-filtering fuzzy candidates
- Per-language chunking (split at function/class boundaries)
- `--watch` mode to auto-reindex on file changes
- Output formats: JSON, TSV
