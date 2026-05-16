# Refactor Methods Longer Than 25 Lines

## Context

The codesearch-java project has 4 methods that exceed the 25-line threshold. These methods handle complex logic but can be refactored by extracting helper methods to improve readability and maintainability without changing behavior.

## Methods to Refactor

### 1. FuzzySearcher.search() (71 lines)
**File**: `src/main/java/dev/codesearch/search/FuzzySearcher.java:27–97`

**Current structure**: Monolithic method combining tokenization, baseline query execution, full query execution, and result processing in one 71-line block.

**Refactoring approach**:
- Extract `buildTokenQuery()` → creates the baseline token-only BooleanQuery
- Extract `buildRankedQuery()` → creates the full query with phrase boost (for multi-token queries)
- Extract `processSearchResults()` → normalizes scores, applies doc weight, filters by threshold, builds SearchResults
- Leaves main method as orchestrator: tokenize → baseline → full query → process results → sort

**Expected result**: ~15 line main method, 3 focused helper methods ~10–15 lines each

### 2. SearchHandler.buildPage() (57 lines)
**File**: `src/main/java/dev/codesearch/web/SearchHandler.java:57–113`

**Current structure**: String concatenation of HTML + CSS + form + results section in one method.

**Refactoring approach**:
- Extract `buildHtmlHeader()` → static HTML/CSS header (lines 59–88)
- Extract `buildSearchForm()` → form HTML (lines 80–88)
- Extract `buildResultsSection()` → conditional results or empty message (lines 90–108)
- Main method: assemble header + form + results + closing tag

**Expected result**: ~10 line main method, 3 focused helper methods ~15–20 lines each

### 3. Main.IndexCommand.call() (37 lines)
**File**: `src/main/java/dev/codesearch/cli/Main.java:70–106`

**Current structure**: Initialization, file walking, thread pool coordination, stale cleanup, and commit all in one method.

**Refactoring approach**:
- Extract `initializeIndexing()` → setup store, pool, committed paths set, progress reporter (lines 71–79)
- Extract `performIndexing()` → file walk + submission loop (lines 81–87)
- Extract `cleanupStalePaths()` → iterate stale paths not in current walk (lines 93–97)
- Main method: initialize → perform → cleanup → commit

**Expected result**: ~10 line main method, 3 focused helper methods ~6–12 lines each

### 4. ProgressReporter.print() (27 lines)
**File**: `src/main/java/dev/codesearch/indexer/ProgressReporter.java:82–108`

**Current structure**: Mixed calculation, conditional branching, and formatting in single method.

**Refactoring approach**:
- Extract `calculateRate()` → compute files/second (lines 88–89)
- Extract `buildScanningStageLine()` → format scanning phase output (lines 92–94)
- Extract `buildCompletionStageLine()` → format completion phase output with ETA (lines 96–101)
- Main method: compute metrics → branch on stage → build appropriate line → output

**Expected result**: ~10 line main method, 3 focused helper methods ~5–8 lines each

## Implementation Order

1. **FuzzySearcher.search()** — highest impact (most complex logic, furthest from threshold)
2. **SearchHandler.buildPage()** — string manipulation, clearest boundaries
3. **Main.IndexCommand.call()** — orchestration logic, clear phases
4. **ProgressReporter.print()** — smallest refactor, mostly formatting

## Critical Files

- `src/main/java/dev/codesearch/search/FuzzySearcher.java`
- `src/main/java/dev/codesearch/web/SearchHandler.java`
- `src/main/java/dev/codesearch/cli/Main.java`
- `src/main/java/dev/codesearch/indexer/ProgressReporter.java`

## Verification

After each method refactor:
1. Compile: `./gradlew build`
2. Run unit tests (if any): `./gradlew test`
3. Manual smoke test:
   - Index a directory: `./codesearch.sh index <test-dir>`
   - Search: `./codesearch.sh search "test-query"`
   - Web UI: `./codesearch.sh serve` and query via browser
4. Ensure output/behavior is identical to before refactoring
