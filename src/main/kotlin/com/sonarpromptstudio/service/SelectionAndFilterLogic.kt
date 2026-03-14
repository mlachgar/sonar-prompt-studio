package com.sonarpromptstudio.service

import com.sonarpromptstudio.model.CoverageFinding
import com.sonarpromptstudio.model.DuplicationFinding
import com.sonarpromptstudio.model.HotspotFinding
import com.sonarpromptstudio.model.IssueFinding

object SelectionAndFilterLogic {
    fun filterIssues(issues: List<IssueFinding>, filters: IssueFilters): List<IssueFinding> = issues.filter { issue ->
        matches(filters.types, issue.type) &&
            matches(filters.severities, issue.severity) &&
            matches(filters.statuses, issue.status) &&
            contains(filters.ruleSubstring, issue.rule) &&
            contains(filters.fileSubstring, issue.component)
    }

    fun selectVisibleIssues(items: List<IssueFinding>): Set<String> = items.mapTo(linkedSetOf()) { it.key }
    fun selectVisibleCoverage(items: List<CoverageFinding>): Set<String> = items.mapTo(linkedSetOf()) { it.key }
    fun selectVisibleDuplication(items: List<DuplicationFinding>): Set<String> = items.mapTo(linkedSetOf()) { it.key }
    fun selectVisibleHotspots(items: List<HotspotFinding>): Set<String> = items.mapTo(linkedSetOf()) { it.key }

    private fun matches(expected: Set<String>, actual: String): Boolean =
        expected.isEmpty() || expected.any { it.equals(actual, ignoreCase = true) }

    private fun contains(expected: String, actual: String): Boolean =
        expected.isBlank() || actual.contains(expected, ignoreCase = true)
}
