package com.aicode.feature.hub.presentation

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * 项目中心浮窗的 VM：直连 GitHub REST API（手机流量，零模型 token）。
 * 凭据复用 git 凭据仓库（host=github.com 的 token）；仓库全名由用户一次性填写后持久化。
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

    data class CommitItem(val sha: String, val message: String, val author: String)

    data class RunItem(
        val id: Long,
        val title: String,
        val branch: String,
        val status: String,
        val conclusion: String?,
        val createdAt: Long
    ) {
        val running: Boolean get() = status != "completed"
    }

    data class RunJob(val name: String, val status: String, val conclusion: String?)

    data class AssetItem(val id: Long, val name: String, val sizeBytes: Long, val url: String)

    data class ReleaseItem(
        val tag: String,
        val name: String,
        val prerelease: Boolean,
        val createdAt: Long,
        val assets: List<AssetItem>
    )

    data class OpsItem(
        val number: Int,
        val title: String,
        val isPull: Boolean,
        val user: String,
        val updatedAt: Long,
        val htmlUrl: String
    )

    data class UiState(
        val repo: String = "",
        val loading: Boolean = false,
        val info: RepoInfo? = null,
        val commits: List<CommitItem> = emptyList(),
        val readmeSnippet: String = "",
        val runs: List<RunItem> = emptyList(),
        val runsLoaded: Boolean = false,
        val releases: List<ReleaseItem> = emptyList(),
        val releasesLoaded: Boolean = false,
        val ops: List<OpsItem> = emptyList(),
        val opsLoaded: Boolean = false,
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
        if (normalized.isNotBlank()) {
            refresh()
            refreshRuns()
            refreshReleases()
            refreshOps()
        }
    }

    fun refresh() {
        val repo = _state.value.repo
        if (repo.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, message = "") }
            val token = token() ?: run {
                _state.update { it.copy(loading = false, message = "未找到 GitHub 凭据，请先在设置里登录 GitHub") }
                return@launch
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    val info = JSONObject(get("$API/repos/$repo", token))
                    val commitsArr = JSONArray(get("$API/repos/$repo/commits?per_page=8", token))
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
                    val readme = if (readmeRaw.isBlank()) "" else runCatching {
                        String(android.util.Base64.decode(readmeRaw, android.util.Base64.DEFAULT)).take(1600)
                    }.getOrDefault("")
                    Triple(
                        RepoInfo(
                            fullName = info.optString("full_name", repo),
                            description = info.optString("description"),
                            stars = info.optInt("stargazers_count", 0),
                            openIssues = info.optInt("open_issues_count", 0),
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

    /** CI 页：最近 workflow runs（轮询由 UI 按可见性驱动）。 */
    fun refreshRuns(silent: Boolean = false) {
        val repo = _state.value.repo
        if (repo.isBlank()) return
        viewModelScope.launch {
            if (!silent) _state.update { it.copy(loading = true) }
            val token = token() ?: return@launch
            runCatching {
                withContext(Dispatchers.IO) {
                    val arr = JSONArray(get("$API/repos/$repo/actions/runs?per_page=12", token))
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.let { r ->
                            RunItem(
                                id = r.optLong("id"),
                                title = r.optString("display_title")
                                    .ifBlank { r.optString("name") },
                                branch = r.optString("head_branch"),
                                status = r.optString("status"),
                                conclusion = r.optString("conclusion").takeIf { it.isNotBlank() },
                                createdAt = parseIsoMs(r.optString("created_at"))
                            )
                        }
                    }
                }
            }.onSuccess { runs ->
                _state.update { it.copy(loading = false, runs = runs, runsLoaded = true) }
            }.onFailure { e ->
                FileLogger.w(TAG, "拉取 CI 状态失败", e)
                _state.update { it.copy(loading = false, runsLoaded = true, message = "CI 拉取失败：${e.message.orEmpty().take(60)}") }
            }
        }
    }

    fun cancelRun(id: Long) = postRunAction(id, "cancel", "已请求取消")
    fun rerunFailed(id: Long) = postRunAction(id, "rerun-failed-jobs", "已请求重跑失败任务")

    private fun postRunAction(id: Long, action: String, okMessage: String) {
        val repo = _state.value.repo
        if (repo.isBlank()) return
        viewModelScope.launch {
            val token = token() ?: return@launch
            runCatching {
                withContext(Dispatchers.IO) {
                    val req = Request.Builder()
                        .url("$API/repos/$repo/actions/runs/$id/$action")
                        .header("Authorization", "Bearer $token")
                        .header("Accept", "application/vnd.github+json")
                        .post(ByteArray(0).toRequestBody("application/json".toMediaType()))
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    }
                }
            }.onSuccess {
                _state.update { it.copy(message = okMessage) }
                viewModelScope.launch { kotlinx.coroutines.delay(1500); refreshRuns(silent = true) }
            }.onFailure { e ->
                _state.update { it.copy(message = "操作失败：${e.message.orEmpty().take(60)}") }
            }
        }
    }

    /** 产物页：Releases（含预发布）与资产列表。 */
    fun refreshReleases() {
        val repo = _state.value.repo
        if (repo.isBlank()) return
        viewModelScope.launch {
            val token = token() ?: return@launch
            runCatching {
                withContext(Dispatchers.IO) {
                    val arr = JSONArray(get("$API/repos/$repo/releases?per_page=10", token))
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.let { r ->
                            val assetsArr = r.optJSONArray("assets")
                            ReleaseItem(
                                tag = r.optString("tag_name"),
                                name = r.optString("name"),
                                prerelease = r.optBoolean("prerelease", false),
                                createdAt = parseIsoMs(r.optString("created_at")),
                                assets = (0 until (assetsArr?.length() ?: 0)).mapNotNull { j ->
                                    assetsArr?.optJSONObject(j)?.let { a ->
                                        AssetItem(
                                            id = a.optLong("id"),
                                            name = a.optString("name"),
                                            sizeBytes = a.optLong("size"),
                                            url = a.optString("browser_download_url")
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }.onSuccess { releases ->
                _state.update { it.copy(releases = releases, releasesLoaded = true) }
            }.onFailure { e ->
                FileLogger.w(TAG, "拉取 Releases 失败", e)
                _state.update { it.copy(releasesLoaded = true, message = "Releases 拉取失败：${e.message.orEmpty().take(60)}") }
            }
        }
    }

    /** 资产下载：系统 DownloadManager + Authorization 头（私有仓库可用），完成后通知栏点开安装。 */
    fun downloadAsset(asset: AssetItem) {
        viewModelScope.launch { downloadAssetInternal(asset) }
    }

    private suspend fun downloadAssetInternal(asset: AssetItem) {
        val token = token()
        if (token.isNullOrBlank()) {
            _state.update { it.copy(message = "未找到 GitHub 凭据") }
            return
        }
        runCatching {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(asset.url))
                .addRequestHeader("Authorization", "Bearer $token")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, asset.name)
                .setMimeType("application/vnd.android.package-archive")
            dm.enqueue(request)
        }.onSuccess {
            _state.update { it.copy(message = "已开始下载 ${asset.name}，完成后在通知栏点开安装") }
        }.onFailure { e ->
            _state.update { it.copy(message = "下载失败：${e.message.orEmpty().take(60)}") }
        }
    }

    /** 事务页：打开的 Issues 与 PR（一次拉取合并展示）。 */
    fun refreshOps() {
        val repo = _state.value.repo
        if (repo.isBlank()) return
        viewModelScope.launch {
            val token = token() ?: return@launch
            runCatching {
                withContext(Dispatchers.IO) {
                    val issuesArr = JSONArray(get("$API/repos/$repo/issues?state=open&per_page=30", token))
                    (0 until issuesArr.length()).mapNotNull { i ->
                        issuesArr.optJSONObject(i)?.let { o ->
                            val isPull = o.has("pull_request")
                            OpsItem(
                                number = o.optInt("number"),
                                title = o.optString("title"),
                                isPull = isPull,
                                user = o.optJSONObject("user")?.optString("login").orEmpty(),
                                updatedAt = parseIsoMs(o.optString("updated_at")),
                                htmlUrl = o.optString("html_url")
                            )
                        }
                    }.sortedByDescending { it.updatedAt }
                }
            }.onSuccess { ops ->
                _state.update { it.copy(ops = ops, opsLoaded = true) }
            }.onFailure { e ->
                FileLogger.w(TAG, "拉取 Issues/PR 失败", e)
                _state.update { it.copy(opsLoaded = true, message = "事务拉取失败：${e.message.orEmpty().take(60)}") }
            }
        }
    }

    /** 远端构建：触发 android-release.yml（workflow_dispatch，ref 默认分支）。 */
    fun dispatchBuild() {
        val repo = _state.value.repo
        if (repo.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, message = "") }
            val token = token() ?: run {
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
                        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} ${resp.message}")
                    }
                }
            }.onSuccess {
                _state.update { it.copy(loading = false, message = "已触发远端构建（Android Release），稍后可在 CI 页查看进度") }
                viewModelScope.launch { kotlinx.coroutines.delay(4000); refreshRuns(silent = true) }
            }.onFailure { e ->
                FileLogger.w(TAG, "触发远端构建失败", e)
                _state.update { it.copy(loading = false, message = "触发失败：${e.message.orEmpty().take(80)}") }
            }
        }
    }

    private suspend fun token(): String? =
        credentialRepository.findForHost("github.com")?.token?.takeIf { it.isNotBlank() }

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

    private fun parseIsoMs(iso: String): Long =
        runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .parse(iso)?.time ?: 0L
        }.getOrDefault(0L)

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
            FloatingWindowGeometry(xDp = 16f, yDp = 96f, widthDp = 380f, heightDp = 600f)
        }
    }
}
