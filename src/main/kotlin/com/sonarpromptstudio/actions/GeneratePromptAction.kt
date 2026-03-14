package com.sonarpromptstudio.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.sonarpromptstudio.ui.UiFacade
import com.sonarpromptstudio.ui.WorkspacePanel

class GeneratePromptAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let {
            UiFacade.openWorkspace(it)
            WorkspacePanel.generate(it)
        }
    }
}
