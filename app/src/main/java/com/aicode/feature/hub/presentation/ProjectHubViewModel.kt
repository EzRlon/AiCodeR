package com.aicode.feature.hub.presentation

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.core.ui.floating.FloatingWindowGeometry
import com.aicode.core.util.FileLogger
import com.aicode.feature.credentials.domain.repository.CredentialRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * 项目中心浮窗的 VM：直连 GitHub REST API（手机流量，零模型 token）。
 * 凭据复用 git 凭据仓库（host=github.com 的 token）；仓库全名由用户一次性填写后持久化。
 * 第一批只做总览 + 远端构建触发；CI 轮询/发行产物/事务在第二批接入。
 */
@HiltViewModel
class ProjectHubViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val credentialRepository: CredentialRepository
) : ViewModel() {

    data class RepoInfo(
        val fullName: String,
        val description: String,
        val stars: Int,
        val openIssues: Int,
        val defaultBranch: String
    )

    data class CommitItem(
        val sha: String,
        val message: String,
        val author: String
    )

    data class UiState(
        val repo: String = "",
        val loading: Boolean = false,
        val info: RepoInfo? = null,
        val commits: List<CommitItem> = emptyList(),
        val readmeSnippet: String = "",
        val message: String = ""
    )

    private companion object {
        const val TAG = "ProjectHub"
        const val PREFS = "project_hub"
        const val KEY_REPO = "repo"
        const val KEY_X = "geom_x"
        const val KEY_Y = "geom_y"
        const val KEY_W = "geom_w"
        const val KEY_H = "geom_h"
        const val API = "https://api.github.com"
        const val RELEASE_WORKFLOW = "android-release.yml"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(UiState(repo = prefs.getString(KEY_REPO, "").orEmpty()))
    val state: StateFlow<UiState> = _state

    var geometry: FloatingWindowGeometry by mutableStateOf(loadGeometry())
        private set

    fun updateGeometry(geometry: FloatingWindowGeometry) {
        this.geometry = geometry
        prefs.edit()
            .putFloat(KEY_X, geometry.xDp)
            .putFloat(KEY_Y, geometry.yDp)
            .putFloat(KEY_W, geometry.widthDp)
            .putFloat(KEY_H, geometry.heightDp)
            .apply()
    }

    fun setRepo(repo: String) {
        val normalized = repo.trim().removePrefix("https://github.com/").trim('/')
        prefs.edit().putString(KEY_REPO, normalized).apply()
        _state.update { it.copy(repo = normalized) }
        if (normalized.isNotBlank()) refresh()
    }

    /** 拉取仓库总览：基础信息 + 最近提交 + README 摘要。 */
    fun refresh() {
        val repo = _state.value.repo
        if (repo.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, message = "") }
            val token = credentialRepository.findForHost("github.com")?.token
            if (token.isNullOrBlank()) {
                _state.update { it.copy(loading = false, message = "未找到 GitHub 凭据，请先在设置里登录 GitHub") }
                return@launch
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    val info = JSONObject(get("$API/repos/$repo", token))
                    val issues = JSONObject(get("$API/repos/$repo", token)).optInt("open_issues_count", 0)
                    val commitsArr = org.json.JSONArray(get("$API/repos/$repo/commits?per_page=8", token))
                    val commits = (0 until commitsArr.length()).mapNotNull { i ->
                        commitsArr.optJSONObject(i)?.let { c ->
                            CommitItem(
                                sha = c.optString("sha").take(7),
                                message = c.optJSONObject("commit")?.optString("message")
                                    ?.lineSequence()?.firstOrNull().orEmpty(),
                                author = c.optJSONObject("commit")?.optJSONObject("author")
                                    ?.optString("name").orEmpty()
                            )
                        }
                    }
                    val readmeRaw = runCatching {
                        JSONObject(get("$API/repos/$repo/readme", token))
                            .optString("content").replace("\n", "")
                    }.getOrDefault("")
                    val readme = if (readmeRaw.isBlank()) "" else {
                        runCatching {
                            String(android.util.Base64.decode(readmeRaw, android.util.Base64.DEFAULT))
                                .take(1600)
                        }.getOrDefault("")
                    }
                    Triple(
                        RepoInfo(
                            fullName = info.optString("full_name", repo),
                            description = info.optString("description"),
                            stars = info.optInt("stargazers_count", 0),
                            openIssues = issues,
                            defaultBranch = info.optString("default_branch", "main")
                        ),
                        commits,
                        readme
                    )
                }
            }.onSuccess { (info, commits, readme) ->
                _state.update {
                    it.copy(loading = false, info = info, commits = commits, readmeSnippet = readme)
                }
            }.onFailure { e ->
                FileLogger.w(TAG, "拉取仓库总览失败", e)
                _state.update { it.copy(loading = false, message = "拉取失败：${e.message.orEmpty().take(80)}") }
            }
        }
    }

    /** 远端构建：触发 android-release.yml（workflow_dispatch，ref 默认分支）。 */
    fun dispatchBuild() {
        val repo = _state.value.repo
        if (repo.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, message = "") }
            val token = credentialRepository.findForHost("github.com")?.token
            if (token.isNullOrBlank()) {
                _state.update { it.copy(loading = false, message = "未找到 GitHub 凭据") }
                return@launch
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    val ref = _state.value.info?.defaultBranch ?: "main"
                    val body = JSONObject().put("ref", ref).toString()
                        .toRequestBody("application/json".toMediaType())
                    val request = Request.Builder()
                        .url("$API/repos/$repo/actions/workflows/$RELEASE_WORKFLOW/dispatches")
                        .header("Authorization", "Bearer $token")
                        .header("Accept", "application/vnd.github+json")
                        .post(body)
                        .build()
                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw IllegalStateException("HTTP ${resp.code} ${resp.message}")
                        }
                    }
                }
            }.onSuccess {
                _state.update { it.copy(loading = false, message = "已触发远端构建（Android Release），稍后可在 CI 页查看进度") }
            }.onFailure { e ->
                FileLogger.w(TAG, "触发远端构建失败", e)
                _state.update { it.copy(loading = false, message = "触发失败：${e.message.orEmpty().take(80)}") }
            }
        }
    }

    private fun get(url: String, token: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .build()
        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
    }

    private fun loadGeometry(): FloatingWindowGeometry {
        val w = prefs.getFloat(KEY_W, 0f)
        val h = prefs.getFloat(KEY_H, 0f)
        return if (w > 0f && h > 0f) {
            FloatingWindowGeometry(
                xDp = prefs.getFloat(KEY_X, 16f),
                yDp = prefs.getFloat(KEY_Y, 96f),
                widthDp = w,
                heightDp = h
            )
        } else {
            FloatingWindowGeometry(xDp = 16f, yDp = 96f, widthDp = 360f, heightDp = 560f)
        }
    }
}
