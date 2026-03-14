package com.sonarpromptstudio.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.sonarpromptstudio.service.FindingsService
import com.sonarpromptstudio.service.UiRefreshService

class RefreshFindingsAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        FindingsService.getInstance(project).refresh { UiRefreshService.getInstance(project).fire() }
    }
}
