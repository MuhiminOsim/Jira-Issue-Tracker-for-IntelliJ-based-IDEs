package com.app.jiraplugin.settings

import com.intellij.openapi.options.Configurable
import org.jetbrains.annotations.Nls
import javax.swing.JComponent

class JiraSettingsConfigurable : Configurable {

    private var mySettingsComponent: JiraSettingsComponent? = null

    @Nls(capitalization = Nls.Capitalization.Title)
    override fun getDisplayName(): String {
        return "Jira Integration"
    }

    override fun getPreferredFocusedComponent(): JComponent? {
        return mySettingsComponent?.preferredFocusedComponent
    }

    override fun createComponent(): JComponent? {
        mySettingsComponent = JiraSettingsComponent()
        return mySettingsComponent?.panel
    }

    override fun isModified(): Boolean {
        val settings = JiraSettingsState.instance
        var modified = mySettingsComponent?.jiraUrl != settings.jiraUrl
        modified = modified or (mySettingsComponent?.email != settings.email)
        modified = modified or (mySettingsComponent?.jqlQuery != settings.jqlQuery)
        val currentToken = JiraCredentials.getApiToken() ?: ""
        modified = modified or (mySettingsComponent?.apiToken != currentToken)
        return modified
    }

    override fun apply() {
        val settings = JiraSettingsState.instance
        settings.jiraUrl = mySettingsComponent?.jiraUrl ?: ""
        settings.email = mySettingsComponent?.email ?: ""
        settings.jqlQuery = mySettingsComponent?.jqlQuery ?: ""
        JiraCredentials.setApiToken(mySettingsComponent?.apiToken)
    }

    override fun reset() {
        val settings = JiraSettingsState.instance
        mySettingsComponent?.jiraUrl = settings.jiraUrl
        mySettingsComponent?.email = settings.email
        mySettingsComponent?.jqlQuery = settings.jqlQuery
        mySettingsComponent?.apiToken = JiraCredentials.getApiToken() ?: ""
    }

    override fun disposeUIResources() {
        mySettingsComponent = null
    }
}
