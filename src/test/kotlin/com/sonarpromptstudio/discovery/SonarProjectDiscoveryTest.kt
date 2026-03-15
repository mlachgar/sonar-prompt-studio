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

    @Test
    fun `discovers sonar properties from gradle builds in root and first-level modules`() {
        val root = createTempDirectory("sonar-discovery-gradle")
        root.resolve("build.gradle.kts").writeText(
            """
            sonar {
                properties {
                    property("sonar.projectKey", "root-key")
                    property("sonar.organization", "root-org")
                }
            }
            """.trimIndent(),
        )
        val child = root.resolve("module").createDirectories()
        child.resolve("build.gradle").writeText(
            """
            sonar {
                properties {
                    property 'sonar.projectKey', 'child-key'
                }
            }
            """.trimIndent(),
        )

        val discovered = SonarProjectDiscovery.discover(root)

        assertEquals(2, discovered.size)
        assertEquals(setOf("child-key", "root-key"), discovered.map { it.sonarProjectKey }.toSet())
        assertEquals("root-org", discovered.single { it.sonarProjectKey == "root-key" }.sonarOrganization)
    }

    @Test
    fun `prefers sonar-project properties over gradle in the same directory`() {
        val root = createTempDirectory("sonar-discovery-priority")
        root.resolve("sonar-project.properties").writeText("sonar.projectKey=properties-key\nsonar.organization=properties-org\n")
        root.resolve("build.gradle.kts").writeText(
            """
            sonar {
                properties {
                    property("sonar.projectKey", "gradle-key")
                    property("sonar.organization", "gradle-org")
                }
            }
            """.trimIndent(),
        )

        val discovered = SonarProjectDiscovery.discover(root)

        assertEquals(1, discovered.size)
        assertEquals("properties-key", discovered.single().sonarProjectKey)
        assertEquals("properties-org", discovered.single().sonarOrganization)
    }
}
