package com.app.jiraplugin.ui

import com.app.jiraplugin.api.JiraApiClient
import com.app.jiraplugin.models.JiraIssueType
import com.app.jiraplugin.models.JiraProject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class JiraCreateIssueDialog(private val project: Project, private val initialProjectKey: String? = null) : DialogWrapper(project) {

    private val projectComboBox = ComboBox<JiraProject>().apply {
        addItem(JiraProject("", "", "Loading..."))
    }
    private val issueTypeComboBox = ComboBox<JiraIssueType>().apply {
        addItem(JiraIssueType("", "Loading...", "", false))
    }
    private val priorityComboBox = ComboBox<com.app.jiraplugin.models.JiraPriority>().apply {
        addItem(com.app.jiraplugin.models.JiraPriority("", "Loading...", ""))
    }
    private val assigneeComboBox = ComboBox<com.app.jiraplugin.models.JiraUser>().apply {
        addItem(com.app.jiraplugin.models.JiraUser("", "Loading...", null, true))
    }
    private val estimateField = JBTextField().apply {
        emptyText.text = "e.g., 2h, 1d"
    }
    private val summaryField = JBTextField()
    private val descriptionArea = JBTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
        rows = 10
    }

    init {
        title = "Create Jira Issue"
        init()
        loadProjects()
        loadIssueTypes()
        loadPriorities()
        
        projectComboBox.addActionListener {
            val selected = projectComboBox.selectedItem as? JiraProject
            if (selected != null && selected.key.isNotBlank()) {
                loadAssignees(selected.key)
            }
        }
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.preferredSize = Dimension(500, 550)
        val c = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(5)
            weightx = 1.0
            gridx = 0
            gridy = 0
        }

        panel.add(JLabel("Project:"), c)
        c.gridy++
        projectComboBox.renderer = object : com.intellij.ui.SimpleListCellRenderer<JiraProject>() {
            override fun customize(list: javax.swing.JList<out JiraProject>, value: JiraProject?, index: Int, selected: Boolean, hasFocus: Boolean) {
                text = value?.name ?: ""
            }
        }
        panel.add(projectComboBox, c)

        c.gridy++
        panel.add(JLabel("Issue Type:"), c)
        c.gridy++
        issueTypeComboBox.renderer = object : com.intellij.ui.SimpleListCellRenderer<JiraIssueType>() {
            override fun customize(list: javax.swing.JList<out JiraIssueType>, value: JiraIssueType?, index: Int, selected: Boolean, hasFocus: Boolean) {
                text = value?.name ?: ""
            }
        }
        panel.add(issueTypeComboBox, c)

        c.gridy++
        panel.add(JLabel("Priority:"), c)
        c.gridy++
        priorityComboBox.renderer = object : com.intellij.ui.SimpleListCellRenderer<com.app.jiraplugin.models.JiraPriority>() {
            override fun customize(list: javax.swing.JList<out com.app.jiraplugin.models.JiraPriority>, value: com.app.jiraplugin.models.JiraPriority?, index: Int, selected: Boolean, hasFocus: Boolean) {
                text = value?.name ?: ""
            }
        }
        panel.add(priorityComboBox, c)

        c.gridy++
        panel.add(JLabel("Assignee:"), c)
        c.gridy++
        assigneeComboBox.renderer = object : com.intellij.ui.SimpleListCellRenderer<com.app.jiraplugin.models.JiraUser>() {
            override fun customize(list: javax.swing.JList<out com.app.jiraplugin.models.JiraUser>, value: com.app.jiraplugin.models.JiraUser?, index: Int, selected: Boolean, hasFocus: Boolean) {
                text = value?.displayName ?: ""
            }
        }
        panel.add(assigneeComboBox, c)

        c.gridy++
        panel.add(JLabel("Original Estimate (optional):"), c)
        c.gridy++
        panel.add(estimateField, c)

        c.gridy++
        panel.add(JLabel("Summary:"), c)
        c.gridy++
        panel.add(summaryField, c)

        c.gridy++
        c.weighty = 0.0
        panel.add(JLabel("Description:"), c)
        c.gridy++
        c.weighty = 1.0
        c.fill = GridBagConstraints.BOTH
        val scrollPane = JBScrollPane(descriptionArea).apply {
            preferredSize = Dimension(450, 150)
        }
        panel.add(scrollPane, c)

        return panel
    }

    private fun loadProjects() {
        ApplicationManager.getApplication().executeOnPooledThread {
            JiraApiClient.instance.getProjects().fold(
                onSuccess = { projects ->
                    ApplicationManager.getApplication().invokeLater({
                        projectComboBox.removeAllItems()
                        projects.sortedBy { it.name }.forEach { projectComboBox.addItem(it) }
                        if (projects.isEmpty()) {
                            projectComboBox.addItem(JiraProject("", "", "No projects found"))
                        }
                        
                        if (initialProjectKey != null) {
                            val index = (0 until projectComboBox.itemCount).find { 
                                projectComboBox.getItemAt(it).key == initialProjectKey 
                            }
                            if (index != null) {
                                projectComboBox.selectedIndex = index
                            } else if (projectComboBox.itemCount > 0) {
                                projectComboBox.selectedIndex = 0
                            }
                        } else if (projectComboBox.itemCount > 0) {
                            projectComboBox.selectedIndex = 0
                        }
                    }, com.intellij.openapi.application.ModalityState.any())
                },
                onFailure = { error ->
                    ApplicationManager.getApplication().invokeLater({
                        projectComboBox.removeAllItems()
                        projectComboBox.addItem(JiraProject("", "", "Error: ${error.message}"))
                        if (projectComboBox.itemCount > 0) projectComboBox.selectedIndex = 0
                    }, com.intellij.openapi.application.ModalityState.any())
                }
            )
        }
    }

    private fun loadIssueTypes() {
        ApplicationManager.getApplication().executeOnPooledThread {
            JiraApiClient.instance.getIssueTypes().fold(
                onSuccess = { types ->
                    ApplicationManager.getApplication().invokeLater({
                        issueTypeComboBox.removeAllItems()
                        types.filter { !it.subtask }.distinctBy { it.name }.sortedBy { it.name }.forEach { issueTypeComboBox.addItem(it) }
                        if (types.isEmpty()) {
                            issueTypeComboBox.addItem(JiraIssueType("", "No issue types found", "", false))
                        }
                        
                        val defaultIndex = (0 until issueTypeComboBox.itemCount).find { 
                            issueTypeComboBox.getItemAt(it).name.equals("Task", ignoreCase = true) 
                        }
                        if (defaultIndex != null) {
                            issueTypeComboBox.selectedIndex = defaultIndex
                        } else if (issueTypeComboBox.itemCount > 0) {
                            issueTypeComboBox.selectedIndex = 0
                        }
                    }, com.intellij.openapi.application.ModalityState.any())
                },
                onFailure = { error ->
                    ApplicationManager.getApplication().invokeLater({
                        issueTypeComboBox.removeAllItems()
                        issueTypeComboBox.addItem(JiraIssueType("", "Error: ${error.message}", "", false))
                        if (issueTypeComboBox.itemCount > 0) issueTypeComboBox.selectedIndex = 0
                    }, com.intellij.openapi.application.ModalityState.any())
                }
            )
        }
    }

    private fun loadPriorities() {
        ApplicationManager.getApplication().executeOnPooledThread {
            JiraApiClient.instance.getPriorities().fold(
                onSuccess = { priorities ->
                    ApplicationManager.getApplication().invokeLater({
                        priorityComboBox.removeAllItems()
                        priorities.forEach { priorityComboBox.addItem(it) }
                        if (priorities.isEmpty()) {
                            priorityComboBox.addItem(com.app.jiraplugin.models.JiraPriority("", "No priorities found", ""))
                        }
                        
                        val defaultIndex = (0 until priorityComboBox.itemCount).find { 
                            priorityComboBox.getItemAt(it).name.equals("Medium", ignoreCase = true) 
                        }
                        if (defaultIndex != null) {
                            priorityComboBox.selectedIndex = defaultIndex
                        } else if (priorityComboBox.itemCount > 0) {
                            priorityComboBox.selectedIndex = 0
                        }
                    }, com.intellij.openapi.application.ModalityState.any())
                },
                onFailure = { error ->
                    ApplicationManager.getApplication().invokeLater({
                        priorityComboBox.removeAllItems()
                        priorityComboBox.addItem(com.app.jiraplugin.models.JiraPriority("", "Error: ${error.message}", ""))
                        if (priorityComboBox.itemCount > 0) priorityComboBox.selectedIndex = 0
                    }, com.intellij.openapi.application.ModalityState.any())
                }
            )
        }
    }

    private fun loadAssignees(projectKey: String) {
        ApplicationManager.getApplication().invokeLater({
            assigneeComboBox.removeAllItems()
            assigneeComboBox.addItem(com.app.jiraplugin.models.JiraUser("", "Loading...", null, true))
        }, com.intellij.openapi.application.ModalityState.any())

        ApplicationManager.getApplication().executeOnPooledThread {
            JiraApiClient.instance.getAssignableUsers(projectKey).fold(
                onSuccess = { users ->
                    ApplicationManager.getApplication().invokeLater({
                        assigneeComboBox.removeAllItems()
                        assigneeComboBox.addItem(com.app.jiraplugin.models.JiraUser("", "Unassigned", null, true))
                        
                        val connectedEmail = com.app.jiraplugin.settings.JiraSettingsState.instance.email.lowercase()
                        val connectedUser = users.find { it.emailAddress?.lowercase() == connectedEmail }
                        if (connectedUser != null) {
                            assigneeComboBox.addItem(connectedUser)
                        }
                        
                        users.filter { it.emailAddress?.lowercase() != connectedEmail }
                             .sortedBy { it.displayName }
                             .forEach { assigneeComboBox.addItem(it) }
                             
                        if (assigneeComboBox.itemCount > 0) assigneeComboBox.selectedIndex = 0
                    }, com.intellij.openapi.application.ModalityState.any())
                },
                onFailure = { error ->
                    ApplicationManager.getApplication().invokeLater({
                        assigneeComboBox.removeAllItems()
                        assigneeComboBox.addItem(com.app.jiraplugin.models.JiraUser("", "Unassigned", null, true))
                    }, com.intellij.openapi.application.ModalityState.any())
                }
            )
        }
    }

    override fun doOKAction() {
        val selectedProject = projectComboBox.selectedItem as? JiraProject
        val selectedIssueType = issueTypeComboBox.selectedItem as? JiraIssueType
        val selectedPriority = priorityComboBox.selectedItem as? com.app.jiraplugin.models.JiraPriority
        val selectedAssignee = assigneeComboBox.selectedItem as? com.app.jiraplugin.models.JiraUser
        
        val summary = summaryField.text
        val description = descriptionArea.text
        val estimate = estimateField.text

        if (selectedProject == null || selectedIssueType == null || summary.isBlank()) {
            Messages.showErrorDialog("Project, Issue Type, and Summary are required.", "Missing Information")
            return
        }

        val priorityId = selectedPriority?.id?.takeIf { it.isNotBlank() }
        val assigneeId = selectedAssignee?.accountId?.takeIf { it.isNotBlank() }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Creating Jira Issue", true) {
            override fun run(indicator: ProgressIndicator) {
                JiraApiClient.instance.createIssue(
                    selectedProject.key, 
                    selectedIssueType.name, 
                    summary, 
                    description,
                    priorityId,
                    assigneeId,
                    estimate
                ).fold(
                    onSuccess = { issueKey ->
                        ApplicationManager.getApplication().invokeLater({
                            Messages.showInfoMessage("Successfully created issue $issueKey", "Issue Created")
                            this@JiraCreateIssueDialog.close(OK_EXIT_CODE)
                        }, com.intellij.openapi.application.ModalityState.any())
                    },
                    onFailure = { error ->
                        ApplicationManager.getApplication().invokeLater({
                            Messages.showErrorDialog("Failed to create issue:\n${error.message}", "Error")
                        }, com.intellij.openapi.application.ModalityState.any())
                    }
                )
            }
        })
    }
}
