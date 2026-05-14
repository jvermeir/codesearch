package dev.codesearch.cli;

import dev.codesearch.indexer.Chunker;
import dev.codesearch.indexer.FileWalker;
import dev.codesearch.indexer.ProgressReporter;
import dev.codesearch.search.FuzzySearcher;
import dev.codesearch.search.ResultMerger;
import dev.codesearch.search.SearchResult;
import dev.codesearch.store.LuceneStore;
import dev.codesearch.web.WebServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;

@Command(
    name = "codesearch",
    mixinStandardHelpOptions = true,
    version = "1.0.0",
    subcommands = {Main.IndexCommand.class, Main.SearchCommand.class, Main.StatsCommand.class, Main.ServeCommand.class}
)
public class Main implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    @Option(names = "--db", description = "Lucene index directory (default: ~/.codesearch/lucene-index/)")
    Path db;

    @Spec CommandSpec spec;

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }

    @Override
    public Integer call() {
        spec.commandLine().usage(System.out);
        return 0;
    }

    // -------------------------------------------------------------------------

    @Command(name = "index", description = "Index a directory (skips unchanged files).")
    static class IndexCommand implements Callable<Integer> {

        private static final String FILES_KEY = "files";
        private static final String CHUNKS_KEY = "chunks";

        @ParentCommand Main parent;

        @Parameters(index = "0", description = "Root directory to index")
        Path root;

        @Option(names = "--threads", description = "Indexer threads (default: half of available CPUs)")
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);

        @Override
        public Integer call() throws Exception {
            Path dbPath = parent.db != null ? parent.db : LuceneStore.DEFAULT_INDEX;
            ProgressReporter progress = new ProgressReporter();

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try (LuceneStore store = new LuceneStore(dbPath)) {
                Set<String> committedPaths = new HashSet<>(store.allFilePaths());
                Set<String> currentPaths = ConcurrentHashMap.newKeySet();

                pool.submit(progress::start);

                String rootPrefix = root.toAbsolutePath().toString();
                FileWalker.walkStreaming(root.toAbsolutePath(), path -> {
                    String abs = path.toAbsolutePath().toString();
                    currentPaths.add(abs);
                    progress.fileScanned();
                    pool.submit(() -> indexOne(store, path, progress));
                });

                progress.scanDone();
                pool.shutdown();
                pool.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);

                for (String stale : committedPaths) {
                    if (stale.startsWith(rootPrefix) && !currentPaths.contains(stale)) {
                        store.deleteByPath(stale);
                    }
                }

                progress.committing();
                store.commit();
            } finally {
                pool.shutdownNow();
            }

            return 0;
        }

        private void indexOne(LuceneStore store, Path fpath, ProgressReporter progress) {
            try {
                BasicFileAttributes attrs = Files.readAttributes(fpath, BasicFileAttributes.class);
                long mtime = attrs.lastModifiedTime().toMillis();
                long size  = attrs.size();

                Map<String, Long> stored = store.getFileMeta(fpath.toString());
                if (stored != null
                        && stored.get(FILES_KEY).equals(mtime)
                        && stored.get(CHUNKS_KEY)  == size) {
                    progress.fileSkippedUnchanged();
                    return;
                }

                List<Chunker.Chunk> chunks = Chunker.chunkFile(fpath);
                if (chunks.isEmpty()) {
                    progress.fileSkippedEmpty();
                    return;
                }

                store.deleteByPath(fpath.toString());
                store.addFileDoc(fpath.toString(), mtime, size);
                for (Chunker.Chunk c : chunks) {
                    store.addChunkDoc(fpath.toString(), c.content(), c.startLine(), c.endLine(), c.fileType());
                }
                progress.fileIndexed();
            } catch (IOException e) {
                logger.error("Error indexing {}:", fpath, e);
            }
        }
    }

    // -------------------------------------------------------------------------

    @Command(name = "search", description = "Search the index.")
    static class SearchCommand implements Callable<Integer> {

        @ParentCommand Main parent;

        @Parameters(index = "0", description = "Search query")
        String query;

        private static final int DEFAULT_TOP = 10;
        private static final int DEFAULT_THRESHOLD = 50;
        private static final int DEFAULT_MAX_PER_FILE = 2;
        private static final double DEFAULT_DOC_WEIGHT = 0.75;

        @Option(names = "--top",          defaultValue = "" + DEFAULT_TOP,          description = "Max results (default: " + DEFAULT_TOP + ")")
        int top;

        @Option(names = "--threshold",    defaultValue = "" + DEFAULT_THRESHOLD,    description = "Min score 0-100 relative to top hit (default: " + DEFAULT_THRESHOLD + ")")
        int threshold;

        @Option(names = "--max-per-file", defaultValue = "" + DEFAULT_MAX_PER_FILE, description = "Max chunks per file (default: " + DEFAULT_MAX_PER_FILE + ")")
        int maxPerFile;

        @Option(names = "--doc-weight",   defaultValue = "" + DEFAULT_DOC_WEIGHT,   description = "Score multiplier for doc files (default: " + DEFAULT_DOC_WEIGHT + ")")
        double docWeight;

        @Option(names = "--context", description = "Show matching code snippet")
        boolean showContext;

        @Override
        public Integer call() throws Exception {
            Path dbPath = parent.db != null ? parent.db : LuceneStore.DEFAULT_INDEX;
            try (LuceneStore store = new LuceneStore(dbPath)) {
                List<SearchResult> candidates = FuzzySearcher.search(store, query, top * 5, threshold, docWeight);
                List<SearchResult> results    = ResultMerger.merge(candidates, top, maxPerFile);

                if (results.isEmpty()) {
                    System.out.println("No results.");
                    return 0;
                }

                for (SearchResult r : results) {
                    logger.info("{}:{}-{}  score=%.3f%n", r.path(), r.startLine(), r.endLine(), r.score());
                    if (showContext) {
                        System.out.println(snippet(r.content(), query));
                        System.out.println();
                    }
                }
            }
            return 0;
        }

        private String snippet(String content, String query) {
            String[] lines = content.split("\n", -1);
            String q = query.toLowerCase();
            int matchIdx = 0;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].toLowerCase().contains(q)) { matchIdx = i; break; }
            }
            int start = Math.max(0, matchIdx - 8);
            int end   = Math.min(lines.length, matchIdx + 9);
            return String.join("\n", Arrays.copyOfRange(lines, start, end));
        }
    }

    // -------------------------------------------------------------------------

    @Command(name = "serve", description = "Start the web search UI.")
    static class ServeCommand implements Callable<Integer> {

        private static final String FILES_KEY = "files";
        private static final String CHUNKS_KEY = "chunks";
        @ParentCommand Main parent;

        private static final int DEFAULT_PORT = 8080;
        private static final int DEFAULT_TOP = 10;
        private static final int DEFAULT_THRESHOLD = 50;
        private static final int DEFAULT_MAX_PER_FILE = 2;
        private static final double DEFAULT_DOC_WEIGHT = 0.75;

        @Option(names = "--port",         defaultValue = "" + DEFAULT_PORT,         description = "HTTP port (default: " + DEFAULT_PORT + ")")
        int port;

        @Option(names = "--top",          defaultValue = "" + DEFAULT_TOP,          description = "Max results (default: " + DEFAULT_TOP + ")")
        int top;

        @Option(names = "--threshold",    defaultValue = "" + DEFAULT_THRESHOLD,    description = "Min score 0-100 (default: " + DEFAULT_THRESHOLD + ")")
        int threshold;

        @Option(names = "--max-per-file", defaultValue = "" + DEFAULT_MAX_PER_FILE, description = "Max chunks per file (default: " + DEFAULT_MAX_PER_FILE + ")")
        int maxPerFile;

        @Option(names = "--doc-weight",   defaultValue = "" + DEFAULT_DOC_WEIGHT,   description = "Score multiplier for doc files (default: " + DEFAULT_DOC_WEIGHT + ")")
        double docWeight;

        @Override
        public Integer call() throws Exception {
            Path dbPath = parent.db != null ? parent.db : LuceneStore.DEFAULT_INDEX;
            try (LuceneStore store = new LuceneStore(dbPath, true)) {
                Map<String, Long> stats = store.stats();
                long chunks = stats.get(CHUNKS_KEY);
                if (chunks == 0) {
                    logger.warn("WARNING: index is empty — run 'index <dir>' first.");
                } else {
                    logger.info("Index ready: {} files, {} chunks.", stats.get(FILES_KEY), chunks);
                }
                WebServer webServer = new WebServer(store, port, top, threshold, maxPerFile, docWeight);
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    webServer.stop();
                    try { store.close(); } catch (Exception ignored) {}
                }));
                webServer.start(port);
                Thread.currentThread().join();
            }
            return 0;
        }
    }

    // -------------------------------------------------------------------------

    @Command(name = "stats", description = "Show index statistics.")
    static class StatsCommand implements Callable<Integer> {

        @ParentCommand Main parent;

        private static final String FILES_KEY = "files";
        private static final String CHUNKS_KEY = "chunks";

        @Override
        public Integer call() throws Exception {
            Path dbPath = parent.db != null ? parent.db : LuceneStore.DEFAULT_INDEX;
            try (LuceneStore store = new LuceneStore(dbPath)) {
                Map<String, Long> stats = store.stats();
                logger.info("Files indexed : {}",  stats.get(FILES_KEY));
                logger.info("Chunks        : {}",  stats.get(CHUNKS_KEY));
                logger.info("DB            : {}",  dbPath);
            }
            return 0;
        }
    }
}