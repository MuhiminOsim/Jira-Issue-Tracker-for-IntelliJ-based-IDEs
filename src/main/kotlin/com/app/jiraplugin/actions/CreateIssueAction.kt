package com.app.jiraplugin.actions

import com.app.jiraplugin.settings.JiraSettingsState
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class CreateIssueAction : AnAction("Create Jira Issue", "Create a new Jira issue", null) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val settings = JiraSettingsState.instance
        if (settings.jiraUrl.isBlank()) {
            Messages.showErrorDialog(
                project,
                "Please configure Jira settings in Settings > Tools > Jira Integration first.",
                "Jira Not Configured"
            )
            return
        }

        // Open the Jira create issue page in browser
        val url = "${settings.jiraUrl.trimEnd('/')}/secure/CreateIssue!default.jspa"
        com.intellij.ide.BrowserUtil.browse(url)
    }
}
