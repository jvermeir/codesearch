package dev.codesearch.store;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.NIOFSDirectory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class LuceneStore implements Closeable {

    public static final Path DEFAULT_INDEX =
        Path.of(System.getProperty("user.home"), ".codesearch", "lucene-index");

    private final NIOFSDirectory directory;
    private final StandardAnalyzer analyzer;
    private final IndexWriter writer;
    private volatile DirectoryReader reader;
    private volatile IndexSearcher searcher;

    public LuceneStore(Path indexPath) throws IOException {
        this(indexPath, false);
    }

    public LuceneStore(Path indexPath, boolean readOnly) throws IOException {
        Files.createDirectories(indexPath);
        directory = new NIOFSDirectory(indexPath);
        analyzer = new StandardAnalyzer();
        if (readOnly) {
            writer = null;
        } else {
            writer = new IndexWriter(directory, new IndexWriterConfig(analyzer));
        }
        refreshReader();
    }

    private void refreshReader() throws IOException {
        if (reader == null) {
            try {
                reader = DirectoryReader.open(directory);
                searcher = new IndexSearcher(reader);
            } catch (IndexNotFoundException e) {
                // empty index — reader/searcher remain null
            }
        } else {
            DirectoryReader newer = DirectoryReader.openIfChanged(reader);
            if (newer != null) {
                reader.close();
                reader = newer;
                searcher = new IndexSearcher(reader);
            }
        }
    }

    public IndexSearcher getSearcher() throws IOException {
        refreshReader();
        return searcher;
    }

    public Analyzer getAnalyzer() {
        return analyzer;
    }

    /** Returns {mtime, size} for the committed file doc, or an empty map if absent. */
    public Map<String, Long> getFileMeta(String path) throws IOException {
        if (searcher == null) return Collections.emptyMap();
        BooleanQuery q = new BooleanQuery.Builder()
            .add(new TermQuery(new Term("kind", "file")), BooleanClause.Occur.MUST)
            .add(new TermQuery(new Term("path", path)), BooleanClause.Occur.MUST)
            .build();
        TopDocs hits = searcher.search(q, 1);
        if (hits.totalHits.value == 0) return Collections.emptyMap();
        Document doc = searcher.storedFields().document(hits.scoreDocs[0].doc);
        Map<String, Long> meta = new HashMap<>();
        meta.put("mtime", doc.getField("mtime").numericValue().longValue());
        meta.put("size",  doc.getField("size").numericValue().longValue());
        return meta;
    }

    /** Deletes all documents (file doc + chunk docs) for this path. */
    public void deleteByPath(String path) throws IOException {
        writer.deleteDocuments(new Term("path", path));
    }

    public void addFileDoc(String path, long mtime, long size) throws IOException {
        Document doc = new Document();
        doc.add(new StringField("kind", "file",  Field.Store.YES));
        doc.add(new StringField("path",  path,   Field.Store.YES));
        doc.add(new StoredField("mtime", mtime));
        doc.add(new StoredField("size",  size));
        writer.addDocument(doc);
    }

    public void addChunkDoc(String path, String content, int startLine, int endLine, String fileType)
        throws IOException {
        Document doc = new Document();
        doc.add(new StringField("kind",      "chunk",   Field.Store.YES));
        doc.add(new StringField("path",       path,     Field.Store.YES));
        doc.add(new TextField("content",      content,  Field.Store.YES));
        doc.add(new StoredField("start_line", startLine));
        doc.add(new StoredField("end_line",   endLine));
        doc.add(new StringField("file_type",  fileType, Field.Store.YES));
        writer.addDocument(doc);
    }

    public void commit() throws IOException {
        writer.commit();
        refreshReader();
    }

    /** All committed file paths — used to detect stale index entries. */
    public List<String> allFilePaths() throws IOException {
        if (searcher == null) return Collections.emptyList();
        TopDocs hits = searcher.search(new TermQuery(new Term("kind", "file")), Integer.MAX_VALUE);
        List<String> paths = new ArrayList<>(hits.scoreDocs.length);
        StoredFields sf = searcher.storedFields();
        for (ScoreDoc sd : hits.scoreDocs) {
            paths.add(sf.document(sd.doc).get("path"));
        }
        return paths;
    }

    public Map<String, Long> stats() throws IOException {
        refreshReader();
        long files = 0, chunks = 0;
        if (searcher != null) {
            files  = searcher.count(new TermQuery(new Term("kind", "file")));
            chunks = searcher.count(new TermQuery(new Term("kind", "chunk")));
        }
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("files",  files);
        m.put("chunks", chunks);
        return m;
    }

    @Override
    public void close() throws IOException {
        if (reader != null) reader.close();
        if (writer != null) writer.close();
        analyzer.close();
        directory.close();
    }
}