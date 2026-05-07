package com.app.jiraplugin.api

import com.app.jiraplugin.models.*
import retrofit2.Call
import retrofit2.http.*

interface JiraApi {

    @POST("rest/api/3/search/jql")
    fun searchIssues(@Body request: JiraSearchRequest): Call<JiraSearchResponse>

    @PUT("rest/api/3/issue/{issueIdOrKey}")
    fun updateIssue(
        @Path("issueIdOrKey") issueIdOrKey: String,
        @Body request: JiraUpdateIssueRequest
    ): Call<Void>

    @PUT("rest/api/3/issue/{issueIdOrKey}/assignee")
    fun assignIssue(
        @Path("issueIdOrKey") issueIdOrKey: String,
        @Body request: JiraAssigneeRequest
    ): Call<Void>

    @POST("rest/api/3/issue/{issueIdOrKey}/comment")
    fun addComment(
        @Path("issueIdOrKey") issueIdOrKey: String,
        @Body request: JiraCommentRequest
    ): Call<Void>

    @GET("rest/api/3/issue/{issueIdOrKey}/transitions")
    fun getTransitions(@Path("issueIdOrKey") issueIdOrKey: String): Call<JiraTransitionsResponse>

    @POST("rest/api/3/issue/{issueIdOrKey}/transitions")
    fun performTransition(
        @Path("issueIdOrKey") issueIdOrKey: String,
        @Body request: JiraTransitionRequest
    ): Call<Void>

    @POST("rest/api/3/issue/{issueIdOrKey}/worklog")
    fun addWorklog(
        @Path("issueIdOrKey") issueIdOrKey: String,
        @Body request: JiraWorklogRequest
    ): Call<Void>

    @GET("rest/api/3/user/assignable/search")
    fun getAssignableUsers(
        @Query("project") projectKey: String,
        @Query("maxResults") maxResults: Int = 50
    ): Call<List<JiraUser>>

    @GET("rest/api/3/project")
    fun getProjects(): Call<List<JiraProject>>

    @GET("rest/api/3/status")
    fun getStatuses(): Call<List<JiraStatus>>

    @GET("rest/api/3/issuetype")
    fun getIssueTypes(): Call<List<JiraIssueType>>

    @GET("rest/api/3/priority")
    fun getPriorities(): Call<List<JiraPriority>>

    @GET("rest/api/3/label")
    fun getLabels(@Query("query") query: String? = null): Call<JiraLabelResponse>

    @GET("rest/agile/1.0/board")
    fun getBoards(@Query("projectKeyOrId") projectKey: String? = null): Call<JiraBoardResponse>

    @GET("rest/agile/1.0/board/{boardId}/sprint")
    fun getSprints(@Path("boardId") boardId: Int): Call<JiraSprintResponse>

    @GET("rest/api/3/project/{projectKeyOrId}/versions")
    fun getProjectVersions(@Path("projectKeyOrId") projectKey: String): Call<List<JiraVersion>>

    @GET("rest/api/3/project/{projectKeyOrId}/statuses")
    fun getProjectStatuses(@Path("projectKeyOrId") projectKey: String): Call<List<JiraIssueTypeStatuses>>
}
