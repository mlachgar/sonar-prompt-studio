package com.sonarpromptstudio.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.sonarpromptstudio.service.SonarSettingsService
import com.sonarpromptstudio.service.UiRefreshService

class ResetConnectionsAndProjectsAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val confirmed = Messages.showYesNoDialog(
            e.project,
            "Reset Sonar Prompt Studio connection profiles, active profile, active project, and plugin cache state?",
            "Reset Sonar Prompt Studio",
            null,
        )
        if (confirmed == Messages.YES) {
            SonarSettingsService.getInstance().resetPluginState()
            e.project?.let { UiRefreshService.getInstance(it).fire() }
        }
    }
}
