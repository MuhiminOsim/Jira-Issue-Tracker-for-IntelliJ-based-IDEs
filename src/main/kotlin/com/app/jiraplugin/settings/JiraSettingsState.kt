package com.app.jiraplugin.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@State(
    name = "com.app.jiraplugin.settings.JiraSettingsState",
    storages = [Storage("JiraPluginSettings.xml")]
)
class JiraSettingsState : PersistentStateComponent<JiraSettingsState> {
    var jiraUrl: String = ""
    var email: String = ""
    var jqlQuery: String = "statusCategory != Done ORDER BY updated DESC"

    override fun getState(): JiraSettingsState {
        return this
    }

    override fun loadState(state: JiraSettingsState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        val instance: JiraSettingsState
            get() = ApplicationManager.getApplication().getService(JiraSettingsState::class.java)
    }
}
