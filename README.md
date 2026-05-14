# codesearch

Grep-like search over a large codebase, backed by a persistent index. Two implementations:

## codesearch-python

Fuzzy search via `rapidfuzz` plus optional semantic search using local sentence-transformers or
OpenAI embeddings, all stored in SQLite. Incremental indexing skips unchanged files. Install with
`uv sync`, run with `uv run codesearch`.

## codesearch-java

Keyword search backed by Apache Lucene BM25 with fuzzy token matching and phrase boosting, packaged
as a single self-contained fat-jar (~7 MB). Starts in ~300 ms with no Python/venv dependency. No
semantic search; run with `./codesearch.sh`.
