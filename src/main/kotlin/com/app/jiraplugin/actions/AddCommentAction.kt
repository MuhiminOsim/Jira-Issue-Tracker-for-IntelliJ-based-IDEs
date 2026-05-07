package com.app.jiraplugin.actions

import com.app.jiraplugin.api.JiraApiClient
import com.app.jiraplugin.models.JiraCommentRequest
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages

class AddCommentAction : AnAction("Add Comment", "Add a comment to a Jira issue", null) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val issueKey = Messages.showInputDialog(
            project,
            "Enter the Jira issue key (e.g. PROJ-123):",
            "Add Comment",
            Messages.getQuestionIcon()
        ) ?: return

        if (issueKey.isBlank()) return

        val comment = Messages.showMultilineInputDialog(
            project,
            "Enter your comment for $issueKey:",
            "Add Comment",
            "",
            Messages.getQuestionIcon(),
            null
        ) ?: return

        if (comment.isBlank()) return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Adding Comment to $issueKey", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = JiraApiClient.instance.addComment(issueKey, comment)
                ApplicationManager.getApplication().invokeLater {
                    result.fold(
                        onSuccess = {
                            Messages.showInfoMessage(project, "Comment added to $issueKey successfully.", "Success")
                        },
                        onFailure = { error ->
                            Messages.showErrorDialog(project, "Failed to add comment:\n${error.message}", "Error")
                        }
                    )
                }
            }
        })
    }
}
