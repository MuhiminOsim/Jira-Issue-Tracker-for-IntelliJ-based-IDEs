package com.app.jiraplugin.models

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

// New search/jql endpoint request body
data class JiraSearchRequest(
    val jql: String,
    val maxResults: Int = 100,
    val fields: List<String> = listOf("summary", "description", "status", "assignee", "priority", "issuetype", "updated", "timespent", "aggregatetimespent", "parent", "customfield_10001", "customfield_10020", "timetracking")
)

data class JiraSearchResponse(
    val startAt: Int?,
    val maxResults: Int?,
    val total: Int?,
    val issues: List<JiraIssue>
)

data class JiraIssue(
    val id: String,
    val key: String,
    val self: String,
    val fields: JiraIssueFields
) {
    override fun toString(): String = key
}

data class JiraIssueFields(
    val summary: String?,
    val description: JsonElement?,
    val status: JsonElement?,
    val assignee: JsonElement?,
    val priority: JsonElement?,
    val issuetype: JsonElement?,
    val timespent: Long?,
    val aggregatetimespent: Long?,
    val parent: JsonElement?,
    @SerializedName("customfield_10001") val epicLink: String?,
    @SerializedName("customfield_10020") val sprints: List<JiraSprint>?,
    val timetracking: JiraTimeTracking?
) {
    fun getSummaryText(): String = summary ?: "No Summary"

    fun getDescriptionText(): String {
        if (description == null || description.isJsonNull) return "(No description)"
        if (description.isJsonPrimitive) return description.asString
        
        val sb = StringBuilder()
        try {
            parseAdf(description, sb)
            val result = sb.toString().trim()
            return if (result.isEmpty()) "(No description content)" else result
        } catch (e: Exception) {
            return "Error parsing description: ${e.message}"
        }
    }

    private fun parseAdf(element: JsonElement, sb: StringBuilder, isInsideList: Boolean = false) {
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            val type = obj.get("type")?.asString

            if (type == "listItem") {
                sb.append("• ")
            }

            if (obj.has("text")) {
                sb.append(obj.get("text").asString)
            }

            if (obj.has("content")) {
                val content = obj.getAsJsonArray("content")
                for (child in content) {
                    parseAdf(child, sb, type == "bulletList" || type == "orderedList" || isInsideList)
                }
                
                // Add newlines for block elements
                when (type) {
                    "paragraph" -> {
                        if (!isInsideList) sb.append("\n\n") else sb.append("\n")
                    }
                    "heading", "listItem" -> sb.append("\n")
                    "bulletList", "orderedList" -> sb.append("\n")
                }
            }
        }
    }

    fun getStatusName(): String = getStringFromObject(status, "name", "Unknown") ?: "Unknown"
    
    fun getStatusCategoryKey(): String? = getNestedStringFromObject(status, "statusCategory", "key")

    fun getAssigneeName(): String = getStringFromObject(assignee, "displayName", "Unassigned") ?: "Unassigned"
    
    fun getAssigneeId(): String? = getStringFromObject(assignee, "accountId", null)
    
    fun getAssigneeAvatarUrl(): String? = getNestedStringFromObject(assignee, "avatarUrls", "24x24")

    fun getPriorityName(): String = getStringFromObject(priority, "name", "None") ?: "None"
    
    fun getPriorityIconUrl(): String? = getStringFromObject(priority, "iconUrl", null)

    fun getIssueTypeName(): String = getStringFromObject(issuetype, "name", "Task") ?: "Task"
    
    fun getIssueTypeIconUrl(): String? = getStringFromObject(issuetype, "iconUrl", null)

    fun getEpicName(): String? {
        if (parent != null && parent.isJsonObject) {
            val pFields = parent.asJsonObject.getAsJsonObject("fields")
            if (pFields != null && pFields.has("summary")) {
                return pFields.get("summary").asString
            }
        }
        return epicLink
    }

    fun getSprintNames(): String {
        return sprints?.joinToString(", ") { it.name } ?: "None"
    }

    private fun getStringFromObject(element: JsonElement?, key: String, default: String?): String? {
        if (element == null || element.isJsonNull) return default
        if (element.isJsonObject && element.asJsonObject.has(key)) {
            val value = element.asJsonObject.get(key)
            if (value.isJsonPrimitive) return value.asString
        }
        return default
    }

    private fun getNestedStringFromObject(element: JsonElement?, outerKey: String, innerKey: String): String? {
        if (element == null || element.isJsonNull) return null
        if (element.isJsonObject && element.asJsonObject.has(outerKey)) {
            val outer = element.asJsonObject.get(outerKey)
            if (outer.isJsonObject && outer.asJsonObject.has(innerKey)) {
                val inner = outer.asJsonObject.get(innerKey)
                if (inner.isJsonPrimitive) return inner.asString
            }
        }
        return null
    }

    fun getOriginalEstimateText(): String {
        return timetracking?.originalEstimate ?: "0h"
    }
}

