package com.sonarpromptstudio.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.sonarpromptstudio.model.ConnectionProfile
import com.sonarpromptstudio.model.CoverageFinding
import com.sonarpromptstudio.model.DiscoveredSonarProject
import com.sonarpromptstudio.model.DuplicationFinding
import com.sonarpromptstudio.model.HotspotFinding
import com.sonarpromptstudio.model.IssueFinding
import com.sonarpromptstudio.model.PromptInput
import com.sonarpromptstudio.model.PromptStyle
import com.sonarpromptstudio.model.PromptTarget
import com.sonarpromptstudio.model.WorkspaceMode
import com.sonarpromptstudio.prompt.PromptBuilder
import com.sonarpromptstudio.service.DiscoveredProjectService
import com.sonarpromptstudio.service.FindingsService
import com.sonarpromptstudio.service.SelectionAndFilterLogic
import com.sonarpromptstudio.service.SonarSettingsService
import com.sonarpromptstudio.service.UiRefreshService
import com.sonarpromptstudio.service.WorkspaceStateService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.time.Instant
import java.nio.file.Paths
import javax.swing.BorderFactory
import javax.swing.DefaultCellEditor
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.JViewport
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

class WorkspacePanel(private val project: Project) : JBPanel<WorkspacePanel>(BorderLayout()) {
    private val settings = SonarSettingsService.getInstance()
    private val findings = FindingsService.getInstance(project)
    private val discovered = DiscoveredProjectService.getInstance(project)
    private val state = WorkspaceStateService.getInstance(project)
    private val refreshService = UiRefreshService.getInstance(project)

