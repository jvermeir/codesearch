package dev.codesearch.intellij.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.codesearch.intellij.search.SearchService
import dev.codesearch.intellij.ui.ResultsPanel
import dev.codesearch.intellij.ui.SearchDialog

class CodeSearchAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return

        // Get selected text as default query
        val editor = e.getData(CommonDataKeys.EDITOR)
        val defaultQuery = editor?.selectionModel?.selectedText ?: ""

        var query = ""
        var threshold = 50
        var maxPerFile = 2
        var docWeight = 0.75

        val dialog = SearchDialog(project) { q, t, m, d ->
            query = q
            threshold = t
            maxPerFile = m
            docWeight = d
        }

        if (!dialog.showAndGet()) {
            return
        }

        // Run search in background
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                performSearch(project, query, threshold, maxPerFile, docWeight)
            },
            "CodeSearch: $query",
            true,
            project
        )
    }

    private fun performSearch(
        project: Project,
        query: String,
        threshold: Int,
        maxPerFile: Int,
        docWeight: Double
    ) {
        val searchService = SearchService(project)
        val result = searchService.search(query, threshold = threshold, maxPerFile = maxPerFile, docWeight = docWeight)

        result.onSuccess { results ->
            ResultsPanel.showResults(project, results, query)
        }

        result.onFailure { error ->
            Messages.showErrorDialog(
                project,
                error.message ?: "Unknown error occurred during search",
                "CodeSearch Error"
            )
        }
    }
}
