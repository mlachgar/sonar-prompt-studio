package com.sonarpromptstudio.service

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.workspace.WorkspaceModelChangeListener
import com.intellij.platform.backend.workspace.WorkspaceModelTopics
import com.intellij.platform.workspace.storage.VersionedStorageChange
import com.intellij.util.messages.MessageBusConnection
import com.sonarpromptstudio.discovery.SonarProjectDiscovery
import com.sonarpromptstudio.model.DiscoveredSonarProject
import java.nio.file.Path

class DiscoveredProjectService(private val project: Project) {
    private val settings = SonarSettingsService.getInstance()
    private var connection: MessageBusConnection? = null
    @Volatile
    private var discoveredProjects: List<DiscoveredSonarProject> = emptyList()

    init {
        rescan()
        connection = project.messageBus.connect().apply {
            subscribe(WorkspaceModelTopics.CHANGED, object : WorkspaceModelChangeListener {
                override fun changed(event: VersionedStorageChange) {
                    rescan()
                }
            })
        }
    }

    fun rescan(): List<DiscoveredSonarProject> {
        val basePath = project.basePath ?: run {
            return emptyList()
        }
        val found = SonarProjectDiscovery.discover(Path.of(basePath))
        discoveredProjects = found
        val active = settings.activeProjectPath()
        if (found.isNotEmpty() && active == null) {
            settings.setActiveProjectPath(found.first().path)
        } else if (active != null && found.none { it.path == active }) {
            settings.setActiveProjectPath(found.firstOrNull()?.path)
        }
        return found
    }

    fun allProjects(): List<DiscoveredSonarProject> = discoveredProjects

    fun activeProject(): DiscoveredSonarProject? {
        val activePath = settings.activeProjectPath()
        return discoveredProjects.firstOrNull { it.path == activePath } ?: discoveredProjects.firstOrNull()
    }

    fun setActiveProject(path: String?) {
        settings.setActiveProjectPath(path)
    }

    companion object {
        fun getInstance(project: Project): DiscoveredProjectService = project.service()
    }
}
