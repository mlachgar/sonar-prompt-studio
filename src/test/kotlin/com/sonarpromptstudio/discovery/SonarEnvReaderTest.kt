package com.sonarpromptstudio.discovery

import com.intellij.openapi.project.Project
import com.sonarpromptstudio.util.SonarEnvReader
import java.lang.reflect.Proxy
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SonarEnvReaderTest {
    @Test
    fun `reads sonar token from local env file`() {
        val root = createTempDirectory("sonar-env")
        root.resolve(".env").writeText(
            """
            SOMETHING_ELSE=1
            SONAR_TOKEN="secret-token"
            """.trimIndent(),
        )

        assertEquals("secret-token", SonarEnvReader.readToken(root.toString()))
    }

    @Test
    fun `reads sonar token from export syntax`() {
        val root = createTempDirectory("sonar-env-export")
        root.resolve(".env").writeText("""export SONAR_TOKEN='another-secret'""")

        assertEquals("another-secret", SonarEnvReader.readToken(root.toString()))
    }

    @Test
    fun `reads sonar token from project base path`() {
        val root = createTempDirectory("sonar-env-project")
        root.resolve(".env").writeText("""SONAR_TOKEN=project-secret""")
        val project = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getBasePath" -> root.toString()
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Double.TYPE -> 0.0
                    java.lang.Float.TYPE -> 0f
                    java.lang.Short.TYPE -> 0.toShort()
                    java.lang.Byte.TYPE -> 0.toByte()
                    java.lang.Character.TYPE -> 0.toChar()
                    String::class.java -> ""
                    else -> null
                }
            }
        } as Project

        assertEquals("project-secret", SonarEnvReader.readToken(project))
    }

    @Test
    fun `returns null when base path or env token is missing or blank`() {
        val root = createTempDirectory("sonar-env-empty")
        root.resolve(".env").writeText(
            """
            SOMETHING=1
            SONAR_TOKEN=
            """.trimIndent(),
        )

        assertNull(SonarEnvReader.readToken(null))
        assertNull(SonarEnvReader.readToken(root.resolve("missing").toString()))
        assertNull(SonarEnvReader.readToken(root.toString()))
    }
}
