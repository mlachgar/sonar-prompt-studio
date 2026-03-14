package com.sonarpromptstudio.state

import com.intellij.util.xmlb.annotations.OptionTag
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import com.sonarpromptstudio.model.AuthMode
import com.sonarpromptstudio.model.ConnectionProfile
import com.sonarpromptstudio.model.PromptStyle
import com.sonarpromptstudio.model.PromptTarget
import com.sonarpromptstudio.model.SonarProfileType

@Tag("sonarPromptStudio")
data class SonarSettingsState(
    @OptionTag("activeProfileId")
    var activeProfileId: String? = null,
    @XCollection(style = XCollection.Style.v2)
    var profiles: MutableList<ConnectionProfileState> = mutableListOf(),
    @OptionTag("activeProjectPath")
    var activeProjectPath: String? = null,
    @OptionTag("defaultPromptTarget")
    var defaultPromptTarget: PromptTarget = PromptTarget.CODEX,
    @OptionTag("defaultPromptStyle")
    var defaultPromptStyle: PromptStyle = PromptStyle.BALANCED,
    @OptionTag("groupingMode")
    var groupingMode: String? = null,
    @OptionTag("onboardingShown")
    var onboardingShown: Boolean = false,
)

data class ConnectionProfileState(
    var id: String = "",
    var name: String = "",
    var type: String = SonarProfileType.CLOUD.name,
    var baseUrl: String = "",
    var branchOverride: String? = null,
    var pullRequestOverride: String? = null,
    var tlsVerificationEnabled: Boolean = true,
    var authMode: String = AuthMode.BEARER.name,
) {
    fun toDomain(): ConnectionProfile = ConnectionProfile(
        id = id,
        name = name,
        type = SonarProfileType.valueOf(type),
        baseUrl = baseUrl,
        branchOverride = branchOverride?.ifBlank { null },
        pullRequestOverride = pullRequestOverride?.ifBlank { null },
        tlsVerificationEnabled = tlsVerificationEnabled,
        authMode = AuthMode.valueOf(authMode),
    )

    companion object {
        fun fromDomain(profile: ConnectionProfile): ConnectionProfileState = ConnectionProfileState(
            id = profile.id,
            name = profile.name,
            type = profile.type.name,
            baseUrl = profile.baseUrl,
            branchOverride = profile.branchOverride,
            pullRequestOverride = profile.pullRequestOverride,
            tlsVerificationEnabled = profile.tlsVerificationEnabled,
            authMode = profile.authMode.name,
        )
    }
}
