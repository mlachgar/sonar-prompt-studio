package com.sonarpromptstudio.backend

import com.sonarpromptstudio.model.ConnectionDiagnostics
import com.sonarpromptstudio.model.ConnectionProfile
import com.sonarpromptstudio.model.DiscoveredSonarProject
import com.sonarpromptstudio.model.FindingsSnapshot

interface SonarBackend {
    fun testConnection(profile: ConnectionProfile, token: String?, project: DiscoveredSonarProject?): ConnectionDiagnostics
    fun loadFindings(profile: ConnectionProfile, token: String, project: DiscoveredSonarProject): FindingsSnapshot
}
