package com.app.jiraplugin.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JPanel

class JiraSettingsComponent {

    val panel: JPanel
    private val myJiraUrlText = JBTextField().apply { columns = 30 }
    private val myEmailText = JBTextField().apply { columns = 30 }
    private val myApiTokenText = JBPasswordField().apply { columns = 30 }
    private val myJqlQueryText = JBTextField().apply { columns = 30 }

    init {
        panel = panel {
            group("Jira Connection Settings") {
                row("Jira URL:") {
                    cell(myJiraUrlText)
                        .align(AlignX.FILL)
                        .comment("e.g. https://your-domain.atlassian.net")
                }
                row("Email:") {
                    cell(myEmailText)
                        .align(AlignX.FILL)
                }
                row("API Token:") {
                    cell(myApiTokenText)
                        .align(AlignX.FILL)
                        .comment("Generate from Atlassian Account Settings")
                }
                row {
                    button("Test Connection") {
                        testConnection()
                    }
                }
            }
            group("Query Settings") {
                row("Default JQL:") {
                    cell(myJqlQueryText)
                        .align(AlignX.FILL)
                        .comment("Default query to load issues in Tool Window")
                }
            }
        }
    }

    private fun testConnection() {
        val url = jiraUrl
        val emailText = email
        val token = apiToken
        
        if (url.isBlank() || emailText.isBlank() || token.isBlank()) {
            Messages.showErrorDialog("Please fill in URL, Email, and API Token first.", "Error")
            return
        }
        
        ProgressManager.getInstance().run(object : Task.Backgroundable(null, "Testing Connection", true) {
            override fun run(indicator: ProgressIndicator) {
                com.app.jiraplugin.api.JiraApiClient.instance.testConnection(url, emailText, token).fold(
                    onSuccess = {
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showInfoMessage("Connection successful!", "Success")
                        }
                    },
                    onFailure = { error ->
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showErrorDialog("Connection failed: ${error.message}", "Error")
                        }
                    }
                )
            }
        })
    }

    val preferredFocusedComponent: javax.swing.JComponent
        get() = myJiraUrlText

    var jiraUrl: String
        get() = myJiraUrlText.text
        set(newText) {
            myJiraUrlText.text = newText
        }

    var email: String
        get() = myEmailText.text
        set(newText) {
            myEmailText.text = newText
        }

    var apiToken: String
        get() = String(myApiTokenText.password)
        set(newText) {
            myApiTokenText.text = newText
        }

    var jqlQuery: String
        get() = myJqlQueryText.text
        set(newText) {
            myJqlQueryText.text = newText
        }
}
