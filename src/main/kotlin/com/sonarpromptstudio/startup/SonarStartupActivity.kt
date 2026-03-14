package com.sonarpromptstudio.startup

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.startup.StartupManager
import com.sonarpromptstudio.model.AuthMode
import com.sonarpromptstudio.model.ConnectionProfile
import com.sonarpromptstudio.model.ConnectionDiagnostics
import com.sonarpromptstudio.model.SonarProfileType
import com.sonarpromptstudio.service.DiscoveredProjectService
import com.sonarpromptstudio.service.FindingsService
import com.sonarpromptstudio.service.SecureTokenService
import com.sonarpromptstudio.service.SonarSettingsService
import com.sonarpromptstudio.service.UiRefreshService
import com.sonarpromptstudio.ui.UiFacade
import java.util.concurrent.ConcurrentHashMap

class SonarStartupActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        val settings = SonarSettingsService.getInstance()
        val findings = FindingsService.getInstance(project)
        val tokenService = SecureTokenService.getInstance()

        DiscoveredProjectService.getInstance(project).rescan()
        showOnboardingIfNeeded(project, settings)

        val activeProfile = findings.activeProfile()
        if (activeProfile != null && !tokenService.loadToken(project, activeProfile.id).isNullOrBlank()) {
            findings.refresh { UiRefreshService.getInstance(project).fire() }
        } else {
            runSonarCloudAutoDetection(project)
        }

        StartupManager.getInstance(project).runAfterOpened {
            ApplicationManager.getApplication().invokeLater {
                runSonarCloudAutoDetection(project)
            }
        }
    }

    private fun runSonarCloudAutoDetection(project: Project) {
        val settings = SonarSettingsService.getInstance()
        val discovered = DiscoveredProjectService.getInstance(project)
        val findings = FindingsService.getInstance(project)
        val tokenService = SecureTokenService.getInstance()
        if (project.isDisposed) return
        if (settings.profiles().isNotEmpty()) return

        discovered.rescan()
        val projectRef = discovered.activeProject()
        val envToken = tokenService.loadEnvToken(project)
        if (projectRef != null && projectRef.isSonarCloudCandidate() && !envToken.isNullOrBlank()) {
            val suggested = ConnectionProfile(
                name = "SonarCloud",
                type = SonarProfileType.CLOUD,
                baseUrl = ConnectionProfile.DEFAULT_SONARCLOUD_URL,
                authMode = AuthMode.BEARER,
            )
            val diagnostics = findings.testConnection(suggested, projectRef, envToken)
            if (diagnostics.success) {
                offerReusableProfile(project, settings, tokenService, suggested, envToken)
            }
        }
    }

    private fun showOnboardingIfNeeded(project: Project, settings: SonarSettingsService) {
        if (!settings.shouldShowOnboarding()) return
        settings.markOnboardingShown()
        NotificationGroupManager.getInstance().getNotificationGroup("Sonar Prompt Studio")
            .createNotification(
                "Sonar Prompt Studio is ready",
                "Use the Sonar Prompt Studio tool window or Settings to connect and generate remediation prompts.",
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimple("Open Configuration") { UiFacade.openConfiguration(project) })
            .addAction(NotificationAction.createSimple("Open Sonar Prompt Studio") { UiFacade.openWorkspace(project) })
            .notify(project)
    }

    private fun offerReusableProfile(
        project: Project,
        settings: SonarSettingsService,
        tokenService: SecureTokenService,
        profile: ConnectionProfile,
        envToken: String,
    ) {
        if (settings.profiles().isNotEmpty()) return
        if (!offeredProjects.add(project.locationHash)) return
        NotificationGroupManager.getInstance().getNotificationGroup("Sonar Prompt Studio")
            .createNotification(
                "Create SonarCloud connection profile?",
                "Detected SONAR_TOKEN in .env and validated SonarCloud access. Create a preconfigured profile with name, URL, and token?",
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimple("Create Profile") {
                if (settings.profiles().isNotEmpty()) return@createSimple
                settings.saveProfiles(listOf(profile))
                settings.setActiveProfileId(profile.id)
                tokenService.saveToken(profile.id, envToken)
                UiRefreshService.getInstance(project).fire()
            })
            .addAction(NotificationAction.createSimple("Open Configuration") {
                settings.setPendingProfileDraft(profile, envToken)
                UiFacade.openConfiguration(project)
            })
            .notify(project)
    }

    companion object {
        private val offeredProjects = ConcurrentHashMap.newKeySet<String>()
    }
}
