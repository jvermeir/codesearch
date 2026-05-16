package dev.codesearch.intellij.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import dev.codesearch.intellij.settings.PluginSettingsImpl
import javax.swing.*
import javax.swing.event.ChangeEvent
import java.util.*

class SearchDialog(
    project: Project?,
    private val onSearch: (query: String, threshold: Int, maxPerFile: Int, docWeight: Double) -> Unit
) : DialogWrapper(project) {
    private val settings = project?.let { PluginSettingsImpl.getInstance(it) }
    private val queryField = JBTextField(20).apply {
        toolTipText = "Enter search query"
        columns = 40
    }
    private val thresholdSlider = JSlider(0, 100, settings?.threshold ?: 50).apply {
        minorTickSpacing = 5
        majorTickSpacing = 10
        paintTicks = true
        val labels: Hashtable<Int, JLabel> = Hashtable()
        labels[0] = JLabel("0")
        labels[50] = JLabel("50")
        labels[100] = JLabel("100")
        labelTable = labels
        paintLabels = true
    }
    private val thresholdLabel = JLabel("Threshold: ${thresholdSlider.value}")
    private val maxPerFileSpinner = JSpinner(SpinnerNumberModel(settings?.maxPerFile ?: 2, 1, 10, 1))
    private val docWeightSlider = JSlider(0, 100, (settings?.docWeight ?: 0.75 * 100).toInt()).apply {
        minorTickSpacing = 5
        majorTickSpacing = 10
        paintTicks = true
    }
    private val docWeightLabel = JLabel(String.format("Doc weight: %.2f", docWeightSlider.value / 100.0))
    private val includeDocFilesCheckbox = JCheckBox("Include doc files", docWeightSlider.value > 0)

    init {
        title = "CodeSearch"
        setOKButtonText("Search")
        init()

        thresholdSlider.addChangeListener { _: ChangeEvent ->
            thresholdLabel.text = "Threshold: ${thresholdSlider.value}"
        }

        docWeightSlider.addChangeListener { _: ChangeEvent ->
            val weight = docWeightSlider.value / 100.0
            docWeightLabel.text = String.format("Doc weight: %.2f", weight)
            includeDocFilesCheckbox.isSelected = weight > 0
        }

        includeDocFilesCheckbox.addChangeListener {
            if (includeDocFilesCheckbox.isSelected && docWeightSlider.value == 0) {
                docWeightSlider.value = 75
            } else if (!includeDocFilesCheckbox.isSelected && docWeightSlider.value > 0) {
                docWeightSlider.value = 0
            }
        }

        queryField.requestFocus()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

        val queryPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JBLabel("Query:"))
            add(Box.createHorizontalStrut(10))
            add(queryField)
        }

        val thresholdPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(thresholdLabel)
            add(thresholdSlider)
        }

        val maxPerFilePanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JBLabel("Max results per file:"))
            add(Box.createHorizontalStrut(10))
            add(maxPerFileSpinner)
        }

        val docWeightPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(docWeightLabel)
            add(docWeightSlider)
            add(includeDocFilesCheckbox)
        }

        panel.add(queryPanel)
        panel.add(Box.createVerticalStrut(10))
        panel.add(thresholdPanel)
        panel.add(Box.createVerticalStrut(10))
        panel.add(maxPerFilePanel)
        panel.add(Box.createVerticalStrut(10))
        panel.add(docWeightPanel)

        return panel
    }

    override fun doOKAction() {
        val query = queryField.text.trim()
        if (query.isEmpty()) {
            setErrorText("Query cannot be empty")
            return
        }

        val threshold = thresholdSlider.value
        val maxPerFile = (maxPerFileSpinner.value as Number).toInt()
        val docWeight = docWeightSlider.value / 100.0

        onSearch(query, threshold, maxPerFile, docWeight)
        super.doOKAction()
    }

    fun getQuery(): String = queryField.text.trim()
}
