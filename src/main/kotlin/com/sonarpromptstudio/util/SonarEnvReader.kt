package com.sonarpromptstudio.util

import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

object SonarEnvReader {
    fun readToken(project: Project): String? {
        return readToken(project.basePath)
    }

    fun readToken(basePath: String?): String? {
        basePath ?: return null
        val envPath = Path.of(basePath, ".env")
        if (!envPath.exists()) return null
        val token = Files.readAllLines(envPath)
            .asSequence()
            .map { it.trim() }
            .filter { it.startsWith("SONAR_TOKEN=") || it.startsWith("export SONAR_TOKEN=") }
            .map { line -> line.substringAfter("SONAR_TOKEN=").trim().trim('"', '\'') }
            .firstOrNull()
            ?.ifBlank { null }
        return token
    }
}
