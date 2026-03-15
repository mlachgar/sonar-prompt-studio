package com.sonarpromptstudio.service

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.sonarpromptstudio.util.SonarEnvReader

class SecureTokenService(
    private val tokenStore: TokenStore = PasswordSafeTokenStore,
    private val envTokenReader: (Project) -> String? = SonarEnvReader::readToken,
) {
    fun loadToken(project: Project, profileId: String): String? {
        val stored = tokenStore.getPassword(attributes(profileId))
        if (!stored.isNullOrBlank()) return stored
        return loadEnvToken(project)
    }

    fun loadEnvToken(project: Project): String? = envTokenReader(project)

    fun hasSecureToken(profileId: String): Boolean = !tokenStore.getPassword(attributes(profileId)).isNullOrBlank()

    fun saveToken(profileId: String, token: String) {
        tokenStore.set(attributes(profileId), Credentials(profileId, token))
    }

    fun removeToken(profileId: String) {
        tokenStore.set(attributes(profileId), null)
    }

    private fun attributes(profileId: String): CredentialAttributes =
        CredentialAttributes(
            serviceName = generateServiceName(SERVICE_NAME, profileId),
            userName = profileId,
        )

    companion object {
        private const val SERVICE_NAME = "Sonar Prompt Studio"

        fun getInstance(): SecureTokenService =
            ApplicationManager.getApplication().getService(SecureTokenService::class.java)
    }
}

interface TokenStore {
    fun getPassword(attributes: CredentialAttributes): String?
    fun set(attributes: CredentialAttributes, credentials: Credentials?)
}

private object PasswordSafeTokenStore : TokenStore {
    override fun getPassword(attributes: CredentialAttributes): String? = PasswordSafe.instance.getPassword(attributes)

    override fun set(attributes: CredentialAttributes, credentials: Credentials?) {
        PasswordSafe.instance[attributes] = credentials
    }
}
