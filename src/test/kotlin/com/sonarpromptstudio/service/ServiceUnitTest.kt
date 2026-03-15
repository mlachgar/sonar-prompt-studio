package com.sonarpromptstudio.service

import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.sonarpromptstudio.backend.SonarBackend
import com.sonarpromptstudio.model.AuthMode
import com.sonarpromptstudio.model.ConnectionDiagnostics
import com.sonarpromptstudio.model.ConnectionProfile
import com.sonarpromptstudio.model.CoverageFinding
import com.sonarpromptstudio.model.DiscoveredSonarProject
import com.sonarpromptstudio.model.DuplicationFinding
import com.sonarpromptstudio.model.FindingsSnapshot
import com.sonarpromptstudio.model.HotspotFinding
import com.sonarpromptstudio.model.IssueFinding
import com.sonarpromptstudio.model.PromptStyle
import com.sonarpromptstudio.model.PromptTarget
import com.sonarpromptstudio.model.SonarProfileType
import com.sonarpromptstudio.model.WorkspaceMode
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.assertFailsWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServiceUnitTest {
    private val dummyProject = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Double.TYPE -> 0.0
            java.lang.Float.TYPE -> 0f
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> 0.toChar()
            String::class.java -> ""
            else -> null
        }
    } as Project

    @Test
    fun `settings service stores defaults drafts and resets state`() {
        val settings = SonarSettingsService()
        val profile = ConnectionProfile(
            id = "p1",
            name = "Cloud",
            type = SonarProfileType.CLOUD,
            baseUrl = "",
            authMode = AuthMode.BEARER,
        )

        settings.saveProfiles(listOf(profile))
        settings.setActiveProfileId("p1")
        settings.setActiveProjectPath("/tmp/demo")
        settings.setDefaultPromptTarget(PromptTarget.CLAUDE)
        settings.setDefaultPromptStyle(PromptStyle.GUIDED)
        settings.setGroupingMode("file")
        settings.setPendingProfileDraft(profile, "token")
        settings.markOnboardingShown()

        assertEquals(listOf(profile), settings.profiles())
        assertEquals("p1", settings.activeProfileId())
        assertEquals("/tmp/demo", settings.activeProjectPath())
        assertEquals(PromptTarget.CLAUDE, settings.defaultPromptTarget())
        assertEquals(PromptStyle.GUIDED, settings.defaultPromptStyle())
        assertEquals("file", settings.groupingMode())
        assertEquals(profile to "token", settings.pendingProfileDraft())
        assertFalse(settings.shouldShowOnboarding())

        settings.clearPendingProfileDraft()
        settings.resetPluginState()

        assertTrue(settings.profiles().isEmpty())
        assertNull(settings.activeProfileId())
        assertNull(settings.activeProjectPath())
        assertNull(settings.groupingMode())
        assertNull(settings.pendingProfileDraft())
    }

    @Test
    fun `ui refresh service fires listeners and supports unsubscribe`() {
        val refresh = UiRefreshService()
        var count = 0
        val unsubscribe = refresh.subscribe { count += 1 }

        refresh.fire()
        unsubscribe()
        refresh.fire()

        assertEquals(1, count)
    }

    @Test
    fun `workspace state service tracks selections and reset`() {
        val workspace = WorkspaceStateService(null, PromptTarget.QWEN, PromptStyle.MINIMAL)

        workspace.setSelection(WorkspaceMode.ISSUES, listOf("I-1"))
        workspace.setSelection(WorkspaceMode.COVERAGE, listOf("C-1"))
        workspace.setSelection(WorkspaceMode.DUPLICATION, listOf("D-1"))
        workspace.setSelection(WorkspaceMode.HOTSPOTS, listOf("H-1"))

        assertEquals("ISSUES:I-1|COVERAGE:C-1|DUPLICATION:D-1|HOTSPOTS:H-1", workspace.currentSelectionSignature())
        assertEquals(PromptTarget.QWEN, workspace.currentPromptTarget)
        assertEquals(PromptStyle.MINIMAL, workspace.currentPromptStyle)
        assertEquals(
            listOf(IssueFinding("I-1", "MAJOR", "BUG", "rule", "file", 1, "OPEN", null, emptyList(), "msg")),
            workspace.selectedIssues(listOf(IssueFinding("I-1", "MAJOR", "BUG", "rule", "file", 1, "OPEN", null, emptyList(), "msg"))),
        )
        assertEquals(
            listOf(CoverageFinding("C-1", "src/Test.kt", 70.0, 60.0, 50.0, 3, 2)),
            workspace.selectedCoverage(listOf(CoverageFinding("C-1", "src/Test.kt", 70.0, 60.0, 50.0, 3, 2))),
        )
        assertEquals(
            listOf(DuplicationFinding("D-1", "src/Test.kt", 10.0, 5, 1)),
            workspace.selectedDuplication(listOf(DuplicationFinding("D-1", "src/Test.kt", 10.0, 5, 1))),
        )
        assertEquals(
            listOf(HotspotFinding("H-1", "src/Test.kt", 9, "TO_REVIEW", "HIGH", "msg")),
            workspace.selectedHotspots(listOf(HotspotFinding("H-1", "src/Test.kt", 9, "TO_REVIEW", "HIGH", "msg"))),
        )

        workspace.lastGeneratedPrompt = "prompt"
        workspace.markPromptGenerated()
        assertFalse(workspace.promptDirty)

        workspace.setSelection(WorkspaceMode.ISSUES, listOf("I-1", "I-2"))
        workspace.markPromptDirtyIfNeeded()
        assertTrue(workspace.promptDirty)

        val untouched = WorkspaceStateService(null, PromptTarget.CODEX, PromptStyle.BALANCED)
        untouched.markPromptDirtyIfNeeded()
        assertFalse(untouched.promptDirty)

        workspace.lastProfile = ConnectionProfile(
            id = "p1",
            name = "Server",
            type = SonarProfileType.SERVER,
            baseUrl = "http://localhost:9000",
            authMode = AuthMode.BEARER,
        )
        workspace.lastSnapshot = FindingsSnapshot(
            issues = listOf(IssueFinding("I-9", "MAJOR", "BUG", "rule", "file", 1, "OPEN", null, emptyList(), "msg")),
        )
        workspace.loading = true
        workspace.promptDirty = true
        workspace.promptSelectionSignature = "stale"
        workspace.issueFilters.types += "BUG"
        workspace.issueFilters.severities += "MAJOR"
        workspace.issueFilters.statuses += "OPEN"
        workspace.issueFilters.ruleSubstring = "java:S111"
        workspace.issueFilters.fileSubstring = "src/"

        workspace.resetItems()
        assertTrue(workspace.lastGeneratedPrompt.isBlank())
        assertTrue(workspace.lastSnapshot.issues.isEmpty())
        assertNull(workspace.lastProfile)
        assertFalse(workspace.loading)
        assertFalse(workspace.promptDirty)
        assertNull(workspace.promptSelectionSignature)
        assertTrue(workspace.issueFilters.types.isEmpty())
        assertTrue(workspace.issueFilters.severities.isEmpty())
        assertTrue(workspace.issueFilters.statuses.isEmpty())
        assertTrue(workspace.issueFilters.ruleSubstring.isBlank())
        assertTrue(workspace.issueFilters.fileSubstring.isBlank())
        assertTrue(workspace.selections.values.all { it.selectedKeys.isEmpty() })
    }

    @Test
    fun `secure token service prefers secure token then falls back to env`() {
        val store = mutableMapOf<String, String?>()
        val tokens = SecureTokenService(
            tokenStore = object : TokenStore {
                override fun getPassword(attributes: com.intellij.credentialStore.CredentialAttributes): String? = store[attributes.serviceName]

                override fun set(attributes: com.intellij.credentialStore.CredentialAttributes, credentials: com.intellij.credentialStore.Credentials?) {
                    store[attributes.serviceName] = credentials?.getPasswordAsString()
                }
            },
            envTokenReader = { "env-token" },
        )

        assertEquals("env-token", tokens.loadToken(dummyProject, "p1"))
        assertFalse(tokens.hasSecureToken("p1"))

        tokens.saveToken("p1", "secure-token")

        assertTrue(tokens.hasSecureToken("p1"))
        assertEquals("secure-token", tokens.loadToken(dummyProject, "p1"))

        tokens.removeToken("p1")

        assertFalse(tokens.hasSecureToken("p1"))
        assertEquals("env-token", tokens.loadEnvToken(dummyProject))
    }

    @Test
    fun `secure token service treats blank stored token as missing and delegates to env reader`() {
        val store = mutableMapOf<String, String?>("Sonar Prompt Studio:p1" to "   ")
        var envProject: Project? = null
        val tokens = SecureTokenService(
            tokenStore = object : TokenStore {
                override fun getPassword(attributes: com.intellij.credentialStore.CredentialAttributes): String? = store[attributes.serviceName]

                override fun set(attributes: com.intellij.credentialStore.CredentialAttributes, credentials: com.intellij.credentialStore.Credentials?) {
                    store[attributes.serviceName] = credentials?.getPasswordAsString()
                }
            },
            envTokenReader = {
                envProject = it
                "env-token"
            },
        )

        assertEquals("env-token", tokens.loadToken(dummyProject, "p1"))
        assertFalse(tokens.hasSecureToken("p1"))
        assertTrue(envProject === dummyProject)
    }

    @Test
    fun `secure token service saves and removes credentials for the expected attribute key`() {
        val writes = mutableListOf<Pair<com.intellij.credentialStore.CredentialAttributes, com.intellij.credentialStore.Credentials?>>()
        val tokens = SecureTokenService(
            tokenStore = object : TokenStore {
                override fun getPassword(attributes: com.intellij.credentialStore.CredentialAttributes): String? = null

                override fun set(
                    attributes: com.intellij.credentialStore.CredentialAttributes,
                    credentials: com.intellij.credentialStore.Credentials?,
                ) {
                    writes += attributes to credentials
                }
            },
            envTokenReader = { null },
        )

        tokens.saveToken("p1", "secure-token")
        tokens.removeToken("p1")

        assertEquals("Sonar Prompt Studio:p1", writes[0].first.serviceName)
        assertEquals("p1", writes[0].second?.userName)
        assertEquals("secure-token", writes[0].second?.getPasswordAsString())
        assertEquals("Sonar Prompt Studio:p1", writes[1].first.serviceName)
        assertNull(writes[1].second)
    }

    @Test
    fun `discovered project service rescans and maintains active project`() {
        val settings = SonarSettingsService()
        val tempDir = createTempDirectory()
        try {
            tempDir.resolve("sonar-project.properties").writeText("sonar.projectKey=root-key\nsonar.organization=root-org\n")
            Files.createDirectories(tempDir.resolve("module"))
            tempDir.resolve("module").resolve("sonar-project.properties").writeText("sonar.projectKey=module-key\n")

            val service = DiscoveredProjectService(
                project = null,
                settings = settings,
                basePathOverride = tempDir.toString(),
                subscribeToWorkspaceChanges = false,
            )

            val discovered = service.rescan()
            assertEquals(2, discovered.size)
            assertEquals(discovered.first().path, settings.activeProjectPath())
            assertEquals(discovered.first().path, service.activeProject()?.path)
            assertEquals(discovered, service.allProjects())

            service.setActiveProject(discovered.last().path)
            assertEquals(discovered.last().path, service.activeProject()?.path)

            tempDir.resolve("module").resolve("sonar-project.properties").deleteIfExists()
            val rescanned = service.rescan()
            assertEquals(1, rescanned.size)
            assertEquals(rescanned.single().path, settings.activeProjectPath())
        } finally {
            tempDir.resolve("module").resolve("sonar-project.properties").deleteIfExists()
            tempDir.resolve("module").deleteIfExists()
            tempDir.resolve("sonar-project.properties").deleteIfExists()
            tempDir.deleteIfExists()
        }
    }

    @Test
    fun `discovered project service returns empty without a base path`() {
        val settings = SonarSettingsService()
        val service = DiscoveredProjectService(
            project = null,
            settings = settings,
            basePathOverride = null,
            discoverProjects = { error("should not be called") },
            subscribeToWorkspaceChanges = false,
        )

        assertTrue(service.rescan().isEmpty())
        assertTrue(service.allProjects().isEmpty())
        assertNull(service.activeProject())

        service.setActiveProject("/tmp/other")
        assertEquals("/tmp/other", settings.activeProjectPath())
    }

    @Test
    fun `discovered project service preserves matching active project and clears missing one when nothing is found`() {
        val settings = SonarSettingsService().apply {
            setActiveProjectPath("/tmp/two")
        }
        val projects = listOf(
            DiscoveredSonarProject("/tmp/one", "one", null),
            DiscoveredSonarProject("/tmp/two", "two", null),
        )
        var discoverCalls = 0
        val service = DiscoveredProjectService(
            project = null,
            settings = settings,
            basePathOverride = "/tmp",
            discoverProjects = {
                discoverCalls += 1
                if (discoverCalls == 1) projects else emptyList()
            },
            subscribeToWorkspaceChanges = false,
        )

        assertEquals("/tmp/two", service.activeProject()?.path)
        assertEquals("/tmp/two", settings.activeProjectPath())

        val rescanned = service.rescan()
        assertTrue(rescanned.isEmpty())
        assertTrue(service.allProjects().isEmpty())
        assertNull(service.activeProject())
        assertNull(settings.activeProjectPath())
    }

    @Test
    fun `discovered project service falls back to first project when active path is unknown`() {
        val settings = SonarSettingsService().apply {
            setActiveProjectPath("/tmp/missing")
        }
        val first = DiscoveredSonarProject("/tmp/one", "one", null)
        val second = DiscoveredSonarProject("/tmp/two", "two", null)
        val service = DiscoveredProjectService(
            project = null,
            settings = settings,
            basePathOverride = "/tmp",
            discoverProjects = { listOf(first, second) },
            subscribeToWorkspaceChanges = false,
        )

        assertEquals("/tmp/one", settings.activeProjectPath())
        assertEquals(first, service.activeProject())
    }

    @Test
    fun `discovered project service keeps active path unset when scan finds no projects`() {
        val settings = SonarSettingsService()
        val service = DiscoveredProjectService(
            project = null,
            settings = settings,
            basePathOverride = "/tmp",
            discoverProjects = { emptyList() },
            subscribeToWorkspaceChanges = false,
        )

        assertTrue(service.allProjects().isEmpty())
        assertNull(settings.activeProjectPath())
        assertNull(service.activeProject())
    }

    @Test
    fun `workspace state does not mark prompt dirty when selection signature is unchanged`() {
        val workspace = WorkspaceStateService(null, PromptTarget.CODEX, PromptStyle.BALANCED)

        workspace.setSelection(WorkspaceMode.ISSUES, listOf("I-1"))
        workspace.lastGeneratedPrompt = "prompt"
        workspace.markPromptGenerated()
        workspace.markPromptDirtyIfNeeded()

        assertFalse(workspace.promptDirty)
        assertEquals("ISSUES:I-1|COVERAGE:|DUPLICATION:|HOTSPOTS:", workspace.promptSelectionSignature)
    }

    @Test
    fun `findings service uses secure store token when no override is provided`() {
        val profile = ConnectionProfile(
            id = "p1",
            name = "Server",
            type = SonarProfileType.SERVER,
            baseUrl = "http://localhost:9000",
            authMode = AuthMode.BEARER,
        )
        var recordedToken: String? = null
        var recordedProject: DiscoveredSonarProject? = null
        val backend = object : SonarBackend {
            override fun testConnection(
                profile: ConnectionProfile,
                token: String?,
                project: DiscoveredSonarProject?,
            ): ConnectionDiagnostics {
                recordedToken = token
                recordedProject = project
                return ConnectionDiagnostics(true, "ok")
            }

            override fun loadFindings(
                profile: ConnectionProfile,
                token: String,
                project: DiscoveredSonarProject,
            ): FindingsSnapshot = error("not used")
        }
        val findings = FindingsService(
            project = dummyProject,
            backend = backend,
            settings = SonarSettingsService(),
            tokens = SecureTokenService(
                tokenStore = object : TokenStore {
                    override fun getPassword(attributes: com.intellij.credentialStore.CredentialAttributes): String? = null
                    override fun set(
                        attributes: com.intellij.credentialStore.CredentialAttributes,
                        credentials: com.intellij.credentialStore.Credentials?,
                    ) = Unit
                },
                envTokenReader = { "env-token" },
            ),
            discoveredProjects = null,
            workspaceState = null,
            backgroundRunner = null,
        )
        val projectRef = DiscoveredSonarProject("/tmp/repo", "demo", null)

        val diagnostics = findings.testConnection(profile, projectRef)

        assertTrue(diagnostics.success)
        assertEquals("env-token", recordedToken)
        assertEquals(projectRef, recordedProject)
    }

    @Test
    fun `findings service refresh loads findings immediately when background runner is disabled`() {
        val profile = ConnectionProfile(
            id = "p1",
            name = "Server",
            type = SonarProfileType.SERVER,
            baseUrl = "http://localhost:9000",
            authMode = AuthMode.BEARER,
        )
        val settings = SonarSettingsService().apply {
            saveProfiles(listOf(profile))
            setActiveProfileId(profile.id)
        }
        val discoveredProject = DiscoveredSonarProject("/tmp/repo", "demo", null)
        val workspace = WorkspaceStateService(null, PromptTarget.CODEX, PromptStyle.BALANCED)
        val expected = FindingsSnapshot(
            issues = listOf(IssueFinding("I-1", "MAJOR", "BUG", "rule", "file", 1, "OPEN", null, emptyList(), "msg")),
        )
        val discoveredProjects = DiscoveredProjectService(
            project = null,
            settings = settings,
            basePathOverride = "/tmp",
            discoverProjects = { listOf(discoveredProject) },
            subscribeToWorkspaceChanges = false,
        )
        val findings = FindingsService(
            project = dummyProject,
            backend = object : SonarBackend {
                override fun testConnection(
                    profile: ConnectionProfile,
                    token: String?,
                    project: DiscoveredSonarProject?,
                ): ConnectionDiagnostics = error("not used")

                override fun loadFindings(
                    profile: ConnectionProfile,
                    token: String,
                    project: DiscoveredSonarProject,
                ): FindingsSnapshot {
                    assertEquals("env-token", token)
                    assertEquals(discoveredProject, project)
                    return expected
                }
            },
            settings = settings,
            tokens = SecureTokenService(
                tokenStore = object : TokenStore {
                    override fun getPassword(attributes: com.intellij.credentialStore.CredentialAttributes): String? = null
                    override fun set(
                        attributes: com.intellij.credentialStore.CredentialAttributes,
                        credentials: com.intellij.credentialStore.Credentials?,
                    ) = Unit
                },
                envTokenReader = { "env-token" },
            ),
            discoveredProjects = discoveredProjects,
            workspaceState = workspace,
            backgroundRunner = null,
        )
        var result: Result<FindingsSnapshot>? = null

        findings.refresh { result = it }

        assertEquals(expected, findings.latestSnapshot())
        assertEquals(expected, workspace.lastSnapshot)
        assertEquals(profile, workspace.lastProfile)
        assertFalse(workspace.loading)
        assertNotNull(result)
        assertEquals(expected, result!!.getOrNull())
    }

    @Test
    fun `findings service refresh fails when token is blank`() {
        val profile = ConnectionProfile(
            id = "p1",
            name = "Server",
            type = SonarProfileType.SERVER,
            baseUrl = "http://localhost:9000",
            authMode = AuthMode.BEARER,
        )
        val settings = SonarSettingsService().apply {
            saveProfiles(listOf(profile))
            setActiveProfileId(profile.id)
        }
        val discoveredProjects = DiscoveredProjectService(
            project = null,
            settings = settings,
            basePathOverride = "/tmp",
            discoverProjects = { listOf(DiscoveredSonarProject("/tmp/repo", "demo", null)) },
            subscribeToWorkspaceChanges = false,
        )
        val findings = FindingsService(
            project = dummyProject,
            backend = object : SonarBackend {
                override fun testConnection(
                    profile: ConnectionProfile,
                    token: String?,
                    project: DiscoveredSonarProject?,
                ): ConnectionDiagnostics = error("not used")

                override fun loadFindings(
                    profile: ConnectionProfile,
                    token: String,
                    project: DiscoveredSonarProject,
                ): FindingsSnapshot = error("should not be called")
            },
            settings = settings,
            tokens = SecureTokenService(
                tokenStore = object : TokenStore {
                    override fun getPassword(attributes: com.intellij.credentialStore.CredentialAttributes): String? = null
                    override fun set(
                        attributes: com.intellij.credentialStore.CredentialAttributes,
                        credentials: com.intellij.credentialStore.Credentials?,
                    ) = Unit
                },
                envTokenReader = { "   " },
            ),
            discoveredProjects = discoveredProjects,
            workspaceState = WorkspaceStateService(null, PromptTarget.CODEX, PromptStyle.BALANCED),
            backgroundRunner = null,
        )
        var result: Result<FindingsSnapshot>? = null

        findings.refresh { result = it }

        assertNotNull(result)
        assertTrue(result!!.exceptionOrNull() is IllegalStateException)
        assertEquals("No token available.", result!!.exceptionOrNull()?.message)
    }

    @Test
    fun `findings service refresh fails when active profile is missing`() {
        val findings = FindingsService(
            project = dummyProject,
            backend = object : SonarBackend {
                override fun testConnection(
                    profile: ConnectionProfile,
                    token: String?,
                    project: DiscoveredSonarProject?,
                ): ConnectionDiagnostics = error("unused")

                override fun loadFindings(
                    profile: ConnectionProfile,
                    token: String,
                    project: DiscoveredSonarProject,
                ): FindingsSnapshot = error("should not be called")
            },
            settings = SonarSettingsService(),
            tokens = SecureTokenService(
                tokenStore = object : TokenStore {
                    override fun getPassword(attributes: com.intellij.credentialStore.CredentialAttributes): String? = "stored-token"
                    override fun set(attributes: com.intellij.credentialStore.CredentialAttributes, credentials: com.intellij.credentialStore.Credentials?) = Unit
                },
                envTokenReader = { null },
            ),
            discoveredProjects = DiscoveredProjectService(
                project = null,
                settings = SonarSettingsService(),
                basePathOverride = "/tmp",
                discoverProjects = { listOf(DiscoveredSonarProject("/tmp/repo", "demo", null)) },
                subscribeToWorkspaceChanges = false,
            ),
            workspaceState = WorkspaceStateService(null, PromptTarget.CODEX, PromptStyle.BALANCED),
            backgroundRunner = null,
        )
        var result: Result<FindingsSnapshot>? = null

        findings.refresh { result = it }

        assertNotNull(result)
        assertTrue(result!!.isFailure)
        assertEquals("Missing active profile or project.", result!!.exceptionOrNull()?.message)
    }

    @Test
    fun `findings service refresh fails when active project is missing`() {
        val profile = ConnectionProfile(
            id = "p1",
            name = "Server",
            type = SonarProfileType.SERVER,
            baseUrl = "http://localhost:9000",
            authMode = AuthMode.BEARER,
        )
        val settings = SonarSettingsService().apply {
            saveProfiles(listOf(profile))
            setActiveProfileId(profile.id)
        }
        val findings = FindingsService(
            project = dummyProject,
            backend = object : SonarBackend {
                override fun testConnection(
                    profile: ConnectionProfile,
                    token: String?,
                    project: DiscoveredSonarProject?,
                ): ConnectionDiagnostics = error("unused")

                override fun loadFindings(
                    profile: ConnectionProfile,
                    token: String,
                    project: DiscoveredSonarProject,
                ): FindingsSnapshot = error("should not be called")
            },
            settings = settings,
            tokens = SecureTokenService(
                tokenStore = object : TokenStore {
                    override fun getPassword(attributes: com.intellij.credentialStore.CredentialAttributes): String? = "stored-token"
                    override fun set(attributes: com.intellij.credentialStore.CredentialAttributes, credentials: com.intellij.credentialStore.Credentials?) = Unit
                },
                envTokenReader = { null },
            ),
            discoveredProjects = DiscoveredProjectService(
                project = null,
                settings = settings,
                basePathOverride = "/tmp",
                discoverProjects = { emptyList() },
                subscribeToWorkspaceChanges = false,
            ),
            workspaceState = WorkspaceStateService(null, PromptTarget.CODEX, PromptStyle.BALANCED),
            backgroundRunner = null,
        )
        var result: Result<FindingsSnapshot>? = null

        findings.refresh { result = it }

        assertNotNull(result)
        assertTrue(result!!.isFailure)
        assertEquals("Missing active profile or project.", result!!.exceptionOrNull()?.message)
    }

    @Test
    fun `findings service refresh reports backend failures and clears loading state`() {
        val profile = ConnectionProfile(
            id = "p1",
            name = "Server",
            type = SonarProfileType.SERVER,
            baseUrl = "http://localhost:9000",
            authMode = AuthMode.BEARER,
        )
        val settings = SonarSettingsService().apply {
            saveProfiles(listOf(profile))
            setActiveProfileId(profile.id)
        }
        val workspace = WorkspaceStateService(null, PromptTarget.CODEX, PromptStyle.BALANCED)
        val notifications = mutableListOf<Pair<String, String>>()
        val discoveredProjects = DiscoveredProjectService(
            project = null,
            settings = settings,
            basePathOverride = "/tmp",
            discoverProjects = { listOf(DiscoveredSonarProject("/tmp/repo", "demo", null)) },
            subscribeToWorkspaceChanges = false,
        )
        val findings = FindingsService(
            project = dummyProject,
            backend = object : SonarBackend {
                override fun testConnection(
                    profile: ConnectionProfile,
                    token: String?,
                    project: DiscoveredSonarProject?,
                ): ConnectionDiagnostics = error("not used")

                override fun loadFindings(
                    profile: ConnectionProfile,
                    token: String,
                    project: DiscoveredSonarProject,
                ): FindingsSnapshot {
                    throw IllegalStateException()
                }
            },
            settings = settings,
            tokens = SecureTokenService(
                tokenStore = object : TokenStore {
                    override fun getPassword(attributes: com.intellij.credentialStore.CredentialAttributes): String? = "secure-token"
                    override fun set(
                        attributes: com.intellij.credentialStore.CredentialAttributes,
                        credentials: com.intellij.credentialStore.Credentials?,
                    ) = Unit
                },
                envTokenReader = { null },
            ),
            discoveredProjects = discoveredProjects,
            workspaceState = workspace,
            notifier = { title, content -> notifications += title to content },
            backgroundRunner = null,
        )
        var result: Result<FindingsSnapshot>? = null

        findings.refresh { result = it }

        assertFalse(workspace.loading)
        assertEquals(listOf("Failed to load findings" to "Unknown error"), notifications)
        assertNotNull(result)
        assertTrue(result!!.isFailure)
        assertTrue(assertFailsWith<IllegalStateException> { result!!.getOrThrow() }.message == null)
    }

    @Test
    fun `findings service supports active profile connection test refresh and clear`() {
        val profile = ConnectionProfile(
            id = "p1",
            name = "Server",
            type = SonarProfileType.SERVER,
            baseUrl = "http://localhost:9000",
            authMode = AuthMode.BEARER,
        )
        val settings = SonarSettingsService().apply {
            saveProfiles(listOf(profile))
            setActiveProfileId("p1")
        }
        val workspace = WorkspaceStateService(null, PromptTarget.CODEX, PromptStyle.BALANCED).apply {
            lastGeneratedPrompt = "old"
            setSelection(WorkspaceMode.ISSUES, listOf("I-1"))
        }
        val backend = object : SonarBackend {
            override fun testConnection(profile: ConnectionProfile, token: String?, project: DiscoveredSonarProject?): ConnectionDiagnostics =
                ConnectionDiagnostics(token == "override-token", if (token == "override-token") "ok" else "bad")

            override fun loadFindings(profile: ConnectionProfile, token: String, project: DiscoveredSonarProject): FindingsSnapshot =
                FindingsSnapshot(issues = listOf(IssueFinding("I-1", "MAJOR", "BUG", "rule", "file", 1, "OPEN", null, emptyList(), "msg")))
        }
        val discovered = DiscoveredProjectService(
            project = null,
            settings = settings,
            basePathOverride = "/tmp",
            discoverProjects = { listOf(DiscoveredSonarProject("/tmp", "demo-key", null)) },
            subscribeToWorkspaceChanges = false,
        )
        val tokens = SecureTokenService(
            tokenStore = object : TokenStore {
                override fun getPassword(attributes: com.intellij.credentialStore.CredentialAttributes): String? = "stored-token"
                override fun set(attributes: com.intellij.credentialStore.CredentialAttributes, credentials: com.intellij.credentialStore.Credentials?) = Unit
            },
            envTokenReader = { null },
        )
        val notifications = mutableListOf<Pair<String, String>>()
        val findings = FindingsService(
            project = dummyProject,
            backend = backend,
            settings = settings,
            tokens = tokens,
            discoveredProjects = discovered,
            workspaceState = workspace,
            notifier = { title, content -> notifications += title to content },
            backgroundRunner = null,
        )

        assertEquals(profile, findings.activeProfile())
        assertEquals("ok", findings.testConnection(profile, discovered.activeProject(), "override-token").summary)

        var refreshResult: Result<FindingsSnapshot>? = null
        findings.refresh { refreshResult = it }

        assertNotNull(refreshResult)
        assertTrue(refreshResult!!.isSuccess)
        assertEquals(1, findings.latestSnapshot().issues.size)
        assertEquals(profile, workspace.lastProfile)
        assertFalse(workspace.loading)

        findings.clearItems()
        assertTrue(findings.latestSnapshot().issues.isEmpty())
        assertTrue(workspace.lastGeneratedPrompt.isBlank())
        assertTrue(notifications.isEmpty())
    }

    @Test
    fun `findings service uses stored token when no override is provided`() {
        val profile = ConnectionProfile(
            id = "p1",
            name = "Server",
            type = SonarProfileType.SERVER,
            baseUrl = "http://localhost:9000",
            authMode = AuthMode.BEARER,
        )
        var loadedProfileId: String? = null
        val findings = FindingsService(
            project = dummyProject,
            backend = object : SonarBackend {
                override fun testConnection(
                    profile: ConnectionProfile,
                    token: String?,
                    project: DiscoveredSonarProject?,
                ): ConnectionDiagnostics = ConnectionDiagnostics(token == "stored-token", token ?: "missing")

                override fun loadFindings(
                    profile: ConnectionProfile,
                    token: String,
                    project: DiscoveredSonarProject,
                ): FindingsSnapshot = error("unused")
            },
            settings = SonarSettingsService(),
            tokens = SecureTokenService(
                tokenStore = object : TokenStore {
                    override fun getPassword(attributes: com.intellij.credentialStore.CredentialAttributes): String? {
                        loadedProfileId = attributes.serviceName
                        return "stored-token"
                    }

                    override fun set(attributes: com.intellij.credentialStore.CredentialAttributes, credentials: com.intellij.credentialStore.Credentials?) = Unit
                },
                envTokenReader = { null },
            ),
            discoveredProjects = null,
            workspaceState = null,
            notifier = { _, _ -> error("unused") },
            backgroundRunner = null,
        )

        val diagnostics = findings.testConnection(profile, null)

        assertTrue(diagnostics.success)
        assertEquals("stored-token", diagnostics.summary)
        assertEquals("Sonar Prompt Studio:p1", loadedProfileId)
    }

    @Test
    fun `findings service reports failures for missing setup and backend errors`() {
        val settings = SonarSettingsService()
        val workspace = WorkspaceStateService(null, PromptTarget.CODEX, PromptStyle.BALANCED)
        val discovered = DiscoveredProjectService(
            project = null,
            settings = settings,
            basePathOverride = "/tmp",
            discoverProjects = { emptyList() },
            subscribeToWorkspaceChanges = false,
        )
        val tokens = SecureTokenService(
            tokenStore = object : TokenStore {
                override fun getPassword(attributes: com.intellij.credentialStore.CredentialAttributes): String? = "stored-token"
                override fun set(attributes: com.intellij.credentialStore.CredentialAttributes, credentials: com.intellij.credentialStore.Credentials?) = Unit
            },
            envTokenReader = { null },
        )
        val notifications = mutableListOf<Pair<String, String>>()
        val findings = FindingsService(
            project = dummyProject,
            backend = object : SonarBackend {
                override fun testConnection(profile: ConnectionProfile, token: String?, project: DiscoveredSonarProject?): ConnectionDiagnostics =
                    ConnectionDiagnostics(false, "unused")

                override fun loadFindings(profile: ConnectionProfile, token: String, project: DiscoveredSonarProject): FindingsSnapshot {
                    error("boom")
                }
            },
            settings = settings,
            tokens = tokens,
            discoveredProjects = discovered,
            workspaceState = workspace,
            notifier = { title, content -> notifications += title to content },
            backgroundRunner = null,
        )

        var result: Result<FindingsSnapshot>? = null
        findings.refresh { result = it }
        assertNotNull(result)
        assertTrue(result!!.isFailure)
        assertTrue(result!!.exceptionOrNull() is IllegalStateException)

        val profile = ConnectionProfile(
            id = "p1",
            name = "Server",
            type = SonarProfileType.SERVER,
            baseUrl = "http://localhost:9000",
            authMode = AuthMode.BEARER,
        )
        settings.saveProfiles(listOf(profile))
        settings.setActiveProfileId("p1")
        val discoveredWithProject = DiscoveredProjectService(
            project = null,
            settings = settings,
            basePathOverride = "/tmp",
            discoverProjects = { listOf(DiscoveredSonarProject("/tmp", "demo-key", null)) },
            subscribeToWorkspaceChanges = false,
        )
        val noTokenFindings = FindingsService(
            project = dummyProject,
            backend = object : SonarBackend {
                override fun testConnection(profile: ConnectionProfile, token: String?, project: DiscoveredSonarProject?): ConnectionDiagnostics =
                    ConnectionDiagnostics(false, "unused")

                override fun loadFindings(profile: ConnectionProfile, token: String, project: DiscoveredSonarProject): FindingsSnapshot =
                    FindingsSnapshot()
            },
            settings = settings,
            tokens = SecureTokenService(
                tokenStore = object : TokenStore {
                    override fun getPassword(attributes: com.intellij.credentialStore.CredentialAttributes): String? = null
                    override fun set(attributes: com.intellij.credentialStore.CredentialAttributes, credentials: com.intellij.credentialStore.Credentials?) = Unit
                },
                envTokenReader = { null },
            ),
            discoveredProjects = discoveredWithProject,
            workspaceState = workspace,
            notifier = { title, content -> notifications += title to content },
            backgroundRunner = null,
        )

        result = null
        noTokenFindings.refresh { result = it }
        assertNotNull(result)
        assertTrue(result!!.isFailure)
        assertEquals("No token available.", result!!.exceptionOrNull()?.message)

        assertEquals("unused", noTokenFindings.testConnection(profile, discoveredWithProject.activeProject(), null).summary)
    }

    @Test
    fun `findings service refresh succeeds without callback and clears loading state`() {
        val profile = ConnectionProfile(
            id = "p1",
            name = "Server",
            type = SonarProfileType.SERVER,
            baseUrl = "http://localhost:9000",
            authMode = AuthMode.BEARER,
        )
        val settings = SonarSettingsService().apply {
            saveProfiles(listOf(profile))
            setActiveProfileId("p1")
        }
        val workspace = WorkspaceStateService(null, PromptTarget.CODEX, PromptStyle.BALANCED)
        val findings = FindingsService(
            project = dummyProject,
            backend = object : SonarBackend {
                override fun testConnection(profile: ConnectionProfile, token: String?, project: DiscoveredSonarProject?): ConnectionDiagnostics =
                    ConnectionDiagnostics(true, "unused")

                override fun loadFindings(profile: ConnectionProfile, token: String, project: DiscoveredSonarProject): FindingsSnapshot =
                    FindingsSnapshot(hotspots = listOf(HotspotFinding("H-1", "src/Test.kt", 3, "TO_REVIEW", "MEDIUM", "msg")))
            },
            settings = settings,
            tokens = SecureTokenService(
                tokenStore = object : TokenStore {
                    override fun getPassword(attributes: com.intellij.credentialStore.CredentialAttributes): String? = "stored-token"
                    override fun set(attributes: com.intellij.credentialStore.CredentialAttributes, credentials: com.intellij.credentialStore.Credentials?) = Unit
                },
                envTokenReader = { null },
            ),
            discoveredProjects = DiscoveredProjectService(
                project = null,
                settings = settings,
                basePathOverride = "/tmp",
                discoverProjects = { listOf(DiscoveredSonarProject("/tmp", "demo-key", null)) },
                subscribeToWorkspaceChanges = false,
            ),
            workspaceState = workspace,
            notifier = { _, _ -> error("should not notify") },
            backgroundRunner = null,
        )

        findings.refresh()

        assertFalse(workspace.loading)
        assertEquals(1, findings.latestSnapshot().hotspots.size)
        assertEquals(profile, workspace.lastProfile)
    }

    @Test
    fun `findings service notifies when backend load fails`() {
        val profile = ConnectionProfile(
            id = "p1",
            name = "Server",
            type = SonarProfileType.SERVER,
            baseUrl = "http://localhost:9000",
            authMode = AuthMode.BEARER,
        )
        val settings = SonarSettingsService().apply {
            saveProfiles(listOf(profile))
            setActiveProfileId("p1")
        }
        val workspace = WorkspaceStateService(null, PromptTarget.CODEX, PromptStyle.BALANCED)
        val notifications = mutableListOf<Pair<String, String>>()
        val findings = FindingsService(
            project = dummyProject,
            backend = object : SonarBackend {
                override fun testConnection(profile: ConnectionProfile, token: String?, project: DiscoveredSonarProject?): ConnectionDiagnostics =
                    ConnectionDiagnostics(false, "unused")

                override fun loadFindings(profile: ConnectionProfile, token: String, project: DiscoveredSonarProject): FindingsSnapshot {
                    throw IllegalStateException("backend boom")
                }
            },
            settings = settings,
            tokens = SecureTokenService(
                tokenStore = object : TokenStore {
                    override fun getPassword(attributes: com.intellij.credentialStore.CredentialAttributes): String? = "stored-token"
                    override fun set(attributes: com.intellij.credentialStore.CredentialAttributes, credentials: com.intellij.credentialStore.Credentials?) = Unit
                },
                envTokenReader = { null },
            ),
            discoveredProjects = DiscoveredProjectService(
                project = null,
                settings = settings,
                basePathOverride = "/tmp",
                discoverProjects = { listOf(DiscoveredSonarProject("/tmp", "demo-key", null)) },
                subscribeToWorkspaceChanges = false,
            ),
            workspaceState = workspace,
            notifier = { title, content -> notifications += title to content },
            backgroundRunner = null,
        )

        var result: Result<FindingsSnapshot>? = null
        findings.refresh { result = it }

        assertNotNull(result)
        assertTrue(result!!.isFailure)
        assertFalse(workspace.loading)
        assertEquals(listOf("Failed to load findings" to "backend boom"), notifications)
    }

    @Test
    fun `findings service runs through background runner and clears loading on success`() {
        val profile = ConnectionProfile(
            id = "p1",
            name = "Server",
            type = SonarProfileType.SERVER,
            baseUrl = "http://localhost:9000",
            authMode = AuthMode.BEARER,
        )
        val settings = SonarSettingsService().apply {
            saveProfiles(listOf(profile))
            setActiveProfileId("p1")
        }
        val workspace = WorkspaceStateService(null, PromptTarget.CODEX, PromptStyle.BALANCED)
        val taskHolder = mutableListOf<Task.Backgroundable>()
        val findings = FindingsService(
            project = dummyProject,
            backend = object : SonarBackend {
                override fun testConnection(profile: ConnectionProfile, token: String?, project: DiscoveredSonarProject?): ConnectionDiagnostics =
                    ConnectionDiagnostics(true, token ?: "no-token")

                override fun loadFindings(profile: ConnectionProfile, token: String, project: DiscoveredSonarProject): FindingsSnapshot =
                    FindingsSnapshot(coverage = listOf(CoverageFinding("C-1", "src/Test.kt", 80.0, 70.0, 60.0, 2, 1)))
            },
            settings = settings,
            tokens = SecureTokenService(
                tokenStore = object : TokenStore {
                    override fun getPassword(attributes: com.intellij.credentialStore.CredentialAttributes): String? = "stored-token"
                    override fun set(attributes: com.intellij.credentialStore.CredentialAttributes, credentials: com.intellij.credentialStore.Credentials?) = Unit
                },
                envTokenReader = { null },
            ),
            discoveredProjects = DiscoveredProjectService(
                project = null,
                settings = settings,
                basePathOverride = "/tmp",
                discoverProjects = { listOf(DiscoveredSonarProject("/tmp", "demo-key", null)) },
                subscribeToWorkspaceChanges = false,
            ),
            workspaceState = workspace,
            notifier = { _, _ -> error("should not notify") },
            backgroundRunner = { taskHolder += it },
        )

        var result: Result<FindingsSnapshot>? = null
        findings.refresh { result = it }

        assertTrue(workspace.loading)
        assertEquals(1, taskHolder.size)
        assertNull(result)

        val indicator = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(ProgressIndicator::class.java),
        ) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Double.TYPE -> 0.0
                java.lang.Float.TYPE -> 0f
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Character.TYPE -> 0.toChar()
                else -> null
            }
        } as ProgressIndicator

        taskHolder.single().run(indicator)

        assertNotNull(result)
        assertTrue(result!!.isSuccess)
        assertFalse(workspace.loading)
        assertEquals(1, findings.latestSnapshot().coverage.size)
    }

    @Test
    fun `findings service uses null token for direct connection test and unknown error message fallback`() {
        val notifications = mutableListOf<Pair<String, String>>()
        val backend = object : SonarBackend {
            override fun testConnection(profile: ConnectionProfile, token: String?, project: DiscoveredSonarProject?): ConnectionDiagnostics =
                ConnectionDiagnostics(token == null, if (token == null) "missing token accepted" else "unexpected token")

            override fun loadFindings(profile: ConnectionProfile, token: String, project: DiscoveredSonarProject): FindingsSnapshot {
                throw IllegalStateException()
            }
        }
        val profile = ConnectionProfile(
            id = "p1",
            name = "Server",
            type = SonarProfileType.SERVER,
            baseUrl = "http://localhost:9000",
            authMode = AuthMode.BEARER,
        )

        val noProjectFindings = FindingsService(
            project = null,
            backend = backend,
            settings = SonarSettingsService(),
            tokens = SecureTokenService(
                tokenStore = object : TokenStore {
                    override fun getPassword(attributes: com.intellij.credentialStore.CredentialAttributes): String? = error("should not load token")
                    override fun set(attributes: com.intellij.credentialStore.CredentialAttributes, credentials: com.intellij.credentialStore.Credentials?) = Unit
                },
                envTokenReader = { error("should not read env token") },
            ),
            discoveredProjects = null,
            workspaceState = null,
            notifier = { title, content -> notifications += title to content },
            backgroundRunner = null,
        )

        assertEquals("missing token accepted", noProjectFindings.testConnection(profile, null, null).summary)

        val settings = SonarSettingsService().apply {
            saveProfiles(listOf(profile))
            setActiveProfileId("p1")
        }
        val findings = FindingsService(
            project = dummyProject,
            backend = backend,
            settings = settings,
            tokens = SecureTokenService(
                tokenStore = object : TokenStore {
                    override fun getPassword(attributes: com.intellij.credentialStore.CredentialAttributes): String? = "stored-token"
                    override fun set(attributes: com.intellij.credentialStore.CredentialAttributes, credentials: com.intellij.credentialStore.Credentials?) = Unit
                },
                envTokenReader = { null },
            ),
            discoveredProjects = DiscoveredProjectService(
                project = null,
                settings = settings,
                basePathOverride = "/tmp",
                discoverProjects = { listOf(DiscoveredSonarProject("/tmp", "demo-key", null)) },
                subscribeToWorkspaceChanges = false,
            ),
            workspaceState = WorkspaceStateService(null, PromptTarget.CODEX, PromptStyle.BALANCED),
            notifier = { title, content -> notifications += title to content },
            backgroundRunner = null,
        )

        var result: Result<FindingsSnapshot>? = null
        findings.refresh { result = it }

        assertNotNull(result)
        assertTrue(result!!.isFailure)
        assertEquals(listOf("Failed to load findings" to "Unknown error"), notifications)
    }

    @Test
    fun `workspace state service does not mark prompt dirty when selection signature is unchanged`() {
        val workspace = WorkspaceStateService(null, PromptTarget.CODEX, PromptStyle.BALANCED)
        workspace.setSelection(WorkspaceMode.ISSUES, listOf("I-2", "I-1"))
        workspace.lastGeneratedPrompt = "prompt"
        workspace.markPromptGenerated()

        workspace.setSelection(WorkspaceMode.ISSUES, listOf("I-1", "I-2"))
        workspace.markPromptDirtyIfNeeded()

        assertFalse(workspace.promptDirty)
        assertEquals("ISSUES:I-1,I-2|COVERAGE:|DUPLICATION:|HOTSPOTS:", workspace.promptSelectionSignature)
    }
}
