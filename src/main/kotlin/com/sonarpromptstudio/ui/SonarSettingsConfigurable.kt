package com.sonarpromptstudio.ui

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.FormBuilder
import com.sonarpromptstudio.model.AuthMode
import com.sonarpromptstudio.model.ConnectionProfile
import com.sonarpromptstudio.model.PromptStyle
import com.sonarpromptstudio.model.PromptTarget
import com.sonarpromptstudio.model.SonarProfileType
import com.sonarpromptstudio.service.DiscoveredProjectService
import com.sonarpromptstudio.service.FindingsService
import com.sonarpromptstudio.service.SecureTokenService
import com.sonarpromptstudio.service.SonarSettingsService
import com.sonarpromptstudio.service.UiRefreshService
import com.sonarpromptstudio.service.WorkspaceStateService
import java.awt.BorderLayout
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JTextField

class SonarSettingsConfigurable : Configurable {
    private val settings = SonarSettingsService.getInstance()
    private val tokens = SecureTokenService.getInstance()

    private val profileSelector = JComboBox<ProfileOption>()
    private val nameField = JTextField()
    private val typeBox = JComboBox(SonarProfileType.entries.toTypedArray())
    private val urlField = JTextField()
    private val branchField = JTextField()
    private val prField = JTextField()
    private val tlsBox = JComboBox(arrayOf(true, false))
    private val authBox = JComboBox(AuthMode.entries.toTypedArray())
    private val tokenField = JPasswordField()
    private val defaultTargetBox = JComboBox(PromptTarget.entries.toTypedArray())
    private val defaultStyleBox = JComboBox(PromptStyle.entries.toTypedArray())
    private var panel: JPanel? = null
    private var pendingDraft: Pair<ConnectionProfile, String?>? = null

    override fun getDisplayName(): String = "Sonar Prompt Studio"

