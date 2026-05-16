package dev.codesearch.intellij.search

data class SearchResult(
    val path: String,
    val startLine: Int,
    val endLine: Int,
    val score: Double,
    val content: String,
    val fileType: String = "code"
)
