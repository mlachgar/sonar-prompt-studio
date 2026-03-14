package com.sonarpromptstudio.ui

import com.sonarpromptstudio.model.IssueFinding
import com.sonarpromptstudio.model.CoverageFinding
import com.sonarpromptstudio.model.DuplicationFinding
import com.sonarpromptstudio.model.HotspotFinding
import com.sonarpromptstudio.service.IssueFilters
import com.sonarpromptstudio.service.SelectionAndFilterLogic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SelectionAndFilterLogicTest {
    @Test
    fun `filters issues by configured fields`() {
        val issues = listOf(
            IssueFinding("1", "MAJOR", "BUG", "kotlin:S1", "src/App.kt", 1, "OPEN", null, emptyList(), "First"),
            IssueFinding("2", "MINOR", "CODE_SMELL", "kotlin:S2", "src/Test.kt", 1, "OPEN", null, emptyList(), "Second"),
        )

        val filtered = SelectionAndFilterLogic.filterIssues(
            issues,
            IssueFilters(
                types = linkedSetOf("BUG"),
                severities = linkedSetOf("MAJOR"),
                statuses = linkedSetOf("OPEN"),
                ruleSubstring = "S1",
                fileSubstring = "App",
            ),
        )

        assertEquals(listOf("1"), filtered.map { it.key })
        assertEquals(setOf("1"), SelectionAndFilterLogic.selectVisibleIssues(filtered))
    }

    @Test
    fun `keeps items when filters are empty and matches case insensitively`() {
        val issues = listOf(
            IssueFinding("1", "MAJOR", "BUG", "kotlin:S1", "src/App.kt", 1, "OPEN", null, emptyList(), "First"),
            IssueFinding("2", "MINOR", "CODE_SMELL", "kotlin:S2", "src/Test.kt", 1, "RESOLVED", null, emptyList(), "Second"),
        )

        val filtered = SelectionAndFilterLogic.filterIssues(
            issues,
            IssueFilters(
                types = linkedSetOf("bug"),
                severities = linkedSetOf("major"),
                statuses = linkedSetOf("open"),
                ruleSubstring = "s1",
                fileSubstring = "app",
            ),
        )

        assertEquals(listOf("1"), filtered.map { it.key })
        assertEquals(setOf("c1"), SelectionAndFilterLogic.selectVisibleCoverage(listOf(CoverageFinding("c1", "file", null, null, null, null, null))))
        assertEquals(setOf("d1"), SelectionAndFilterLogic.selectVisibleDuplication(listOf(DuplicationFinding("d1", "file", null, null, null))))
        assertEquals(setOf("h1"), SelectionAndFilterLogic.selectVisibleHotspots(listOf(HotspotFinding("h1", "file", null, null, null, "msg"))))
    }

    @Test
    fun `returns all issues when no filters are configured`() {
        val issues = listOf(
            IssueFinding("1", "MAJOR", "BUG", "kotlin:S1", "src/App.kt", 1, "OPEN", null, emptyList(), "First"),
            IssueFinding("2", "MINOR", "CODE_SMELL", "kotlin:S2", "src/Test.kt", 1, "RESOLVED", null, emptyList(), "Second"),
        )

        val filtered = SelectionAndFilterLogic.filterIssues(issues, IssueFilters())

        assertEquals(issues, filtered)
        assertTrue(SelectionAndFilterLogic.selectVisibleIssues(emptyList()).isEmpty())
    }
}
