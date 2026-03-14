package com.sonarpromptstudio.service

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList

class UiRefreshService(@Suppress("UNUSED_PARAMETER") private val project: Project? = null) {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun subscribe(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun fire() {
        listeners.forEach { it() }
    }

    companion object {
        fun getInstance(project: Project): UiRefreshService = project.service()
    }
}
