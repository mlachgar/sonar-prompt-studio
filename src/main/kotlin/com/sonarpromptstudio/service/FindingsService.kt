package com.sonarpromptstudio.service

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.sonarpromptstudio.backend.SonarBackendFactory
import com.sonarpromptstudio.model.ConnectionDiagnostics
import com.sonarpromptstudio.model.ConnectionProfile
import com.sonarpromptstudio.model.DiscoveredSonarProject
import com.sonarpromptstudio.model.FindingsSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FindingsService(private val project: Project) {
    private val backend = SonarBackendFactory.create()
    private val settings = SonarSettingsService.getInstance()
    private val tokens = SecureTokenService.getInstance()
    private val discoveredProjects = DiscoveredProjectService.getInstance(project)
    private val workspaceState = WorkspaceStateService.getInstance(project)

    @Volatile
    private var latestSnapshot: FindingsSnapshot = FindingsSnapshot()

    fun latestSnapshot(): FindingsSnapshot = latestSnapshot

    fun activeProfile(): ConnectionProfile? = settings.profiles().firstOrNull { it.id == settings.activeProfileId() }

    fun testConnection(profile: ConnectionProfile, projectRef: DiscoveredSonarProject?, tokenOverride: String? = null): ConnectionDiagnostics {
        val token = tokenOverride ?: tokens.loadToken(project, profile.id)
        return backend.testConnection(profile, token, projectRef)
    }

    fun refresh(onComplete: ((Result<FindingsSnapshot>) -> Unit)? = null) {
        val profile = activeProfile()
        val discoveredProject = discoveredProjects.activeProject()
        if (profile == null || discoveredProject == null) {
            onComplete?.invoke(Result.failure(IllegalStateException("Missing active profile or project.")))
            return
        }
        val token = tokens.loadToken(project, profile.id)
        if (token.isNullOrBlank()) {
            onComplete?.invoke(Result.failure(IllegalStateException("No token available.")))
            return
        }

        workspaceState.loading = true
        object : Task.Backgroundable(project, "Loading Sonar findings", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    val snapshot = backend.loadFindings(profile, token, discoveredProject)
                    latestSnapshot = snapshot
                    workspaceState.lastSnapshot = snapshot
                    workspaceState.lastProfile = profile
                    workspaceState.loading = false
                    onComplete?.invoke(Result.success(snapshot))
                } catch (t: Throwable) {
                    workspaceState.loading = false
                    notifyError("Failed to load findings", t.message ?: "Unknown error")
                    onComplete?.invoke(Result.failure(t))
                }
            }
        }.queue()
    }

    fun clearItems() {
        latestSnapshot = FindingsSnapshot()
        workspaceState.resetItems()
    }

    private fun notifyError(title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Sonar Prompt Studio")
            .createNotification(title, content, NotificationType.ERROR)
            .notify(project)
    }

    companion object {
        fun getInstance(project: Project): FindingsService = project.service()
    }
}
