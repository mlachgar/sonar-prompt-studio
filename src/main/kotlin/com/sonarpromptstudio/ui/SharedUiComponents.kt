package com.sonarpromptstudio.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

fun toolbarPanel(vararg components: JComponent): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 8)).apply {
    components.forEach(::add)
}

fun sectionPanel(title: String, content: JComponent): JPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
    border = JBUI.Borders.empty(8)
    add(JBLabel(title), BorderLayout.NORTH)
    add(JBScrollPane(content), BorderLayout.CENTER)
}

fun actionButton(text: String, onClick: () -> Unit): JButton = JButton(text).apply {
    addActionListener { onClick() }
}

class MultiSelectComboBox(
    private val placeholder: String,
) : JComboBox<MultiSelectOption>() {
    private var updating = false
    private var selectionListener: ((Set<String>) -> Unit)? = null

    init {
        prototypeDisplayValue = MultiSelectOption("W".repeat(18))
        maximumRowCount = 12
        renderer = ListCellRenderer { list: JList<out MultiSelectOption>, value: MultiSelectOption?, index: Int, isSelected: Boolean, _: Boolean ->
            if (index < 0) {
                JBLabel(summaryText()).apply {
                    border = JBUI.Borders.empty(2, 6)
                    isOpaque = true
                    background = list.background
                    foreground = list.foreground
                }
            } else {
                JCheckBox().apply {
                    border = JBUI.Borders.empty(2, 4)
                    isOpaque = true
                    background = if (isSelected) list.selectionBackground else list.background
                    foreground = if (isSelected) list.selectionForeground else list.foreground
                    text = value?.label.orEmpty()
                    setSelected(value?.selected == true)
                }
            }
        }
        addActionListener {
            if (updating) return@addActionListener
            val option = selectedItem as? MultiSelectOption ?: return@addActionListener
            option.selected = !option.selected
            updating = true
            selectedItem = null
            repaint()
            updating = false
            selectionListener?.invoke(selectedValues())
        }
    }

    fun setOptions(options: List<String>, selected: Set<String>) {
        updating = true
        removeAllItems()
        options.forEach { addItem(MultiSelectOption(it, selected.any { picked -> picked.equals(it, ignoreCase = true) })) }
        selectedItem = null
        repaint()
        updating = false
    }

    fun selectedValues(): Set<String> =
        (0 until itemCount).mapNotNull { getItemAt(it) }
            .filter { it.selected }
            .mapTo(linkedSetOf()) { it.label }

    fun onSelectionChanged(listener: (Set<String>) -> Unit) {
        selectionListener = listener
    }

    private fun summaryText(): String {
        val selected = selectedValues()
        return if (selected.isEmpty()) placeholder else selected.joinToString(", ")
    }
}

data class MultiSelectOption(
    val label: String,
    var selected: Boolean = false,
)
