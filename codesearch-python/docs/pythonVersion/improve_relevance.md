# Improving Search Relevance

## Problem

Searching for "Course Material" returned one good match (`README.md`) alongside irrelevant results like `errorController.js` and `tours.json`, which contained neither search term.

Three root causes were identified:

1. **No minimum score for semantic results** — semantic search returned the top-k chunks regardless of cosine similarity. Low-quality matches filled the list when fewer than k good results existed.
2. **`partial_ratio` too permissive for short queries** — for a 14-character query like "course material", `partial_ratio` finds the best-matching 14-character window anywhere in the document and scores that. At threshold 40, most files pass.
3. **RRF collapsed scores to ~0.016** — Reciprocal Rank Fusion was introduced to combine fuzzy and semantic results but encodes only rank position (`1/(60+rank)`), discarding absolute match quality. Every top-5 result looked equally relevant.

## Changes

### `codesearch/searcher.py`

| What | Before | After |
|------|--------|-------|
| Fuzzy scorer | `fuzz.partial_ratio` | `fuzz.WRatio` — picks the best algorithm per pair, more precise for natural-language queries |
| Fuzzy threshold | 40 | 50 |
| Semantic threshold | none | 0.3 — chunks with cosine similarity below this are dropped before ranking |
| Result merge | Reciprocal Rank Fusion | `max(fuzzy_score, semantic_score)` — preserves meaningful 0–1 scores |

### `codesearch/cli.py`

- Added `--semantic-threshold` flag (default `0.3`) to `search` command for runtime tuning.
- Updated `--threshold` default from `40` to `50` to match the new fuzzy default.

## Tuning

- **`--semantic-threshold`**: raise to `0.35` for stricter semantic filtering, lower to `0.2` to recover more results at the cost of more noise.
- **`--threshold`**: controls fuzzy matching strictness (0–100).
