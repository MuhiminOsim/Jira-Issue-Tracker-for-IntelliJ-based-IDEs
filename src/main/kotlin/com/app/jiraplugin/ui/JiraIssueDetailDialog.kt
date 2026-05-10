package com.app.jiraplugin.ui

import com.app.jiraplugin.api.JiraApiClient
import com.app.jiraplugin.models.JiraIssue
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.*

class JiraIssueDetailDialog(private val project: Project, private val issue: JiraIssue) : DialogWrapper(project) {

    init {
        title = "${issue.key}: ${issue.fields.getSummaryText()}"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 20)).apply {
            preferredSize = Dimension(600, 500)
            border = JBUI.Borders.empty(20)
        }

        // Header: Summary
        val headerPanel = JPanel(BorderLayout(0, 8)).apply {
            val keyLabel = JLabel(issue.key).apply {
                foreground = UIUtil.getInactiveTextColor()
                font = JBUI.Fonts.smallFont()
            }
            val summaryLabel = JTextArea(issue.fields.getSummaryText()).apply {
                font = JBUI.Fonts.label(18f).asBold()
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                background = UIUtil.getPanelBackground()
            }
            add(keyLabel, BorderLayout.NORTH)
            add(summaryLabel, BorderLayout.CENTER)
        }

        // Content: Description and Details
        val contentPanel = JPanel(GridBagLayout()).apply {
            val c = GridBagConstraints().apply {
                fill = GridBagConstraints.BOTH
                weightx = 1.0
                insets = JBUI.insetsBottom(16)
            }

            // Status & Priority Row
            val metaRow = JPanel(FlowLayout(FlowLayout.LEFT, 20, 0)).apply {
                add(createMetaItem("Status", issue.fields.getStatusName()))
                add(createMetaItem("Priority", issue.fields.getPriorityName()))
                add(createMetaItem("Assignee", issue.fields.getAssigneeName()))
                add(createMetaItem("Estimate", issue.fields.getOriginalEstimateText()))
            }
            c.gridy = 0
            add(metaRow, c)

            // Description
            val descTitle = JLabel("Description").apply {
                font = JBUI.Fonts.label().asBold()
                border = JBUI.Borders.emptyBottom(8)
            }
            c.gridy = 1
            add(descTitle, c)

            val descriptionArea = JTextArea(issue.fields.getDescriptionText()).apply {
                font = JBUI.Fonts.label(13f)
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                background = UIUtil.getPanelBackground()
            }
            val scrollPane = JBScrollPane(descriptionArea).apply {
                border = JBUI.Borders.empty()
            }
            c.gridy = 2
            c.weighty = 1.0
            add(scrollPane, c)
        }

        panel.add(headerPanel, BorderLayout.NORTH)
        panel.add(contentPanel, BorderLayout.CENTER)

        return panel
    }

    private fun createMetaItem(label: String, value: String): JPanel {
        return JPanel(BorderLayout(0, 4)).apply {
            add(JLabel(label.uppercase()).apply {
                font = JBUI.Fonts.smallFont().asBold()
                foreground = UIUtil.getInactiveTextColor()
            }, BorderLayout.NORTH)
            add(JLabel(value).apply {
                font = JBUI.Fonts.label().asBold()
            }, BorderLayout.CENTER)
        }
    }
}
