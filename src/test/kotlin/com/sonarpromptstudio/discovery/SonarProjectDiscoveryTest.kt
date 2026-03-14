package com.sonarpromptstudio.discovery

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
