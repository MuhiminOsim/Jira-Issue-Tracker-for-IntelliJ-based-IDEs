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

class JiraBoardPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val columnsContainer = JPanel(FlowLayout(FlowLayout.LEFT, 15, 0)).apply {
        background = UIUtil.getPanelBackground()
    }
    private val scrollPane = JBScrollPane(columnsContainer).apply {
        border = JBUI.Borders.empty()
        viewport.background = UIUtil.getPanelBackground()
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
    }

    private val iconCache = ConcurrentHashMap<String, Icon>()
    private var currentBoard: JiraBoard? = null
    private var currentConfig: JiraBoardConfiguration? = null
    private var issuesByStatus = mutableMapOf<String, MutableList<JiraIssue>>()
    private val allLists = mutableListOf<JList<JiraIssue>>()

    init {
        add(scrollPane, BorderLayout.CENTER)
        background = UIUtil.getPanelBackground()
    }

    fun refreshBoard(projectKey: String) {
        if (projectKey.isBlank()) {
            showError("Please select a space in the 'Issues' tab first.")
            return
        }
        
        columnsContainer.removeAll()
        columnsContainer.add(JLabel("Loading board...").apply { 
            border = JBUI.Borders.empty(20)
            foreground = JBColor.GRAY
        })
        columnsContainer.revalidate()
        columnsContainer.repaint()

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading Board", true) {
            override fun run(indicator: ProgressIndicator) {
                JiraApiClient.instance.getBoards(projectKey).fold(
                    onSuccess = { boards ->
                        val board = boards.firstOrNull()
                        if (board != null) {
                            currentBoard = board
                            JiraApiClient.instance.getBoardConfiguration(board.id).fold(
                                onSuccess = { config ->
                                    currentConfig = config
                                    loadIssues(projectKey, config)
                                },
                                onFailure = { error -> showError("Failed to load board configuration: ${error.message}") }
                            )
                        } else {
                            showError("No boards found for project $projectKey")
                        }
                    },
                    onFailure = { error -> showError("Failed to load boards: ${error.message}") }
                )
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

    private fun loadIssues(projectKey: String, config: JiraBoardConfiguration) {
        val jql = "project = \"$projectKey\" AND sprint IN openSprints() ORDER BY rank ASC"
        JiraApiClient.instance.searchIssues(jql).fold(
            onSuccess = { response ->
                ApplicationManager.getApplication().invokeLater {
                    if (response.issues.isEmpty()) {
                        showError("No active sprint found or no issues in the current sprint.")
                    } else {
                        updateUI(config, response.issues)
                    }
                }
            },
            onFailure = { error -> showError("Failed to load issues: ${error.message}") }
        )
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

        config.columnConfig.columns.forEach { column ->
            val columnPanel = BoardColumn(column)
            columnsContainer.add(columnPanel)
            
            // Add issues to column
            column.statuses.forEach { statusId ->
                issuesByStatus[statusId.id]?.forEach { issue ->
                    columnPanel.addIssue(issue)
                }
            }
        }
        
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
        private val issueList = JList(listModel).apply {
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
            preferredSize = Dimension(300, 1000)
            minimumSize = Dimension(300, 200)
            background = UIUtil.getPanelBackground()
            
            val header = JPanel(BorderLayout()).apply {
                background = UIUtil.getPanelBackground()
                border = JBUI.Borders.empty(12, 8)
                add(titleLabel, BorderLayout.WEST)
            }
            
            val scroll = JBScrollPane(issueList).apply {
                border = JBUI.Borders.empty()
                viewport.background = JBColor(Color(244, 245, 247), Color(30, 34, 40))
            }

            add(header, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
        }

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
