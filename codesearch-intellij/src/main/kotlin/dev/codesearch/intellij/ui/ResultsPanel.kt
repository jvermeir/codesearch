package dev.codesearch.intellij.ui

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import dev.codesearch.intellij.search.SearchResult
import java.io.File

object ResultsPanel {
    fun showResults(project: Project, results: List<SearchResult>, query: String) {
        if (results.isEmpty()) {
            com.intellij.openapi.ui.Messages.showInfoMessage(
                project,
                "No results found for: $query",
                "CodeSearch"
            )
            return
        }

        val result = results.first()
        val file = LocalFileSystem.getInstance().findFileByIoFile(File(result.path))

        file?.let { vf ->
            val descriptor = OpenFileDescriptor(project, vf, result.startLine - 1, 0)
            descriptor.navigate(true)
        }

        com.intellij.openapi.ui.Messages.showInfoMessage(
            project,
            "Found ${results.size} results for: $query\nOpened first result: ${result.path}",
            "CodeSearch"
        )
    }
}
