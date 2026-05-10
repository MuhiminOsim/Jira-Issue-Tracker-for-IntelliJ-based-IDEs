package com.app.jiraplugin.api

import com.app.jiraplugin.models.*
import com.app.jiraplugin.settings.JiraCredentials
import com.app.jiraplugin.settings.JiraSettingsState
import com.intellij.openapi.diagnostic.Logger
import okhttp3.Credentials
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class JiraApiClient {
    private val logger = Logger.getInstance(JiraApiClient::class.java)

    // Caching
    private val statusCache = mutableListOf<JiraStatus>()
    private val issueTypeCache = mutableListOf<JiraIssueType>()
    private val priorityCache = mutableListOf<JiraPriority>()
    private val projectBoardsCache = mutableMapOf<String, List<JiraBoard>>()
    private val sprintCache = mutableMapOf<Int, List<JiraSprint>>()
    private val versionCache = mutableMapOf<String, List<JiraVersion>>()

    companion object {
        val instance: JiraApiClient by lazy { JiraApiClient() }
    }

    private fun createApi(): JiraApi? {
        val settings = JiraSettingsState.instance
        if (settings.jiraUrl.isBlank() || settings.email.isBlank()) return null

        val password = JiraCredentials.getApiToken() ?: return null

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val auth = Credentials.basic(settings.email, password)
                val request = chain.request().newBuilder()
                    .addHeader("Authorization", auth)
                    .addHeader("Accept", "application/json")
                    .build()
                chain.proceed(request)
            }.build()

        return Retrofit.Builder()
            .baseUrl(settings.jiraUrl.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(JiraApi::class.java)
    }

    fun searchIssues(jql: String, maxResults: Int = 100): Result<JiraSearchResponse> {
        return try {
            val api = createApi() ?: return Result.failure(IllegalStateException("Jira not configured"))
            val response = api.searchIssues(JiraSearchRequest(jql, maxResults)).execute()
            if (response.isSuccessful) {
                Result.success(response.body() ?: JiraSearchResponse(0, 0, 0, emptyList()))
            } else {
                Result.failure(RuntimeException("Search failed: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch issues", e)
            Result.failure(e)
        }
    }

    fun addComment(issueKey: String, comment: String): Result<Unit> {
        return try {
            val api = createApi() ?: return Result.failure(IllegalStateException("Jira not configured"))
            val response = api.addComment(issueKey, JiraCommentRequest.fromPlainText(comment)).execute()
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(RuntimeException("Failed to add comment: ${response.code()}"))
            }
        } catch (e: Exception) {
            logger.error("Failed to add comment to $issueKey", e)
            Result.failure(e)
        }
    }

    fun addWorklog(issueKey: String, timeSpent: String, comment: String?): Result<Unit> {
        return try {
            val api = createApi() ?: return Result.failure(IllegalStateException("Jira not configured"))
            val response = api.addWorklog(issueKey, JiraWorklogRequest(timeSpent, comment)).execute()
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(RuntimeException("Failed to log time: ${response.code()}"))
            }
        } catch (e: Exception) {
            logger.error("Failed to log time for $issueKey", e)
            Result.failure(e)
        }
    }

    fun getTransitions(issueKey: String): Result<List<JiraTransition>> {
        return try {
            val api = createApi() ?: return Result.failure(IllegalStateException("Jira not configured"))
            val response = api.getTransitions(issueKey).execute()
            if (response.isSuccessful) {
                Result.success(response.body()?.transitions ?: emptyList())
            } else {
                Result.failure(RuntimeException("Failed to fetch transitions"))
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch transitions for $issueKey", e)
            Result.failure(e)
        }
    }

    fun performTransition(issueKey: String, transitionId: String): Result<Unit> {
        return try {
            val api = createApi() ?: return Result.failure(IllegalStateException("Jira not configured"))
            val response = api.performTransition(issueKey, JiraTransitionRequest(JiraTransitionId(transitionId))).execute()
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(RuntimeException("Transition failed: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            logger.error("Failed to transition $issueKey", e)
            Result.failure(e)
        }
    }

    fun updateEstimate(issueKey: String, estimate: String): Result<Unit> {
        return try {
            val api = createApi() ?: return Result.failure(IllegalStateException("Jira not configured"))
            val fields = mapOf("timetracking" to mapOf("originalEstimate" to estimate))
            val response = api.updateIssue(issueKey, JiraUpdateIssueRequest(fields)).execute()
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("Failed to update estimate: ${response.code()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun assignIssue(issueKey: String, accountId: String?): Result<Unit> {
        return try {
            val api = createApi() ?: return Result.failure(IllegalStateException("Jira not configured"))
            val response = api.assignIssue(issueKey, JiraAssigneeRequest(accountId)).execute()
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("Failed to assign issue: ${response.code()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getAssignableUsers(projectKey: String): Result<List<JiraUser>> {
        return try {
            val api = createApi() ?: return Result.failure(IllegalStateException("Jira not configured"))
            val response = api.getAssignableUsers(projectKey).execute()
            if (response.isSuccessful) Result.success(response.body() ?: emptyList())
            else Result.failure(Exception("Failed to fetch assignable users"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getProjects(): Result<List<JiraProject>> {
        return try {
            val api = createApi() ?: return Result.failure(IllegalStateException("Jira not configured"))
            val response = api.getProjects().execute()
            if (response.isSuccessful) Result.success(response.body() ?: emptyList())
            else Result.failure(Exception("Failed to fetch projects"))
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
        projectBoardsCache[projectKey]?.let { return Result.success(it) }
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
        sprintCache[boardId]?.let { return Result.success(it) }
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

    fun getProjectVersions(projectKey: String): Result<List<JiraVersion>> {
        versionCache[projectKey]?.let { return Result.success(it) }
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
                // The endpoint returns statuses grouped by issue type — flatten and deduplicate
                val statuses = response.body()?.flatMap { it.statuses }?.distinctBy { it.name } ?: emptyList()
                Result.success(statuses)
            } else Result.failure(Exception("Failed to fetch project statuses: ${response.code()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun testConnection(url: String, email: String, token: String): Result<Unit> {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val auth = Credentials.basic(email, token)
                    val request = chain.request().newBuilder()
                        .addHeader("Authorization", auth)
                        .addHeader("Accept", "application/json")
                        .build()
                    chain.proceed(request)
                }.build()

            val tempApi = Retrofit.Builder()
                .baseUrl(url.trimEnd('/') + "/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(JiraApi::class.java)

            val response = tempApi.testConnection().execute()
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(RuntimeException("Connection failed: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun updateIssueDetails(issueKey: String, summary: String, description: String): Result<Unit> {
        return try {
            val api = createApi() ?: return Result.failure(IllegalStateException("Jira not configured"))
            
            val descriptionAdf = JiraAdfDocument(
                type = "doc",
                version = 1,
                content = listOf(
                    JiraAdfBlock(
                        type = "paragraph",
                        content = listOf(
                            JiraAdfInline(type = "text", text = description.takeIf { it.isNotEmpty() } ?: " ")
                        )
                    )
                )
            )

            val request = JiraUpdateIssueRequest(
                fields = mapOf(
                    "summary" to summary,
                    "description" to descriptionAdf
                )
            )
            
            val response = api.updateIssue(issueKey, request).execute()
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                val errorMsg = response.errorBody()?.string() ?: response.message()
                Result.failure(RuntimeException("Failed to update issue: ${response.code()} $errorMsg"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun createIssue(
        projectKey: String, 
        issueTypeName: String, 
        summary: String, 
        description: String,
        priorityId: String?,
        assigneeAccountId: String?,
        originalEstimate: String?
    ): Result<String> {
        return try {
            val api = createApi() ?: return Result.failure(IllegalStateException("Jira not configured"))
            
            val descriptionAdf = JiraAdfDocument(
                type = "doc",
                version = 1,
                content = listOf(
                    JiraAdfBlock(
                        type = "paragraph",
                        content = listOf(
                            JiraAdfInline(type = "text", text = description.takeIf { it.isNotEmpty() } ?: " ")
                        )
                    )
                )
            )

            val fields = mutableMapOf<String, Any>(
                "project" to mapOf("key" to projectKey),
                "summary" to summary,
                "description" to descriptionAdf,
                "issuetype" to mapOf("name" to issueTypeName)
            )
            
            if (priorityId != null && priorityId.isNotBlank()) {
                fields["priority"] = mapOf("id" to priorityId)
            }
            if (assigneeAccountId != null && assigneeAccountId.isNotBlank()) {
                fields["assignee"] = mapOf("accountId" to assigneeAccountId)
            }
            if (originalEstimate != null && originalEstimate.isNotBlank()) {
                fields["timetracking"] = mapOf("originalEstimate" to originalEstimate)
            }

            val request = JiraCreateIssueRequest(fields)
            
            val response = api.createIssue(request).execute()
            if (response.isSuccessful) {
                Result.success(response.body()?.key ?: "")
            } else {
                val errorMsg = response.errorBody()?.string() ?: response.message()
                Result.failure(RuntimeException("Failed to create issue: ${response.code()} $errorMsg"))
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
}
