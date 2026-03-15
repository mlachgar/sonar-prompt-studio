package com.sonarpromptstudio.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sonarpromptstudio.model.*
import com.sonarpromptstudio.state.SonarSettingsState
import com.sonarpromptstudio.ui.SonarSettingsConfigurable
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComboBox
import javax.swing.JPasswordField
import javax.swing.JTextField
import kotlin.io.path.deleteIfExists

class ServicesPlatformTest : BasePlatformTestCase() {
    private val settings: SonarSettingsService
        get() = ApplicationManager.getApplication().getService(SonarSettingsService::class.java)

    private val projectDir: Path
        get() = Path.of(project.basePath ?: error("Missing project base path"))

    override fun tearDown() {
        try {
            settings.resetPluginState()
            settings.clearPendingProfileDraft()
            settings.setDefaultPromptTarget(PromptTarget.CODEX)
            settings.setDefaultPromptStyle(PromptStyle.BALANCED)
            settings.loadState(SonarSettingsState())
            ApplicationManager.getApplication().getService(SecureTokenService::class.java).removeToken("service-test-profile")
            projectDir.resolve(".env").deleteIfExists()
            projectDir.resolve("sonar-project.properties").deleteIfExists()
            projectDir.resolve("module").resolve("sonar-project.properties").deleteIfExists()
            projectDir.resolve("module").deleteIfExists()
        } finally {
            super.tearDown()
        }
    }

    override fun getTestDataPath(): String = ""

    fun testSonarSettingsServiceStoresAndResetsState() {
        val profile = ConnectionProfile(
            id = "service-test-profile",
            name = "Cloud",
            type = SonarProfileType.CLOUD,
            baseUrl = "",
            authMode = AuthMode.BEARER,
        )

        settings.saveProfiles(listOf(profile))
        settings.setActiveProfileId(profile.id)
        settings.setActiveProjectPath("/tmp/repo")
        settings.setDefaultPromptTarget(PromptTarget.QWEN)
        settings.setDefaultPromptStyle(PromptStyle.GUIDED)
        settings.setGroupingMode("by-file")
        settings.setPendingProfileDraft(profile, "draft-token")
        settings.markOnboardingShown()

        assertEquals(listOf(profile), settings.profiles())
        assertEquals(profile.id, settings.activeProfileId())
        assertEquals("/tmp/repo", settings.activeProjectPath())
        assertEquals(PromptTarget.QWEN, settings.defaultPromptTarget())
        assertEquals(PromptStyle.GUIDED, settings.defaultPromptStyle())
        assertEquals("by-file", settings.groupingMode())
        assertEquals(profile to "draft-token", settings.pendingProfileDraft())
        assertFalse(settings.shouldShowOnboarding())

        settings.clearPendingProfileDraft()
        settings.resetPluginState()

        assertTrue(settings.profiles().isEmpty())
        assertNull(settings.activeProfileId())
        assertNull(settings.activeProjectPath())
        assertNull(settings.groupingMode())
        assertNull(settings.pendingProfileDraft())
    }

    fun testWorkspaceStateServiceTracksSelectionsAndPromptDirtyState() {
        val workspace = project.service<WorkspaceStateService>()
        workspace.resetItems()

        workspace.setSelection(WorkspaceMode.ISSUES, listOf("I-1", "I-2"))
        workspace.setSelection(WorkspaceMode.COVERAGE, listOf("C-1"))
        workspace.lastGeneratedPrompt = "prompt"
        workspace.markPromptGenerated()

        assertEquals("ISSUES:I-1,I-2|COVERAGE:C-1|DUPLICATION:|HOTSPOTS:", workspace.currentSelectionSignature())
        assertFalse(workspace.promptDirty)
        assertEquals(
            listOf(
                IssueFinding("I-1", "MAJOR", "BUG", "rule", "file", 2, "OPEN", null, emptyList(), "msg"),
                IssueFinding("I-2", "MAJOR", "BUG", "rule", "file", 3, "OPEN", null, emptyList(), "msg"),
            ),
            workspace.selectedIssues(
                listOf(
                    IssueFinding("I-1", "MAJOR", "BUG", "rule", "file", 2, "OPEN", null, emptyList(), "msg"),
                    IssueFinding("I-2", "MAJOR", "BUG", "rule", "file", 3, "OPEN", null, emptyList(), "msg"),
                ),
            ),
        )

        workspace.setSelection(WorkspaceMode.HOTSPOTS, listOf("H-1"))
        workspace.markPromptDirtyIfNeeded()

        assertTrue(workspace.promptDirty)

        workspace.lastSnapshot = FindingsSnapshot(
            coverage = listOf(CoverageFinding("C-1", "src/Test.kt", 10.0, 20.0, 30.0, 1, 2)),
            duplication = listOf(DuplicationFinding("D-1", "src/Test.kt", 40.0, 3, 1)),
            hotspots = listOf(HotspotFinding("H-1", "src/Test.kt", 7, "TO_REVIEW", "HIGH", "check")),
        )
        assertEquals(1, workspace.selectedCoverage(workspace.lastSnapshot.coverage).size)
        assertTrue(workspace.selectedDuplication(workspace.lastSnapshot.duplication).isEmpty())
        assertEquals(1, workspace.selectedHotspots(workspace.lastSnapshot.hotspots).size)

        workspace.resetItems()
        assertFalse(workspace.loading)
        assertFalse(workspace.promptDirty)
        assertTrue(workspace.lastGeneratedPrompt.isBlank())
        assertTrue(workspace.issueFilters.types.isEmpty())
        assertTrue(workspace.selections.values.all { it.selectedKeys.isEmpty() })
    }

