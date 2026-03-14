package com.sonarpromptstudio.prompt

import com.sonarpromptstudio.model.GeneratedPrompt
import com.sonarpromptstudio.model.PromptInput
import com.sonarpromptstudio.model.PromptStyle
import com.sonarpromptstudio.model.PromptTarget
import com.sonarpromptstudio.model.WorkspaceMode

object PromptBuilder {
    fun build(input: PromptInput): GeneratedPrompt {
        val renderer = when (input.target) {
            PromptTarget.CODEX -> codexPromptRenderer
            PromptTarget.CLAUDE -> claudePromptRenderer
            PromptTarget.QWEN -> qwenPromptRenderer
        }
        return renderer(input)
    }
}

private val codexPromptRenderer: (PromptInput) -> GeneratedPrompt = { input ->
    GeneratedPrompt(
        title = "Codex ${input.source.name.lowercase().replaceFirstChar(Char::titlecase)} Prompt",
        content = sharedBody("Codex", input, extraInstructions = listOf(
            "Make minimal, safe, localized changes.",
            "Avoid unrelated refactors, cleanup, and broad rewrites.",
            "Inspect referenced files before editing when context is incomplete.",
        ) + sourceGoal(input.source)),
    )
}

private val claudePromptRenderer: (PromptInput) -> GeneratedPrompt = { input ->
    GeneratedPrompt(
        title = "Claude Code ${input.source.name.lowercase().replaceFirstChar(Char::titlecase)} Prompt",
        content = sharedBody("Claude Code", input, extraInstructions = sourceGoal(input.source)),
    )
}

private val qwenPromptRenderer: (PromptInput) -> GeneratedPrompt = { input ->
    GeneratedPrompt(
        title = "Qwen Code ${input.source.name.lowercase().replaceFirstChar(Char::titlecase)} Prompt",
        content = sharedBody("Qwen Code", input, extraInstructions = sourceGoal(input.source)),
    )
}

private fun sharedBody(rendererName: String, input: PromptInput, extraInstructions: List<String>): String {
    val styleLine = when (input.style) {
        PromptStyle.MINIMAL -> "Keep the response brief and execution-focused."
        PromptStyle.BALANCED -> "Be concise, but include enough explanation to justify each fix."
        PromptStyle.GUIDED -> "Include helpful reasoning while keeping changes practical and localized."
    }
    return buildString {
        appendLine("You are $rendererName working on Sonar findings.")
        appendLine(styleLine)
        input.repositoryName?.let { appendLine("Repository: $it") }
        appendLine()
        appendLine("Constraints:")
        appendLine("- Preserve existing behavior unless a safe change is required.")
        appendLine("- Prefer minimal, localized fixes over broad refactors.")
        appendLine("- Do not address unrelated style issues or code smells.")
        appendLine("- Inspect referenced files before changing code when context is incomplete.")
        appendLine("- End with a short summary grouped by file.")
        extraInstructions.forEach { appendLine("- $it") }
        appendLine()
        appendLine("Findings to fix:")
        appendLine()
        append(selectedFindingsSection(input))
    }
}

private fun selectedFindingsSection(input: PromptInput): String {
    val sections = linkedMapOf(
        WorkspaceMode.ISSUES to input.selectedIssues.joinToString("\n") {
            "- Issue ${it.key}: [${it.severity}/${it.type}] ${it.message} | rule=${it.rule} | file=${it.component}${it.line?.let { line -> " line=$line" } ?: ""}"
        },
        WorkspaceMode.COVERAGE to input.selectedCoverageTargets.joinToString("\n") {
            "- Coverage ${it.key}: path=${it.path} coverage=${it.coverage ?: "n/a"} line=${it.lineCoverage ?: "n/a"} branch=${it.branchCoverage ?: "n/a"} uncoveredLines=${it.uncoveredLines ?: "n/a"} uncoveredBranches=${it.uncoveredBranches ?: "n/a"}"
        },
        WorkspaceMode.DUPLICATION to input.selectedDuplicationTargets.joinToString("\n") {
            "- Duplication ${it.key}: path=${it.path} duplication=${it.duplication ?: "n/a"} duplicatedLines=${it.duplicatedLines ?: "n/a"} duplicatedBlocks=${it.duplicatedBlocks ?: "n/a"}"
        },
        WorkspaceMode.HOTSPOTS to input.selectedHotspots.joinToString("\n") {
            "- Hotspot ${it.key}: component=${it.component}${it.line?.let { line -> " line=$line" } ?: ""} status=${it.status ?: "n/a"} probability=${it.vulnerabilityProbability ?: "n/a"} message=${it.message}"
        },
    ).filterValues { it.isNotBlank() }

    if (sections.isEmpty()) return "- No findings selected."

    val orderedModes = listOf(input.source) + WorkspaceMode.entries.filterNot { it == input.source }
    return orderedModes.mapNotNull { mode ->
        val body = sections[mode] ?: return@mapNotNull null
        "${modeLabel(mode)}:\n$body"
    }.joinToString("\n\n")
}

private fun modeLabel(mode: WorkspaceMode): String = when (mode) {
    WorkspaceMode.ISSUES -> "Issues"
    WorkspaceMode.COVERAGE -> "Coverage"
    WorkspaceMode.DUPLICATION -> "Duplication"
    WorkspaceMode.HOTSPOTS -> "Security Hotspots"
}

private fun sourceGoal(mode: WorkspaceMode): List<String> = when (mode) {
    WorkspaceMode.ISSUES -> listOf("Fix the selected Sonar findings with minimal, safe code changes.")
    WorkspaceMode.COVERAGE -> listOf("Add or update tests to cover the selected uncovered lines and branches, preferring tests over production edits.")
    WorkspaceMode.DUPLICATION -> listOf("Reduce the selected duplication with minimal, safe refactors, preferring small extractions or shared helpers.")
    WorkspaceMode.HOTSPOTS -> listOf("Remediate the selected security hotspots with minimal, safe changes and call out any remaining human review items.")
}
