package com.app.jiraplugin.ui

import com.app.jiraplugin.api.JiraApiClient
import com.app.jiraplugin.models.JiraIssue
import com.app.jiraplugin.models.JiraProject
import com.app.jiraplugin.models.JiraUser
import com.app.jiraplugin.settings.JiraSettingsState
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class JiraIssuePanel(private val project: Project) : JPanel(BorderLayout()) {

    private val issueListContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = UIUtil.getListBackground()
    }
    private val statusLabel = JLabel("Ready")

    private val iconCache = ConcurrentHashMap<String, Icon>()
    private val loadingIcons = ConcurrentHashMap.newKeySet<String>()

    // Filtering
    private val searchField = SearchTextField()
    private val projectFilter = com.intellij.openapi.ui.ComboBox<String>(arrayOf("Select Space..."))
    private val filterButton = JButton("Filter", AllIcons.General.Filter).apply { isFocusable = false }

    private val assigneeFilter = com.intellij.openapi.ui.ComboBox<String>(arrayOf("Assignee: All")).apply { isFocusable = false }
    private val priorityFilter = com.intellij.openapi.ui.ComboBox<String>(arrayOf("Priority: All")).apply { isFocusable = false }
    private val workTypeFilter = com.intellij.openapi.ui.ComboBox<String>(arrayOf("Type: All")).apply { isFocusable = false }
    private val parentFilter = com.intellij.openapi.ui.ComboBox<String>(arrayOf("Parent: All")).apply { isFocusable = false }
    
    private var activeFilters = mutableMapOf<FilterCategory, List<String>>()
    
    private var allIssues: List<JiraIssue> = emptyList()
    private var allProjects: List<JiraProject> = emptyList()
    private var isUpdatingFilters = false

    private var isProgrammaticUpdate = false

    private val issueKeyLabel = JLabel()
    private val issueSummaryArea = JTextArea().apply {
        isEditable = true
        lineWrap = true
        wrapStyleWord = true
        font = JBUI.Fonts.label(16f).asBold()
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.customLine(JBColor.border(), 1)
    }
    private val issueStatusLabel = JLabel()
    private val issuePriorityLabel = JLabel()
    private val issueAssigneeLabel = JLabel()
    private val issueSprintLabel = JLabel()
    private val issueDescriptionArea = JTextArea().apply {
        isEditable = true
        lineWrap = true
        wrapStyleWord = true
        font = JBUI.Fonts.label(13f)
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(4)
    }

    private val detailPanel = JPanel(BorderLayout())
    private val splitter = OnePixelSplitter(false, 0.45f).apply {
        setHonorComponentsMinimumSize(true)
    }

    private val commentButton = JButton("💬 Add Comment")
    private val transitionButton = JButton("🔄 Change Status")
    private val logTimeButton = JButton("⏱ Log Time").apply { isFocusable = false }
    private val refreshButton = JButton(AllIcons.Actions.Refresh).apply { isFocusable = false }
    private val resetFiltersButton = JButton("Reset").apply {
        isVisible = false
        margin = JBUI.insets(2, 4)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isFocusable = false
    }
    private val changeSprintButton = JButton("🏃 Change Sprint").apply { isFocusable = false }
    private val openInBrowserButton = JButton("🌐 Open in Browser").apply { isFocusable = false }
    private val saveChangesButton = JButton("💾 Save Changes").apply {
        isEnabled = false
        isFocusable = false
    }

    private var selectedIssue: JiraIssue? = null
    private var selectedRow: IssueRowComponent? = null

    init {
        setupUI()
        setupListeners()
        // Automatically load projects on init
        loadProjects()
    }

    private fun setupUI() {
        background = UIUtil.getPanelBackground()

        // --- Filter Bar ---
        val filterPanel = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
            
            // Left part: Search + Space Selection + Scrollable Filters
            val leftContent = JPanel(GridBagLayout()).apply {
                isOpaque = false
                val c = GridBagConstraints().apply {
                    fill = GridBagConstraints.VERTICAL
                    anchor = GridBagConstraints.WEST
                    insets = JBUI.insets(8, 12, 8, 8)
                }
                
                // Search field
                c.gridx = 0
                searchField.preferredSize = Dimension(120, searchField.preferredSize.height)
                searchField.minimumSize = Dimension(120, searchField.preferredSize.height)
                add(searchField, c)

                // Project/Space Filter
                c.gridx++
                projectFilter.prototypeDisplayValue = "Select Space: XXXXXXXXXXX"
                add(projectFilter, c)
                
                // Filter button (Fixed)
                c.gridx++
                add(filterButton, c)
                
                // Scrollable filters
                c.gridx++
                c.weightx = 1.0
                c.fill = GridBagConstraints.BOTH
                c.insets = JBUI.emptyInsets()
                
                val filtersPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 8)).apply {
                    isOpaque = false
                    assigneeFilter.prototypeDisplayValue = "Assignee: XXXXXXXX"
                    parentFilter.prototypeDisplayValue = "Parent: XXXXXXXXXXXXXX"
                    add(assigneeFilter)
                    add(priorityFilter)
                    add(workTypeFilter)
                    add(parentFilter)
                }
                
                val scrollFilters = JBScrollPane(filtersPanel).apply {
                    border = JBUI.Borders.empty()
                    isOpaque = false
                    viewport.isOpaque = false
                    verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
                    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
                    preferredSize = Dimension(100, 42)
                }
                add(scrollFilters, c)
            }
            
            add(leftContent, BorderLayout.CENTER)
            
            // Right part: Reset + Refresh buttons
            val rightContent = JPanel(FlowLayout(FlowLayout.RIGHT, 12, 8)).apply {
                isOpaque = false
                add(resetFiltersButton)
                add(refreshButton)
            }
            add(rightContent, BorderLayout.EAST)
        }

        // --- Issue List Container ---
        val listScrollPane = JBScrollPane(issueListContainer).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 1)
            minimumSize = Dimension(350, 0)
            viewport.background = UIUtil.getListBackground()
        }

        val listPanel = JPanel(BorderLayout()).apply {
            add(filterPanel, BorderLayout.NORTH)
            add(listScrollPane, BorderLayout.CENTER)
            add(statusLabel.apply {
                border = JBUI.Borders.empty(4, 12)
                foreground = JBColor.GRAY
                font = JBUI.Fonts.smallFont()
            }, BorderLayout.SOUTH)
        }

        // --- Detail Panel ---
        detailPanel.background = UIUtil.getPanelBackground()
        detailPanel.minimumSize = Dimension(300, 0)
        
        val detailHeader = JPanel().apply {
            layout = GridBagLayout()
            border = JBUI.Borders.empty(16, 20)
            background = UIUtil.getPanelBackground()
            val c = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
                gridwidth = 2
                insets = JBUI.insetsBottom(8)
            }

            issueKeyLabel.font = JBUI.Fonts.label(18f).asBold()
            issueKeyLabel.foreground = JBColor(Color(0, 82, 204), Color(76, 154, 255))
            add(issueKeyLabel, c)
            
            c.gridy = 1
            c.insets = JBUI.insetsBottom(16)
            add(issueSummaryArea, c)

            c.gridy = 2
            c.gridwidth = 1
            c.weightx = 0.5
            c.insets = JBUI.insets(0, 0, 10, 10)
            add(createMetaRow("Status", issueStatusLabel), c)
            
            c.gridx = 1
            add(createMetaRow("Priority", issuePriorityLabel), c)
            
            c.gridy = 3
            c.gridx = 0
            add(createMetaRow("Assignee", issueAssigneeLabel), c)
            
            c.gridx = 1
            add(createMetaRow("Sprint", issueSprintLabel), c)
        }

        val descPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(0, 20, 16, 20)
            isOpaque = false
            add(JLabel("Description").apply {
                font = JBUI.Fonts.label(12f).asBold()
                foreground = JBColor.GRAY
                border = JBUI.Borders.emptyBottom(8)
            }, BorderLayout.NORTH)
            add(JBScrollPane(issueDescriptionArea).apply {
                border = JBUI.Borders.empty()
                isOpaque = false
                viewport.isOpaque = false
            }, BorderLayout.CENTER)
        }

        val actionPanel = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0)
            background = UIUtil.getPanelBackground()
            
            val c = GridBagConstraints().apply {
                fill = GridBagConstraints.NONE
                anchor = GridBagConstraints.WEST
                insets = JBUI.insets(10, 10, 0, 0)
                gridy = 0
                gridx = 0
            }
            
            add(saveChangesButton, c)
            c.gridx++
            add(commentButton, c)
            c.gridx++
            c.weightx = 1.0
            add(transitionButton, c)
            
            c.gridy = 1
            c.gridx = 0
            c.weightx = 0.0
            c.insets = JBUI.insets(10, 10, 10, 0)
            add(logTimeButton, c)
            c.gridx++
            c.weightx = 1.0
            c.gridwidth = 1
            add(openInBrowserButton, c)
            c.gridx++
            add(changeSprintButton, c)
        }

        detailPanel.add(detailHeader, BorderLayout.NORTH)
        detailPanel.add(descPanel, BorderLayout.CENTER)
        detailPanel.add(actionPanel, BorderLayout.SOUTH)

        showEmptyDetailState()

        splitter.firstComponent = listPanel
        // Detail panel hidden by default
        splitter.secondComponent = null

        add(splitter, BorderLayout.CENTER)
    }

    private fun createMetaRow(label: String, valueLabel: JLabel): JPanel {
        return JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            add(JLabel("$label:").apply {
                foreground = JBColor.GRAY
                preferredSize = Dimension(60, 0)
            }, BorderLayout.WEST)
            add(valueLabel.apply {
                font = JBUI.Fonts.label().asBold()
            }, BorderLayout.CENTER)
        }
    }

    private fun setupListeners() {
        refreshButton.addActionListener { 
            loadProjects()
            refreshIssues() 
        }
        resetFiltersButton.addActionListener {
            activeFilters.clear()
            searchField.text = ""
            syncCombosFromActiveFilters()
            resetFiltersButton.isVisible = false
            refreshIssues()
        }
        commentButton.addActionListener { addComment() }
        transitionButton.addActionListener { changeStatus() }
        logTimeButton.addActionListener { logTime() }
        openInBrowserButton.addActionListener { openInBrowser() }
        changeSprintButton.addActionListener { changeSprint() }
        saveChangesButton.addActionListener { saveIssueDetails() }

        val docListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = checkDirty()
            override fun removeUpdate(e: DocumentEvent) = checkDirty()
            override fun changedUpdate(e: DocumentEvent) = checkDirty()
        }
        issueSummaryArea.document.addDocumentListener(docListener)
        issueDescriptionArea.document.addDocumentListener(docListener)

        issueSprintLabel.apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        changeSprint()
                    }
                }
            })
        }

        val comboListener = { _: java.awt.event.ActionEvent ->
            if (!isProgrammaticUpdate) {
                updateActiveFiltersFromCombos()
                refreshIssues()
            }
        }
        assigneeFilter.addActionListener(comboListener)
        priorityFilter.addActionListener(comboListener)
        workTypeFilter.addActionListener(comboListener)
        parentFilter.addActionListener(comboListener)

        searchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onSearchChanged()
            override fun removeUpdate(e: DocumentEvent) = onSearchChanged()
            override fun changedUpdate(e: DocumentEvent) = onSearchChanged()
        })
        
        filterButton.addActionListener { openFilterDialog() }
        projectFilter.addActionListener {
            if (!isUpdatingFilters) {
                val selectedProjectStr = projectFilter.selectedItem as? String
                if (selectedProjectStr != null && selectedProjectStr != "Select Space...") {
                    val projectKey = selectedProjectStr.substringBefore(" - ")
                    JiraSettingsState.instance.selectedProjectKey = projectKey
                    activeFilters.clear() // Reset filters when changing space
                    syncCombosFromActiveFilters()
                    updateAssigneeFilterOptions(projectKey)
                } else {
                    JiraSettingsState.instance.selectedProjectKey = ""
                    activeFilters.clear()
                    syncCombosFromActiveFilters()
                    refreshIssues()
                }
            }
        }
    }

    private fun updateActiveFiltersFromCombos() {
        if (isProgrammaticUpdate) return
        
        fun updateCategory(category: FilterCategory, combo: com.intellij.openapi.ui.ComboBox<String>, prefix: String) {
            val selected = (combo.selectedItem as? String) ?: return
            if (selected == "$prefix: All") {
                activeFilters.remove(category)
            } else {
                val value = if (category == FilterCategory.PARENT) selected.substringBefore(":") else selected
                activeFilters[category] = listOf(value)
            }
        }

        updateCategory(FilterCategory.ASSIGNEE, assigneeFilter, "Assignee")
        updateCategory(FilterCategory.PRIORITY, priorityFilter, "Priority")
        updateCategory(FilterCategory.WORK_TYPE, workTypeFilter, "Type")
        updateCategory(FilterCategory.PARENT, parentFilter, "Parent")
    }

    private fun syncCombosFromActiveFilters() {
        isProgrammaticUpdate = true
        fun syncCombo(category: FilterCategory, combo: com.intellij.openapi.ui.ComboBox<String>) {
            val selected = activeFilters[category]?.firstOrNull()
            if (selected == null) {
                combo.selectedIndex = 0
            } else {
                for (i in 0 until combo.itemCount) {
                    val item = combo.getItemAt(i)
                    if (item == selected) {
                        combo.selectedIndex = i
                        return
                    }
                }
                combo.selectedIndex = 0
            }
        }
        syncCombo(FilterCategory.ASSIGNEE, assigneeFilter)
        syncCombo(FilterCategory.PRIORITY, priorityFilter)
        syncCombo(FilterCategory.WORK_TYPE, workTypeFilter)
        syncCombo(FilterCategory.PARENT, parentFilter)
        isProgrammaticUpdate = false
    }

    private fun onSearchChanged() {
        updateFilterUI()
        applyLocalFilter()
    }

    private fun openFilterDialog() {
        val selectedProjectStr = projectFilter.selectedItem as? String ?: return
        if (selectedProjectStr == "Select Space...") return
        val projectKey = selectedProjectStr.substringBefore(" - ")
        
        val dialog = JiraFilterDialog(project, projectKey, activeFilters)
        if (dialog.showAndGet()) {
            activeFilters = dialog.getSelectedFilters().toMutableMap()
            syncCombosFromActiveFilters()
            refreshIssues()
        }
    }

    private fun toggleDetailPanel() {
        if (splitter.secondComponent == null) {
            splitter.secondComponent = detailPanel
        } else {
            splitter.secondComponent = null
        }
        splitter.revalidate()
        splitter.repaint()
    }

    private fun applyLocalFilter() {
        if (isUpdatingFilters) return
        
        val query = searchField.text.lowercase()
        
        val filtered = allIssues.filter {
            it.key.lowercase().contains(query) || it.fields.getSummaryText().lowercase().contains(query)
        }
        
        issueListContainer.removeAll()

        // Always show Quick Create row if a project is selected
        val selectedProjectStr = projectFilter.selectedItem as? String
        if (selectedProjectStr != null && selectedProjectStr != "Select Space...") {
            issueListContainer.add(CreateIssueRowComponent())
        }
        
        filtered.forEach { issue ->
            val row = IssueRowComponent(issue)
            issueListContainer.add(row)
        }
        issueListContainer.add(Box.createVerticalGlue())
        issueListContainer.revalidate()
        issueListContainer.repaint()
        
        statusLabel.text = "Showing ${filtered.size} of ${allIssues.size} issues"
    }

    private fun updateFilterUI() {
        val hasFilters = activeFilters.isNotEmpty() || searchField.text.isNotEmpty()
        resetFiltersButton.isVisible = hasFilters
        
        if (activeFilters.isNotEmpty()) {
            filterButton.foreground = JBColor(Color(0, 82, 204), Color(76, 154, 255))
            filterButton.text = "Filter •"
        } else {
            filterButton.foreground = UIUtil.getLabelForeground()
            filterButton.text = "Filter"
        }
    }

    private fun updateAssigneeFilterOptions(projectKey: String) {
        val currentUserEmail = JiraSettingsState.instance.email.lowercase()
        
        // Parallel data fetching with central caching
        ApplicationManager.getApplication().executeOnPooledThread {
            // Assignees
            JiraApiClient.instance.getAssignableUsers(projectKey).onSuccess { users ->
                val sortedUsers = users.distinctBy { it.accountId }.sortedWith(compareByDescending<JiraUser> { 
                    it.emailAddress?.lowercase() == currentUserEmail 
                }.thenBy { it.displayName })
                
                val names = listOf("Assignee: All") + sortedUsers.map { it.displayName }
                ApplicationManager.getApplication().invokeLater {
                    isProgrammaticUpdate = true
                    assigneeFilter.removeAllItems()
                    names.forEach { assigneeFilter.addItem(it) }
                    syncCombosFromActiveFilters()
                    isProgrammaticUpdate = false
                }
            }
            
            // Priorities
            JiraApiClient.instance.getPriorities().onSuccess { priorities ->
                val names = listOf("Priority: All") + priorities.map { it.name }.distinct().sorted()
                ApplicationManager.getApplication().invokeLater {
                    isProgrammaticUpdate = true
                    priorityFilter.removeAllItems()
                    names.forEach { priorityFilter.addItem(it) }
                    syncCombosFromActiveFilters()
                    isProgrammaticUpdate = false
                }
            }
            
            // Issue Types
            JiraApiClient.instance.getIssueTypes().onSuccess { types ->
                val names = listOf("Type: All") + types.map { it.name }.distinct().sorted()
                ApplicationManager.getApplication().invokeLater {
                    isProgrammaticUpdate = true
                    workTypeFilter.removeAllItems()
                    names.forEach { workTypeFilter.addItem(it) }
                    syncCombosFromActiveFilters()
                    isProgrammaticUpdate = false
                }
            }
            
            // Epics (Parents)
            JiraApiClient.instance.getEpics(projectKey).onSuccess { issues ->
                val names = listOf("Parent: All") + issues.map { "${it.key}: ${it.fields.summary}" }.distinct()
                ApplicationManager.getApplication().invokeLater {
                    isProgrammaticUpdate = true
                    parentFilter.removeAllItems()
                    names.forEach { parentFilter.addItem(it) }
                    syncCombosFromActiveFilters()
                    isProgrammaticUpdate = false
                }
            }
            
            ApplicationManager.getApplication().invokeLater { refreshIssues() }
        }
    }

    private fun checkDirty() {
        if (isProgrammaticUpdate) return
        val issue = selectedIssue ?: return
        val summaryChanged = issueSummaryArea.text != issue.fields.getSummaryText()
        val descChanged = issueDescriptionArea.text != issue.fields.getDescriptionText()
        saveChangesButton.isEnabled = summaryChanged || descChanged
    }

    private fun showIssueDetail(issue: JiraIssue) {
        isProgrammaticUpdate = true
        issueKeyLabel.text = issue.key
        issueSummaryArea.text = issue.fields.getSummaryText()
        issueStatusLabel.text = issue.fields.getStatusName()
        issuePriorityLabel.text = issue.fields.getPriorityName()
        issueAssigneeLabel.text = issue.fields.getAssigneeName()
        issueSprintLabel.text = issue.fields.getSprintNames()
        issueDescriptionArea.text = issue.fields.getDescriptionText()

        commentButton.isEnabled = true
        transitionButton.isEnabled = true
        logTimeButton.isEnabled = true
        openInBrowserButton.isEnabled = true
        changeSprintButton.isEnabled = true
        saveChangesButton.isEnabled = false
        
        isProgrammaticUpdate = false

        detailPanel.revalidate()
        detailPanel.repaint()
    }

    private fun showEmptyDetailState() {
        isProgrammaticUpdate = true
        issueKeyLabel.text = ""
        issueSummaryArea.text = "Select an issue to view details"
        issueStatusLabel.text = "-"
        issuePriorityLabel.text = "-"
        issueAssigneeLabel.text = "-"
        issueSprintLabel.text = "-"
        issueDescriptionArea.text = ""

        commentButton.isEnabled = false
        transitionButton.isEnabled = false
        logTimeButton.isEnabled = false
        openInBrowserButton.isEnabled = false
        changeSprintButton.isEnabled = false
        saveChangesButton.isEnabled = false
        isProgrammaticUpdate = false

        detailPanel.revalidate()
        detailPanel.repaint()
    }
    
    private fun loadProjects() {
        val settings = JiraSettingsState.instance
        if (settings.jiraUrl.isBlank()) return
        
        statusLabel.text = "Loading spaces..."
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading Jira spaces", true) {
            override fun run(indicator: ProgressIndicator) {
                JiraApiClient.instance.getProjects().fold(
                    onSuccess = { projects ->
                        ApplicationManager.getApplication().invokeLater {
                            allProjects = projects.sortedBy { it.name }
                            isUpdatingFilters = true
                            val current = projectFilter.selectedItem as? String
                            projectFilter.removeAllItems()
                            projectFilter.addItem("Select Space...")
                            allProjects.forEach { projectFilter.addItem("${it.key} - ${it.name}") }
                            
                            val savedKey = JiraSettingsState.instance.selectedProjectKey
                            var projectMatched = false
                            if (savedKey.isNotBlank()) {
                                for (i in 0 until projectFilter.itemCount) {
                                    if (projectFilter.getItemAt(i).startsWith(savedKey)) {
                                        projectFilter.selectedIndex = i
                                        projectMatched = true
                                        break
                                    }
                                }
                            } else if (current != null) {
                                projectFilter.selectedItem = current
                                projectMatched = true
                            }
                            isUpdatingFilters = false
                            
                            if (projectMatched) {
                                val projectKey = projectFilter.getItemAt(projectFilter.selectedIndex).substringBefore(" - ")
                                isUpdatingFilters = true
                                updateAssigneeFilterOptions(projectKey)
                                isUpdatingFilters = false
                            } else {
                                statusLabel.text = "Select a space to view issues."
                            }
                        }
                    },
                    onFailure = { error ->
                        ApplicationManager.getApplication().invokeLater {
                            statusLabel.text = "Failed to load spaces: ${error.message}"
                        }
                    }
                )
            }
        })
    }

    fun refreshIssues() {
        val settings = JiraSettingsState.instance
        if (settings.jiraUrl.isBlank()) {
            statusLabel.text = "Please configure Jira settings first."
            return
        }

        updateFilterUI()

        val selectedProjectStr = projectFilter.selectedItem as? String
        if (selectedProjectStr == null || selectedProjectStr == "Select Space...") {
            statusLabel.text = "Please select a space to view issues."
            allIssues = emptyList()
            applyLocalFilter()
            return
        }

        val projectKey = selectedProjectStr.substringBefore(" - ")
        var jql = "project = '$projectKey'"
        
        // Add active filters to JQL
        activeFilters.forEach { (category, values) ->
            if (values.isNotEmpty()) {
                val fieldName = when (category) {
                    FilterCategory.STATUS -> "status"
                    FilterCategory.WORK_TYPE -> "issuetype"
                    FilterCategory.ASSIGNEE -> "assignee"
                    FilterCategory.PRIORITY -> "priority"
                    FilterCategory.LABELS -> "labels"
                    FilterCategory.SPRINT -> "sprint"
                    FilterCategory.PARENT -> "parent"
                    FilterCategory.FIX_VERSIONS -> "fixVersion"
                }
                
                val valuesStr = values.joinToString(", ") { "\"$it\"" }
                jql += " AND $fieldName IN ($valuesStr)"
            }
        }

        // Sorting
        jql += " ORDER BY updated DESC"

        statusLabel.text = "Searching..."
        refreshButton.isEnabled = false

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading Jira issues", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = JiraApiClient.instance.searchIssues(jql, maxResults = 500)
                ApplicationManager.getApplication().invokeLater {
                    refreshButton.isEnabled = true
                    result.fold(
                        onSuccess = { response ->
                            // Show only backlog (not done) and active/future sprints. Exclude closed sprint issues.
                            allIssues = response.issues.filter { issue ->
                                val sprints = issue.fields.sprints
                                val isActive = sprints?.any { it.state.equals("active", ignoreCase = true) } == true
                                val isFuture = sprints?.any { it.state.equals("future", ignoreCase = true) } == true
                                val isDone = issue.fields.getStatusCategoryKey()?.equals("done", ignoreCase = true) == true
                                
                                // Logic:
                                // 1. Always show issues in active or future sprints
                                // 2. For issues not in a current/future sprint (Backlog), show only if they are not 'Done'
                                isActive || isFuture || (!isDone && (sprints == null || sprints.isEmpty() || sprints.all { it.state.equals("closed", ignoreCase = true) }))
                            }
                            applyLocalFilter()
                            statusLabel.text = "Found ${allIssues.size} issues (Last updated: ${java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))})"
                        },
                        onFailure = { error ->
                            statusLabel.text = "Error: ${error.message}"
                        }
                    )
                }
            }
        })
    }

    private fun saveIssueDetails() {
        val issue = selectedIssue ?: return
        val newSummary = issueSummaryArea.text
        val newDescription = issueDescriptionArea.text

        saveChangesButton.isEnabled = false
        saveChangesButton.text = "Saving..."

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Saving Issue Changes", true) {
            override fun run(indicator: ProgressIndicator) {
                JiraApiClient.instance.updateIssueDetails(issue.key, newSummary, newDescription).fold(
                    onSuccess = {
                        ApplicationManager.getApplication().invokeLater {
                            saveChangesButton.text = "💾 Save Changes"
                            refreshIssues()
                        }
                    },
                    onFailure = { error ->
                        ApplicationManager.getApplication().invokeLater {
                            saveChangesButton.isEnabled = true
                            saveChangesButton.text = "💾 Save Changes"
                            Messages.showErrorDialog(project, "Failed to save changes:\n${error.message}", "Error")
                        }
                    }
                )
            }
        })
    }

    private fun addComment() {
        val issue = selectedIssue ?: return
        val comment = Messages.showMultilineInputDialog(project, "Add comment to ${issue.key}:", "Add Comment", "", Messages.getQuestionIcon(), null) ?: return
        if (comment.isBlank()) return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Adding Comment", true) {
            override fun run(indicator: ProgressIndicator) {
                JiraApiClient.instance.addComment(issue.key, comment).onFailure { error ->
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, error.message, "Error")
                    }
                }
            }
        })
    }

    private fun changeStatus(issue: JiraIssue? = selectedIssue) {
        val target = issue ?: return
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Getting Transitions", true) {
            override fun run(indicator: ProgressIndicator) {
                JiraApiClient.instance.getTransitions(target.key).onSuccess { transitions ->
                    ApplicationManager.getApplication().invokeLater {
                        val names = transitions.map { it.name }.toTypedArray()
                        val selected = Messages.showEditableChooseDialog("New status for ${target.key}:", "Change Status", Messages.getQuestionIcon(), names, names.firstOrNull() ?: "", null) ?: return@invokeLater
                        val transition = transitions.find { it.name == selected } ?: return@invokeLater
                        
                        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Updating Status", true) {
                            override fun run(indicator: ProgressIndicator) {
                                JiraApiClient.instance.performTransition(target.key, transition.id).onSuccess {
                                    refreshIssues()
                                }
                            }
                        })
                    }
                }
            }
        })
    }

    private fun updateEstimate(issue: JiraIssue) {
        val current = issue.fields.getOriginalEstimateText()
        val newEstimate = Messages.showInputDialog(project, "New original estimate for ${issue.key} (e.g., 2h, 1d):", "Update Estimate", Messages.getQuestionIcon(), current, null) ?: return
        
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Updating Estimate", true) {
            override fun run(indicator: ProgressIndicator) {
                JiraApiClient.instance.updateEstimate(issue.key, newEstimate).fold(
                    onSuccess = { refreshIssues() },
                    onFailure = { error ->
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showErrorDialog(project, error.message, "Error")
                        }
                    }
                )
            }
        })
    }

    private fun changeAssignee(issue: JiraIssue) {
        val projectKey = issue.key.substringBefore("-")
        
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Fetching Assignable Users", true) {
            override fun run(indicator: ProgressIndicator) {
                JiraApiClient.instance.getAssignableUsers(projectKey).fold(
                    onSuccess = { users ->
                        ApplicationManager.getApplication().invokeLater {
                            val names = users.map { it.displayName }.toTypedArray()
                            val selected = Messages.showEditableChooseDialog("Select Assignee for ${issue.key}:", "Change Assignee", Messages.getQuestionIcon(), names, issue.fields.getAssigneeName(), null) ?: return@invokeLater
                            val user = users.find { it.displayName == selected } ?: return@invokeLater
                            
                            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Assigning Issue", true) {
                                override fun run(indicator: ProgressIndicator) {
                                    JiraApiClient.instance.assignIssue(issue.key, user.accountId).onSuccess {
                                        refreshIssues()
                                    }
                                }
                            })
                        }
                    },
                    onFailure = { error ->
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showErrorDialog(project, error.message, "Error")
                        }
                    }
                )
            }
        })
    }

    private fun logTime(issue: JiraIssue? = selectedIssue) {
        val target = issue ?: return
        val time = Messages.showInputDialog(project, "Time spent on ${target.key} (e.g. 1h 30m):", "Log Time", Messages.getQuestionIcon()) ?: return
        val comment = Messages.showInputDialog(project, "Comment:", "Log Time", Messages.getQuestionIcon())
        
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Logging Time", true) {
            override fun run(indicator: ProgressIndicator) {
                JiraApiClient.instance.addWorklog(target.key, time, comment).onSuccess {
                    Messages.showInfoMessage(project, "Time logged successfully", "Success")
                }
            }
        })
    }

    private fun changeSprint(issue: JiraIssue? = selectedIssue) {
        val target = issue ?: return
        val projectKey = target.key.substringBefore("-")
        
        statusLabel.text = "Loading sprints for $projectKey..."
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading Sprints", true) {
            override fun run(indicator: ProgressIndicator) {
                JiraApiClient.instance.getBoards(projectKey).fold(
                    onSuccess = { boards ->
                        val board = boards.firstOrNull()
                        if (board != null) {
                            JiraApiClient.instance.getSprints(board.id).fold(
                                onSuccess = { sprints ->
                                    ApplicationManager.getApplication().invokeLater {
                                        val filtered = sprints.filter { it.state != "closed" }
                                        val names = filtered.map { it.name }.toTypedArray()
                                        val selected = Messages.showEditableChooseDialog("Select Sprint for ${target.key}:", "Change Sprint", Messages.getQuestionIcon(), names, target.fields.getSprintNames().split(", ").firstOrNull() ?: "", null) ?: return@invokeLater
                                        val sprint = filtered.find { it.name == selected } ?: return@invokeLater
                                        
                                        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Moving Issue to Sprint", true) {
                                            override fun run(indicator: ProgressIndicator) {
                                                JiraApiClient.instance.moveIssueToSprint(target.key, sprint.id).onSuccess {
                                                    refreshIssues()
                                                }
                                            }
                                        })
                                    }
                                },
                                onFailure = { error ->
                                    ApplicationManager.getApplication().invokeLater {
                                        Messages.showErrorDialog(project, "Failed to load sprints: ${error.message}", "Error")
                                    }
                                }
                            )
                        } else {
                            ApplicationManager.getApplication().invokeLater {
                                Messages.showErrorDialog(project, "No boards found for project $projectKey", "Error")
                            }
                        }
                    },
                    onFailure = { error ->
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showErrorDialog(project, "Failed to load boards: ${error.message}", "Error")
                        }
                    }
                )
            }
        })
    }

    private fun openInBrowser() {
        selectedIssue?.let {
            com.intellij.ide.BrowserUtil.browse("${JiraSettingsState.instance.jiraUrl.trimEnd('/')}/browse/${it.key}")
        }
    }

    private fun getIcon(url: String?): Icon? {
        if (url == null) return null
        val cached = iconCache[url]
        if (cached != null) return cached

        if (loadingIcons.add(url)) {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val image = ImageIO.read(URL(url))
                    val icon = ImageIcon(image.getScaledInstance(16, 16, Image.SCALE_SMOOTH))
                    iconCache[url] = icon
                    ApplicationManager.getApplication().invokeLater { 
                        issueListContainer.repaint()
                    }
                } catch (e: Exception) {
                } finally {
                    loadingIcons.remove(url)
                }
            }
        }
        return null
    }

    private inner class CreateIssueRowComponent : JPanel(BorderLayout()) {
        init {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(8, 20)
            )
            // Use a distinct Jira-like background highlight
            background = JBColor(Color(240, 245, 255), Color(30, 45, 70))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            maximumSize = Dimension(Int.MAX_VALUE, 45)
            
            val label = JLabel("Create Issue", AllIcons.General.Add, SwingConstants.LEFT).apply {
                font = JBUI.Fonts.label(14f).asBold()
                foreground = JBColor(Color(0, 82, 204), Color(76, 154, 255))
                iconTextGap = 12
            }
            add(label, BorderLayout.WEST)
            
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        // Clear regular issue selection
                        selectedRow?.background = UIUtil.getListBackground()
                        selectedRow = null
                        selectedIssue = null
                        showEmptyDetailState()

                        val selectedProjectStr = projectFilter.selectedItem as? String
                        val projectKey = if (selectedProjectStr != null && selectedProjectStr != "Select Space...") {
                            selectedProjectStr.substringBefore(" - ")
                        } else null

                        if (JiraCreateIssueDialog(project, projectKey).showAndGet()) {
                            refreshIssues()
                        }
                    }
                }
                override fun mouseEntered(e: MouseEvent) {
                    background = background.brighter()
                }
                override fun mouseExited(e: MouseEvent) {
                    background = JBColor(Color(240, 245, 255), Color(30, 45, 70))
                }
            })
        }
    }

    private inner class IssueRowComponent(val issue: JiraIssue) : JPanel(GridBagLayout()) {
        
        init {
            border = JBUI.Borders.empty(8, 12)
            background = UIUtil.getListBackground()
            maximumSize = Dimension(Int.MAX_VALUE, 45)
            toolTipText = issue.fields.getSummaryText()
            
            val c = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST
                weighty = 1.0
            }

            // 1. Type Icon
            c.weightx = 0.0
            add(JLabel(getIcon(issue.fields.getIssueTypeIconUrl())).apply {
                preferredSize = Dimension(20, 20)
            }, c)

            // 2. Key
            c.gridx = 1
            c.insets = JBUI.insetsLeft(8)
            add(JLabel(issue.key).apply {
                font = JBUI.Fonts.label().asBold()
                foreground = JBColor(Color(0, 82, 204), Color(76, 154, 255))
                preferredSize = Dimension(85, 20)
                minimumSize = Dimension(85, 20)
            }, c)

            // 3. Summary
            c.gridx = 2
            c.weightx = 1.0
            c.insets = JBUI.insetsLeft(12)
            add(JLabel(issue.fields.getSummaryText()).apply {
                foreground = UIUtil.getListForeground()
                preferredSize = Dimension(0, 20)
                minimumSize = Dimension(0, 20)
            }, c)

            // 4. Assignee Badge
            c.gridx = 3
            c.weightx = 0.0
            c.insets = JBUI.insetsLeft(12)
            val assigneeName = issue.fields.getAssigneeName()
            add(createBadge(if (assigneeName.length > 12) assigneeName.take(10) + ".." else assigneeName,
                            JBColor(Color(244, 245, 247), Color(45, 53, 64)),
                            UIUtil.getLabelForeground()).apply {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) = changeAssignee(issue)
                })
                preferredSize = Dimension(100, 22)
                minimumSize = Dimension(100, 22)
            }, c)

            // 5. Estimate
            c.gridx = 4
            c.insets = JBUI.insetsLeft(12)
            val estimate = issue.fields.getOriginalEstimateText()
            add(JLabel(estimate).apply {
                font = JBUI.Fonts.smallFont()
                foreground = JBColor.GRAY
                horizontalAlignment = SwingConstants.RIGHT
                preferredSize = Dimension(60, 20)
                minimumSize = Dimension(60, 20)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) = updateEstimate(issue)
                })
            }, c)

            // 6. Status Badge
            c.gridx = 5
            c.insets = JBUI.insetsLeft(12)
            val statusBadge = JPanel(BorderLayout()).apply {
                isOpaque = true
                background = getStatusBgColor(issue.fields.getStatusCategoryKey())
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                val label = JLabel(issue.fields.getStatusName().uppercase()).apply {
                    font = JBUI.Fonts.smallFont().asBold()
                    foreground = getStatusTextColor(issue.fields.getStatusCategoryKey())
                    border = JBUI.Borders.empty(2, 6)
                    horizontalAlignment = SwingConstants.CENTER
                }
                add(label)
                preferredSize = Dimension(100, 22)
                minimumSize = Dimension(100, 22)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) = changeStatus(issue)
                })
            }
            add(statusBadge, c)

            // 7. Priority
            c.gridx = 6
            c.insets = JBUI.insets(0, 12, 0, 4)
            add(JLabel(getIcon(issue.fields.getPriorityIconUrl())).apply {
                preferredSize = Dimension(20, 20)
            }, c)

            // Selection Logic
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        selectRow(this@IssueRowComponent)
                        if (e.clickCount == 2) {
                            toggleDetailPanel()
                        }
                    }
                }
                override fun mouseEntered(e: MouseEvent) {
                    if (selectedRow != this@IssueRowComponent) {
                        background = UIUtil.getListBackground().brighter()
                    }
                }
                override fun mouseExited(e: MouseEvent) {
                    if (selectedRow != this@IssueRowComponent) {
                        background = UIUtil.getListBackground()
                    }
                }
            })
        }

        private fun createBadge(text: String, bg: Color, fg: Color): JPanel {
            return JPanel(BorderLayout()).apply {
                isOpaque = true
                background = bg
                add(JLabel(text).apply {
                    font = JBUI.Fonts.smallFont().asBold()
                    foreground = fg
                    border = JBUI.Borders.empty(2, 4)
                    horizontalAlignment = SwingConstants.CENTER
                })
            }
        }
    }

    private fun selectRow(row: IssueRowComponent) {
        selectedRow?.background = UIUtil.getListBackground()
        selectedRow = row
        row.background = UIUtil.getListSelectionBackground(true)
        selectedIssue = row.issue
        showIssueDetail(row.issue)
    }

    private fun getStatusBgColor(key: String?): Color {
        return when (key) {
            "new" -> JBColor(Color(223, 225, 230), Color(40, 46, 54))
            "indeterminate" -> JBColor(Color(222, 235, 255), Color(7, 33, 70))
            "done" -> JBColor(Color(227, 252, 239), Color(21, 52, 42))
            else -> JBColor(Color(223, 225, 230), Color(40, 46, 54))
        }
    }

    private fun getStatusTextColor(key: String?): Color {
        return when (key) {
            "new" -> JBColor(Color(66, 82, 110), Color(165, 173, 186))
            "indeterminate" -> JBColor(Color(0, 82, 204), Color(76, 154, 255))
            "done" -> JBColor(Color(0, 102, 68), Color(54, 179, 126))
            else -> JBColor(Color(66, 82, 110), Color(165, 173, 186))
        }
    }
}
