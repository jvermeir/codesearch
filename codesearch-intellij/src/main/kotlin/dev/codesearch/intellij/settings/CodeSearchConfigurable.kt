package dev.codesearch.intellij.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import javax.swing.*

class CodeSearchConfigurable(private val project: Project) : Configurable {
    private val settings = PluginSettingsImpl.getInstance(project)

    private val jarPathField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            "Select codesearch JAR",
            "Select the codesearch.jar file",
            project,
            FileChooserDescriptorFactory.createSingleFileDescriptor("jar")
        )
        text = settings.codesearchJarPath
    }

    private val indexPathField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            "Select Index Directory",
            "Select the Lucene index directory",
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
        )
        text = settings.indexPath
    }

    private val topKSpinner = JSpinner(SpinnerNumberModel(settings.topK, 1, 100, 1))
    private val thresholdSlider = JSlider(0, 100, settings.threshold).apply {
        minorTickSpacing = 5
        majorTickSpacing = 10
        paintTicks = true
    }
    private val maxPerFileSpinner = JSpinner(SpinnerNumberModel(settings.maxPerFile, 1, 10, 1))
    private val docWeightSlider = JSlider(0, 100, (settings.docWeight * 100).toInt()).apply {
        minorTickSpacing = 5
        majorTickSpacing = 10
        paintTicks = true
    }
    private val timeoutSpinner = JSpinner(SpinnerNumberModel(settings.searchTimeout, 5, 120, 5))
    private val testConnectionButton = JButton("Test Connection")
    private val statusLabel = JLabel()

    init {
        testConnectionButton.addActionListener {
            testConnection()
        }
    }

    override fun getDisplayName(): String = "CodeSearch"

    override fun getHelpTopic(): String? = null

    override fun createComponent(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

        panel.add(JBLabel("CodeSearch JAR Path:"))
        panel.add(jarPathField)
        panel.add(Box.createVerticalStrut(5))

        panel.add(JBLabel("Index Directory:"))
        panel.add(indexPathField)
        panel.add(Box.createVerticalStrut(10))

        panel.add(JBLabel("Default Top K Results:"))
        panel.add(topKSpinner)
        panel.add(Box.createVerticalStrut(10))

        panel.add(JBLabel("Default Threshold (0-100):"))
        panel.add(thresholdSlider)
        panel.add(Box.createVerticalStrut(10))

        panel.add(JBLabel("Max Results Per File:"))
        panel.add(maxPerFileSpinner)
        panel.add(Box.createVerticalStrut(10))

        panel.add(JBLabel("Doc Weight (0.0-1.0):"))
        panel.add(docWeightSlider)
        panel.add(Box.createVerticalStrut(10))

        panel.add(JBLabel("Search Timeout (seconds):"))
        panel.add(timeoutSpinner)
        panel.add(Box.createVerticalStrut(10))

        val testPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(testConnectionButton)
            add(Box.createHorizontalStrut(10))
            add(statusLabel)
        }
        panel.add(testPanel)

        panel.add(Box.createVerticalGlue())
        return panel
    }

    override fun isModified(): Boolean {
        return jarPathField.text != settings.codesearchJarPath ||
                indexPathField.text != settings.indexPath ||
                (topKSpinner.value as Number).toInt() != settings.topK ||
                thresholdSlider.value != settings.threshold ||
                (maxPerFileSpinner.value as Number).toInt() != settings.maxPerFile ||
                docWeightSlider.value != (settings.docWeight * 100).toInt() ||
                (timeoutSpinner.value as Number).toInt() != settings.searchTimeout
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        // Validate JAR path
        val jarPath = jarPathField.text
        if (jarPath.isNotBlank()) {
            val jarError = validateJarPath(jarPath)
            if (jarError != null) {
                throw ConfigurationException(jarError)
            }
        }

        // Validate index path
        val indexPath = indexPathField.text
        if (indexPath.isNotBlank()) {
            val indexError = validateIndexPath(indexPath)
            if (indexError != null) {
                throw ConfigurationException(indexError)
            }
        }

        settings.codesearchJarPath = jarPath
        settings.indexPath = indexPath
        settings.topK = (topKSpinner.value as Number).toInt()
        settings.threshold = thresholdSlider.value
        settings.maxPerFile = (maxPerFileSpinner.value as Number).toInt()
        settings.docWeight = docWeightSlider.value / 100.0
        settings.searchTimeout = (timeoutSpinner.value as Number).toInt()
    }

    override fun reset() {
        jarPathField.text = settings.codesearchJarPath
        indexPathField.text = settings.indexPath
        topKSpinner.value = settings.topK
        thresholdSlider.value = settings.threshold
        maxPerFileSpinner.value = settings.maxPerFile
        docWeightSlider.value = (settings.docWeight * 100).toInt()
        timeoutSpinner.value = settings.searchTimeout
        statusLabel.text = ""
    }

    private fun testConnection() {
        statusLabel.text = "Testing..."
        statusLabel.foreground = java.awt.Color.BLACK

        try {
            val searchService = SearchService(project)
            val result = searchService.search("test", topK = 1, threshold = 0)

            if (result.isSuccess) {
                statusLabel.text = "✓ Connection successful"
                statusLabel.foreground = java.awt.Color.GREEN.darker()
            } else {
                statusLabel.text = "✗ " + (result.exceptionOrNull()?.message ?: "Unknown error")
                statusLabel.foreground = java.awt.Color.RED
            }
        } catch (e: Exception) {
            statusLabel.text = "✗ " + (e.message ?: "Unknown error")
            statusLabel.foreground = java.awt.Color.RED
        }
    }

    private fun validateJarPath(path: String): String? {
        return if (path.isNotBlank()) {
            settings.validateJarPath()
        } else {
            null
        }
    }

    private fun validateIndexPath(path: String): String? {
        return if (path.isNotBlank()) {
            settings.validateIndexPath()
        } else {
            null
        }
    }
}

class PluginSettingsListener : com.intellij.openapi.application.ApplicationActivationListener {
    override fun applicationActivated(ideFrame: com.intellij.openapi.wm.IdeFrame) {
        // Validate settings on IDE activation
    }
}
