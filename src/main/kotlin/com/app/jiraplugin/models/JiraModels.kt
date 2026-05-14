package com.app.jiraplugin.models

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

// Search request body for rest/api/3/search/jql
data class JiraSearchRequest(
    val jql: String,
    val maxResults: Int = 50,
    val nextPageToken: String? = null,
    val fields: List<String> = listOf("summary", "description", "status", "assignee", "priority", "issuetype", "updated", "parent", "customfield_10020", "customfield_10001", "customfield_10007", "customfield_11000", "sprint")
)

data class JiraSearchResponse(
    @SerializedName("issues") val issues: List<JiraIssue> = emptyList(),
    @SerializedName("nextPageToken") val nextPageToken: String? = null,
    @SerializedName("total") val total: Int? = null,
    @SerializedName("startAt") val startAt: Int? = null,
    @SerializedName("maxResults") val maxResults: Int? = null
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
    @SerializedName("customfield_10020", alternate = ["sprint", "customfield_10007", "customfield_11000"]) val sprintField: JsonElement?,
    val timetracking: JiraTimeTracking?
) {
    companion object {
        private val gson = com.google.gson.Gson()
    }

    val sprints: List<JiraSprint>
        get() = parseSprints(sprintField)

    private fun parseSprints(element: JsonElement?): List<JiraSprint> {
        if (element == null || element.isJsonNull) return emptyList()
        
        val list = mutableListOf<JiraSprint>()
        
        fun parseString(s: String): JiraSprint? {
            // Format: com.atlassian.greenhopper.service.sprint.Sprint@...[id=1,rapidViewId=1,state=ACTIVE,name=Sprint 1,...]
            try {
                val content = s.substringAfter("[", "").substringBeforeLast("]", "")
                if (content.isEmpty()) return null
                
                // Use a smarter split that doesn't break on commas in names
                val pairs = content.split(Regex(",(?=[a-zA-Z]+=)")).associate { 
                    val parts = it.split("=", limit = 2)
                    if (parts.size == 2) parts[0].trim() to parts[1].trim()
                    else "" to ""
                }
                
                val id = pairs["id"]?.toIntOrNull() ?: return null
                val name = pairs["name"] ?: "Unnamed Sprint"
                val state = pairs["state"] ?: "UNKNOWN"
                
                return JiraSprint(id, name, state, pairs["originBoardId"]?.toIntOrNull())
            } catch (e: Exception) {
                return null
            }
        }

        if (element.isJsonArray) {
            element.asJsonArray.forEach { 
                if (it.isJsonObject) {
                    try {
                        val sprint = gson.fromJson(it, JiraSprint::class.java)
                        if (sprint != null) list.add(sprint)
                    } catch (e: Exception) {}
                } else if (it.isJsonPrimitive && it.asJsonPrimitive.isString) {
                    parseString(it.asString)?.let { s -> list.add(s) }
                }
            }
        } else if (element.isJsonObject) {
            try {
                val sprint = gson.fromJson(element, JiraSprint::class.java)
                if (sprint != null) list.add(sprint)
            } catch (e: Exception) {}
        } else if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            parseString(element.asString)?.let { s -> list.add(s) }
        }
        
        return list
    }

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
        return if (sprints.isEmpty()) "None" else sprints.joinToString(", ") { it.name }
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
) {
    companion object {
        fun fromPlainText(text: String): JiraAdfDocument {
            return JiraAdfDocument(
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
        }
    }
}

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
