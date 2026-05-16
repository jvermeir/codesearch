package dev.codesearch.intellij.search

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.codesearch.intellij.settings.PluginSettingsImpl
import java.io.File
import java.util.concurrent.TimeUnit

class SearchService(private val project: Project) {
    private val logger = Logger.getInstance(SearchService::class.java)
    private val settings = PluginSettingsImpl.getInstance(project)

    fun search(
        query: String,
        topK: Int = settings.topK,
        threshold: Int = settings.threshold,
        maxPerFile: Int = settings.maxPerFile,
        docWeight: Double = settings.docWeight
    ): Result<List<SearchResult>> {
        if (query.isBlank()) {
            return Result.failure(IllegalArgumentException("Query cannot be empty"))
        }

        val jarValidation = settings.validateJarPath()
        if (jarValidation != null) {
            return Result.failure(IllegalStateException(jarValidation))
        }

        val indexValidation = settings.validateIndexPath()
        if (indexValidation != null) {
            return Result.failure(IllegalStateException(indexValidation))
        }

        return try {
            val results = executeSearch(query, topK, threshold, maxPerFile, docWeight)
            Result.success(results)
        } catch (e: Exception) {
            logger.warn("Search failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun executeSearch(
        query: String,
        topK: Int,
        threshold: Int,
        maxPerFile: Int,
        docWeight: Double
    ): List<SearchResult> {
        val javaHome = System.getProperty("java.home")
        val javaExe = File(javaHome, "bin/java").absolutePath

        val command = listOf(
            javaExe,
            "-jar", settings.codesearchJarPath,
            "--db", settings.indexPath,
            "search", query,
            "--top", topK.toString(),
            "--threshold", threshold.toString(),
            "--max-per-file", maxPerFile.toString(),
            "--doc-weight", docWeight.toString(),
            "--context"
        )

        val process = ProcessBuilder(command)
            .redirectErrorStream(false)
            .start()

        val completed = process.waitFor(settings.searchTimeout.toLong(), TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            throw TimeoutException("Search timed out after ${settings.searchTimeout} seconds")
        }

        val exitCode = process.exitValue()
        if (exitCode != 0) {
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            throw RuntimeException("codesearch exited with code $exitCode: $stderr")
        }

        val output = process.inputStream.bufferedReader().use { it.readText() }
        return parseResults(output)
    }

    private fun parseResults(output: String): List<SearchResult> {
        if (output.isBlank()) {
            return emptyList()
        }

        val results = mutableListOf<SearchResult>()
        var currentPath: String? = null
        val contentBuilder = StringBuilder()
        var startLine = 0
        var endLine = 0
        var score = 0.0

        for (line in output.lines()) {
            // Parse line format: "path:start_line:end_line:score:content"
            // This is a simplified parser; adjust based on actual output format
            val parts = line.split(":", limit = 5)
            if (parts.size >= 5) {
                try {
                    currentPath = parts[0]
                    startLine = parts[1].toInt()
                    endLine = parts[2].toInt()
                    score = parts[3].toDoubleOrNull() ?: 0.0
                    contentBuilder.clear()
                    contentBuilder.append(parts[4])

                    val fileType = if (currentPath.endsWith(".md") || currentPath.endsWith(".rst")) "doc" else "code"
                    results.add(
                        SearchResult(
                            path = currentPath,
                            startLine = startLine,
                            endLine = endLine,
                            score = score,
                            content = contentBuilder.toString().trim(),
                            fileType = fileType
                        )
                    )
                } catch (e: NumberFormatException) {
                    // Skip malformed lines
                    logger.debug("Failed to parse result line: $line")
                }
            }
        }

        return results.sortedByDescending { it.score }
    }
}

class TimeoutException(message: String) : RuntimeException(message)
