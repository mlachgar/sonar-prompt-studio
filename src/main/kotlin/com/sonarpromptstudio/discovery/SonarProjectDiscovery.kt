package com.sonarpromptstudio.discovery

import com.sonarpromptstudio.model.DiscoveredSonarProject
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.isDirectory
import kotlin.io.path.name

object SonarProjectDiscovery {
    private const val SONAR_PROJECT_PROPERTIES = "sonar-project.properties"
    private val GRADLE_BUILD_FILES = listOf("build.gradle.kts", "build.gradle")
    private val GRADLE_PROPERTY_PATTERNS = mapOf(
        "sonar.projectKey" to Regex(
            """(?m)(?:property\s*\(\s*["']sonar\.projectKey["']\s*,\s*["']([^"']+)["']\s*\)|property\s+["']sonar\.projectKey["']\s*,\s*["']([^"']+)["']|["']sonar\.projectKey["']\s*[:=]\s*["']([^"']+)["'])""",
        ),
        "sonar.organization" to Regex(
            """(?m)(?:property\s*\(\s*["']sonar\.organization["']\s*,\s*["']([^"']+)["']\s*\)|property\s+["']sonar\.organization["']\s*,\s*["']([^"']+)["']|["']sonar\.organization["']\s*[:=]\s*["']([^"']+)["'])""",
        ),
    )

    fun discover(basePath: Path): List<DiscoveredSonarProject> {
        val candidates = mutableListOf<DiscoveryCandidate>()
        candidates.addAll(candidatesForDirectory(basePath))

        Files.list(basePath).use { children ->
            children.filter { it.isDirectory() }
                .forEach { candidates.addAll(candidatesForDirectory(it)) }
        }

        return candidates
            .mapNotNull(::readProject)
            .groupBy { it.project.path }
            .values
            .map { projects -> projects.minBy { it.source.priority }.project }
            .sortedBy { it.path }
    }

    private fun candidatesForDirectory(directory: Path): List<DiscoveryCandidate> {
        val candidates = mutableListOf<DiscoveryCandidate>()
        val propertiesFile = directory.resolve(SONAR_PROJECT_PROPERTIES)
        if (Files.isRegularFile(propertiesFile)) {
            candidates.add(DiscoveryCandidate(propertiesFile, DiscoverySource.PROPERTIES))
        }
        GRADLE_BUILD_FILES
            .map(directory::resolve)
            .filter(Files::isRegularFile)
            .forEach { candidates.add(DiscoveryCandidate(it, DiscoverySource.GRADLE)) }
        return candidates
    }

    private fun readProject(candidate: DiscoveryCandidate): DiscoveredProjectCandidate? = when (candidate.source) {
        DiscoverySource.PROPERTIES -> readPropertiesProject(candidate)
        DiscoverySource.GRADLE -> readGradleProject(candidate)
    }

    private fun readPropertiesProject(candidate: DiscoveryCandidate): DiscoveredProjectCandidate? {
        val properties = Properties()
        Files.newInputStream(candidate.path).use(properties::load)
        val key = properties.getProperty("sonar.projectKey")?.trim()?.ifBlank { null } ?: return null
        val organization = properties.getProperty("sonar.organization")?.trim()?.ifBlank { null }
        return DiscoveredProjectCandidate(
            project = DiscoveredSonarProject(
                path = candidate.path.parent.toString(),
                sonarProjectKey = key,
                sonarOrganization = organization,
            ),
            source = candidate.source,
        )
    }

    private fun readGradleProject(candidate: DiscoveryCandidate): DiscoveredProjectCandidate? {
        val content = Files.readString(candidate.path)
        val key = extractGradleProperty(content, "sonar.projectKey") ?: return null
        val organization = extractGradleProperty(content, "sonar.organization")
        return DiscoveredProjectCandidate(
            project = DiscoveredSonarProject(
                path = candidate.path.parent.toString(),
                sonarProjectKey = key,
                sonarOrganization = organization,
            ),
            source = candidate.source,
        )
    }

    private fun extractGradleProperty(content: String, propertyName: String): String? =
        GRADLE_PROPERTY_PATTERNS.getValue(propertyName)
            .find(content)
            ?.groupValues
            ?.drop(1)
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.ifBlank { null }

    private data class DiscoveryCandidate(
        val path: Path,
        val source: DiscoverySource,
    )

    private data class DiscoveredProjectCandidate(
        val project: DiscoveredSonarProject,
        val source: DiscoverySource,
    )

    private enum class DiscoverySource(val priority: Int) {
        PROPERTIES(0),
        GRADLE(1),
    }
}
