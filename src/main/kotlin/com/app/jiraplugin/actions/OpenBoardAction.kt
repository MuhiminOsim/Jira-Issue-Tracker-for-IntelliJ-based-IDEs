package com.app.jiraplugin.actions

import com.app.jiraplugin.settings.JiraSettingsState
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class OpenBoardAction : AnAction("Open Jira Board", "Open the Jira board in browser", null) {

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

        val url = "${settings.jiraUrl.trimEnd('/')}/jira/software/projects"
        com.intellij.ide.BrowserUtil.browse(url)
    }
}
