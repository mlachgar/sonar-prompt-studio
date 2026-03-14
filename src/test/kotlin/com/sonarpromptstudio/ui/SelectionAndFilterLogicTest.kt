package com.sonarpromptstudio.ui

import com.sonarpromptstudio.model.IssueFinding
import com.sonarpromptstudio.service.IssueFilters
import com.sonarpromptstudio.service.SelectionAndFilterLogic
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
