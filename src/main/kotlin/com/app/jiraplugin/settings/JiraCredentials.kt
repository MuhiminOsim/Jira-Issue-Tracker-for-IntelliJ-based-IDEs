package com.app.jiraplugin.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe

object JiraCredentials {
    private val CREDENTIAL_ATTRIBUTES = CredentialAttributes(
        "JiraPlugin — ApiToken",
        "JiraPlugin_ApiToken"
    )

    fun getApiToken(): String? {
        val credentials = PasswordSafe.instance.get(CREDENTIAL_ATTRIBUTES)
        return credentials?.getPasswordAsString()
    }

    fun setApiToken(token: String?) {
        val credentials = if (token.isNullOrEmpty()) null else Credentials("", token)
        PasswordSafe.instance.set(CREDENTIAL_ATTRIBUTES, credentials)
    }
}
