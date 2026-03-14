package com.sonarpromptstudio.model

import java.time.Instant
import java.util.UUID

enum class SonarProfileType { CLOUD, SERVER }
enum class AuthMode { BEARER, BASIC_TOKEN }
enum class PromptTarget { CODEX, CLAUDE, QWEN }
enum class PromptStyle { MINIMAL, BALANCED, GUIDED }
enum class WorkspaceMode { ISSUES, COVERAGE, DUPLICATION, HOTSPOTS }

data class ConnectionProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: SonarProfileType,
    val baseUrl: String,
    val branchOverride: String? = null,
    val pullRequestOverride: String? = null,
    val tlsVerificationEnabled: Boolean = true,
    val authMode: AuthMode = AuthMode.BEARER,
) {
    fun effectiveBaseUrl(): String = when (type) {
        SonarProfileType.CLOUD -> baseUrl.ifBlank { DEFAULT_SONARCLOUD_URL }
        SonarProfileType.SERVER -> baseUrl
    }

    companion object {
        const val DEFAULT_SONARCLOUD_URL: String = "https://sonarcloud.io/"
    }
}

data class DiscoveredSonarProject(
    val path: String,
    val sonarProjectKey: String,
    val sonarOrganization: String?,
) {
    fun isSonarCloudCandidate(): Boolean = !sonarOrganization.isNullOrBlank()
}

data class ConnectionDiagnostics(
    val success: Boolean,
    val summary: String,
    val details: List<String> = emptyList(),
)

data class SonarProjectRef(
    val profile: ConnectionProfile,
    val project: DiscoveredSonarProject,
)

data class IssueFinding(
    val key: String,
    val severity: String,
    val type: String,
    val rule: String,
    val component: String,
    val line: Int?,
    val status: String,
    val effort: String?,
    val tags: List<String>,
    val message: String,
)

data class CoverageFinding(
    val key: String,
    val path: String,
    val coverage: Double?,
    val lineCoverage: Double?,
    val branchCoverage: Double?,
    val uncoveredLines: Int?,
    val uncoveredBranches: Int?,
)

data class DuplicationFinding(
    val key: String,
    val path: String,
    val duplication: Double?,
    val duplicatedLines: Int?,
    val duplicatedBlocks: Int?,
)

data class HotspotFinding(
    val key: String,
    val component: String,
    val line: Int?,
    val status: String?,
    val vulnerabilityProbability: String?,
    val message: String,
)

data class FindingsSnapshot(
    val loadedAt: Instant = Instant.now(),
    val issues: List<IssueFinding> = emptyList(),
    val coverage: List<CoverageFinding> = emptyList(),
    val duplication: List<DuplicationFinding> = emptyList(),
    val hotspots: List<HotspotFinding> = emptyList(),
) {
    fun countFor(mode: WorkspaceMode): Int = when (mode) {
        WorkspaceMode.ISSUES -> issues.size
        WorkspaceMode.COVERAGE -> coverage.size
        WorkspaceMode.DUPLICATION -> duplication.size
        WorkspaceMode.HOTSPOTS -> hotspots.size
    }
}