data class JiraTimeTracking(
    val originalEstimate: String?,
    val remainingEstimate: String?,
    val timeSpent: String?
)

// For updating issue fields (like original estimate)
data class JiraUpdateIssueRequest(
    val fields: Map<String, Any>
)

data class JiraAssigneeRequest(
    val accountId: String?
)

// For adding comments (v3 API requires ADF body)
data class JiraCommentRequest(
    val body: JiraAdfDocument
) {
    companion object {
        fun fromPlainText(text: String): JiraCommentRequest {
            return JiraCommentRequest(
                body = JiraAdfDocument(
                    type = "doc",
                    version = 1,
                    content = listOf(
                        JiraAdfBlock(
                            type = "paragraph",
                            content = listOf(
                                JiraAdfInline(type = "text", text = text)
                            )
                        )
                    )
                )
            )
        }
    }
}

data class JiraAdfDocument(
    val type: String,
    val version: Int,
    val content: List<JiraAdfBlock>
)

data class JiraAdfBlock(
    val type: String,
    val content: List<JiraAdfInline>
)

data class JiraAdfInline(
    val type: String,
    val text: String
)

// For transitions
data class JiraTransitionRequest(
    val transition: JiraTransitionId
)

data class JiraTransitionId(
    val id: String
)

data class JiraTransitionsResponse(
    val transitions: List<JiraTransition>
)

data class JiraTransition(
    val id: String,
    val name: String,
    val to: JiraStatus?
)

// For logging time
data class JiraWorklogRequest(
    val timeSpent: String, // e.g., "1d 2h 30m"
    val comment: String?
)

data class JiraUser(
    val accountId: String,
    val displayName: String,
    val emailAddress: String?,
    val active: Boolean
)

data class JiraProject(
    val id: String,
    val key: String,
    val name: String
)

data class JiraStatus(
    val id: String,
    val name: String,
    val statusCategory: JiraStatusCategory?
)

data class JiraStatusCategory(
    val key: String,
    val name: String,
    val colorName: String?
)

data class JiraIssueType(
    val id: String,
    val name: String,
    val iconUrl: String,
    val subtask: Boolean
)

data class JiraPriority(
    val id: String,
    val name: String,
    val iconUrl: String
)

data class JiraLabelResponse(
    val values: List<String>
)

data class JiraBoardResponse(
    val values: List<JiraBoard>
)

data class JiraBoard(
    val id: Int,
    val name: String,
    val type: String
)

data class JiraSprintResponse(
    val values: List<JiraSprint>
)

data class JiraSprint(
    val id: Int,
    val name: String,
    val state: String,
    val originBoardId: Int?
)

data class JiraBoardConfiguration(
    val id: Int,
    val name: String,
    val columnConfig: JiraColumnConfig
)

data class JiraColumnConfig(
    val columns: List<JiraColumn>
)

data class JiraColumn(
    val name: String,
    val statuses: List<JiraStatusId>
)

data class JiraStatusId(
    val id: String
)

data class JiraVersion(
    val id: String,
    val name: String,
    val released: Boolean?,
    val archived: Boolean?
)

// For GET /rest/api/3/project/{key}/statuses — statuses per issue type
data class JiraIssueTypeStatuses(
    val id: String,
    val name: String,
    val statuses: List<JiraStatus>
)

// For creating issues
data class JiraCreateIssueRequest(
    val fields: Map<String, Any>
)

data class JiraCreateIssueResponse(
    val id: String,
    val key: String,
    val self: String
)

data class JiraMoveIssuesRequest(
    val issues: List<String>
)
