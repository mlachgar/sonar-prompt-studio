package com.sonarpromptstudio.state

import com.sonarpromptstudio.model.ConnectionProfile
import com.sonarpromptstudio.model.SonarProfileType
import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectionProfileDefaultsTest {
    @Test
    fun `keeps explicit base url when cloud profile url is provided`() {
        val profile = ConnectionProfile(
            id = "1",
            name = "SonarCloud",
            type = SonarProfileType.CLOUD,
            baseUrl = "https://example.sonarcloud.test",
        )

        assertEquals("https://example.sonarcloud.test", profile.effectiveBaseUrl())
    }

    @Test
    fun `uses sonarcloud default url when cloud base url is blank`() {
        val profile = ConnectionProfile(
            id = "1",
            name = "SonarCloud",
            type = SonarProfileType.CLOUD,
            baseUrl = "",
        )

        assertEquals(ConnectionProfile.DEFAULT_SONARCLOUD_URL, profile.effectiveBaseUrl())
    }

    @Test
    fun `detects sonarcloud candidate from sonar organization`() {
        val project = com.sonarpromptstudio.model.DiscoveredSonarProject(
            path = "/tmp",
            sonarProjectKey = "demo",
            sonarOrganization = "my-org",
        )

        assertEquals(true, project.isSonarCloudCandidate())
    }
}