    fun testUiRefreshServiceSubscribesAndUnsubscribesListeners() {
        val refreshService = project.service<UiRefreshService>()
        var count = 0
        val unsubscribe = refreshService.subscribe { count += 1 }

        refreshService.fire()
        unsubscribe()
        refreshService.fire()

        assertEquals(1, count)
    }

    fun testSecureTokenServicePrefersSecureStoreOverEnvAndCanRemoveToken() {
        val tokens = ApplicationManager.getApplication().getService(SecureTokenService::class.java)
        Files.writeString(projectDir.resolve(".env"), "SONAR_TOKEN=env-token\n")

        assertEquals("env-token", tokens.loadToken(project, "service-test-profile"))
        assertFalse(tokens.hasSecureToken("service-test-profile"))

        tokens.saveToken("service-test-profile", "secure-token")

        assertTrue(tokens.hasSecureToken("service-test-profile"))
        assertEquals("secure-token", tokens.loadToken(project, "service-test-profile"))

        tokens.removeToken("service-test-profile")

        assertFalse(tokens.hasSecureToken("service-test-profile"))
        assertEquals("env-token", tokens.loadToken(project, "service-test-profile"))
    }

    fun testSettingsApplySavesProfileAndTokenTogether() {
        val configurable = SonarSettingsConfigurable()
        configurable.createComponent()

        textField(configurable, "nameField").text = "Cloud"
        comboBox<SonarProfileType>(configurable, "typeBox").selectedItem = SonarProfileType.CLOUD
        textField(configurable, "urlField").text = ConnectionProfile.DEFAULT_SONARCLOUD_URL
        comboBox<Boolean>(configurable, "tlsBox").selectedItem = true
        comboBox<AuthMode>(configurable, "authBox").selectedItem = AuthMode.BEARER
        passwordField(configurable, "tokenField").text = "saved-token"

        configurable.apply()

        val savedProfile = settings.profiles().single()
        val tokens = ApplicationManager.getApplication().getService(SecureTokenService::class.java)
        try {
            assertEquals("Cloud", savedProfile.name)
            assertEquals(savedProfile.id, settings.activeProfileId())
            assertEquals("saved-token", tokens.loadToken(project, savedProfile.id))
        } finally {
            tokens.removeToken(savedProfile.id)
        }
    }

    fun testDiscoveredProjectServiceRescansAndMaintainsActiveProject() {
        val moduleDir = projectDir.resolve("module")
        Files.createDirectories(moduleDir)
        Files.writeString(projectDir.resolve("sonar-project.properties"), "sonar.projectKey=root-key\nsonar.organization=root-org\n")
        Files.writeString(moduleDir.resolve("sonar-project.properties"), "sonar.projectKey=module-key\n")

        val service = project.service<DiscoveredProjectService>()
        val projects = service.rescan()

        assertEquals(2, projects.size)
        assertEquals(projects.first().path, settings.activeProjectPath())
        assertEquals(projects.first().path, service.activeProject()?.path)

        service.setActiveProject(projects.last().path)

        assertEquals(projects.last().path, service.activeProject()?.path)

        Files.deleteIfExists(moduleDir.resolve("sonar-project.properties"))
        val rescanned = service.rescan()

        assertEquals(1, rescanned.size)
        assertEquals(rescanned.single().path, service.activeProject()?.path)
    }

    fun testFindingsServiceUsesConfiguredProfileAndCanClearItems() {
        val profile = ConnectionProfile(
            id = "service-test-profile",
            name = "Server",
            type = SonarProfileType.SERVER,
            baseUrl = "http://localhost:9000",
            authMode = AuthMode.BEARER,
        )
        settings.saveProfiles(listOf(profile))
        settings.setActiveProfileId(profile.id)

        val findings = project.service<FindingsService>()
        val workspace = project.service<WorkspaceStateService>()
        workspace.lastGeneratedPrompt = "existing"
        workspace.setSelection(WorkspaceMode.ISSUES, listOf("I-1"))
        workspace.lastSnapshot = FindingsSnapshot(
            issues = listOf(IssueFinding("I-1", "MAJOR", "BUG", "rule", "file", 1, "OPEN", null, emptyList(), "msg")),
        )

        assertEquals(profile, findings.activeProfile())

        val diagnostics = findings.testConnection(profile, null, tokenOverride = "token")
        assertTrue(diagnostics.success)
        assertEquals("Connection successful", diagnostics.summary)

        var result: Result<FindingsSnapshot>? = null
        findings.refresh { result = it }

        assertNotNull(result)
        assertTrue(result!!.isFailure)

        findings.clearItems()

        assertTrue(findings.latestSnapshot().issues.isEmpty())
        assertTrue(workspace.lastGeneratedPrompt.isBlank())
        assertTrue(workspace.selections.getValue(WorkspaceMode.ISSUES).selectedKeys.isEmpty())
    }

    private fun textField(target: Any, name: String): JTextField =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) as JTextField

    private fun passwordField(target: Any, name: String): JPasswordField =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) as JPasswordField

    @Suppress("UNCHECKED_CAST")
    private fun <T> comboBox(target: Any, name: String): JComboBox<T> =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) as JComboBox<T>
}
