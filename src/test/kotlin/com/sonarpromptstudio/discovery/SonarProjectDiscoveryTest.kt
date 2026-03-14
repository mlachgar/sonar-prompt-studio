package com.sonarpromptstudio.discovery

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SonarProjectDiscoveryTest {
    @Test
    fun `discovers root and first-level sonar projects only`() {
        val root = createTempDirectory("sonar-discovery")
        root.resolve("sonar-project.properties").writeText(
            """
            sonar.projectKey=root-key
            sonar.organization=root-org
            """.trimIndent(),
        )
        val child = root.resolve("module").createDirectories()
        child.resolve("sonar-project.properties").writeText("sonar.projectKey=child-key\n")
        val nested = child.resolve("nested").createDirectories()
        nested.resolve("sonar-project.properties").writeText("sonar.projectKey=ignored\n")

        val discovered = SonarProjectDiscovery.discover(root)

        assertEquals(2, discovered.size)
        assertEquals(setOf("child-key", "root-key"), discovered.map { it.sonarProjectKey }.toSet())
    }

    @Test
    fun `ignores invalid sonar property files and sorts discovered projects by path`() {
        val root = createTempDirectory("sonar-discovery-invalid")
        root.resolve("sonar-project.properties").writeText("sonar.organization=org-only\n")
        val childA = root.resolve("b-module").createDirectories()
        childA.resolve("sonar-project.properties").writeText("sonar.projectKey=b-key\n")
        val childB = root.resolve("a-module").createDirectories()
        childB.resolve("sonar-project.properties").writeText("sonar.projectKey=a-key\n")
        root.resolve("sonar-project.txt").writeText("sonar.projectKey=ignored\n")

        val discovered = SonarProjectDiscovery.discover(root)

        assertEquals(listOf("a-key", "b-key"), discovered.map { it.sonarProjectKey })
        assertTrue(discovered.none { it.sonarProjectKey == "ignored" })
    }
}
