package com.sonarpromptstudio.util

import com.intellij.openapi.application.ApplicationManager
import javax.swing.SwingUtilities

object UiUtils {
    fun invokeLater(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            action()
        } else {
            ApplicationManager.getApplication().invokeLater(action)
        }
    }
}
