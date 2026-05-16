package dev.codesearch.intellij.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import java.io.File

interface PluginSettings {
    var codesearchJarPath: String
    var indexPath: String
    var topK: Int
    var threshold: Int
    var maxPerFile: Int
    var docWeight: Double
    var searchTimeout: Int

    fun validateJarPath(): String?
    fun validateIndexPath(): String?
}

@Service(Service.Level.PROJECT)
@State(
    name = "CodeSearchSettings",
    storages = [Storage("codeSearchSettings.xml")]
)
class PluginSettingsImpl : PluginSettings, PersistentStateComponent<PluginSettingsState> {
    private var state = PluginSettingsState()

    override var codesearchJarPath: String
        get() = state.codesearchJarPath
        set(value) { state.codesearchJarPath = value }

    override var indexPath: String
        get() = state.indexPath
        set(value) { state.indexPath = value }

    override var topK: Int
        get() = state.topK
        set(value) { state.topK = value }

    override var threshold: Int
        get() = state.threshold.coerceIn(0, 100)
        set(value) { state.threshold = value.coerceIn(0, 100) }

    override var maxPerFile: Int
        get() = state.maxPerFile.coerceIn(1, 10)
        set(value) { state.maxPerFile = value.coerceIn(1, 10) }

    override var docWeight: Double
        get() = state.docWeight.coerceIn(0.0, 1.0)
        set(value) { state.docWeight = value.coerceIn(0.0, 1.0) }

    override var searchTimeout: Int
        get() = state.searchTimeout.coerceIn(5, 120)
        set(value) { state.searchTimeout = value.coerceIn(5, 120) }

    override fun getState(): PluginSettingsState = state

    override fun loadState(state: PluginSettingsState) {
        this.state = state
    }

    override fun validateJarPath(): String? {
        val path = codesearchJarPath.takeIf { it.isNotBlank() } ?: return "JAR path is empty"
        val file = File(path)
        return when {
            !file.exists() -> "JAR file not found: $path"
            !file.isFile -> "Not a file: $path"
            !file.canRead() -> "Cannot read: $path"
            !path.endsWith(".jar") -> "Not a JAR file: $path"
            else -> null
        }
    }

    override fun validateIndexPath(): String? {
        val path = indexPath.takeIf { it.isNotBlank() } ?: return "Index path is empty"
        val dir = File(path)
        return when {
            !dir.exists() -> "Index directory not found: $path"
            !dir.isDirectory -> "Not a directory: $path"
            !dir.canRead() -> "Cannot read: $path"
            dir.listFiles { f -> f.name.startsWith("segments_") }?.isEmpty() != false -> "Not a valid Lucene index (no segment files): $path"
            else -> null
        }
    }

    companion object {
        fun getInstance(project: Project): PluginSettings =
            project.getService(PluginSettingsImpl::class.java) as PluginSettings
    }
}

data class PluginSettingsState(
    var codesearchJarPath: String = expandPath("~/.codesearch/codesearch.jar"),
    var indexPath: String = expandPath("~/.codesearch/lucene-index/"),
    var topK: Int = 10,
    var threshold: Int = 50,
    var maxPerFile: Int = 2,
    var docWeight: Double = 0.75,
    var searchTimeout: Int = 30
)

private fun expandPath(path: String): String {
    return if (path.startsWith("~")) {
        System.getProperty("user.home") + path.substring(1)
    } else {
        path
    }
}
