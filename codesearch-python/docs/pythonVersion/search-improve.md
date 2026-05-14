# Search Improvement Plan

## Context

The indexed corpus is primarily software source code, with some markdown and plain-text documentation files. The goal is to improve relevance for code queries while still surfacing documentation hits — at a lower weight.

## Changes

### 1. File-type classification (`indexer.py`, `store.py`)

Add a `file_type` column (`code` | `doc`) to the `chunks` table. Assign at index time based on file extension: source extensions (`.py`, `.ts`, `.go`, `.java`, etc.) → `code`; everything else (`.md`, `.txt`, `.rst`) → `doc`. Used by all downstream scoring.

### 2. Score damping for doc files (`searcher.py`)

After scoring, multiply doc-file chunk scores by a configurable factor (default `0.75`). A strong markdown hit (0.9) becomes 0.675 — still surfaced, but ranked below an equivalent code hit. Expose as `--doc-weight` CLI flag.

### 3. Code-optimized embedding model (`embeddings.py`)

Switch the default local model to `jinaai/jina-embeddings-v2-base-code` for `code` files. Keep `all-MiniLM-L6-v2` (or equivalent general model) for `doc` files. Both embeddings land in the same DB column — the model is selected at index time based on `file_type`.

### 4. Identifier normalization in fuzzy matching (`searcher.py`)

Before fuzzy scoring, normalize both query and chunk text for `code` chunks: split camelCase and snake_case into tokens, lowercase all, then score with `WRatio`. This makes `get user by id` match `getUserById` and `get_user_by_id` correctly. No-op for `doc` chunks.

### 5. Heading-based chunking for doc files (`indexer.py`)

For `.md`/`.rst` files, chunk at heading boundaries (`#`, `##`, `###`) instead of fixed line count. Plain text stays line-based. Keeps doc chunks semantically coherent.

### 6. AST-aware chunking for code files (`indexer.py`, `store.py`)

Parse `code` files with language-specific tooling (Python `ast`, regex heuristics for JS/TS/Go/Java) and emit one chunk per top-level function, class, or method. Store `symbol_name` and `symbol_kind` per chunk. Fall back to line-based for unsupported languages.

### 7. BM25 token scoring (`searcher.py`)

Add a lightweight BM25 scorer over tokenized chunk content as a third scoring lane alongside fuzzy and semantic. Applies to all `file_type` values. Score feeds into the existing `max()` merge. No new DB columns needed.

### 8. Symbol-weighted scoring (`searcher.py`, `store.py`)

Use the `symbol_name` stored in step 6 as a separate scoring target. If the query matches a symbol name, boost the chunk score (`max(body_score, symbol_score * 1.2)` capped at 1.0). Applies to `code` chunks only.

## Priority

| # | Change | Effort | Impact |
|---|--------|--------|--------|
| 1 | File-type classification + score damping | Low | High |
| 2 | Code embedding model (code files only) | Low | High |
| 3 | Identifier normalization (code only) | Low | Medium |
| 4 | Heading-based chunking for doc files | Low | Medium |
| 5 | AST-aware chunking (code only) | Medium | High |
| 6 | BM25 token scoring (all types) | Medium | Medium |
| 7 | Symbol-weighted scoring (code only) | Medium | Medium |

Start with 1–4: cheap changes, no schema migration, compound the existing pipeline gains. Items 5 and 6 require a schema change (`symbol_name`, `symbol_kind` columns) and a re-index.

## Tuning knobs

- `--doc-weight` (default `0.75`): damping factor for doc-file scores
- `--threshold` (default `50`): fuzzy match threshold
- `--semantic-threshold` (default `0.3`): minimum cosine similarity for semantic results
