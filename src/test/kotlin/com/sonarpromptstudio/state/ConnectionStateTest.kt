package com.sonarpromptstudio.state

import com.sonarpromptstudio.model.AuthMode
import com.sonarpromptstudio.model.ConnectionProfile
import com.sonarpromptstudio.model.SonarProfileType
import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectionStateTest {
    @Test
    fun `round-trips connection profile state`() {
        val profile = ConnectionProfile(
            id = "1",
            name = "Cloud",
            type = SonarProfileType.CLOUD,
            baseUrl = "https://sonarcloud.io",
            branchOverride = "main",
            pullRequestOverride = "42",
            tlsVerificationEnabled = false,
            authMode = AuthMode.BASIC_TOKEN,
        )

        val restored = ConnectionProfileState.fromDomain(profile).toDomain()

        assertEquals(profile, restored)
    }
}
