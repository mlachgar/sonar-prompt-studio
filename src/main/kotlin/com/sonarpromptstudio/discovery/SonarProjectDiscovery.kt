package com.sonarpromptstudio.discovery

import com.sonarpromptstudio.model.DiscoveredSonarProject
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name

object SonarProjectDiscovery {
    private const val SONAR_PROJECT_PROPERTIES = "sonar-project.properties"

    fun discover(basePath: Path): List<DiscoveredSonarProject> {
        val candidates = mutableListOf<Path>()
        val rootCandidate = basePath.resolve(SONAR_PROJECT_PROPERTIES)
        if (Files.isRegularFile(rootCandidate)) candidates.add(rootCandidate)

        Files.list(basePath).use { children ->
            children.filter { it.isDirectory() }
                .map { it.resolve(SONAR_PROJECT_PROPERTIES) }
                .filter { Files.isRegularFile(it) }
                .forEach { candidates.add(it) }
        }

        return candidates.mapNotNull(::readProject).sortedBy { it.path }
    }

    private fun readProject(path: Path): DiscoveredSonarProject? {
        if (path.extension != "properties" || path.name != SONAR_PROJECT_PROPERTIES) return null
        val properties = Properties()
        Files.newInputStream(path).use(properties::load)
        val key = properties.getProperty("sonar.projectKey")?.trim()?.ifBlank { null } ?: return null
        val organization = properties.getProperty("sonar.organization")?.trim()?.ifBlank { null }
        return DiscoveredSonarProject(
            path = path.parent.toString(),
            sonarProjectKey = key,
            sonarOrganization = organization,
        )
    }

}
