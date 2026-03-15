package com.sonarpromptstudio.service

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
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
        if (project != null && ApplicationManager.getApplication() != null) {
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
            completeFailure(
                onComplete = onComplete,
                failure = IllegalStateException(MISSING_CONTEXT_MESSAGE),
                onEdt = false,
            )
            return
        }

        workspaceState?.loading = true
        if (backgroundRunner == null) {
            val token = resolveToken(profile)
            if (token.isNullOrBlank()) {
                workspaceState?.loading = false
                completeFailure(
                    onComplete = onComplete,
                    failure = IllegalStateException(MISSING_TOKEN_MESSAGE),
                    onEdt = false,
                )
                return
            }
            loadFindings(profile, token, discoveredProject, onComplete)
            return
        }
        val taskProject = project
        backgroundRunner.invoke(object : Task.Backgroundable(taskProject, "Loading Sonar findings", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val token = resolveToken(profile)
                if (token.isNullOrBlank()) {
                    workspaceState?.loading = false
                    completeFailure(
                        onComplete = onComplete,
                        failure = IllegalStateException(MISSING_TOKEN_MESSAGE),
                        onEdt = true,
                    )
                    return
                }
                loadFindings(profile, token, discoveredProject, onComplete)
            }
        })
    }

    fun clearItems() {
        latestSnapshot = FindingsSnapshot()
        workspaceState?.resetItems()
    }

    private fun notifyError(title: String, content: String) {
        if (backgroundRunner == null) {
            notifier(title, content)
            return
        }
        invokeLaterIfAvailable {
            notifier(title, content)
        }
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
            complete(onComplete, Result.success(snapshot))
        } catch (t: Throwable) {
            workspaceState?.loading = false
            completeFailure(onComplete, t, onEdt = backgroundRunner != null)
        }
    }

    private fun resolveToken(profile: ConnectionProfile): String? = project?.let { tokens.loadToken(it, profile.id) }

    private fun complete(onComplete: ((Result<FindingsSnapshot>) -> Unit)?, result: Result<FindingsSnapshot>) {
        if (backgroundRunner == null) {
            onComplete?.invoke(result)
            return
        }
        completeOnEdt(onComplete, result)
    }

    private fun completeFailure(
        onComplete: ((Result<FindingsSnapshot>) -> Unit)?,
        failure: Throwable,
        onEdt: Boolean,
    ) {
        notifyError(LOAD_FAILURE_TITLE, failure.message ?: UNKNOWN_ERROR_MESSAGE)
        if (onEdt) {
            completeOnEdt(onComplete, Result.failure(failure))
        } else {
            onComplete?.invoke(Result.failure(failure))
        }
    }

    private fun completeOnEdt(onComplete: ((Result<FindingsSnapshot>) -> Unit)?, result: Result<FindingsSnapshot>) {
        invokeLaterIfAvailable {
            onComplete?.invoke(result)
        }
    }

    private fun invokeLaterIfAvailable(action: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application == null) {
            action()
            return
        }
        application.invokeLater(action)
    }

    companion object {
        private const val LOAD_FAILURE_TITLE = "Failed to load findings"
        private const val UNKNOWN_ERROR_MESSAGE = "Unknown error"
        private const val MISSING_CONTEXT_MESSAGE = "Missing active profile or project."
        private const val MISSING_TOKEN_MESSAGE = "No token available."

        fun getInstance(project: Project): FindingsService = project.service()
    }
}
