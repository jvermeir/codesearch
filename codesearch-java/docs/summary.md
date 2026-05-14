# codesearch-java — Summary

A complete Java reimplementation of codesearch using Apache Lucene 9.11 for indexing and search,
and Picocli for the CLI. It replaces the Python version's SQLite + rapidfuzz + sentence-transformers
stack with a single self-contained fat-jar (~7 MB).

---

## Architecture

```
codesearch-java/
├── build.gradle                              Gradle + Shadow plugin (fat-jar)
├── gradlew                                   Gradle 8.8 wrapper
└── src/main/java/dev/codesearch/
    ├── store/LuceneStore.java                index lifecycle, CRUD, stats
    ├── indexer/FileWalker.java               directory traversal with skip-lists; streaming variant
    ├── indexer/Chunker.java                  fixed-window and heading-based chunking
    ├── indexer/ProgressReporter.java         async progress with background reporter thread
    ├── search/SearchResult.java              result record (path, lines, score, content)
    ├── search/FuzzySearcher.java             BM25 + per-token Term/FuzzyQuery + phrase boost + normalization
    ├── search/ResultMerger.java              per-file cap + overlap deduplication
    └── cli/Main.java                         Picocli commands: index / search / stats / serve
```

---

## Data model

Two Lucene document kinds share the same index, distinguished by a `kind` field.

### File document — change detection only

| Field   | Lucene type            | Purpose                           |
|---------|------------------------|-----------------------------------|
| `kind`  | `StringField` (stored) | literal `"file"`                  |
| `path`  | `StringField` (stored) | absolute path, used as delete key |
| `mtime` | `StoredField` (long)   | last-modified epoch millis        |
| `size`  | `StoredField` (long)   | file size in bytes                |

### Chunk document — searchable content

| Field        | Lucene type            | Purpose                                    |
|--------------|------------------------|--------------------------------------------|
| `kind`       | `StringField` (stored) | literal `"chunk"`                          |
| `path`       | `StringField` (stored) | absolute path, for display                 |
| `content`    | `TextField` (stored)   | chunk text, analyzed by `StandardAnalyzer` |
| `start_line` | `StoredField` (int)    | 1-based start line                         |
| `end_line`   | `StoredField` (int)    | 1-based end line (inclusive)               |
| `file_type`  | `StringField` (stored) | `"code"` or `"doc"`                        |

Default index location: `~/.codesearch/lucene-index/`

---

## Indexing pipeline

1. **Committed-path snapshot**: before walking, `allFilePaths()` is called once to capture the set
   of paths already in the index. This is used for stale cleanup after the walk completes.

2. **FileWalker** streams the root directory recursively via `walkStreaming(root, consumer)`.
   Each accepted file is handed to the consumer immediately — no buffering into a list.
    - Skips dirs: `.git`, `__pycache__`, `node_modules`, `.venv`, `venv`, `dist`, `build`, `.idea`,
      `.vscode`, and any hidden directory (name starts with `.`).
    - Skips extensions: `.map`, `.lock`.
    - Accepts known text extensions (code + doc + config). Unknown files are sniffed: read 512
      bytes, reject if any null byte is found.

3. **Thread pool**: `IndexCommand` creates a `FixedThreadPool` of `--threads` workers (default:
   `max(1, availableProcessors / 2)`). As each file arrives from the walker, an `indexOne` task
   is submitted to the pool. The walker and workers run concurrently.

4. **Change detection** (per worker): look up the file doc by `kind=file AND path=<path>`.
   If the stored `mtime` and `size` match current filesystem attributes, the file is skipped.
   `IndexWriter` is thread-safe; `IndexSearcher` is stable (no commit until all workers finish),
   so concurrent reads and writes are safe. `reader`/`searcher` fields in `LuceneStore` are
   `volatile` to guarantee cross-thread visibility.

5. **Chunker** splits files into overlapping chunks:
    - Code files (all non-doc extensions): fixed 50-line windows with 10-line overlap (step = 40).
    - Doc files (`.md`, `.rst`): split on Markdown headings (`/^#{1,6}\s/`); each section is one
      chunk.
    - `file_type` is set to `"code"` for known code extensions, `"doc"` otherwise.

6. **Write** (per worker): one file doc + N chunk docs written via `IndexWriter`.

7. **Stale cleanup**: after `awaitTermination`, committed paths not present in the current walk are
   deleted by path term. Paths outside the current root are not touched, allowing multiple roots
   to coexist in the same index.

8. **Commit**: a single `commit()` is called after stale cleanup. Progress output goes to `stderr`;
   the final summary line goes to `stdout`.

