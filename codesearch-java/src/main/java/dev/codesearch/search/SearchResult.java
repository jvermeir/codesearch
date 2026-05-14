package dev.codesearch.search;

public record SearchResult(
    String path,
    int    startLine,
    int    endLine,
    String content,
    double score,
    String fileType
) {}
