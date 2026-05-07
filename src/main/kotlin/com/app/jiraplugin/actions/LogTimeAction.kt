package com.app.jiraplugin.actions

import com.app.jiraplugin.api.JiraApiClient
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages

class LogTimeAction : AnAction("Log Time", "Log work time on a Jira issue", null) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val issueKey = Messages.showInputDialog(
            project,
            "Enter the Jira issue key (e.g. PROJ-123):",
            "Log Time",
            Messages.getQuestionIcon()
        ) ?: return

        if (issueKey.isBlank()) return

        val timeSpent = Messages.showInputDialog(
            project,
            "Enter time spent (e.g. 1d 2h 30m):",
            "Log Time for $issueKey",
            Messages.getQuestionIcon()
        ) ?: return

        if (timeSpent.isBlank()) return

        val comment = Messages.showInputDialog(
            project,
            "Optional worklog comment (leave blank to skip):",
            "Worklog Comment",
            Messages.getQuestionIcon()
        )

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Logging Time on $issueKey", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = JiraApiClient.instance.addWorklog(
                    issueKey,
                    timeSpent,
                    if (comment.isNullOrBlank()) null else comment
                )
                ApplicationManager.getApplication().invokeLater {
                    result.fold(
                        onSuccess = {
                            Messages.showInfoMessage(project, "Logged '$timeSpent' on $issueKey.", "Success")
                        },
                        onFailure = { error ->
                            Messages.showErrorDialog(project, "Failed to log time:\n${error.message}", "Error")
                        }
                    )
                }
            }
        })
    }
}
