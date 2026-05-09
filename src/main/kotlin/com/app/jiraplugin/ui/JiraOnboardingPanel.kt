package com.app.jiraplugin.ui

import com.app.jiraplugin.api.JiraApiClient
import com.app.jiraplugin.settings.JiraCredentials
import com.app.jiraplugin.settings.JiraSettingsState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

class JiraOnboardingPanel(private val onConnected: () -> Unit) : JPanel(BorderLayout()) {

    private val urlField = JBTextField().apply { columns = 30 }
    private val emailField = JBTextField().apply { columns = 30 }
    private val tokenField = JBPasswordField().apply { columns = 30 }
    private val connectButton = JButton("Connect to Jira")

    init {
        background = UIUtil.getPanelBackground()
        setupUI()
        setupListeners()
    }

    private fun setupUI() {
        val centerPanel = JPanel(GridBagLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(40)
        }

        val c = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(10)
            gridx = 0
            gridy = 0
            gridwidth = 2
        }

        val titleLabel = JLabel("Welcome to Jira Issue Tracker").apply {
            font = JBUI.Fonts.label(20f).asBold()
            horizontalAlignment = SwingConstants.CENTER
        }
        centerPanel.add(titleLabel, c)

        c.gridy++
        val descLabel = JLabel("<html><center>To get started, please connect your Jira account.<br/>You can generate an API token from your Atlassian Account Settings.</center></html>").apply {
            foreground = UIUtil.getContextHelpForeground()
            horizontalAlignment = SwingConstants.CENTER
        }
        centerPanel.add(descLabel, c)

        c.gridwidth = 1
        c.gridy++
        c.weightx = 0.0
        centerPanel.add(JLabel("Jira URL:"), c)
        c.gridx = 1
        c.weightx = 1.0
        centerPanel.add(urlField.apply { emptyText.text = "e.g. https://your-domain.atlassian.net" }, c)

        c.gridx = 0
        c.gridy++
        c.weightx = 0.0
        centerPanel.add(JLabel("Email:"), c)
        c.gridx = 1
        c.weightx = 1.0
        centerPanel.add(emailField, c)

        c.gridx = 0
        c.gridy++
        c.weightx = 0.0
        centerPanel.add(JLabel("API Token:"), c)
        c.gridx = 1
        c.weightx = 1.0
        centerPanel.add(tokenField, c)

        c.gridx = 0
        c.gridy++
        c.gridwidth = 2
        c.weightx = 1.0
        val buttonPanel = JPanel(FlowLayout(FlowLayout.CENTER)).apply {
            background = UIUtil.getPanelBackground()
            add(connectButton)
        }
        centerPanel.add(buttonPanel, c)

        add(centerPanel, BorderLayout.NORTH)
    }

    private fun setupListeners() {
        connectButton.addActionListener {
            val url = urlField.text.trim()
            val email = emailField.text.trim()
            val token = String(tokenField.password).trim()

            if (url.isBlank() || email.isBlank() || token.isBlank()) {
                Messages.showErrorDialog("Please fill in all fields.", "Missing Information")
                return@addActionListener
            }

            connectButton.isEnabled = false
            connectButton.text = "Connecting..."

            ProgressManager.getInstance().run(object : Task.Backgroundable(null, "Testing Jira Connection", true) {
                override fun run(indicator: ProgressIndicator) {
                    JiraApiClient.instance.testConnection(url, email, token).fold(
                        onSuccess = {
                            ApplicationManager.getApplication().invokeLater {
                                // Save settings
                                val settings = JiraSettingsState.instance
                                settings.jiraUrl = url
                                settings.email = email
                                JiraCredentials.setApiToken(token)

                                Messages.showInfoMessage("Successfully connected to Jira!", "Connection Success")
                                onConnected()
                            }
                        },
                        onFailure = { error ->
                            ApplicationManager.getApplication().invokeLater {
                                connectButton.isEnabled = true
                                connectButton.text = "Connect to Jira"
                                Messages.showErrorDialog("Failed to connect to Jira:\n${error.message}", "Connection Error")
                            }
                        }
                    )
                }
            })
        }
    }
}
