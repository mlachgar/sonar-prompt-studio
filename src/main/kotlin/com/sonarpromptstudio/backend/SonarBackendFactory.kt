package com.sonarpromptstudio.backend

object SonarBackendFactory {
    fun create(): SonarBackend = SonarHttpBackend()
}
