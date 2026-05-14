package com.app.jiraplugin.api

import com.app.jiraplugin.models.*
import com.app.jiraplugin.settings.JiraCredentials
import com.app.jiraplugin.settings.JiraSettingsState
import com.intellij.openapi.diagnostic.Logger
import okhttp3.Credentials
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class JiraApiClient {
    private val logger = Logger.getInstance(JiraApiClient::class.java)

    // Caching
    private val projectCache = mutableListOf<JiraProject>()
    private val statusCache = mutableListOf<JiraStatus>()
    private val issueTypeCache = mutableListOf<JiraIssueType>()
    private val priorityCache = mutableListOf<JiraPriority>()
    private val assignableUsersCache = ConcurrentHashMap<String, List<JiraUser>>()
    private val projectBoardsCache = ConcurrentHashMap<String, List<JiraBoard>>()
    private val sprintCache = ConcurrentHashMap<Int, List<JiraSprint>>()
    private val versionCache = ConcurrentHashMap<String, List<JiraVersion>>()
    private val boardConfigCache = ConcurrentHashMap<Int, JiraBoardConfiguration>()
    private val epicCache = ConcurrentHashMap<String, List<JiraIssue>>()

    companion object {
        val instance by lazy { JiraApiClient() }
    }

    private fun createApi(): JiraApi? {
        val settings = JiraSettingsState.instance
        val token = JiraCredentials.getApiToken()
        if (settings.jiraUrl.isBlank() || settings.email.isBlank() || token.isNullOrBlank()) return null

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Authorization", Credentials.basic(settings.email, token))
                    .addHeader("Accept", "application/json")
                    .addHeader("Content-Type", "application/json")
                    .build()
                chain.proceed(request)
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(settings.jiraUrl.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(JiraApi::class.java)
    }

    fun searchIssues(jql: String, maxResults: Int = 1000): Result<JiraSearchResponse> {
        return try {
            val api = createApi() ?: return Result.failure(IllegalStateException("Jira not configured"))
            
            val allIssues = mutableListOf<JiraIssue>()
            val seenKeys = mutableSetOf<String>()
            var currentToken: String? = null
            var iterations = 0
            
            do {
                val request = if (currentToken == null) {
                    JiraSearchRequest(jql = jql, maxResults = 100)
                } else {
                    JiraSearchRequest(jql = jql, maxResults = 100, nextPageToken = currentToken)
                }
                
                val response = api.searchIssues(request).execute()
                
                if (response.isSuccessful) {
                    val body = response.body() ?: break
                    val newIssues = body.issues
                    if (newIssues.isEmpty()) break
                    
                    var addedAny = false
                    newIssues.forEach { 
                        if (seenKeys.add(it.key)) {
                            allIssues.add(it)
                            addedAny = true
                        }
                    }
                    
                    if (!addedAny) break // Avoid infinite loops if we keep getting same issues
                    
                    val nextToken = body.nextPageToken
                    if (nextToken == null || nextToken == currentToken) break
                    
                    currentToken = nextToken
                    iterations++
                } else {
                    val errorBody = response.errorBody()?.string()
                    return Result.failure(Exception("Failed to fetch issues: ${response.code()} - $errorBody"))
                }
            } while (allIssues.size < maxResults && iterations < 10)
            
            Result.success(JiraSearchResponse(issues = allIssues))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun addComment(issueKey: String, comment: String): Result<Unit> {
        return try {
            val api = createApi() ?: return Result.failure(IllegalStateException("Jira not configured"))
            val response = api.addComment(issueKey, JiraCommentRequest.fromPlainText(comment)).execute()
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("Failed to add comment: ${response.code()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun addWorklog(issueKey: String, timeSpent: String, comment: String? = null): Result<Unit> {
        return try {
            val api = createApi() ?: return Result.failure(IllegalStateException("Jira not configured"))
            val response = api.addWorklog(issueKey, JiraWorklogRequest(timeSpent, comment)).execute()
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("Failed to add worklog: ${response.code()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getTransitions(issueKey: String): Result<List<JiraTransition>> {
        return try {
            val api = createApi() ?: return Result.failure(IllegalStateException("Jira not configured"))
            val response = api.getTransitions(issueKey).execute()
            if (response.isSuccessful) Result.success(response.body()?.transitions ?: emptyList())
            else Result.failure(Exception("Failed to fetch transitions"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun performTransition(issueKey: String, transitionId: String): Result<Unit> {
        return try {
            val api = createApi() ?: return Result.failure(IllegalStateException("Jira not configured"))
            val response = api.performTransition(issueKey, JiraTransitionRequest(JiraTransitionId(transitionId))).execute()
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("Failed to perform transition"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun updateEstimate(issueKey: String, estimate: String): Result<Unit> {
        return try {
            val api = createApi() ?: return Result.failure(IllegalStateException("Jira not configured"))
            val fields = mapOf("timetracking" to mapOf("originalEstimate" to estimate))
            val response = api.updateIssue(issueKey, JiraUpdateIssueRequest(fields)).execute()
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("Failed to update estimate"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun assignIssue(issueKey: String, accountId: String?): Result<Unit> {
        return try {
            val api = createApi() ?: return Result.failure(IllegalStateException("Jira not configured"))
            val response = api.assignIssue(issueKey, JiraAssigneeRequest(accountId)).execute()
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("Failed to assign issue"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getAssignableUsers(projectKey: String): Result<List<JiraUser>> {
        val cached = assignableUsersCache[projectKey]
        if (cached != null) return Result.success(cached)
        return try {
            val api = createApi() ?: return Result.failure(IllegalStateException("Jira not configured"))
            val response = api.getAssignableUsers(projectKey).execute()
            if (response.isSuccessful) {
                val users = response.body() ?: emptyList()
                assignableUsersCache[projectKey] = users
                Result.success(users)
            } else Result.failure(Exception("Failed to fetch assignable users"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getProjects(): Result<List<JiraProject>> {
        if (projectCache.isNotEmpty()) return Result.success(projectCache)
        return try {
            val api = createApi() ?: return Result.failure(IllegalStateException("Jira not configured"))
            val response = api.getProjects().execute()
            if (response.isSuccessful) {
                val projects = response.body() ?: emptyList()
                projectCache.clear()
                projectCache.addAll(projects)
                Result.success(projects)
            } else Result.failure(Exception("Failed to fetch projects"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getStatuses(): Result<List<JiraStatus>> {
        if (statusCache.isNotEmpty()) return Result.success(statusCache)
        return try {
            val api = createApi() ?: return Result.failure(IllegalStateException("Jira not configured"))
            val response = api.getStatuses().execute()
            if (response.isSuccessful) {
                val statuses = response.body() ?: emptyList()
                statusCache.clear()
                statusCache.addAll(statuses)
                Result.success(statuses)
            } else Result.failure(Exception("Failed to fetch statuses"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getIssueTypes(): Result<List<JiraIssueType>> {
        if (issueTypeCache.isNotEmpty()) return Result.success(issueTypeCache)
        return try {
            val api = createApi() ?: return Result.failure(IllegalStateException("Jira not configured"))
            val response = api.getIssueTypes().execute()
            if (response.isSuccessful) {
                val types = response.body() ?: emptyList()
                issueTypeCache.clear()
                issueTypeCache.addAll(types)
                Result.success(types)
            } else Result.failure(Exception("Failed to fetch issue types"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getPriorities(): Result<List<JiraPriority>> {
        if (priorityCache.isNotEmpty()) return Result.success(priorityCache)
        return try {
            val api = createApi() ?: return Result.failure(IllegalStateException("Jira not configured"))
            val response = api.getPriorities().execute()
            if (response.isSuccessful) {
                val priorities = response.body() ?: emptyList()
                priorityCache.clear()
                priorityCache.addAll(priorities)
                Result.success(priorities)
            } else Result.failure(Exception("Failed to fetch priorities"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getLabels(query: String? = null): Result<List<String>> {
        return try {
            val api = createApi() ?: return Result.failure(IllegalStateException("Jira not configured"))
            val response = api.getLabels(query).execute()
            if (response.isSuccessful) Result.success(response.body()?.values ?: emptyList())
            else Result.failure(Exception("Failed to fetch labels"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getBoards(projectKey: String): Result<List<JiraBoard>> {
        val cached = projectBoardsCache[projectKey]
        if (cached != null) return Result.success(cached)
        return try {
            val api = createApi() ?: return Result.failure(IllegalStateException("Jira not configured"))
            val response = api.getBoards(projectKey).execute()
            if (response.isSuccessful) {
                val boards = response.body()?.values ?: emptyList()
                projectBoardsCache[projectKey] = boards
                Result.success(boards)
            } else Result.failure(Exception("Failed to fetch boards"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getSprints(boardId: Int): Result<List<JiraSprint>> {
        val cached = sprintCache[boardId]
        if (cached != null) return Result.success(cached)
        return try {
            val api = createApi() ?: return Result.failure(IllegalStateException("Jira not configured"))
            val response = api.getSprints(boardId).execute()
            if (response.isSuccessful) {
                val sprints = response.body()?.values ?: emptyList()
                sprintCache[boardId] = sprints
                Result.success(sprints)
            } else Result.failure(Exception("Failed to fetch sprints"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getBoardConfiguration(boardId: Int): Result<JiraBoardConfiguration> {
        val cached = boardConfigCache[boardId]
        if (cached != null) return Result.success(cached)
        return try {
            val api = createApi() ?: return Result.failure(IllegalStateException("Jira not configured"))
            val response = api.getBoardConfiguration(boardId).execute()
            if (response.isSuccessful) {
                val config = response.body() ?: throw Exception("Empty board configuration")
                boardConfigCache[boardId] = config
                Result.success(config)
            } else Result.failure(Exception("Failed to fetch board configuration: ${response.code()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getProjectVersions(projectKey: String): Result<List<JiraVersion>> {
        val cached = versionCache[projectKey]
        if (cached != null) return Result.success(cached)
        return try {
            val api = createApi() ?: return Result.failure(IllegalStateException("Jira not configured"))
            val response = api.getProjectVersions(projectKey).execute()
            if (response.isSuccessful) {
                val versions = response.body() ?: emptyList()
                versionCache[projectKey] = versions
                Result.success(versions)
            } else Result.failure(Exception("Failed to fetch versions"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getProjectStatuses(projectKey: String): Result<List<JiraStatus>> {
        return try {
            val api = createApi() ?: return Result.failure(IllegalStateException("Jira not configured"))
            val response = api.getProjectStatuses(projectKey).execute()
            if (response.isSuccessful) {
                val typeStatuses = response.body() ?: emptyList()
                val allStatuses = typeStatuses.flatMap { it.statuses }.distinctBy { it.id }
                Result.success(allStatuses)
            } else Result.failure(Exception("Failed to fetch project statuses"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun testConnection(url: String, email: String, token: String): Result<Unit> {
        return try {
            val client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .addHeader("Authorization", Credentials.basic(email, token))
                        .build()
                    chain.proceed(request)
                }
                .connectTimeout(10, TimeUnit.SECONDS)
                .build()

            val api = Retrofit.Builder()
                .baseUrl(url.trimEnd('/') + "/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(JiraApi::class.java)

            val response = api.testConnection().execute()
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("Connection failed: ${response.code()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun updateIssueDetails(issueKey: String, summary: String, description: String): Result<Unit> {
        return try {
            val api = createApi() ?: return Result.failure(IllegalStateException("Jira not configured"))
            
            // Description in Jira Cloud usually needs ADF (Atlassian Document Format)
            // But some APIs accept plain text. Let's try sending it as ADF if possible or just plain text update.
            // For simplicity, many implementations use a map.
            
            val fields = mutableMapOf<String, Any>(
                "summary" to summary
            )
            
            if (description.isNotEmpty()) {
                fields["description"] = JiraAdfDocument.fromPlainText(description)
            }
            
            val response = api.updateIssue(issueKey, JiraUpdateIssueRequest(fields)).execute()
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("Failed to update issue: ${response.code()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun createIssue(
        projectKey: String,
        issueType: String,
        summary: String,
        description: String,
        priorityId: String?,
        assigneeId: String?,
        estimate: String?
    ): Result<String> {
        return try {
            val api = createApi() ?: return Result.failure(IllegalStateException("Jira not configured"))
            
            val fields = mutableMapOf<String, Any>(
                "project" to mapOf("key" to projectKey),
                "issuetype" to mapOf("name" to issueType),
                "summary" to summary
            )
            
            if (description.isNotBlank()) {
                // Simplified ADF for description
                fields["description"] = mapOf(
                    "type" to "doc",
                    "version" to 1,
                    "content" to listOf(
                        mapOf(
                            "type" to "paragraph",
                            "content" to listOf(
                                mapOf("type" to "text", "text" to description)
                            )
                        )
                    )
                )
            }
            
            if (priorityId != null) {
                fields["priority"] = mapOf("id" to priorityId)
            }
            
            if (assigneeId != null) {
                fields["assignee"] = mapOf("accountId" to assigneeId)
            }
            
            if (!estimate.isNullOrBlank()) {
                fields["timetracking"] = mapOf("originalEstimate" to estimate)
            }
            
            val response = api.createIssue(JiraCreateIssueRequest(fields)).execute()
            if (response.isSuccessful) {
                Result.success(response.body()?.key ?: "")
            } else {
                val errorBody = response.errorBody()?.string()
                Result.failure(Exception("Failed to create issue: ${response.code()} - $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun moveIssueToSprint(issueKey: String, sprintId: Int): Result<Unit> {
        return try {
            val api = createApi() ?: return Result.failure(IllegalStateException("Jira not configured"))
            val response = api.moveIssuesToSprint(sprintId, JiraMoveIssuesRequest(listOf(issueKey))).execute()
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("Failed to move issue to sprint: ${response.code()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getEpics(projectKey: String): Result<List<JiraIssue>> {
        val cached = epicCache[projectKey]
        if (cached != null) return Result.success(cached)
        return searchIssues("project = \"$projectKey\" AND issuetype = Epic ORDER BY updated DESC", 50).onSuccess { 
            epicCache[projectKey] = it.issues
        }.map { it.issues }
    }

    fun clearCache() {
        projectCache.clear()
        statusCache.clear()
        issueTypeCache.clear()
        priorityCache.clear()
        assignableUsersCache.clear()
        projectBoardsCache.clear()
        sprintCache.clear()
        versionCache.clear()
        boardConfigCache.clear()
        epicCache.clear()
    }
}
