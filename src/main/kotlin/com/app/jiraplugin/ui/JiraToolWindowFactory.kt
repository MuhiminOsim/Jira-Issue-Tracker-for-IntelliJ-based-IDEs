package com.app.jiraplugin.ui

import com.app.jiraplugin.settings.JiraCredentials
import com.app.jiraplugin.settings.JiraSettingsState
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class JiraToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        showContent(project, toolWindow)
    }

    private fun showContent(project: Project, toolWindow: ToolWindow) {
        val settings = JiraSettingsState.instance
        val isConfigured = settings.jiraUrl.isNotBlank() && settings.email.isNotBlank() && !JiraCredentials.getApiToken().isNullOrBlank()

        val contentFactory = ContentFactory.getInstance()
        toolWindow.contentManager.removeAllContents(true)

        if (isConfigured) {
            val issuePanel = JiraIssuePanel(project)
            val content = contentFactory.createContent(issuePanel, "Issues", false)
            toolWindow.contentManager.addContent(content)
            issuePanel.refreshIssues()
        } else {
            val onboardingPanel = JiraOnboardingPanel {
                // When connected successfully, re-render
                showContent(project, toolWindow)
            }
            val content = contentFactory.createContent(onboardingPanel, "Setup", false)
            toolWindow.contentManager.addContent(content)
        }
    }
}
