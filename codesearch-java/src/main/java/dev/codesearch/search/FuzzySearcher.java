package dev.codesearch.search;

import dev.codesearch.store.LuceneStore;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;

import java.io.IOException;
import java.util.*;

public final class FuzzySearcher {

    private static final String CONTENT_FIELD = "content";

    /**
     * Searches the index using per-token FuzzyQuery (BM25 scoring).
     * Scores are normalized relative to the top hit (0–1) so --threshold
     * has a consistent meaning regardless of query or corpus size.
     *
     * @param topK     max results to return (before ResultMerger applies per-file cap)
     * @param threshold minimum score 0–100; hits below this fraction of top-hit score are dropped
     * @param docWeight score multiplier applied to "doc" file_type chunks
     */
    public static List<SearchResult> search(
        LuceneStore store, String query, int topK, int threshold, double docWeight
    ) throws IOException {
        IndexSearcher searcher = store.getSearcher();
        if (searcher == null) return Collections.emptyList();

        List<String> tokens = tokenize(store, query);
        if (tokens.isEmpty()) return Collections.emptyList();

        BooleanQuery.Builder tokenQb = new BooleanQuery.Builder();
        tokenQb.add(new TermQuery(new Term("kind", "chunk")), BooleanClause.Occur.FILTER);
        for (String token : tokens) {
            Query tq = token.length() <= 3
                ? new TermQuery(new Term(CONTENT_FIELD, token))
                : new FuzzyQuery(new Term(CONTENT_FIELD, token), 1, 1);
            tokenQb.add(tq, BooleanClause.Occur.SHOULD);
        }
        tokenQb.setMinimumNumberShouldMatch(1);

        // Normalization baseline from token-only query so the phrase boost
        // doesn't inflate maxScore and crush partial matches below threshold.
        TopDocs tokenHits = searcher.search(tokenQb.build(), 1);
        if (tokenHits.scoreDocs.length == 0) return Collections.emptyList();
        float maxScore = tokenHits.scoreDocs[0].score;
        if (maxScore == 0) return Collections.emptyList();

        // Full query adds phrase boost for ordering — exact matches rank first.
        BooleanQuery.Builder qb = new BooleanQuery.Builder();
        qb.add(new TermQuery(new Term("kind", "chunk")), BooleanClause.Occur.FILTER);
        for (String token : tokens) {
            Query tq = token.length() <= 3
                ? new TermQuery(new Term(CONTENT_FIELD, token))
                : new FuzzyQuery(new Term(CONTENT_FIELD, token), 1, 1);
            qb.add(tq, BooleanClause.Occur.SHOULD);
        }
        qb.setMinimumNumberShouldMatch(1);
        if (tokens.size() > 1) {
            PhraseQuery.Builder pb = new PhraseQuery.Builder();
            for (int i = 0; i < tokens.size(); i++) {
                pb.add(new Term(CONTENT_FIELD, tokens.get(i)), i);
            }
            qb.add(new BoostQuery(pb.build(), tokens.size()), BooleanClause.Occur.SHOULD);
        }

        int luceneTopK = Math.min(topK * 10, 2000);
        TopDocs hits = searcher.search(qb.build(), luceneTopK);
        if (hits.scoreDocs.length == 0) return Collections.emptyList();

        double thresholdNorm = threshold / 100.0;
        List<SearchResult> results = new ArrayList<>();
        StoredFields sf = searcher.storedFields();

        for (ScoreDoc sd : hits.scoreDocs) {
            Document doc = sf.document(sd.doc);
            String ft = doc.get("file_type");
            double normalized = sd.score / maxScore;
            if ("doc".equals(ft)) normalized *= docWeight;
            if (normalized < thresholdNorm) continue;
            results.add(new SearchResult(
                doc.get("path"),
                doc.getField("start_line").numericValue().intValue(),
                doc.getField("end_line").numericValue().intValue(),
                doc.get("content"),
                normalized,
                ft
            ));
        }

        results.sort(Comparator.comparingDouble(SearchResult::score).reversed());
        return results.subList(0, Math.min(topK, results.size()));
    }

    private FuzzySearcher() {
        // Private constructor to hide the implicit public one.
    }

    private static List<String> tokenize(LuceneStore store, String text) throws IOException {
        List<String> tokens = new ArrayList<>();
        try (TokenStream ts = store.getAnalyzer().tokenStream(CONTENT_FIELD, text)) {
            CharTermAttribute attr = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) tokens.add(attr.toString());
            ts.end();
        }
        return tokens;
    }
}