    override fun createComponent(): JPanel {
        pendingDraft = settings.pendingProfileDraft()
        panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(buildProfilesPanel(), BorderLayout.CENTER)
        }
        reloadProfiles()
        return panel!!
    }

    private fun buildProfilesPanel(): JPanel {
        profileSelector.addActionListener {
            (profileSelector.selectedItem as? ProfileOption)?.profile?.let(::populateFields)
        }
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent("Profiles", profileSelector)
            .addComponent(toolbarPanel(
                actionButton("Create Profile") { clearFields() },
                actionButton("Delete Profile") { deleteSelectedProfile() },
                actionButton("Test Connection") { testConnection() },
            ))
            .addLabeledComponent("Name", nameField)
            .addLabeledComponent("Type", typeBox)
            .addLabeledComponent("Base URL", urlField)
            .addLabeledComponent("Branch Override", branchField)
            .addLabeledComponent("Pull Request Override", prField)
            .addLabeledComponent("TLS Verification", tlsBox)
            .addLabeledComponent("Auth Mode", authBox)
            .addLabeledComponent("Token", tokenField)
            .addComponent(toolbarPanel(actionButton("Remove Token") { removeToken() }))
            .addLabeledComponent("Default Prompt Target", defaultTargetBox)
            .addLabeledComponent("Default Prompt Style", defaultStyleBox)
            .addComponent(JBLabel("A secure token always overrides SONAR_TOKEN from local .env."))
            .panel
        defaultTargetBox.selectedItem = settings.defaultPromptTarget()
        defaultStyleBox.selectedItem = settings.defaultPromptStyle()
        return form
    }

    private fun populateFields(profile: ConnectionProfile) {
        nameField.text = profile.name
        typeBox.selectedItem = profile.type
        urlField.text = profile.baseUrl
        branchField.text = profile.branchOverride.orEmpty()
        prField.text = profile.pullRequestOverride.orEmpty()
        tlsBox.selectedItem = profile.tlsVerificationEnabled
        authBox.selectedItem = profile.authMode
        tokenField.text = ""
    }

    private fun populateDraft(profile: ConnectionProfile, token: String?) {
        profileSelector.selectedItem = null
        nameField.text = profile.name
        typeBox.selectedItem = profile.type
        urlField.text = profile.baseUrl
        branchField.text = profile.branchOverride.orEmpty()
        prField.text = profile.pullRequestOverride.orEmpty()
        tlsBox.selectedItem = profile.tlsVerificationEnabled
        authBox.selectedItem = profile.authMode
        tokenField.text = token.orEmpty()
    }

    private fun clearFields() {
        profileSelector.selectedItem = null
        populateFields(
            ConnectionProfile(
                name = "",
                type = SonarProfileType.CLOUD,
                baseUrl = ConnectionProfile.DEFAULT_SONARCLOUD_URL,
            ),
        )
    }

    private fun clearEditor() {
        profileSelector.selectedItem = null
        nameField.text = ""
        typeBox.selectedItem = SonarProfileType.CLOUD
        urlField.text = ConnectionProfile.DEFAULT_SONARCLOUD_URL
        branchField.text = ""
        prField.text = ""
        tlsBox.selectedItem = true
        authBox.selectedItem = AuthMode.BEARER
        tokenField.text = ""
    }

    override fun isModified(): Boolean = true

    override fun apply() {
        val selected = selectedProfile()
        if (selected == null && nameField.text.trim().isBlank()) {
            settings.setDefaultPromptTarget(defaultTargetBox.selectedItem as PromptTarget)
            settings.setDefaultPromptStyle(defaultStyleBox.selectedItem as PromptStyle)
            settings.clearPendingProfileDraft()
            notifyProjects()
            return
        }
        val updated = ConnectionProfile(
            id = selected?.id ?: java.util.UUID.randomUUID().toString(),
            name = nameField.text.trim(),
            type = typeBox.selectedItem as SonarProfileType,
            baseUrl = normalizeBaseUrl(typeBox.selectedItem as SonarProfileType, urlField.text.trim()),
            branchOverride = branchField.text.trim().ifBlank { null },
            pullRequestOverride = prField.text.trim().ifBlank { null },
            tlsVerificationEnabled = tlsBox.selectedItem as Boolean,
            authMode = authBox.selectedItem as AuthMode,
        )
        val profiles = settings.profiles().toMutableList()
        val existingIndex = profiles.indexOfFirst { it.id == updated.id }
        if (existingIndex >= 0) profiles[existingIndex] = updated else profiles += updated
        settings.saveProfiles(profiles)
        settings.setActiveProfileId(updated.id)
        val token = String(tokenField.password).trim()
        if (token.isNotBlank()) {
            tokens.saveToken(updated.id, token)
        }
        settings.setDefaultPromptTarget(defaultTargetBox.selectedItem as PromptTarget)
        settings.setDefaultPromptStyle(defaultStyleBox.selectedItem as PromptStyle)
        settings.clearPendingProfileDraft()
        pendingDraft = null
        reloadProfiles(updated.id)
        notifyProjects()
    }

    override fun reset() {
        if (pendingDraft == null) {
            pendingDraft = settings.pendingProfileDraft()
        }
        reloadProfiles()
    }

    private fun reloadProfiles(selectedId: String? = settings.activeProfileId()) {
        profileSelector.removeAllItems()
        settings.profiles().map(::ProfileOption).forEach(profileSelector::addItem)
        pendingDraft?.let { (profile, token) ->
            populateDraft(profile, token)
            return
        }
        settings.profiles().firstOrNull { it.id == selectedId }?.let {
            profileSelector.selectedItem = ProfileOption(it)
            populateFields(it)
            return
        }
        clearEditor()
    }

    private fun deleteSelectedProfile() {
        val selected = selectedProfile() ?: return
        val deletedWasActive = settings.activeProfileId() == selected.id
        settings.saveProfiles(settings.profiles().filterNot { it.id == selected.id })
        if (settings.activeProfileId() == selected.id) {
            settings.setActiveProfileId(settings.profiles().firstOrNull()?.id)
        }
        tokens.removeToken(selected.id)
        reloadProfiles()
        resetProjectsAfterProfileDelete(selected.id, deletedWasActive)
    }

    private fun removeToken() {
        val profile = selectedProfile() ?: return
        tokens.removeToken(profile.id)
        tokenField.text = ""
        Messages.showInfoMessage("Token removed from secure storage.", DIALOG_TITLE)
    }

    private fun testConnection() {
        val ideaProject = ProjectManager.getInstance().openProjects.firstOrNull()
        val projectRef = ideaProject?.let { DiscoveredProjectService.getInstance(it).activeProject() }
        val profile = ConnectionProfile(
            id = selectedProfile()?.id ?: "transient",
            name = nameField.text.trim(),
            type = typeBox.selectedItem as SonarProfileType,
            baseUrl = normalizeBaseUrl(typeBox.selectedItem as SonarProfileType, urlField.text.trim()),
            branchOverride = branchField.text.trim().ifBlank { null },
            pullRequestOverride = prField.text.trim().ifBlank { null },
            tlsVerificationEnabled = tlsBox.selectedItem as Boolean,
            authMode = authBox.selectedItem as AuthMode,
        )
        val tokenOverride = String(tokenField.password).trim().ifBlank { null }
        val diagnostics = if (ideaProject != null) FindingsService.getInstance(ideaProject).testConnection(profile, projectRef, tokenOverride) else null
        Messages.showInfoMessage(
            diagnostics?.let { "${it.summary}\n${it.details.joinToString("\n")}" } ?: "Open a project first to test the connection.",
            DIALOG_TITLE,
        )
    }

    private fun notifyProjects() {
        ProjectManager.getInstance().openProjects.forEach { UiRefreshService.getInstance(it).fire() }
    }

    private fun resetProjectsAfterProfileDelete(deletedProfileId: String, deletedWasActive: Boolean) {
        ProjectManager.getInstance().openProjects.forEach { project ->
            val findings = FindingsService.getInstance(project)
            val shouldReset = deletedWasActive ||
                findings.activeProfile() == null ||
                WorkspaceStateService.getInstance(project).lastProfile?.id == deletedProfileId
            if (shouldReset) {
                findings.clearItems()
            }
            UiRefreshService.getInstance(project).fire()
        }
    }

    private fun normalizeBaseUrl(type: SonarProfileType, input: String): String =
        if (type == SonarProfileType.CLOUD) input.ifBlank { ConnectionProfile.DEFAULT_SONARCLOUD_URL } else input

    private fun selectedProfile(): ConnectionProfile? = (profileSelector.selectedItem as? ProfileOption)?.profile

    private data class ProfileOption(val profile: ConnectionProfile) {
        override fun toString(): String = profile.name.ifBlank { "Unnamed Profile" }
    }

    companion object {
        private const val DIALOG_TITLE = "Sonar Prompt Studio"
    }
}
