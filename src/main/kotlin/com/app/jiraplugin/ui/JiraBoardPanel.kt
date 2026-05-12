package com.app.jiraplugin.ui

import com.app.jiraplugin.api.JiraApiClient
import com.app.jiraplugin.models.*
import com.app.jiraplugin.settings.JiraSettingsState
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class JiraBoardPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val searchField = SearchTextField().apply {
        textEditor.emptyText.text = "Search issues..."
    }
    private val filterButton = JButton("Filters", AllIcons.General.Filter).apply { isFocusable = false }
    private val refreshButton = JButton(AllIcons.Actions.Refresh).apply { isFocusable = false }
    private val resetButton = JButton("Reset").apply {
        isVisible = false
        margin = JBUI.insets(2, 4)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isFocusable = false
    }

    private val assigneeFilter = ComboBox<String>(arrayOf("Assignee: All"))
    private val priorityFilter = ComboBox<String>(arrayOf("Priority: All"))
    private val workTypeFilter = ComboBox<String>(arrayOf("Type: All"))
    private val parentFilter = ComboBox<String>(arrayOf("Parent: All"))

    private val statusLabel = JLabel(" ").apply {
        font = JBUI.Fonts.smallFont()
        foreground = JBColor.GRAY
        border = JBUI.Borders.empty(4, 12)
    }

    private var activeFilters = mutableMapOf<FilterCategory, List<String>>()
    private var allIssues = listOf<JiraIssue>()

    private val columnsContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(0, 15)
    }
    private val scrollPane = JBScrollPane(columnsContainer).apply {
        border = JBUI.Borders.empty()
        viewport.background = UIUtil.getPanelBackground()
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
    }

    private val iconCache = ConcurrentHashMap<String, Icon>()
    private var currentProjectKey: String = ""
    private var issuesByStatus = mutableMapOf<String, MutableList<JiraIssue>>()
    private val allLists = mutableListOf<JList<JiraIssue>>()

    init {
        setupUI()
        setupListeners()
        background = UIUtil.getPanelBackground()
    }

    private fun setupUI() {
        val filterBar = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
            
            // Left part: Search + Scrollable Filters
            val leftContent = JPanel(GridBagLayout()).apply {
                isOpaque = false
                val c = GridBagConstraints().apply {
                    fill = GridBagConstraints.VERTICAL
                    anchor = GridBagConstraints.WEST
                    insets = JBUI.insets(8, 12, 8, 8)
                }
                
                // Search field (fixed width)
                c.gridx = 0
                searchField.preferredSize = Dimension(120, searchField.preferredSize.height)
                searchField.minimumSize = Dimension(120, searchField.preferredSize.height)
                add(searchField, c)
                
                // Filters button (Fixed)
                c.gridx++
                add(filterButton, c)
                
                // Scrollable filters
                c.gridx++
                c.weightx = 1.0
                c.fill = GridBagConstraints.BOTH
                c.insets = JBUI.emptyInsets()
                
                val filtersPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 8)).apply {
                    isOpaque = false
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
                add(resetButton)
                add(refreshButton)
            }
            add(rightContent, BorderLayout.EAST)
        }

        add(filterBar, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)
    }

    private fun setupListeners() {
        val docListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onFilterChanged()
            override fun removeUpdate(e: DocumentEvent) = onFilterChanged()
            override fun changedUpdate(e: DocumentEvent) = onFilterChanged()
        }
        searchField.addDocumentListener(docListener)

        val comboListener = { _: java.awt.event.ActionEvent ->
            updateActiveFiltersFromCombos()
            onFilterChanged()
        }
        assigneeFilter.addActionListener(comboListener)
        priorityFilter.addActionListener(comboListener)
        workTypeFilter.addActionListener(comboListener)
        parentFilter.addActionListener(comboListener)

        filterButton.addActionListener {
            val dialog = JiraFilterDialog(project, currentProjectKey, activeFilters)
            if (dialog.showAndGet()) {
                activeFilters = dialog.getSelectedFilters().toMutableMap()
                syncCombosFromActiveFilters()
                loadIssues(currentProjectKey)
            }
        }

        resetButton.addActionListener {
            searchField.text = ""
            activeFilters.clear()
            syncCombosFromActiveFilters()
            onFilterChanged()
        }

        refreshButton.addActionListener {
            refreshBoard(currentProjectKey, force = true)
        }
    }

    private var isUpdatingCombos = false

    private fun updateActiveFiltersFromCombos() {
        if (isUpdatingCombos) return
        
        fun updateCategory(category: FilterCategory, combo: ComboBox<String>, prefix: String) {
            val selected = combo.selectedItem as String
            if (selected == "$prefix: All") {
                activeFilters.remove(category)
            } else {
                activeFilters[category] = listOf(selected)
            }
        }

        updateCategory(FilterCategory.ASSIGNEE, assigneeFilter, "Assignee")
        updateCategory(FilterCategory.PRIORITY, priorityFilter, "Priority")
        updateCategory(FilterCategory.WORK_TYPE, workTypeFilter, "Type")
        updateCategory(FilterCategory.PARENT, parentFilter, "Parent")
    }

    private fun syncCombosFromActiveFilters() {
        isUpdatingCombos = true
        fun syncCombo(category: FilterCategory, combo: ComboBox<String>) {
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
        isUpdatingCombos = false
    }

    private fun onFilterChanged() {
        updateFilterUI()
        loadIssues(currentProjectKey)
    }

    private fun updateFilterUI() {
        val hasFilters = searchField.text.isNotEmpty() || activeFilters.isNotEmpty()
        resetButton.isVisible = hasFilters
        
        if (activeFilters.isNotEmpty()) {
            filterButton.foreground = JBColor(Color(0, 82, 204), Color(76, 154, 255))
            filterButton.text = "Filters •"
        } else {
            filterButton.foreground = UIUtil.getLabelForeground()
            filterButton.text = "Filters"
        }
    }

    fun refreshBoard(projectKey: String, force: Boolean = false) {
        if (projectKey.isBlank()) {
            showError("Please select a space in the 'Issues' tab first.")
            return
        }
        
        val isNewProject = currentProjectKey != projectKey
        if (!force && !isNewProject && allIssues.isNotEmpty()) {
            return
        }
        
        currentProjectKey = projectKey
        refreshButton.isEnabled = false
        statusLabel.text = "Refreshing board..."
        updateFilterUI()
        
        // Show loading UI
        if (force || isNewProject || columnsContainer.componentCount == 0) {
            columnsContainer.removeAll()
            columnsContainer.add(JLabel("Loading board...").apply { 
                border = JBUI.Borders.empty(20)
                foreground = JBColor.GRAY
            })
            columnsContainer.revalidate()
            columnsContainer.repaint()
        }

        // Start everything in parallel
        fetchFilterData(projectKey)
        loadIssues(projectKey)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading board", true) {
            override fun run(indicator: ProgressIndicator) {
                JiraApiClient.instance.getBoards(projectKey).onSuccess { boards ->
                    val board = boards.firstOrNull()
                    if (board != null) {
                        JiraApiClient.instance.getBoardConfiguration(board.id).onSuccess {
                            ApplicationManager.getApplication().invokeLater {
                                refreshButton.isEnabled = true
                                if (allIssues.isNotEmpty()) {
                                    applyLocalFilter()
                                }
                            }
                        }.onFailure { error -> 
                            ApplicationManager.getApplication().invokeLater { refreshButton.isEnabled = true }
                            showError("Failed to load board configuration: ${error.message}") 
                        }
                    } else {
                        ApplicationManager.getApplication().invokeLater { refreshButton.isEnabled = true }
                        showError("No boards found for project $projectKey")
                    }
                }.onFailure { error ->
                    ApplicationManager.getApplication().invokeLater { refreshButton.isEnabled = true }
                    showError("Failed to load boards: ${error.message}")
                }
            }
        })
    }

    private fun showError(message: String) {
        ApplicationManager.getApplication().invokeLater {
            columnsContainer.removeAll()
            columnsContainer.add(JLabel(message).apply {
                border = JBUI.Borders.empty(20)
                foreground = JBColor.RED
            })
            columnsContainer.revalidate()
            columnsContainer.repaint()
        }
    }

    private fun fetchFilterData(projectKey: String) {
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
                    isUpdatingCombos = true
                    assigneeFilter.removeAllItems()
                    names.forEach { assigneeFilter.addItem(it) }
                    syncCombosFromActiveFilters()
                    isUpdatingCombos = false
                }
            }

            // Priorities
            JiraApiClient.instance.getPriorities().onSuccess { priorities ->
                val names = listOf("Priority: All") + priorities.map { it.name }.distinct().sorted()
                ApplicationManager.getApplication().invokeLater {
                    isUpdatingCombos = true
                    priorityFilter.removeAllItems()
                    names.forEach { priorityFilter.addItem(it) }
                    syncCombosFromActiveFilters()
                    isUpdatingCombos = false
                }
            }

            // Issue Types
            JiraApiClient.instance.getIssueTypes().onSuccess { types ->
                val names = listOf("Type: All") + types.map { it.name }.distinct().sorted()
                ApplicationManager.getApplication().invokeLater {
                    isUpdatingCombos = true
                    workTypeFilter.removeAllItems()
                    names.forEach { workTypeFilter.addItem(it) }
                    syncCombosFromActiveFilters()
                    isUpdatingCombos = false
                }
            }

            // Epics (Parents)
            JiraApiClient.instance.getEpics(projectKey).onSuccess { issues ->
                val names = listOf("Parent: All") + issues.map { "${it.key}: ${it.fields.summary}" }.distinct()
                ApplicationManager.getApplication().invokeLater {
                    isUpdatingCombos = true
                    parentFilter.removeAllItems()
                    names.forEach { parentFilter.addItem(it) }
                    syncCombosFromActiveFilters()
                    isUpdatingCombos = false
                }
            }
        }
    }

    private fun loadIssues(projectKey: String) {
        var jql = "project = \"$projectKey\" AND sprint IN openSprints()"
        
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
        
        jql += " ORDER BY rank ASC"
        
        ApplicationManager.getApplication().executeOnPooledThread {
            JiraApiClient.instance.searchIssues(jql).fold(
                onSuccess = { response ->
                    ApplicationManager.getApplication().invokeLater {
                        allIssues = response.issues
                        refreshButton.isEnabled = true
                        statusLabel.text = "Last updated: ${java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))} (${allIssues.size} issues)"
                        if (allIssues.isEmpty() && activeFilters.isEmpty()) {
                            showError("No active sprint found or no issues in the current sprint.")
                        } else {
                            applyLocalFilter()
                        }
                    }
                },
                onFailure = { error -> 
                    ApplicationManager.getApplication().invokeLater { 
                        refreshButton.isEnabled = true
                        statusLabel.text = "Error refreshing issues"
                    }
                    showError("Failed to load issues: ${error.message}") 
                }
            )
        }
    }

    private fun applyLocalFilter() {
        val projectKey = currentProjectKey
        val boards = JiraApiClient.instance.getBoards(projectKey).getOrNull()
        val boardId = boards?.firstOrNull()?.id ?: return
        val config = JiraApiClient.instance.getBoardConfiguration(boardId).getOrNull() ?: return

        val searchText = searchField.text.lowercase()
        
        val filteredIssues = allIssues.filter { issue ->
            searchText.isEmpty() || 
                issue.key.lowercase().contains(searchText) || 
                issue.fields.getSummaryText().lowercase().contains(searchText)
        }
        
        updateUI(config, filteredIssues)
    }

    private fun updateUI(config: JiraBoardConfiguration, issues: List<JiraIssue>) {
        columnsContainer.removeAll()
        issuesByStatus.clear()
        allLists.clear()
        
        // Group issues by status ID
        issues.forEach { issue ->
            val statusId = issue.fields.status?.asJsonObject?.get("id")?.asString ?: return@forEach
            issuesByStatus.getOrPut(statusId) { mutableListOf() }.add(issue)
        }

        config.columnConfig.columns.forEachIndexed { index, column ->
            if (index > 0) columnsContainer.add(Box.createRigidArea(Dimension(15, 0)))
            val columnPanel = BoardColumn(column)
            columnsContainer.add(columnPanel)
            
            // Add issues to column
            column.statuses.forEach { statusId ->
                issuesByStatus[statusId.id]?.forEach { issue ->
                    columnPanel.addIssue(issue)
                }
            }
        }
        columnsContainer.add(Box.createHorizontalGlue())
        
        columnsContainer.revalidate()
        columnsContainer.repaint()
    }

    private fun getIcon(url: String?): Icon? {
        if (url == null) return null
        iconCache[url]?.let { return it }
        
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val image = ImageIO.read(URL(url))
                val icon = ImageIcon(image.getScaledInstance(14, 14, Image.SCALE_SMOOTH))
                iconCache[url] = icon
                ApplicationManager.getApplication().invokeLater { 
                    columnsContainer.repaint()
                }
            } catch (e: Exception) {}
        }
        return null
    }

    inner class BoardColumn(val column: JiraColumn) : JPanel(BorderLayout()) {
        private val listModel = DefaultListModel<JiraIssue>()
        private val issueList = JBList<JiraIssue>(listModel).apply {
            cellRenderer = IssueCardRenderer()
            background = JBColor(Color(244, 245, 247), Color(30, 34, 40))
            selectionBackground = background // Hide default selection
            border = JBUI.Borders.empty(4, 0)
            dragEnabled = true
            transferHandler = BoardTransferHandler()
            dropMode = DropMode.INSERT
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    val index = locationToIndex(e.point)
                    if (index != -1 && getCellBounds(index, index).contains(e.point)) {
                        // Clicked on an item, proceed with default behavior
                    } else {
                        // Clicked on empty space, clear selection
                        clearSelection()
                    }
                    // Clear selection in all other lists
                    allLists.filter { it != this@apply }.forEach { it.clearSelection() }
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        val index = locationToIndex(e.point)
                        if (index != -1 && getCellBounds(index, index).contains(e.point)) {
                            val issue = model.getElementAt(index) ?: return
                            JiraIssueDetailDialog(project, issue).show()
                        }
                    }
                }
            })
        }

        private val titleLabel = JLabel("${column.name.uppercase()} • 0").apply {
            font = JBUI.Fonts.label(11f).asBold()
            foreground = JBColor.GRAY
        }

        init {
            allLists.add(issueList)
            background = UIUtil.getPanelBackground()
            alignmentY = 0.0f
            
            val header = JPanel(BorderLayout()).apply {
                background = UIUtil.getPanelBackground()
                border = JBUI.Borders.empty(12, 8)
                add(titleLabel, BorderLayout.WEST)
            }
            
            // Put the list directly in the panel instead of a scroll pane
            // This allows the full board to scroll vertically
            add(header, BorderLayout.NORTH)
            add(issueList, BorderLayout.CENTER)
        }

        override fun getPreferredSize(): Dimension {
            val base = super.getPreferredSize()
            return Dimension(300, base.height)
        }

        override fun getMinimumSize(): Dimension = Dimension(300, 200)

        fun addIssue(issue: JiraIssue) {
            listModel.addElement(issue)
            updateCount()
        }

        fun updateCount() {
            titleLabel.text = "${column.name.uppercase()} • ${listModel.size()}"
        }
    }

    inner class IssueCardRenderer : ListCellRenderer<JiraIssue> {
        override fun getListCellRendererComponent(
            list: JList<out JiraIssue>,
            value: JiraIssue,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            // Container panel to provide spacing between cards
            val container = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(6, 12)
            }

            // The actual card panel
            val card = JPanel(GridBagLayout()).apply {
                val cardBg = if (isSelected) {
                    JBColor(Color(222, 235, 255), Color(38, 55, 78))
                } else {
                    JBColor(Color.WHITE, Color(45, 53, 64))
                }
                background = cardBg
                
                border = JBUI.Borders.customLine(if (isSelected) JBColor(Color(0, 82, 204), Color(76, 154, 255)) else JBColor(Color(227, 230, 233), Color(60, 68, 78)), 1)
                
                val c = GridBagConstraints().apply {
                    fill = GridBagConstraints.HORIZONTAL
                    weightx = 1.0
                }

                // Summary
                c.gridy = 0
                c.insets = JBUI.insets(12, 12, 8, 12)
                // Use a smaller width in HTML to ensure it fits within the column without causing horizontal scroll
                add(JLabel("<html><body style='width: 180px'>${value.fields.getSummaryText()}</body></html>").apply {
                    font = JBUI.Fonts.label(13f).asBold()
                    minimumSize = Dimension(180, 32)
                }, c)

                // Footer
                c.gridy = 1
                c.insets = JBUI.insets(0, 12, 12, 12)
                val footer = JPanel(BorderLayout()).apply {
                    isOpaque = false
                    
                    val leftSide = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                        isOpaque = false
                        add(JLabel(getIcon(value.fields.getIssueTypeIconUrl())))
                        add(JLabel(getIcon(value.fields.getPriorityIconUrl())))
                        add(JLabel(value.key).apply {
                            font = JBUI.Fonts.smallFont()
                            foreground = JBColor.GRAY
                        })
                    }
                    add(leftSide, BorderLayout.WEST)

                    val rightSide = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                        isOpaque = false
                        
                        // Estimate Badge
                        val estimate = value.fields.getOriginalEstimateText()
                        if (estimate != "0h") {
                            add(createBadge(estimate, JBColor(Color(227, 230, 233), Color(40, 46, 54)), JBColor.GRAY))
                        }

                        // Assignee Initials
                        val assigneeName = value.fields.getAssigneeName()
                        val initials = if (assigneeName.contains(" ")) {
                            assigneeName.split(" ").mapNotNull { it.firstOrNull() }.joinToString("").uppercase()
                        } else {
                            assigneeName.take(2).uppercase()
                        }
                        
                        add(createBadge(initials, JBColor(Color(0, 82, 204), Color(76, 154, 255)), Color.WHITE).apply {
                            toolTipText = assigneeName
                        })
                    }
                    add(rightSide, BorderLayout.EAST)
                }
                add(footer, c)
            }
            
            container.add(card, BorderLayout.CENTER)
            return container
        }

        private fun createBadge(text: String, bgColor: Color, fgColor: Color): JLabel {
            return JLabel(text).apply {
                font = JBUI.Fonts.smallFont().asBold()
                foreground = fgColor
                background = bgColor
                isOpaque = true
                border = JBUI.Borders.empty(1, 6)
                // We'll simulate rounded corners with a custom border if needed, 
                // but standard opaque JLabel is a start.
            }
        }
    }

    inner class BoardTransferHandler : TransferHandler() {
        override fun getSourceActions(c: JComponent): Int = MOVE

        @Suppress("UNCHECKED_CAST")
        override fun createTransferable(c: JComponent): Transferable? {
            val list = c as? JList<JiraIssue> ?: return null
            val issue = list.selectedValue ?: return null
            return IssueTransferable(issue)
        }

        override fun canImport(support: TransferSupport): Boolean {
            return support.isDataFlavorSupported(IssueTransferable.DATA_FLAVOR)
        }

        override fun importData(support: TransferSupport): Boolean {
            if (!canImport(support)) return false
            
            @Suppress("UNCHECKED_CAST")
            val list = support.component as? JList<JiraIssue> ?: return false
            val transferable = support.transferable
            val issue = transferable.getTransferData(IssueTransferable.DATA_FLAVOR) as JiraIssue
            
            // Identify target column
            val columnPanel = SwingUtilities.getAncestorOfClass(BoardColumn::class.java, list) as? BoardColumn ?: return false
            val targetStatusId = columnPanel.column.statuses.firstOrNull()?.id ?: return false
            
            // Check if status is different
            val currentStatusId = issue.fields.status?.asJsonObject?.get("id")?.asString
            if (currentStatusId == targetStatusId) return false

            // Perform transition
            transitionIssue(issue, columnPanel.column)
            
            return true
        }
    }

    private fun transitionIssue(issue: JiraIssue, targetColumn: JiraColumn) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Updating Status", true) {
            override fun run(indicator: ProgressIndicator) {
                JiraApiClient.instance.getTransitions(issue.key).onSuccess { transitions ->
                    val statusIds = targetColumn.statuses.map { it.id }
                    val transition = transitions.find { trans ->
                        val toId = trans.to?.id
                        toId != null && statusIds.contains(toId)
                    }
                    
                    if (transition != null) {
                        JiraApiClient.instance.performTransition(issue.key, transition.id).onSuccess {
                            ApplicationManager.getApplication().invokeLater {
                                refreshBoard(issue.key.substringBefore("-"))
                            }
                        }
                    } else {
                        ApplicationManager.getApplication().invokeLater {
                            JOptionPane.showMessageDialog(null, "No valid Jira transition found to move this issue to ${targetColumn.name}. Check your Jira workflow permissions.", "Transition Error", JOptionPane.ERROR_MESSAGE)
                        }
                    }
                }
            }
        })
    }

    class IssueTransferable(val issue: JiraIssue) : Transferable {
        companion object {
            val DATA_FLAVOR = DataFlavor(JiraIssue::class.java, "JiraIssue")
        }
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DATA_FLAVOR)
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DATA_FLAVOR
        override fun getTransferData(flavor: DataFlavor): Any = issue
    }
}
