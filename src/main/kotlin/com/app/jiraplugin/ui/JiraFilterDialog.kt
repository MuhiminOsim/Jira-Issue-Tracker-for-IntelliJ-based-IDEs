package com.app.jiraplugin.ui

import com.app.jiraplugin.api.JiraApiClient
import com.app.jiraplugin.models.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CheckBoxList
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

enum class FilterCategory(val displayName: String) {
    FIX_VERSIONS("Fix versions"),
    PARENT("Parent"),
    SPRINT("Sprint"),
    ASSIGNEE("Assignee"),
    WORK_TYPE("Work type"),
    LABELS("Labels"),
    STATUS("Status"),
    PRIORITY("Priority")
}

class JiraFilterDialog(
    private val project: Project,
    private val projectKey: String,
    initialSelections: Map<FilterCategory, List<String>>
) : DialogWrapper(project, true) {

    private val selections = initialSelections.mapValues { it.value.toMutableList() }.toMutableMap()
    
    private val categorySearchField = SearchTextField().apply {
        textEditor.emptyText.text = "Search category..."
    }
    
    private val categoryList = JBList<FilterCategory>().apply {
        setCellRenderer { _, value, _, isSelected, _ ->
            JBLabel(value.displayName).apply {
                border = JBUI.Borders.empty(8, 12)
                if (isSelected) {
                    background = UIUtil.getListSelectionBackground(true)
                    foreground = UIUtil.getListSelectionForeground(true)
                    isOpaque = true
                }
            }
        }
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }
    
    private val itemSearchField = SearchTextField().apply {
        textEditor.emptyText.text = "Search items..."
    }
    private val itemCheckBoxList = CheckBoxList<String>().apply {
        emptyText.text = "Nothing to show"
    }
    
    private var allItemsForCurrentCategory = listOf<String>()
    private var isUpdatingItems = false
    @Volatile private var currentRequestToken = 0

    init {
        title = "Filter"
        setOKButtonText("Apply")
        init()
        setupListeners()
        filterCategories()
        updateItems()
    }

    override fun createCenterPanel(): JComponent {
        val leftPanel = JPanel(BorderLayout()).apply {
            val topPanel = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(10)
                add(categorySearchField, BorderLayout.CENTER)
            }
            add(topPanel, BorderLayout.NORTH)
            
            val scrollPane = JBScrollPane(categoryList).apply {
                border = JBUI.Borders.customLine(UIUtil.getBoundsColor(), 0, 0, 0, 1)
            }
            add(scrollPane, BorderLayout.CENTER)
            preferredSize = Dimension(180, 450)
        }

        val rightPanel = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            val topPanel = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(10)
                isOpaque = false
                add(itemSearchField, BorderLayout.CENTER)
            }
            add(topPanel, BorderLayout.NORTH)
            
            val scrollPane = JBScrollPane(itemCheckBoxList).apply {
                border = JBUI.Borders.empty()
                viewport.background = UIUtil.getPanelBackground()
            }
            add(scrollPane, BorderLayout.CENTER)
            
            val footerPanel = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(10)
                isOpaque = false
                
                val countLabel = JBLabel("0 of 0").apply {
                    foreground = UIUtil.getInactiveTextColor()
                    font = JBUI.Fonts.smallFont()
                }
                
                // Track item count
                itemCheckBoxList.model.addListDataListener(object : javax.swing.event.ListDataListener {
                    override fun intervalAdded(e: javax.swing.event.ListDataEvent?) = updateCount(countLabel)
                    override fun intervalRemoved(e: javax.swing.event.ListDataEvent?) = updateCount(countLabel)
                    override fun contentsChanged(e: javax.swing.event.ListDataEvent?) = updateCount(countLabel)
                })
                
                add(countLabel, BorderLayout.EAST)
                
                add(JBLabel("Press Shift + F to open and close").apply {
                    foreground = UIUtil.getInactiveTextColor()
                    font = JBUI.Fonts.smallFont()
                }, BorderLayout.WEST)
            }
            add(footerPanel, BorderLayout.SOUTH)
            
            preferredSize = Dimension(400, 450)
        }

        return OnePixelSplitter(false, 0.3f).apply {
            firstComponent = leftPanel
            secondComponent = rightPanel
            setHonorComponentsMinimumSize(true)
        }
    }

    private fun updateCount(label: JBLabel) {
        val selected = selections[categoryList.selectedValue]?.size ?: 0
        val total = allItemsForCurrentCategory.size
        label.text = "$selected of $total"
    }

    private fun setupListeners() {
        categorySearchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = filterCategories()
            override fun removeUpdate(e: DocumentEvent) = filterCategories()
            override fun changedUpdate(e: DocumentEvent) = filterCategories()
        })

        categoryList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                updateItems()
            }
        }

        itemSearchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = filterItems()
            override fun removeUpdate(e: DocumentEvent) = filterItems()
            override fun changedUpdate(e: DocumentEvent) = filterItems()
        })
        
        itemCheckBoxList.setCheckBoxListListener { _, _ ->
            if (!isUpdatingItems) {
                saveCurrentSelections()
            }
        }
    }

    private fun filterCategories() {
        val query = categorySearchField.text.lowercase()
        val filtered = FilterCategory.values().filter { it.displayName.lowercase().contains(query) }
        
        val model = DefaultListModel<FilterCategory>()
        filtered.forEach { model.addElement(it) }
        categoryList.model = model
        
        if (filtered.isNotEmpty() && categoryList.selectedIndex == -1) {
            categoryList.selectedIndex = 0
        }
    }

    private fun updateItems() {
        val category = categoryList.selectedValue ?: return
        itemSearchField.text = ""
        itemSearchField.textEditor.emptyText.text = "Search ${category.displayName.lowercase()}..."
        
        itemCheckBoxList.clear()
        itemCheckBoxList.emptyText.text = "Loading ${category.displayName.lowercase()}..."
        val requestToken = ++currentRequestToken
        
        Thread {
            try {
                when (category) {
                    FilterCategory.FIX_VERSIONS -> loadData(requestToken, { JiraApiClient.instance.getProjectVersions(projectKey) }, { it.reversed().map { v -> v.name }.distinct() })
                    FilterCategory.PARENT -> loadData(requestToken, { JiraApiClient.instance.searchIssues("project = \"$projectKey\" AND issuetype = Epic ORDER BY updated DESC", 50) }, { it.issues.map { i -> "${i.key}: ${i.fields.summary}" }.distinct() })
                    FilterCategory.SPRINT -> loadSprints(requestToken)
                    FilterCategory.ASSIGNEE -> loadData(requestToken, { JiraApiClient.instance.getAssignableUsers(projectKey) }, { users -> 
                        val connectedEmail = com.app.jiraplugin.settings.JiraSettingsState.instance.email.lowercase()
                        val connectedUser = users.find { it.emailAddress?.lowercase() == connectedEmail }
                        val sortedNames = users.map { it.displayName }.distinct().sorted().toMutableList()
                        if (connectedUser != null) {
                            sortedNames.remove(connectedUser.displayName)
                            sortedNames.add(0, connectedUser.displayName)
                        }
                        sortedNames
                    })
                    FilterCategory.WORK_TYPE -> loadData(requestToken, { JiraApiClient.instance.getIssueTypes() }, { it.map { t -> t.name }.distinct().sorted() })
                    FilterCategory.LABELS -> loadData(requestToken, { JiraApiClient.instance.getLabels() }, { it.sorted() })
                    FilterCategory.STATUS -> loadData(requestToken, { JiraApiClient.instance.getProjectStatuses(projectKey) }, { it.map { s -> s.name }.distinct().sorted() })
                    FilterCategory.PRIORITY -> loadData(requestToken, { JiraApiClient.instance.getPriorities() }, { it.map { p -> p.name }.distinct().sorted() })
                }
            } catch (e: Throwable) {
                if (requestToken == currentRequestToken) {
                    SwingUtilities.invokeLater { itemCheckBoxList.emptyText.text = "Critical error: ${e.message ?: e.javaClass.simpleName}" }
                }
            }
        }.start()
    }

    private fun <T> loadData(requestToken: Int, call: () -> Result<T>, mapper: (T) -> List<String>) {
        try {
            call().fold(
                onSuccess = { data -> if (requestToken == currentRequestToken) applyItemsToList(requestToken, mapper(data)) },
                onFailure = { error ->
                    if (requestToken == currentRequestToken) {
                        SwingUtilities.invokeLater { itemCheckBoxList.emptyText.text = "Error: ${error.message ?: "Unknown error"}" }
                    }
                }
            )
        } catch (e: Throwable) {
            if (requestToken == currentRequestToken) {
                SwingUtilities.invokeLater { itemCheckBoxList.emptyText.text = "Exception: ${e.message ?: e.javaClass.simpleName}" }
            }
        }
    }

    private fun loadSprints(requestToken: Int) {
        try {
            JiraApiClient.instance.getBoards(projectKey).fold(
                onSuccess = { boards ->
                    val board = boards.firstOrNull()
                    if (board != null) {
                        JiraApiClient.instance.getSprints(board.id).fold(
                            onSuccess = { sprints -> if (requestToken == currentRequestToken) applyItemsToList(requestToken, sprints.sortedByDescending { it.id }.map { it.name }.distinct()) },
                            onFailure = { error -> if (requestToken == currentRequestToken) SwingUtilities.invokeLater { itemCheckBoxList.emptyText.text = "Sprint error: ${error.message}" } }
                        )
                    } else {
                        if (requestToken == currentRequestToken) applyItemsToList(requestToken, emptyList())
                    }
                },
                onFailure = { error -> if (requestToken == currentRequestToken) SwingUtilities.invokeLater { itemCheckBoxList.emptyText.text = "Board error: ${error.message}" } }
            )
        } catch (e: Throwable) {
            if (requestToken == currentRequestToken) SwingUtilities.invokeLater { itemCheckBoxList.emptyText.text = "Sprint load error: ${e.message}" }
        }
    }

    private fun applyItemsToList(requestToken: Int, items: List<String>) {
        SwingUtilities.invokeLater {
            if (requestToken != currentRequestToken) return@invokeLater
            allItemsForCurrentCategory = items
            itemCheckBoxList.emptyText.text = "Nothing to show"
            filterItems()
        }
    }

    private fun filterItems() {
        isUpdatingItems = true
        val query = itemSearchField.text.lowercase()
        val filtered = allItemsForCurrentCategory.filter { it.lowercase().contains(query) }
        
        val category = categoryList.selectedValue
        val selectedForCategory = selections[category] ?: emptyList()
        
        itemCheckBoxList.clear()
        filtered.forEach { item ->
            itemCheckBoxList.addItem(item, item, selectedForCategory.contains(item))
        }
        isUpdatingItems = false
    }

    private fun saveCurrentSelections() {
        val category = categoryList.selectedValue ?: return
        val currentItems = mutableListOf<String>()
        for (i in 0 until itemCheckBoxList.model.size) {
            val item = itemCheckBoxList.getItemAt(i) ?: continue
            if (itemCheckBoxList.isItemSelected(i)) {
                currentItems.add(item)
            }
        }
        
        // We only want to update the selections for the items currently visible/managed in the right panel.
        // But since CheckBoxList is a bit simple, we'll just merge with existing hidden selections if we were filtering.
        // Actually, for simplicity, we'll just track all selections in the 'selections' map.
        
        val allSelectedForCategory = selections.getOrPut(category) { mutableListOf() }
        
        // Remove all items that are currently in the filtered list but NOT checked
        for (i in 0 until itemCheckBoxList.model.size) {
            val item = itemCheckBoxList.getItemAt(i) ?: continue
            if (itemCheckBoxList.isItemSelected(i)) {
                if (!allSelectedForCategory.contains(item)) {
                    allSelectedForCategory.add(item)
                }
            } else {
                allSelectedForCategory.remove(item)
            }
        }
    }

    fun getSelectedFilters(): Map<FilterCategory, List<String>> = selections
}
