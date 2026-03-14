package com.sonarpromptstudio.state

import com.sonarpromptstudio.model.AuthMode
import com.sonarpromptstudio.model.ConnectionProfile
import com.sonarpromptstudio.model.CoverageFinding
import com.sonarpromptstudio.model.DuplicationFinding
import com.sonarpromptstudio.model.FindingsSnapshot
import com.sonarpromptstudio.model.HotspotFinding
import com.sonarpromptstudio.model.IssueFinding
import com.sonarpromptstudio.model.PromptStyle
import com.sonarpromptstudio.model.PromptTarget
import com.sonarpromptstudio.model.SonarProfileType
import com.sonarpromptstudio.model.WorkspaceMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersistentStateModelsTest {
    @Test
    fun `sonar settings state defaults are stable`() {
        val state = SonarSettingsState()

        assertNull(state.activeProfileId)
        assertTrue(state.profiles.isEmpty())
        assertNull(state.activeProjectPath)
        assertEquals(PromptTarget.CODEX, state.defaultPromptTarget)
        assertEquals(PromptStyle.BALANCED, state.defaultPromptStyle)
        assertNull(state.groupingMode)
        assertFalse(state.onboardingShown)
    }

    @Test
    fun `connection profile state normalizes blank optional fields`() {
        val restored = ConnectionProfileState(
            id = "p1",
            name = "Cloud",
            type = SonarProfileType.CLOUD.name,
            baseUrl = "https://sonarcloud.io/",
            branchOverride = "",
            pullRequestOverride = " ",
            tlsVerificationEnabled = true,
            authMode = AuthMode.BEARER.name,
        ).toDomain()

        assertNull(restored.branchOverride)
        assertNull(restored.pullRequestOverride)
    }

    @Test
    fun `connection profile state serializes domain values`() {
        val state = ConnectionProfileState.fromDomain(
            ConnectionProfile(
                id = "p1",
                name = "Server",
                type = SonarProfileType.SERVER,
                baseUrl = "http://localhost:9000",
                branchOverride = "main",
                pullRequestOverride = "99",
                tlsVerificationEnabled = false,
                authMode = AuthMode.BASIC_TOKEN,
            ),
        )

        assertEquals("p1", state.id)
        assertEquals("Server", state.name)
        assertEquals(SonarProfileType.SERVER.name, state.type)
        assertEquals("http://localhost:9000", state.baseUrl)
        assertEquals("main", state.branchOverride)
        assertEquals("99", state.pullRequestOverride)
        assertFalse(state.tlsVerificationEnabled)
        assertEquals(AuthMode.BASIC_TOKEN.name, state.authMode)
    }

    @Test
    fun `server profiles keep explicit base url`() {
        val profile = ConnectionProfile(
            id = "server",
            name = "Server",
            type = SonarProfileType.SERVER,
            baseUrl = "http://localhost:9000",
        )

        assertEquals("http://localhost:9000", profile.effectiveBaseUrl())
    }

    @Test
    fun `sonarqube server project is not treated as sonarcloud candidate`() {
        val project = com.sonarpromptstudio.model.DiscoveredSonarProject(
            path = "/tmp",
            sonarProjectKey = "demo",
            sonarOrganization = null,
        )

        assertFalse(project.isSonarCloudCandidate())
    }

    @Test
    fun `findings snapshot counts items by workspace mode`() {
        val snapshot = FindingsSnapshot(
            issues = listOf(IssueFinding("I1", "MAJOR", "BUG", "rule", "file", 1, "OPEN", null, emptyList(), "msg")),
            coverage = listOf(CoverageFinding("C1", "file", 80.0, 80.0, 50.0, 2, 1)),
            duplication = listOf(DuplicationFinding("D1", "file", 10.0, 3, 1)),
            hotspots = listOf(HotspotFinding("H1", "file", 4, "TO_REVIEW", "HIGH", "msg")),
        )

        assertEquals(1, snapshot.countFor(WorkspaceMode.ISSUES))
        assertEquals(1, snapshot.countFor(WorkspaceMode.COVERAGE))
        assertEquals(1, snapshot.countFor(WorkspaceMode.DUPLICATION))
        assertEquals(1, snapshot.countFor(WorkspaceMode.HOTSPOTS))
    }
}
