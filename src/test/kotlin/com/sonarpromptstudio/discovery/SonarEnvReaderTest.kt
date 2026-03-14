package com.sonarpromptstudio.discovery

import com.sonarpromptstudio.util.SonarEnvReader
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
