package com.sonarpromptstudio.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.sonarpromptstudio.ui.UiFacade

class OpenConfigurationAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        UiFacade.openConfiguration(e.project)
    }
}
