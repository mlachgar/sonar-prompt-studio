package com.sonarpromptstudio.prompt

import com.sonarpromptstudio.model.CoverageFinding
import com.sonarpromptstudio.model.DuplicationFinding
import com.sonarpromptstudio.model.HotspotFinding
import com.sonarpromptstudio.model.IssueFinding
import com.sonarpromptstudio.model.PromptInput
import com.sonarpromptstudio.model.PromptStyle
import com.sonarpromptstudio.model.PromptTarget
import com.sonarpromptstudio.model.WorkspaceMode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PromptBuilderTest {
    @Test
    fun `renders codex issue prompt with required constraints`() {
        val prompt = PromptBuilder.build(
            PromptInput(
                target = PromptTarget.CODEX,
                style = PromptStyle.GUIDED,
                source = WorkspaceMode.ISSUES,
                connectionMetadata = "cloud@test",
                repositoryName = "demo",
                generatedAt = Instant.parse("2024-01-01T00:00:00Z"),
                selectedIssues = listOf(
                    IssueFinding("I1", "MAJOR", "BUG", "rule-1", "src/App.kt", 12, "OPEN", "5min", listOf("bug"), "Fix this"),
                ),
            ),
        )

        assertContains(prompt.content, "Make minimal, safe, localized changes.")
        assertContains(prompt.content, "Fix the selected Sonar findings")
        assertContains(prompt.content, "Issue I1")
    }

    @Test
    fun `renders qwen coverage prompt with selected coverage targets`() {
        val prompt = PromptBuilder.build(
            PromptInput(
                target = PromptTarget.QWEN,
                style = PromptStyle.MINIMAL,
                source = WorkspaceMode.COVERAGE,
                connectionMetadata = "server@test",
                repositoryName = "demo",
                generatedAt = Instant.parse("2024-01-01T00:00:00Z"),
                selectedCoverageTargets = listOf(
                    CoverageFinding("C1", "src/Test.kt", 72.0, 68.0, 55.0, 10, 4),
                ),
            ),
        )

        assertContains(prompt.content, "Add or update tests")
        assertContains(prompt.content, "Coverage C1")
    }

    @Test
    fun `renders selected findings from multiple modes not just the active tab`() {
        val prompt = PromptBuilder.build(
            PromptInput(
                target = PromptTarget.CODEX,
                style = PromptStyle.BALANCED,
                source = WorkspaceMode.ISSUES,
                connectionMetadata = "cloud@test",
                repositoryName = "demo",
                generatedAt = Instant.parse("2024-01-01T00:00:00Z"),
                selectedIssues = listOf(
                    IssueFinding("I1", "MAJOR", "BUG", "rule-1", "src/App.kt", 12, "OPEN", "5min", listOf("bug"), "Fix this"),
                ),
                selectedDuplicationTargets = listOf(
                    DuplicationFinding("D1", "src/App.kt", 32.0, 24, 2),
                ),
            ),
        )

        assertContains(prompt.content, "Issues:")
        assertContains(prompt.content, "Issue I1")
        assertContains(prompt.content, "Duplication:")
        assertContains(prompt.content, "Duplication D1")
    }

    @Test
    fun `renders claude hotspot prompt and keeps active source section first`() {
        val prompt = PromptBuilder.build(
            PromptInput(
                target = PromptTarget.CLAUDE,
                style = PromptStyle.BALANCED,
                source = WorkspaceMode.HOTSPOTS,
                connectionMetadata = "server@test",
                repositoryName = "demo",
                generatedAt = Instant.parse("2024-01-01T00:00:00Z"),
                selectedIssues = listOf(
                    IssueFinding("I1", "MAJOR", "BUG", "rule-1", "src/App.kt", 12, "OPEN", "5min", listOf("bug"), "Fix this"),
                ),
                selectedHotspots = listOf(
                    HotspotFinding("H1", "src/Security.kt", 8, "TO_REVIEW", "HIGH", "Validate input"),
                ),
            ),
        )

        assertEquals("Claude Code Hotspots Prompt", prompt.title)
        assertTrue(prompt.content.indexOf("Security Hotspots:") < prompt.content.indexOf("Issues:"))
        assertContains(prompt.content, "Remediate the selected security hotspots")
        assertContains(prompt.content, "Hotspot H1")
    }

    @Test
    fun `renders fallback text when no findings are selected`() {
        val prompt = PromptBuilder.build(
            PromptInput(
                target = PromptTarget.QWEN,
                style = PromptStyle.MINIMAL,
                source = WorkspaceMode.DUPLICATION,
                connectionMetadata = "server@test",
                repositoryName = null,
                generatedAt = Instant.parse("2024-01-01T00:00:00Z"),
            ),
        )

        assertContains(prompt.content, "- No findings selected.")
    }

    @Test
    fun `renders balanced coverage prompt with repository and all numeric fallbacks`() {
        val prompt = PromptBuilder.build(
            PromptInput(
                target = PromptTarget.CODEX,
                style = PromptStyle.BALANCED,
                source = WorkspaceMode.COVERAGE,
                connectionMetadata = "server@test",
                repositoryName = "demo",
                generatedAt = Instant.parse("2024-01-01T00:00:00Z"),
                selectedCoverageTargets = listOf(
                    CoverageFinding("C1", "src/Test.kt", null, null, null, null, null),
                ),
            ),
        )

        assertEquals("Codex Coverage Prompt", prompt.title)
        assertContains(prompt.content, "Be concise, but include enough explanation to justify each fix.")
        assertContains(prompt.content, "Repository: demo")
        assertContains(prompt.content, "coverage=n/a line=n/a branch=n/a uncoveredLines=n/a uncoveredBranches=n/a")
    }

    @Test
    fun `renders minimal issue and hotspot findings without line suffixes`() {
        val prompt = PromptBuilder.build(
            PromptInput(
                target = PromptTarget.CLAUDE,
                style = PromptStyle.MINIMAL,
                source = WorkspaceMode.ISSUES,
                connectionMetadata = "server@test",
                repositoryName = null,
                generatedAt = Instant.parse("2024-01-01T00:00:00Z"),
                selectedIssues = listOf(
                    IssueFinding("I1", "MINOR", "CODE_SMELL", "rule-1", "src/App.kt", null, "OPEN", null, emptyList(), "Fix this"),
                ),
                selectedHotspots = listOf(
                    HotspotFinding("H1", "src/Security.kt", null, null, null, "Review this"),
                ),
            ),
        )

        assertContains(prompt.content, "Keep the response brief and execution-focused.")
        assertContains(prompt.content, "Issue I1: [MINOR/CODE_SMELL] Fix this | rule=rule-1 | file=src/App.kt")
        assertContains(prompt.content, "Hotspot H1: component=src/Security.kt status=n/a probability=n/a message=Review this")
        assertTrue("Repository:" !in prompt.content)
    }
}