    private val tabs = JBTabbedPane()
    private val profileSelector = JComboBox<String>()
    private val projectSelector = JComboBox<ProjectOption>()
    private val loadingLabel = JBLabel("Ready")
    private val selectionSummaryLabel = JBLabel("")
    private val promptDirtyIconLabel = JBLabel(AllIcons.General.Warning).apply {
        toolTipText = "Prompt out of date. Re-generate to reflect the latest selection or findings."
        isVisible = false
    }
    private val promptText = JBTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
        isEditable = false
        background = JBColor(Color(0xFAFAFC), Color(0x25272B))
        border = JBUI.Borders.empty(12)
    }
    private val targetSelector = JComboBox(PromptTarget.entries.toTypedArray())
    private val styleSelector = JComboBox(PromptStyle.entries.toTypedArray())

    private val issueTypeFilter = MultiSelectComboBox("All types")
    private val issueSeverityFilter = MultiSelectComboBox("All severities")
    private val issueStatusFilter = MultiSelectComboBox("All statuses")
    private val issueRuleField = JTextField(10)
    private val issueFileField = JTextField(10)

    private val issuesModel = SelectionTableModel(arrayOf("Select", "Severity", "Type", "Rule", "Component", "Line", "Status", "Effort", "Tags", "Message"))
    private val coverageModel = SelectionTableModel(arrayOf("Select", "Path", "Coverage", "Line %", "Branch %", "Uncovered Lines", "Uncovered Branches"))
    private val duplicationModel = SelectionTableModel(arrayOf("Select", "Path", "Duplication %", "Duplicated Lines", "Duplicated Blocks"))
    private val hotspotsModel = SelectionTableModel(arrayOf("Select", "Component", "Line", "Status", "Probability", "Message"))

    private val issuesTable = selectionTable(issuesModel, TableLayout.ISSUES)
    private val coverageTable = selectionTable(coverageModel, TableLayout.COVERAGE)
    private val duplicationTable = selectionTable(duplicationModel, TableLayout.DUPLICATION)
    private val hotspotsTable = selectionTable(hotspotsModel, TableLayout.HOTSPOTS)
    private var visibleIssueKeys: List<String> = emptyList()
    private var visibleCoverageKeys: List<String> = emptyList()
    private var visibleDuplicationKeys: List<String> = emptyList()
    private var visibleHotspotKeys: List<String> = emptyList()
    private var syncingTableModels: Boolean = false

    init {
        add(buildToolbar(), BorderLayout.NORTH)
        add(buildCenter(), BorderLayout.CENTER)
        refreshService.subscribe { reload() }
        registerInstance(project, this)
        wireSelectors()
        wireIssueFilters()
        reload()
    }

    private fun buildToolbar(): JPanel = toolbarPanel(
        JBLabel("Profile"),
        profileSelector,
        JBLabel("Project"),
        projectSelector,
        actionButton("Refresh") { refreshFindings() },
        actionButton("Configure") { UiFacade.openConfiguration(project) },
        loadingLabel,
    ).also {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLineBottom(JBColor.border()),
            JBUI.Borders.empty(10, 14),
        )
        background = JBColor(Color(0xFFFFFF), Color(0x1F2125))
        targetSelector.selectedItem = state.currentPromptTarget
        styleSelector.selectedItem = state.currentPromptStyle
        targetSelector.addActionListener {
            (targetSelector.selectedItem as? PromptTarget)?.let {
                state.currentPromptTarget = it
                settings.setDefaultPromptTarget(it)
            }
        }
        styleSelector.addActionListener {
            (styleSelector.selectedItem as? PromptStyle)?.let {
                state.currentPromptStyle = it
                settings.setDefaultPromptStyle(it)
            }
        }
    }

    private fun wireSelectors() {
        profileSelector.addActionListener {
            val selectedName = profileSelector.selectedItem as? String ?: return@addActionListener
            settings.profiles().firstOrNull { it.name == selectedName }?.let {
                settings.setActiveProfileId(it.id)
                refreshService.fire()
            }
        }
        projectSelector.addActionListener {
            val selected = projectSelector.selectedItem as? ProjectOption ?: return@addActionListener
            discovered.allProjects().firstOrNull { it.path == selected.path }?.let {
                discovered.setActiveProject(it.path)
                refreshService.fire()
            }
        }
    }

    private fun buildCenter(): JPanel = Splitter(false, 0.57f).apply {
        border = JBUI.Borders.empty(8)
        firstComponent = buildSelectionPane()
        secondComponent = buildPromptPane()
    }

    private fun buildSelectionPane(): JPanel = cardPanel().apply {
        add(
            JBPanel<JBPanel<*>>(BorderLayout()).apply {
                add(sectionTitle("Item Selection", "Choose Sonar items to include in the generated prompt."), BorderLayout.NORTH)
            },
            BorderLayout.NORTH,
        )
        add(buildModesPanel(), BorderLayout.CENTER)
    }

    private fun buildModesPanel(): JPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        tabs.add("Issues", buildIssuesPanel())
        tabs.add("Coverage", buildDataPanel(coverageTable, WorkspaceMode.COVERAGE))
        tabs.add("Duplication", buildDataPanel(duplicationTable, WorkspaceMode.DUPLICATION))
        tabs.add("Security Hotspots", buildDataPanel(hotspotsTable, WorkspaceMode.HOTSPOTS))
        tabs.addChangeListener {
            state.currentMode = WorkspaceMode.entries[tabs.selectedIndex]
        }
        add(tabs, BorderLayout.CENTER)
    }

    private fun buildIssuesPanel(): JPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(
            toolbarPanel(
                JLabel("Type"), issueTypeFilter,
                JLabel("Severity"), issueSeverityFilter,
                JLabel("Status"), issueStatusFilter,
                JLabel("Rule"), issueRuleField,
                JLabel("File"), issueFileField,
                actionButton("Apply Filters") { reloadIssues() },
                actionButton("Reset Filters") { resetIssueFilters() },
                actionButton("Select Visible") { selectVisible(WorkspaceMode.ISSUES) },
                actionButton("Unselect Visible") { unselectVisible(WorkspaceMode.ISSUES) },
            ).also {
                it.border = JBUI.Borders.emptyBottom(8)
                it.background = background
            },
            BorderLayout.NORTH,
        )
        add(styledScrollPane(issuesTable), BorderLayout.CENTER)
    }

    private fun buildDataPanel(table: JTable, mode: WorkspaceMode): JPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(
            toolbarPanel(
                actionButton("Select Visible") { selectVisible(mode) },
                actionButton("Unselect Visible") { unselectVisible(mode) },
            ).also {
                it.border = JBUI.Borders.emptyBottom(8)
                it.background = background
            },
            BorderLayout.NORTH,
        )
        add(styledScrollPane(table), BorderLayout.CENTER)
    }

    private fun buildPromptPane(): JPanel = cardPanel().apply {
        add(
            JBPanel<JBPanel<*>>(BorderLayout()).apply {
                add(sectionTitle("Prompt Generation", "Tune the target and style, then generate and copy the prompt."), BorderLayout.NORTH)
                add(
                    toolbarPanel(
                        JBLabel("Target"),
                        targetSelector,
                        JBLabel("Style"),
                        styleSelector,
                        actionButton("Generate Prompt") { generatePrompt() },
                    ),
                    BorderLayout.SOUTH,
                )
            },
            BorderLayout.NORTH,
        )
        add(
            JBPanel<JBPanel<*>>(BorderLayout()).apply {
                border = JBUI.Borders.emptyTop(8)
                add(promptHeader(), BorderLayout.NORTH)
                add(
                    JBPanel<JBPanel<*>>(BorderLayout()).apply {
                        isOpaque = false
                        add(styledScrollPane(promptText), BorderLayout.CENTER)
                    },
                    BorderLayout.CENTER,
                )
            },
            BorderLayout.CENTER,
        )
    }

    private fun reload() {
        val profiles = settings.profiles()
        resetCombo(profileSelector, profiles.map(ConnectionProfile::name), profiles.firstOrNull { it.id == settings.activeProfileId() }?.name)
        val discoveredProjects = discovered.rescan()
        resetProjectCombo(discoveredProjects, discovered.activeProject()?.path)
        val snapshot = findings.latestSnapshot()
        state.lastSnapshot = snapshot
        state.markPromptDirtyIfNeeded()
        loadingLabel.text = if (state.loading) "Loading..." else if (findings.activeProfile() == null || discovered.activeProject() == null) "Configure a profile and project" else "Ready"
        updateTabTitles(snapshot)
        reloadIssues()
        reloadCoverage(snapshot.coverage)
        reloadDuplication(snapshot.duplication)
        reloadHotspots(snapshot.hotspots)
        updateSelectionSummary()
        updatePromptDirtyMarker()
    }

    private fun refreshFindings() {
        loadingLabel.text = "Loading..."
        findings.refresh {
            loadingLabel.text = if (it.isSuccess) "Loaded" else "Failed"
            refreshService.fire()
        }
    }

    private fun reloadIssues() {
        issueTypeFilter.setOptions(
            state.lastSnapshot.issues.map(IssueFinding::type).distinct().sorted(),
            state.issueFilters.types,
        )
        issueSeverityFilter.setOptions(
            state.lastSnapshot.issues.map(IssueFinding::severity).distinct().sorted(),
            state.issueFilters.severities,
        )
        issueStatusFilter.setOptions(
            state.lastSnapshot.issues.map(IssueFinding::status).distinct().sorted(),
            state.issueFilters.statuses,
        )
        state.issueFilters.types = issueTypeFilter.selectedValues().toMutableSet()
        state.issueFilters.severities = issueSeverityFilter.selectedValues().toMutableSet()
        state.issueFilters.statuses = issueStatusFilter.selectedValues().toMutableSet()
        state.issueFilters.ruleSubstring = issueRuleField.text
        state.issueFilters.fileSubstring = issueFileField.text
        val visible = SelectionAndFilterLogic.filterIssues(state.lastSnapshot.issues, state.issueFilters)
        visibleIssueKeys = visible.map(IssueFinding::key)
        refill(
            issuesModel,
            visible.map {
                arrayOf(
                    state.selections.getValue(WorkspaceMode.ISSUES).selectedKeys.contains(it.key),
                    it.severity,
                    it.type,
                    it.rule,
                    it.component,
                    it.line ?: "",
                    it.status,
                    it.effort ?: "",
                    it.tags.joinToString(","),
                    it.message,
                )
            },
        )
    }

    private fun wireIssueFilters() {
        issueTypeFilter.onSelectionChanged { state.issueFilters.types = it.toMutableSet() }
        issueSeverityFilter.onSelectionChanged { state.issueFilters.severities = it.toMutableSet() }
        issueStatusFilter.onSelectionChanged { state.issueFilters.statuses = it.toMutableSet() }
    }

    private fun resetIssueFilters() {
        state.issueFilters.types.clear()
        state.issueFilters.severities.clear()
        state.issueFilters.statuses.clear()
        state.issueFilters.ruleSubstring = ""
        state.issueFilters.fileSubstring = ""
        issueRuleField.text = ""
        issueFileField.text = ""
        issueTypeFilter.setOptions(
            state.lastSnapshot.issues.map(IssueFinding::type).distinct().sorted(),
            emptySet(),
        )
        issueSeverityFilter.setOptions(
            state.lastSnapshot.issues.map(IssueFinding::severity).distinct().sorted(),
            emptySet(),
        )
        issueStatusFilter.setOptions(
            state.lastSnapshot.issues.map(IssueFinding::status).distinct().sorted(),
            emptySet(),
        )
        reloadIssues()
    }

    private fun reloadCoverage(items: List<CoverageFinding>) {
        visibleCoverageKeys = items.map(CoverageFinding::key)
        refill(
            coverageModel,
            items.map {
                arrayOf(
                    state.selections.getValue(WorkspaceMode.COVERAGE).selectedKeys.contains(it.key),
                    it.path,
                    it.coverage ?: "",
                    it.lineCoverage ?: "",
                    it.branchCoverage ?: "",
                    it.uncoveredLines ?: "",
                    it.uncoveredBranches ?: "",
                )
            },
        )
    }

    private fun reloadDuplication(items: List<DuplicationFinding>) {
        visibleDuplicationKeys = items.map(DuplicationFinding::key)
        refill(
            duplicationModel,
            items.map {
                arrayOf(
                    state.selections.getValue(WorkspaceMode.DUPLICATION).selectedKeys.contains(it.key),
                    it.path,
                    it.duplication ?: "",
                    it.duplicatedLines ?: "",
                    it.duplicatedBlocks ?: "",
                )
            },
        )
    }

    private fun reloadHotspots(items: List<HotspotFinding>) {
        visibleHotspotKeys = items.map(HotspotFinding::key)
        refill(
            hotspotsModel,
            items.map {
                arrayOf(
                    state.selections.getValue(WorkspaceMode.HOTSPOTS).selectedKeys.contains(it.key),
                    it.component,
                    it.line ?: "",
                    it.status ?: "",
                    it.vulnerabilityProbability ?: "",
                    it.message,
                )
            },
        )
    }

    private fun selectVisible(mode: WorkspaceMode) {
        when (mode) {
            WorkspaceMode.ISSUES -> state.setSelection(mode, SelectionAndFilterLogic.selectVisibleIssues(SelectionAndFilterLogic.filterIssues(state.lastSnapshot.issues, state.issueFilters)))
            WorkspaceMode.COVERAGE -> state.setSelection(mode, SelectionAndFilterLogic.selectVisibleCoverage(state.lastSnapshot.coverage))
            WorkspaceMode.DUPLICATION -> state.setSelection(mode, SelectionAndFilterLogic.selectVisibleDuplication(state.lastSnapshot.duplication))
            WorkspaceMode.HOTSPOTS -> state.setSelection(mode, SelectionAndFilterLogic.selectVisibleHotspots(state.lastSnapshot.hotspots))
        }
        reload()
    }

    private fun unselectVisible(mode: WorkspaceMode) {
        val current = state.selections.getValue(mode).selectedKeys.toMutableSet()
        current.removeAll(
            when (mode) {
                WorkspaceMode.ISSUES -> visibleIssueKeys
                WorkspaceMode.COVERAGE -> visibleCoverageKeys
                WorkspaceMode.DUPLICATION -> visibleDuplicationKeys
                WorkspaceMode.HOTSPOTS -> visibleHotspotKeys
            },
        )
        state.setSelection(mode, current.toList())
        reload()
    }

    private fun generatePrompt() {
        syncSelectionsFromTables()
        val generated = PromptBuilder.build(
            PromptInput(
                target = state.currentPromptTarget,
                style = state.currentPromptStyle,
                source = state.currentMode,
                connectionMetadata = connectionMetadata(findings.activeProfile()),
                repositoryName = project.name,
                generatedAt = Instant.now(),
                selectedIssues = state.selectedIssues(state.lastSnapshot.issues),
                selectedCoverageTargets = state.selectedCoverage(state.lastSnapshot.coverage),
                selectedDuplicationTargets = state.selectedDuplication(state.lastSnapshot.duplication),
                selectedHotspots = state.selectedHotspots(state.lastSnapshot.hotspots),
            ),
        )
        state.lastGeneratedPrompt = generated.content
        state.markPromptGenerated()
        promptText.text = generated.content
        updateSelectionSummary()
        updatePromptDirtyMarker()
    }

    private fun copyPrompt() {
        val prompt = promptText.text.ifBlank { state.lastGeneratedPrompt }
        if (prompt.isBlank()) return
        CopyPasteManager.getInstance().setContents(StringSelection(prompt))
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(prompt), null)
    }

    private fun syncSelectionsFromTables() {
        state.setSelection(WorkspaceMode.ISSUES, extractSelectedKeys(issuesModel, visibleIssueKeys))
        state.setSelection(WorkspaceMode.COVERAGE, extractSelectedKeys(coverageModel, visibleCoverageKeys))
        state.setSelection(WorkspaceMode.DUPLICATION, extractSelectedKeys(duplicationModel, visibleDuplicationKeys))
        state.setSelection(WorkspaceMode.HOTSPOTS, extractSelectedKeys(hotspotsModel, visibleHotspotKeys))
        state.markPromptDirtyIfNeeded()
        updateSelectionSummary()
        updatePromptDirtyMarker()
    }

    private fun extractSelectedKeys(model: DefaultTableModel, keys: List<String>): List<String> =
        (0 until model.rowCount).mapNotNull { row ->
            if (model.getValueAt(row, 0) == true) keys.getOrNull(row) else null
        }

    private fun refill(model: DefaultTableModel, rows: List<Array<Any>>) {
        syncingTableModels = true
        try {
            model.rowCount = 0
            rows.forEach(model::addRow)
        } finally {
            syncingTableModels = false
        }
    }

    private fun resetCombo(combo: JComboBox<String>, items: List<String>, selected: String?) {
        combo.removeAllItems()
        items.forEach(combo::addItem)
        if (selected != null) combo.selectedItem = selected
    }

    private fun resetProjectCombo(items: List<DiscoveredSonarProject>, selectedPath: String?) {
        projectSelector.removeAllItems()
        items.map(::ProjectOption).forEach(projectSelector::addItem)
        items.firstOrNull { it.path == selectedPath }?.let {
            projectSelector.selectedItem = ProjectOption(it)
        }
    }

    private fun updateTabTitles(snapshot: com.sonarpromptstudio.model.FindingsSnapshot) {
        tabs.setTitleAt(0, "Issues (${snapshot.issues.size})")
        tabs.setTitleAt(1, "Coverage (${snapshot.coverage.size})")
        tabs.setTitleAt(2, "Duplication (${snapshot.duplication.size})")
        tabs.setTitleAt(3, "Security Hotspots (${snapshot.hotspots.size})")
    }

    private fun selectionTable(model: DefaultTableModel, layout: TableLayout): JTable = FlexibleTable(model).apply {
        autoCreateRowSorter = true
        autoResizeMode = JTable.AUTO_RESIZE_OFF
        setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        rowHeight = JBUI.scale(28)
        intercellSpacing = Dimension(JBUI.scale(8), JBUI.scale(4))
        setShowGrid(false)
        fillsViewportHeight = true
        tableHeader.reorderingAllowed = false
        tableHeader.resizingAllowed = true
        tableHeader.preferredSize = Dimension(tableHeader.preferredSize.width, JBUI.scale(30))
        putClientProperty("JTable.autoStartsEdit", false)
        model.addTableModelListener {
            if (syncingTableModels) return@addTableModelListener
            syncSelectionsFromTables()
        }
        setDefaultRenderer(Boolean::class.java, checkboxRenderer())
        columnModel.getColumn(0).cellEditor = DefaultCellEditor(JCheckBox())
        columnModel.getColumn(0).minWidth = JBUI.scale(72)
        columnModel.getColumn(0).maxWidth = JBUI.scale(72)
        columnModel.getColumn(0).preferredWidth = JBUI.scale(72)
        styleTableColumns(this, layout)
    }

    private fun styleTableColumns(table: JTable, layout: TableLayout) {
        when (layout) {
            TableLayout.ISSUES -> configureColumns(
                table,
                listOf(110, 120, 120, 180, 270, 80, 130, 100, 180, 560),
                flexibleColumn = 9,
            )
            TableLayout.COVERAGE -> configureColumns(
                table,
                listOf(110, 520, 100, 100, 100, 120, 150),
                flexibleColumn = 1,
            )
            TableLayout.DUPLICATION -> configureColumns(
                table,
                listOf(110, 560, 110, 120, 150),
                flexibleColumn = 1,
            )
            TableLayout.HOTSPOTS -> configureColumns(
                table,
                listOf(110, 160, 80, 130, 130, 320),
                flexibleColumn = 5,
            )
        }
    }

    private fun configureColumns(table: JTable, widths: List<Int>, flexibleColumn: Int) {
        (table as? FlexibleTable)?.flexibleColumn = flexibleColumn
        widths.forEachIndexed { index, width ->
            val column = table.columnModel.getColumn(index)
            val scaledWidth = JBUI.scale(width)
            column.width = scaledWidth
            column.preferredWidth = scaledWidth
            if (index == flexibleColumn) {
                column.maxWidth = Int.MAX_VALUE
                column.minWidth = JBUI.scale(width.coerceAtLeast(140))
            } else {
                val maxWidth = when {
                    index == 0 -> width
                    width <= 60 -> width + 4
                    width <= 80 -> width + 6
                    width <= 100 -> width + 8
                    else -> width + 10
                }
                column.maxWidth = JBUI.scale(maxWidth)
            }
            if (width <= 100 || index == 0) {
                column.minWidth = JBUI.scale(width)
            }
            if (index != 0 && width <= 120) {
                column.cellRenderer = compactRenderer(SwingConstants.CENTER)
            }
        }
        (table as? FlexibleTable)?.adjustFlexibleColumn()
    }

    private fun styledScrollPane(component: java.awt.Component): JBScrollPane = JBScrollPane(component).apply {
        border = JBUI.Borders.customLine(JBColor.border(), 1)
        viewport.background = component.background
    }

    private fun promptHeader(): JPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(0, 0, 8, 0)
        add(
            JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                isOpaque = false
                selectionSummaryLabel.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                add(selectionSummaryLabel)
                add(promptDirtyIconLabel)
            },
            BorderLayout.CENTER,
        )
        add(
            JButton(AllIcons.Actions.Copy).apply {
                toolTipText = "Copy prompt"
                addActionListener { copyPrompt() }
                isContentAreaFilled = false
                border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
            },
            BorderLayout.EAST,
        )
    }

    private fun updateSelectionSummary() {
        val counts = listOf(
            "Issues" to state.selections.getValue(WorkspaceMode.ISSUES).selectedKeys.size,
            "Coverage" to state.selections.getValue(WorkspaceMode.COVERAGE).selectedKeys.size,
            "Duplication" to state.selections.getValue(WorkspaceMode.DUPLICATION).selectedKeys.size,
            "Hotspots" to state.selections.getValue(WorkspaceMode.HOTSPOTS).selectedKeys.size,
        )
        val totalSelected = counts.sumOf { it.second }
        val summaryParts = counts.filter { (_, count) -> count > 0 }
            .joinToString("&nbsp;&nbsp;&nbsp;") { (label, count) ->
                "<span style='color:#AFC3D6; font-size: 108%;'><b>$label ($count)</b></span>"
            }

        selectionSummaryLabel.text = if (totalSelected == 0) {
            ""
        } else {
            "<html>Selected findings&nbsp;&nbsp;<span style='color:#AFC3D6; font-size: 108%;'><b>$totalSelected</b></span>&nbsp;&nbsp;&nbsp;&nbsp;$summaryParts</html>"
        }
        selectionSummaryLabel.isVisible = totalSelected > 0
    }

    private fun updatePromptDirtyMarker() {
        promptDirtyIconLabel.isVisible = state.promptDirty
    }

    private fun connectionMetadata(profile: ConnectionProfile?): String =
        profile?.let { "${it.name} (${it.type.name.lowercase()} @ ${it.baseUrl}) project=${discovered.activeProject()?.sonarProjectKey ?: "n/a"}" }
            ?: "No active connection"

    private fun sectionTitle(title: String, subtitle: String): JPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        isOpaque = false
        add(JBLabel(title).apply { font = font.deriveFont(font.size2D + 3f) }, BorderLayout.NORTH)
        add(JBLabel(subtitle).apply { foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND }, BorderLayout.SOUTH)
    }

    private fun checkboxRenderer() = object : JCheckBox(), javax.swing.table.TableCellRenderer {
        init {
            horizontalAlignment = SwingConstants.CENTER
            isOpaque = true
            border = JBUI.Borders.empty()
        }

        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ) = apply {
            setSelected(value as? Boolean ?: false)
            background = if (isSelected) table.selectionBackground else table.background
            foreground = if (isSelected) table.selectionForeground else table.foreground
        }
    }

    private fun compactRenderer(alignment: Int) = DefaultTableCellRenderer().apply {
        horizontalAlignment = alignment
    }

    private fun cardPanel(): JPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(12))
        background = JBColor(Color(0xFFFFFF), Color(0x1F2125))
    }

    private data class ProjectOption(private val discoveredProject: DiscoveredSonarProject) {
        val path: String = discoveredProject.path

        override fun toString(): String = runCatching {
            Paths.get(path).fileName?.toString()?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: path.substringAfterLast('/').ifBlank { path }
    }

    private class SelectionTableModel(columns: Array<String>) : DefaultTableModel(columns, 0) {
        override fun getColumnClass(columnIndex: Int): Class<*> = if (columnIndex == 0) java.lang.Boolean::class.java else String::class.java

        override fun isCellEditable(row: Int, column: Int): Boolean = column == 0
    }

    private enum class TableLayout {
        ISSUES,
        COVERAGE,
        DUPLICATION,
        HOTSPOTS,
    }

    private class FlexibleTable(model: DefaultTableModel) : JBTable(model) {
        var flexibleColumn: Int = -1

        override fun doLayout() {
            super.doLayout()
            adjustFlexibleColumn()
        }

        fun adjustFlexibleColumn() {
            if (flexibleColumn !in 0 until columnModel.columnCount) return
            val viewportWidth = (parent as? JViewport)?.extentSize?.width ?: 0
            val availableWidth = viewportWidth.takeIf { it > 0 } ?: visibleRect.width.takeIf { it > 0 } ?: width
            if (availableWidth <= 0) return

            var fixedWidth = 0
            for (index in 0 until columnModel.columnCount) {
                if (index == flexibleColumn) continue
                fixedWidth += columnModel.getColumn(index).preferredWidth
            }

            val column = columnModel.getColumn(flexibleColumn)
            val spacingWidth = intercellSpacing.width * (columnModel.columnCount - 1)
            val targetWidth = (availableWidth - fixedWidth - spacingWidth).coerceAtLeast(column.minWidth)
            column.width = targetWidth
            column.preferredWidth = targetWidth
        }
    }

    companion object {
        private val instances = mutableMapOf<Project, WorkspacePanel>()

        fun registerInstance(project: Project, panel: WorkspacePanel) {
            instances[project] = panel
        }

        fun focusMode(project: Project, mode: WorkspaceMode) {
            instances[project]?.tabs?.selectedIndex = WorkspaceMode.entries.indexOf(mode)
        }

        fun generate(project: Project) {
            instances[project]?.generatePrompt()
        }

        fun copy(project: Project) {
            instances[project]?.copyPrompt()
        }
    }
}
