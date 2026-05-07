package com.app.jiraplugin.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class JiraToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val issuePanel = JiraIssuePanel(project)
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(issuePanel, "Issues", false)
        toolWindow.contentManager.addContent(content)

        // Auto-refresh on open
        issuePanel.refreshIssues()
    }
}
