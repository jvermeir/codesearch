package dev.codesearch.intellij.ui

import com.intellij.find.findUsages.PsiElement2UsageTargetAdapter
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.usages.*
import com.intellij.util.Processor
import dev.codesearch.intellij.search.SearchResult
import java.io.File

class CodeSearchUsage(
    private val project: Project,
    private val result: SearchResult
) : Usage, DataProvider {
    private val file by lazy {
        LocalFileSystem.getInstance().findFileByIoFile(File(result.path))
    }

    override fun getPresentation(): UsagePresentation {
        return object : UsagePresentation {
            override fun getPlainText(): String = "${result.path}:${result.startLine}"
            override fun getTooltipText(): String = "${result.path}:${result.startLine}:${result.endLine} (score: %.2f)".format(result.score)
            override fun getTextChunks(): Array<TextChunk> = arrayOf(
                TextChunk(UsageTreeColors.USAGES_TREE_FOREGROUND_ATTRIBUTES, "${result.path}:${result.startLine}"),
                TextChunk(UsageTreeColors.USAGES_TREE_FOREGROUND_ATTRIBUTES, " "),
                TextChunk(UsageTreeColors.USAGES_TREE_FOREGROUND_ATTRIBUTES, result.content.take(60).replace("\n", " "))
            )
            override fun isValid(): Boolean = file?.exists() ?: false
            override fun navigate(b: Boolean) { openFile() }
            override fun getIcon(): javax.swing.Icon? = null
        }
    }

    override fun getLocation(): UsageTarget? {
        return file?.let { vf ->
            try {
                val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return@let null
                PsiElement2UsageTargetAdapter(psiFile as PsiElement)
            } catch (e: Exception) {
                null
            }
        }
    }

    override fun getNavigationRange(): com.intellij.openapi.util.TextRange? {
        return null
    }

    override fun isValid(): Boolean = file?.exists() ?: false

    override fun isReadOnly(): Boolean = true

    override fun isCachedValueUpToDate(): Boolean = true

    override fun getData(dataId: String): Any? = null

    fun openFile() {
        file?.let { vf ->
            val descriptor = OpenFileDescriptor(project, vf, result.startLine - 1, 0)
            descriptor.navigate(true)
        }
    }
}

object ResultsPanel {
    fun showResults(project: Project, results: List<SearchResult>, query: String) {
        val usages = results.map { CodeSearchUsage(project, it) }
        if (usages.isEmpty()) {
            com.intellij.openapi.ui.Messages.showInfoMessage(
                project,
                "No results found for: $query",
                "CodeSearch"
            )
            return
        }

        val usageTarget = UsageTarget {
            "CodeSearch: $query (${usages.size} results)"
        }

        val usageViewPresentation = UsageViewPresentation().apply {
            setTabText("CodeSearch: $query")
            setTabAlertWord("search")
            setShowReadOnlyStatusAsWarning(false)
        }

        UsageViewManager.getInstance(project).searchAndShowUsages(
            arrayOf(usageTarget),
            Processor { usage ->
                usages.contains(usage as? CodeSearchUsage)
            },
            true,
            true,
            usageViewPresentation,
            null
        )
    }
}

private class UsageTarget(private val labelProvider: () -> String) : com.intellij.usages.UsageTarget {
    override fun getName(): String = labelProvider()

    override fun getPresentation(): com.intellij.usages.UsagePresentation =
        object : UsagePresentation {
            override fun getPlainText(): String = labelProvider()
            override fun getTooltipText(): String = labelProvider()
            override fun getTextChunks(): Array<TextChunk> = arrayOf(
                TextChunk(UsageTreeColors.USAGES_TREE_FOREGROUND_ATTRIBUTES, labelProvider())
            )
            override fun isValid(): Boolean = true
            override fun navigate(requestFocus: Boolean) {}
            override fun getIcon(): javax.swing.Icon? = null
        }

    override fun isValid(): Boolean = true

    override fun isReadOnly(): Boolean = true

    override fun isShowing(): Boolean = true

    override fun update() {}
}
