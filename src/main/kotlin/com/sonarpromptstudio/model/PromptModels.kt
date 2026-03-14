package com.sonarpromptstudio.model

import java.time.Instant

data class PromptInput(
    val target: PromptTarget,
    val style: PromptStyle,
    val source: WorkspaceMode,
    val connectionMetadata: String,
    val repositoryName: String?,
    val generatedAt: Instant,
    val selectedIssues: List<IssueFinding> = emptyList(),
    val selectedCoverageTargets: List<CoverageFinding> = emptyList(),
    val selectedDuplicationTargets: List<DuplicationFinding> = emptyList(),
    val selectedHotspots: List<HotspotFinding> = emptyList(),
)

data class GeneratedPrompt(
    val title: String,
    val content: String,
)
