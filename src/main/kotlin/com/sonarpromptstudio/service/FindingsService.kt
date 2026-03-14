package com.sonarpromptstudio.service

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.sonarpromptstudio.backend.SonarBackendFactory
import com.sonarpromptstudio.backend.SonarBackend
import com.sonarpromptstudio.model.ConnectionDiagnostics
import com.sonarpromptstudio.model.ConnectionProfile
import com.sonarpromptstudio.model.DiscoveredSonarProject
import com.sonarpromptstudio.model.FindingsSnapshot

class FindingsService @JvmOverloads constructor(
    private val project: Project? = null,
    private val backend: SonarBackend = SonarBackendFactory.create(),
    private val settings: SonarSettingsService = SonarSettingsService.getInstance(),
    private val tokens: SecureTokenService = SecureTokenService.getInstance(),
    private val discoveredProjects: DiscoveredProjectService? = project?.let { DiscoveredProjectService.getInstance(it) },
    private val workspaceState: WorkspaceStateService? = project?.let { WorkspaceStateService.getInstance(it) },
    private val notifier: (String, String) -> Unit = { title, content ->
        if (project != null) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Sonar Prompt Studio")
                .createNotification(title, content, NotificationType.ERROR)
                .notify(project)
        }
    },
    private val backgroundRunner: ((Task.Backgroundable) -> Unit)? = { it.queue() },
) {

    @Volatile
    private var latestSnapshot: FindingsSnapshot = FindingsSnapshot()

    fun latestSnapshot(): FindingsSnapshot = latestSnapshot

    fun activeProfile(): ConnectionProfile? = settings.profiles().firstOrNull { it.id == settings.activeProfileId() }

    fun testConnection(profile: ConnectionProfile, projectRef: DiscoveredSonarProject?, tokenOverride: String? = null): ConnectionDiagnostics {
        val token = tokenOverride ?: project?.let { tokens.loadToken(it, profile.id) }
        return backend.testConnection(profile, token, projectRef)
    }

    fun refresh(onComplete: ((Result<FindingsSnapshot>) -> Unit)? = null) {
        val profile = activeProfile()
        val discoveredProject = discoveredProjects?.activeProject()
        if (profile == null || discoveredProject == null) {
            onComplete?.invoke(Result.failure(IllegalStateException("Missing active profile or project.")))
            return
        }
        val token = project?.let { tokens.loadToken(it, profile.id) }
        if (token.isNullOrBlank()) {
            onComplete?.invoke(Result.failure(IllegalStateException("No token available.")))
            return
        }

        workspaceState?.loading = true
        if (backgroundRunner == null) {
            loadFindings(profile, token, discoveredProject, onComplete)
            return
        }
        val taskProject = project
        backgroundRunner.invoke(object : Task.Backgroundable(taskProject, "Loading Sonar findings", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                loadFindings(profile, token, discoveredProject, onComplete)
            }
        })
    }

    fun clearItems() {
        latestSnapshot = FindingsSnapshot()
        workspaceState?.resetItems()
    }

    private fun notifyError(title: String, content: String) {
        notifier(title, content)
    }

    private fun loadFindings(
        profile: ConnectionProfile,
        token: String,
        discoveredProject: DiscoveredSonarProject,
        onComplete: ((Result<FindingsSnapshot>) -> Unit)?,
    ) {
        try {
            val snapshot = backend.loadFindings(profile, token, discoveredProject)
            latestSnapshot = snapshot
            workspaceState?.lastSnapshot = snapshot
            workspaceState?.lastProfile = profile
            workspaceState?.loading = false
            onComplete?.invoke(Result.success(snapshot))
        } catch (t: Throwable) {
            workspaceState?.loading = false
            notifyError("Failed to load findings", t.message ?: "Unknown error")
            onComplete?.invoke(Result.failure(t))
        }
    }

    companion object {
        fun getInstance(project: Project): FindingsService = project.service()
    }
}
