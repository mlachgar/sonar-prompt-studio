package com.sonarpromptstudio.ui

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

object UiFacade {
    fun openConfiguration(project: Project?) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, SonarSettingsConfigurable::class.java)
    }

    fun openWorkspace(project: Project) {
        ToolWindowManager.getInstance(project).getToolWindow("Sonar Prompt Studio")?.show()
    }
}
