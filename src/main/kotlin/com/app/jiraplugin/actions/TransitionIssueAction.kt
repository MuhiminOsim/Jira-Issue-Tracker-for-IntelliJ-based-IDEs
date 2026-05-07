package com.app.jiraplugin.actions

import com.app.jiraplugin.api.JiraApiClient
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages

class TransitionIssueAction : AnAction("Change Issue Status", "Transition a Jira issue to a new status", null) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val issueKey = Messages.showInputDialog(
            project,
            "Enter the Jira issue key (e.g. PROJ-123):",
            "Change Issue Status",
            Messages.getQuestionIcon()
        ) ?: return

        if (issueKey.isBlank()) return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading Transitions for $issueKey", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = JiraApiClient.instance.getTransitions(issueKey)
                ApplicationManager.getApplication().invokeLater {
                    result.fold(
                        onSuccess = { transitions ->
                            if (transitions.isEmpty()) {
                                Messages.showInfoMessage(project, "No transitions available for $issueKey.", "Info")
                                return@invokeLater
                            }

                            val names = transitions.map { it.name }.toTypedArray()
                            val selected = Messages.showEditableChooseDialog(
                                "Select new status for $issueKey:",
                                "Change Status",
                                Messages.getQuestionIcon(),
                                names,
                                names.first(),
                                null
                            ) ?: return@invokeLater

                            val transition = transitions.find { it.name == selected } ?: return@invokeLater

                            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Transitioning $issueKey", true) {
                                override fun run(indicator: ProgressIndicator) {
                                    val transResult = JiraApiClient.instance.performTransition(issueKey, transition.id)
                                    ApplicationManager.getApplication().invokeLater {
                                        transResult.fold(
                                            onSuccess = {
                                                Messages.showInfoMessage(project, "$issueKey transitioned to '${transition.name}'.", "Success")
                                            },
                                            onFailure = { error ->
                                                Messages.showErrorDialog(project, "Failed: ${error.message}", "Error")
                                            }
                                        )
                                    }
                                }
                            })
                        },
                        onFailure = { error ->
                            Messages.showErrorDialog(project, "Failed to load transitions:\n${error.message}", "Error")
                        }
                    )
                }
            }
        })
    }
}