---

## Search pipeline

1. **Tokenize** the query with `StandardAnalyzer` (same analyzer used at index time).

2. **Build a token query** (used for score normalization):
    - FILTER clause: `kind = "chunk"`.
    - SHOULD clause per token: `TermQuery` for tokens ≤ 3 chars (exact match); `FuzzyQuery(term,
      maxEdits=1, prefixLength=1)` for longer tokens.
    - `minShouldMatch = 1`.
    - Run with `topK=1` to obtain `maxScore` — the baseline for normalization.

3. **Build the ranked query** (same token clauses, plus for multi-token queries):
    - SHOULD clause: `BoostQuery(PhraseQuery(all tokens in order), boost=numTokens)`.
    - This pushes exact phrase matches to the top without inflating `maxScore` (which comes from
      the token-only query), so partial matches remain above threshold.

4. **Retrieve** up to `topK * 10` hits (capped at 2000) from Lucene; hits are BM25-scored and
   returned in descending score order.

5. **Normalize scores**: divide each raw BM25 score by `maxScore` (from the token-only query),
   yielding a 0–1 range (phrase-matched hits may exceed 1.0 — that's fine, they just rank first).
   Doc-type chunks are further multiplied by `--doc-weight` (default 0.75).

6. **Threshold filter**: drop any result whose normalized score is below `threshold / 100`.

7. **ResultMerger** applies a greedy per-file cap and overlap deduplication:
    - Cap: at most `--max-per-file` (default 2) chunks per file.
    - Overlap: two chunks from the same file overlap if their line ranges intersect; only the
      higher-scored one is kept.

---

## Design decisions

### Streaming walk + parallel indexing

`FileWalker.walkStreaming` hands each file to a `Consumer<Path>` as it is found rather than
collecting into a list first. `IndexCommand` submits each file to a `FixedThreadPool` immediately,
so scanning and indexing overlap in time. A single `commit()` at the end keeps Lucene's segment
count low and avoids flushing intermediate state.

`IndexWriter` is documented as thread-safe for concurrent `addDocument`/`deleteDocuments` calls.
`IndexSearcher` (used for change-detection reads) is stable throughout the concurrent phase because
no `commit()` is issued until all workers finish. `reader` and `searcher` are `volatile` in
`LuceneStore` to ensure cross-thread visibility without full synchronization.

### NIOFSDirectory instead of FSDirectory

`FSDirectory.open()` selects `MMapDirectory` on Linux/macOS, which loads a
`MemorySegmentIndexInputProvider` via `ServiceLoader`. The Shadow JAR plugin does not correctly
merge Lucene's multi-release JAR, causing a `LinkageError` at startup. `NIOFSDirectory` is used
directly — it has no functional difference for this workload.

### Relative score normalization

BM25 raw scores vary with query length and corpus size, so a fixed ceiling (e.g. 10.0) would
require per-corpus tuning. Instead, scores are normalized against the top hit of a token-only query:

```
normalized = rawScore / maxTokenScore
```

`--threshold 50` therefore always means "at least half as relevant as the best token-only match."
The phrase boost (added to the ranked query for multi-token searches) can push exact phrase matches
above 1.0 — that is intentional; they rank first while partial matches remain visible.

### Exact match preference for short tokens

Tokens of 3 characters or fewer use `TermQuery` (exact match) instead of `FuzzyQuery`. This
prevents single-edit fuzzy expansion from matching unrelated short tokens (e.g. `"duh"` fuzzy-
matching `"du"` tokens from longer identifiers like `duration`).

### Phrase boosting for multi-token queries

When the query has more than one token, a `PhraseQuery` across all tokens is added as a boosted
SHOULD clause (boost = number of tokens). This ensures a document containing the exact phrase
ranks above documents that merely contain individual query words at high frequency.

### Single-analyzer consistency

The same `StandardAnalyzer` instance is used for both indexing and query tokenization. This ensures
that stemming and case-folding are applied identically in both directions.

### No semantic search

This version is intentionally keyword-only (Lucene BM25 + FuzzyQuery). The Python version
optionally uses sentence-transformers for semantic (vector) search; this Java version does not.
Adding semantic search would require Lucene's `KnnVectorField` and an embedding model.

### Fat-jar packaging

The `com.github.johnrengelman.shadow` plugin bundles all dependencies into a single JAR:

```
build/libs/codesearch.jar   ~7 MB
```

The manifest sets `Multi-Release: true` to satisfy Lucene's multi-release JAR requirements.

---

## CLI reference

```
./codesearch.sh [--db <path>] <command>

index  <root> [--threads N]
search <query> [--top 10] [--threshold 50] [--max-per-file 2] [--doc-weight 0.75] [--context]
stats
serve  [--port 8080] [--top 10] [--threshold 50] [--max-per-file 2] [--doc-weight 0.75]
```

`--threads` defaults to `max(1, availableProcessors / 2)`. Progress is printed to `stderr` in two
phases: a scanning line while the walk is in progress, then a `[done/total | pct%] rate ETA` line
once the total file count is known. In a non-TTY context (log files, CI), a new line is printed
every 10 seconds instead of overwriting.

`codesearch.sh` is a thin wrapper:

```sh
exec java --add-modules jdk.incubator.vector -jar "$(dirname "$0")/build/libs/codesearch.jar" "$@"
```

`--add-modules jdk.incubator.vector` is required for Lucene's SIMD optimizations on Java 21.

---

## Build

Requirements: Java 21+, no other local dependencies needed (Gradle wrapper downloads itself).

```sh
cd codesearch-java
./gradlew shadowJar          # produces build/libs/codesearch.jar
./codesearch.sh index <root>
./codesearch.sh search "query"
```

### Dependencies (runtime)

| Artifact                                   | Version | Purpose             |
|--------------------------------------------|---------|---------------------|
| `org.apache.lucene:lucene-core`            | 9.11.0  | Index + BM25 search |
| `org.apache.lucene:lucene-analysis-common` | 9.11.0  | StandardAnalyzer    |
| `org.apache.lucene:lucene-queryparser`     | 9.11.0  | (available, unused) |
| `info.picocli:picocli`                     | 4.7.6   | CLI parsing         |

`picocli-codegen` is annotation-processor only (not included in the fat-jar).

---

## Comparison with the Python version

| Concern          | Python                              | Java/Lucene           |
|------------------|-------------------------------------|-----------------------|
| Full-text search | O(N) rapidfuzz scan                 | Lucene inverted index |
| Startup time     | ~2 s (sentence-transformers import) | ~300 ms               |
| Deployable       | pip + venv                          | single fat-jar (7 MB) |
| Semantic search  | yes (optional)                      | no                    |
| Concurrency      | GIL-limited                         | true JVM threads      |

---

## Recreating the code

To reimplement from scratch, build the components in this order:

1. **`LuceneStore`** — open `NIOFSDirectory`, create `IndexWriter` with `StandardAnalyzer`,
   implement `addFileDoc`, `addChunkDoc`, `deleteByPath`, `commit`, `getFileMeta`, `allFilePaths`,
   `stats`, and `close`. Hold a `volatile DirectoryReader` + `volatile IndexSearcher` refreshed
   after each commit via `DirectoryReader.openIfChanged`.

2. **`FileWalker`** — implement `walkStreaming(Path root, Consumer<Path> consumer)` using
   `Files.walkFileTree` + `SimpleFileVisitor`. Skip dirs and extensions as listed above. For files
   with unknown extensions, sniff the first 512 bytes for null bytes. `walk()` delegates to this.

3. **`Chunker`** — implement `chunkByLines` (50-line window, 10-line overlap) and
   `chunkByHeadings` (split on `^#{1,6}\s`). Return
   `record Chunk(content, startLine, endLine, fileType)`.

4. **`ProgressReporter`** — hold `AtomicInteger` counters for `scanned`, `indexed`, `skipped` and
   a `volatile boolean scanDone`. Start a daemon thread that prints to `stderr` every 1 s (TTY) or
   10 s (non-TTY): scanning phase shows raw counts; post-scan shows percentage + ETA.

5. **`FuzzySearcher`** — tokenize query via `analyzer.tokenStream`. Build a token-only
   `BooleanQuery` and run it with `topK=1` to get `maxScore` for normalization. Build a second
   query adding a boosted `PhraseQuery` (multi-token queries only) and run it for the full hit list.
   Use `TermQuery` for tokens ≤ 3 chars, `FuzzyQuery(maxEdits=1, prefixLength=1)` for longer ones.
   Normalize hits against `maxScore`, apply doc-weight and threshold.

6. **`ResultMerger`** — greedy loop over descending results: skip if over per-file cap or if line
   ranges overlap with an already-kept result.

7. **`Main`** — wire the four Picocli subcommands (`index`, `search`, `stats`, `serve`) using
   `@ParentCommand` to share the `--db` option.

8. **`build.gradle`** — add Shadow plugin, set `mainClass`, add `--add-modules jdk.incubator.vector`
   to `applicationDefaultJvmArgs`, set `archiveBaseName` and `Multi-Release: true` in the manifest.
