package dev.codesearch.search;

import java.util.*;

public class ResultMerger {
    private ResultMerger() {}

    /**
     * Applies per-file cap and overlap deduplication to an already-scored,
     * descending-sorted list of results.
     */
    public static List<SearchResult> merge(List<SearchResult> results, int topK, int maxPerFile) {
        List<SearchResult> deduped = new ArrayList<>();
        Map<String, Integer> perFile = new HashMap<>();

        for (SearchResult candidate : results) {
            int count = perFile.getOrDefault(candidate.path(), 0);
            if (count >= maxPerFile) continue;
            boolean overlapsKept = false;
            for (SearchResult kept : deduped) {
                if (overlaps(candidate, kept)) { 
                    overlapsKept = true; 
                    break; 
                }
            }
            if (!overlapsKept && deduped.size() < topK) {
                deduped.add(candidate);
                perFile.put(candidate.path(), count + 1);
            }
        }
        return deduped;
    }

    private static boolean overlaps(SearchResult a, SearchResult b) {
        return a.path().equals(b.path())
            && a.startLine() <= b.endLine()
            && b.startLine() <= a.endLine();
    }
}