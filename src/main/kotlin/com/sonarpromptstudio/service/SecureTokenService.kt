package com.sonarpromptstudio.service

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.sonarpromptstudio.util.SonarEnvReader

class SecureTokenService {
    fun loadToken(project: Project, profileId: String): String? {
        val stored = PasswordSafe.instance.getPassword(attributes(profileId))
        if (!stored.isNullOrBlank()) return stored
        return loadEnvToken(project)
    }

    fun loadEnvToken(project: Project): String? = SonarEnvReader.readToken(project)

    fun hasSecureToken(profileId: String): Boolean = !PasswordSafe.instance.getPassword(attributes(profileId)).isNullOrBlank()

    fun saveToken(profileId: String, token: String) {
        PasswordSafe.instance[attributes(profileId)] = Credentials(profileId, token)
    }

    fun removeToken(profileId: String) {
        PasswordSafe.instance[attributes(profileId)] = null
    }

    private fun attributes(profileId: String): CredentialAttributes =
        CredentialAttributes("Sonar Prompt Studio:$profileId")

    companion object {
        fun getInstance(): SecureTokenService =
            ApplicationManager.getApplication().getService(SecureTokenService::class.java)
    }
}
