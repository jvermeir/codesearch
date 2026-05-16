# CodeSearch IntelliJ Plugin

A simple search plugin for IntelliJ IDEA that integrates with codesearch-java for keyword-based code search.

## Features

- Search across your indexed codebase from within IntelliJ
- Configurable search parameters (threshold, max results per file, doc weight)
- Quick navigation to first result with summary count
- Integrated with IntelliJ's file editor for seamless workflow

## Architecture

The plugin uses a subprocess approach to call the codesearch fat-jar:

- **Action**: Menu item (Tools → CodeSearch) or hotkey (Ctrl+Shift+X)
- **Dialog**: Search query input with configurable threshold, max-per-file, and doc-weight sliders
- **Search Service**: Calls `java -jar codesearch.jar search <query>` and parses JSON results
- **Results**: Opens first result in editor and displays summary count in a message dialog

## Setup

### Prerequisites

1. Build codesearch-java:
```bash
cd codesearch-java
./gradlew shadowJar
```

2. Index your project:
```bash
./codesearch.sh index /path/to/project
```

### Build the Plugin

```bash
cd codesearch-intellij
./gradlew build
```

This produces: `build/distributions/codesearch-intellij-0.1.0.zip`

### Install in IntelliJ

1. Open IntelliJ IDEA
2. Go to Settings → Plugins → ⚙️ → Install Plugin from Disk
3. Select `build/distributions/codesearch-intellij-0.1.0.zip`
4. Restart IntelliJ

### Configuration

After installation:

1. Go to Settings → Tools → CodeSearch
2. Set the path to `codesearch.jar` (usually `~/.codesearch/codesearch.jar`)
3. Set the path to the Lucene index (usually `~/.codesearch/lucene-index/`)
4. Click "Test Connection" to validate

## Usage

1. Press `Ctrl+Shift+X` (or use Tools menu → CodeSearch)
2. Enter your search query
3. Adjust threshold (fuzzy match score), max-per-file, and doc-weight as needed
4. Click Search or press Enter
5. The first result opens automatically in the editor
6. A dialog shows the total count of matches found

## Files

- `build.gradle` - Plugin build configuration
- `src/main/kotlin/dev/codesearch/intellij/`
  - `actions/CodeSearchAction.kt` - Menu action entry point
  - `ui/SearchDialog.kt` - Query input dialog with threshold/doc-weight sliders
  - `ui/ResultsPanel.kt` - Opens first result and shows summary dialog
  - `search/SearchService.kt` - Subprocess manager for codesearch.jar calls
  - `search/SearchResult.kt` - Result data class
  - `settings/PluginSettings.kt` - Configuration persistence
  - `settings/CodeSearchConfigurable.kt` - Settings UI with JAR/index paths
- `src/main/resources/META-INF/plugin.xml` - Plugin manifest

## Troubleshooting

### "JAR file not found"
- Ensure `codesearch.jar` exists at the configured path
- Run codesearch-java build: `cd codesearch-java && ./gradlew shadowJar`

### "Not a valid Lucene index"
- Index your project: `./codesearch.sh index /path/to/project`
- Check that the index directory contains `segments.gen` file

### "Search timed out"
- Increase timeout in Settings → Tools → CodeSearch
- Check that codesearch.jar is not hanging (kill any java processes)

## Known Limitations

- Search-only (no indexing from within the plugin)
- Opens only the first result; to see others, re-run with different parameters
- No semantic/vector search (fuzzy keyword matching only)
- Requires external codesearch.jar and Lucene index setup

## Future Enhancements

- Browse all results in a results panel instead of just the first match
- Automatic incremental indexing on file changes
- Semantic/vector search integration
- Custom syntax highlighting for matched terms in editor
- Built-in index management and status UI
- Search history and saved searches
