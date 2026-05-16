# CodeSearch IntelliJ Plugin

A simple search plugin for IntelliJ IDEA that integrates with codesearch-java for keyword-based code search.

## Features

- Search across your indexed codebase from within IntelliJ
- Configurable search parameters (threshold, max results per file, etc.)
- Results displayed in IntelliJ's native UsageView
- Double-click to navigate to results with line numbers

## Architecture

The plugin uses a subprocess approach to call the codesearch fat-jar:

- **Action**: Menu item (Tools → CodeSearch) or hotkey (Ctrl+Shift+X)
- **Dialog**: Query input with configurable threshold, max-per-file, doc-weight
- **Search Service**: Calls `java -jar codesearch.jar search <query>` and parses results
- **Results**: Displayed in IntelliJ's native UsageView for integrated navigation

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
gradle build
```

This produces: `build/libs/codesearch-intellij-0.1.0.zip`

### Install in IntelliJ

1. Open IntelliJ IDEA
2. Go to Preferences → Plugins → ⚙️ → Install Plugin from Disk
3. Select `build/libs/codesearch-intellij-0.1.0.zip`
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
3. Adjust threshold, max-per-file, and doc-weight as needed
4. Press Enter or click Search
5. Results appear in a UsageView panel
6. Double-click a result to open the file at that line

## Files

- `build.gradle` - Plugin build configuration
- `src/main/kotlin/dev/codesearch/intellij/`
  - `actions/CodeSearchAction.kt` - Menu action entry point
  - `ui/SearchDialog.kt` - Query input dialog
  - `ui/ResultsPanel.kt` - Results display using UsageView
  - `search/SearchService.kt` - Subprocess manager and parser
  - `search/SearchResult.kt` - Result data class
  - `settings/PluginSettings.kt` - Configuration persistence
  - `settings/CodeSearchConfigurable.kt` - Settings UI
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
- Results always sorted by score (descending)
- No semantic/vector search (keyword-only)
- Limited result preview (first 60 characters of content)

## Future Enhancements

- Automatic incremental indexing on file changes
- JSON output format for codesearch-java
- Semantic search integration
- Custom syntax highlighting in results
- Project-specific index management
