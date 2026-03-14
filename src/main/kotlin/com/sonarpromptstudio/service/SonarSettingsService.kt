package com.sonarpromptstudio.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.sonarpromptstudio.model.ConnectionProfile
import com.sonarpromptstudio.model.PromptStyle
import com.sonarpromptstudio.model.PromptTarget
import com.sonarpromptstudio.state.ConnectionProfileState
import com.sonarpromptstudio.state.SonarSettingsState

@State(name = "SonarPromptStudioSettings", storages = [Storage("sonarPromptStudio.xml")])
class SonarSettingsService : PersistentStateComponent<SonarSettingsState> {
    private var state = SonarSettingsState()
    private var pendingProfileDraft: ConnectionProfile? = null
    private var pendingDraftToken: String? = null

    override fun getState(): SonarSettingsState = state

    override fun loadState(state: SonarSettingsState) {
        this.state = state
    }

    fun profiles(): List<ConnectionProfile> = state.profiles.map(ConnectionProfileState::toDomain)

    fun saveProfiles(profiles: List<ConnectionProfile>) {
        state.profiles = profiles.map(ConnectionProfileState::fromDomain).toMutableList()
    }

    fun activeProfileId(): String? = state.activeProfileId
    fun setActiveProfileId(profileId: String?) {
        state.activeProfileId = profileId
    }

    fun activeProjectPath(): String? = state.activeProjectPath
    fun setActiveProjectPath(path: String?) {
        state.activeProjectPath = path
    }

    fun defaultPromptTarget(): PromptTarget = state.defaultPromptTarget
    fun setDefaultPromptTarget(target: PromptTarget) {
        state.defaultPromptTarget = target
    }

    fun defaultPromptStyle(): PromptStyle = state.defaultPromptStyle
    fun setDefaultPromptStyle(style: PromptStyle) {
        state.defaultPromptStyle = style
    }

    fun groupingMode(): String? = state.groupingMode
    fun setGroupingMode(groupingMode: String?) {
        state.groupingMode = groupingMode
    }

    fun shouldShowOnboarding(): Boolean = !state.onboardingShown
    fun markOnboardingShown() {
        state.onboardingShown = true
    }

    fun resetPluginState() {
        state.activeProfileId = null
        state.activeProjectPath = null
        state.profiles.clear()
        state.groupingMode = null
    }

    fun setPendingProfileDraft(profile: ConnectionProfile, token: String?) {
        pendingProfileDraft = profile
        pendingDraftToken = token
    }

    fun pendingProfileDraft(): Pair<ConnectionProfile, String?>? = pendingProfileDraft?.let { it to pendingDraftToken }

    fun clearPendingProfileDraft() {
        pendingProfileDraft = null
        pendingDraftToken = null
    }

    companion object {
        fun getInstance(): SonarSettingsService =
            ApplicationManager.getApplication().getService(SonarSettingsService::class.java)
    }
}
