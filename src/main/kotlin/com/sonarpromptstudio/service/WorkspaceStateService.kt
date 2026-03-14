package com.sonarpromptstudio.service

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.sonarpromptstudio.model.ConnectionProfile
import com.sonarpromptstudio.model.CoverageFinding
import com.sonarpromptstudio.model.DuplicationFinding
import com.sonarpromptstudio.model.FindingsSnapshot
import com.sonarpromptstudio.model.HotspotFinding
import com.sonarpromptstudio.model.IssueFinding
import com.sonarpromptstudio.model.PromptStyle
import com.sonarpromptstudio.model.PromptTarget
import com.sonarpromptstudio.model.WorkspaceMode
import java.util.concurrent.ConcurrentHashMap

data class IssueFilters(
    var types: MutableSet<String> = linkedSetOf(),
    var severities: MutableSet<String> = linkedSetOf(),
    var statuses: MutableSet<String> = linkedSetOf(),
    var ruleSubstring: String = "",
    var fileSubstring: String = "",
)

data class ModeState<T>(
    val selectedKeys: MutableSet<String> = linkedSetOf(),
)

class WorkspaceStateService @JvmOverloads constructor(
    @Suppress("UNUSED_PARAMETER") private val project: Project? = null,
    initialPromptTarget: PromptTarget? = null,
    initialPromptStyle: PromptStyle? = null,
) {
    var currentMode: WorkspaceMode = WorkspaceMode.ISSUES
    var currentPromptTarget: PromptTarget = initialPromptTarget ?: SonarSettingsService.getInstance().defaultPromptTarget()
    var currentPromptStyle: PromptStyle = initialPromptStyle ?: SonarSettingsService.getInstance().defaultPromptStyle()
    var lastGeneratedPrompt: String = ""
    var lastSnapshot: FindingsSnapshot = FindingsSnapshot()
    var lastProfile: ConnectionProfile? = null
    var loading: Boolean = false
    var promptDirty: Boolean = false
    var promptSelectionSignature: String? = null

    val issueFilters: IssueFilters = IssueFilters()
    val selections: MutableMap<WorkspaceMode, ModeState<String>> = ConcurrentHashMap(
        mapOf(
            WorkspaceMode.ISSUES to ModeState(),
            WorkspaceMode.COVERAGE to ModeState(),
            WorkspaceMode.DUPLICATION to ModeState(),
            WorkspaceMode.HOTSPOTS to ModeState(),
        ),
    )

    fun setSelection(mode: WorkspaceMode, selectedKeys: Collection<String>) {
        selections.getValue(mode).selectedKeys.apply {
            clear()
            addAll(selectedKeys)
        }
    }

    fun selectedIssues(issues: List<IssueFinding>): List<IssueFinding> =
        issues.filter { selections.getValue(WorkspaceMode.ISSUES).selectedKeys.contains(it.key) }

    fun selectedCoverage(items: List<CoverageFinding>): List<CoverageFinding> =
        items.filter { selections.getValue(WorkspaceMode.COVERAGE).selectedKeys.contains(it.key) }

    fun selectedDuplication(items: List<DuplicationFinding>): List<DuplicationFinding> =
        items.filter { selections.getValue(WorkspaceMode.DUPLICATION).selectedKeys.contains(it.key) }

    fun selectedHotspots(items: List<HotspotFinding>): List<HotspotFinding> =
        items.filter { selections.getValue(WorkspaceMode.HOTSPOTS).selectedKeys.contains(it.key) }

    fun currentSelectionSignature(): String = WorkspaceMode.entries.joinToString("|") { mode ->
        val selected = selections.getValue(mode).selectedKeys.toList().sorted().joinToString(",")
        "${mode.name}:$selected"
    }

    fun markPromptGenerated() {
        promptDirty = false
        promptSelectionSignature = currentSelectionSignature()
    }

    fun markPromptDirtyIfNeeded() {
        if (lastGeneratedPrompt.isBlank()) return
        if (promptSelectionSignature != currentSelectionSignature()) {
            promptDirty = true
        }
    }

    fun resetItems() {
        lastGeneratedPrompt = ""
        lastSnapshot = FindingsSnapshot()
        lastProfile = null
        loading = false
        promptDirty = false
        promptSelectionSignature = null
        issueFilters.types.clear()
        issueFilters.severities.clear()
        issueFilters.statuses.clear()
        issueFilters.ruleSubstring = ""
        issueFilters.fileSubstring = ""
        selections.values.forEach { it.selectedKeys.clear() }
    }

    companion object {
        fun getInstance(project: Project): WorkspaceStateService = project.service()
    }
}
