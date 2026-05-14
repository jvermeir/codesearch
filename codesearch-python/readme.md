# codesearch

Grep-like search over a large codebase, backed by a persistent index. Fuzzy and semantic (AI embedding) search. Only re-indexes files that have changed.

## Install

```bash
# Create/update virtualenv and install project (fuzzy search only)
uv sync

# + local semantic search (sentence-transformers, ~100 MB model download)
uv sync --extra local

# + OpenAI semantic search
uv sync --extra openai

# Both
uv sync --extra all
```

Add new dependencies with uv:

```bash
uv add <package>
uv add --optional local <package>
```

## Quick start

```bash
# Build index (fuzzy only, fast)
uv run codesearch index /path/to/codebase --embedder none

# Build index with local semantic embeddings
uv run codesearch index /path/to/codebase --embedder local

# Search
uv run codesearch search "auth error handling"
```

## Commands

### `index`

```
codesearch index <ROOT> [OPTIONS]

  --embedder  none | local | openai   (default: none)
  --model     override embedding model name
  --batch-size  chunks per embedding API call (default: 32)
```

Walks `ROOT` recursively, skips unchanged files (mtime + size check), removes deleted files from the index. Run again at any time to pick up changes.

### `search`

```
codesearch search <QUERY> [OPTIONS]

  --type      fuzzy | semantic | both   (default: both)
  --top       number of results         (default: 10)
  --embedder  local | openai            (default: local)
  --model     override embedding model
  --threshold fuzzy match threshold 0-100 (default: 40)
  --context   print the matched code snippet
```

Examples:

```bash
# Fuzzy — typo-tolerant, no embeddings needed
uv run codesearch search "authn middleware" --type fuzzy

# Semantic — finds conceptually related code
uv run codesearch search "how users log in" --type semantic --embedder local

# Both — deduplicated, best of both methods
uv run codesearch search "rate limiting" --context
```

Output format: `<file>:<start>-<end>  <method>  score=<0-1>`

### `stats`

```
codesearch stats
```

Shows number of indexed files, chunks, and embedded chunks.

## Global options

```
--db PATH   Path to SQLite index file (default: ~/.codesearch/index.db)
            Can also be set via $CODESEARCH_DB
```

Example — per-project index:

```bash
export CODESEARCH_DB=./.codesearch.db
uv run codesearch index . --embedder local
uv run codesearch search "connection pool"
```

## How it works

Files are split into 50-line overlapping chunks and stored in SQLite. Fuzzy search scores every chunk with `rapidfuzz.partial_ratio`. Semantic search embeds the query and computes cosine similarity against stored chunk embeddings. `--type both` merges and deduplicates results from both methods.
