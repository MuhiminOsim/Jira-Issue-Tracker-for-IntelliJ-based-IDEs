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
            val boardPanel = JiraBoardPanel(project)
            
            val issuesContent = contentFactory.createContent(issuePanel, "Issues", false)
            val boardContent = contentFactory.createContent(boardPanel, "Board", false)
            
            toolWindow.contentManager.addContent(issuesContent)
            toolWindow.contentManager.addContent(boardContent)
            
            // Wait for indexing to complete if needed
            com.intellij.openapi.project.DumbService.getInstance(project).runWhenSmart {
                issuePanel.refreshIssues()
            }
            
            // Refresh board when selected
            toolWindow.contentManager.addContentManagerListener(object : com.intellij.ui.content.ContentManagerListener {
                override fun selectionChanged(event: com.intellij.ui.content.ContentManagerEvent) {
                    if (event.content == boardContent && toolWindow.contentManager.selectedContent == boardContent) {
                        val savedKey = JiraSettingsState.instance.selectedProjectKey
                        if (savedKey.isNotBlank()) {
                            boardPanel.refreshBoard(savedKey)
                        }
                    }
                }
            })
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
