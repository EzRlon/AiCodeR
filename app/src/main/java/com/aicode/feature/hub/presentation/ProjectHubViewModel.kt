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
import com.aicode.core.io.FileAccessProvider
import com.aicode.core.ui.floating.FloatingWindowGeometry
import com.aicode.core.util.FileLogger
import com.aicode.feature.credentials.domain.repository.CredentialRepository
import com.aicode.feature.terminal.domain.TabFinishedEvent
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * 项目中心浮窗的 VM：直连 GitHub REST API（手机流量，零模型 token）。
 * 仓库来源全自动：读取当前工作区 .git/config 的 remote 解析 owner/repo，不再让用户手填。
 * 国内网络直连 api.github.com 失败时自动切换镜像（api.kkgithub.com），资产下载走 gh-proxy。
 */
@HiltViewModel
class ProjectHubViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val credentialRepository: CredentialRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val fileAccess: FileAccessProvider,
    private val terminal: TerminalSessionProvider,
    private val pathMapper: WorkspacePathMapper
) : ViewModel() {

    data class RepoInfo(
        val fullName: String,
        val description: String,
        val defaultBranch: String,
        val localPath: String
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
        val htmlUrl: String,
        val isOpen: Boolean
    )

    /** 构建方式：远端 CI / 本地容器。 */
    enum class BuildMode { REMOTE, LOCAL }

    data class LocalArtifact(val name: String, val path: String, val sizeBytes: Long)

    data class LocalBuildState(
        val running: Boolean = false,
        val task: String = DEFAULT_LOCAL_TASK,
        val startedAt: Long = 0L,
        val finishedAt: Long = 0L,
        val exitCode: Int? = null,
        val logTail: List<String> = emptyList(),
        val artifacts: List<LocalArtifact> = emptyList()
    ) {
        val elapsedMs: Long
            get() = if (running) System.currentTimeMillis() - startedAt else if (finishedAt > 0) finishedAt - startedAt else 0L
    }

    data class UiState(
        val repo: String = "",
        val detected: Boolean = false,
        val loading: Boolean = false,
        val quietRefreshing: Boolean = false,
        val info: RepoInfo? = null,
        val commits: List<CommitItem> = emptyList(),
        val readmeSnippet: String = "",
        val runs: List<RunItem> = emptyList(),
        val runsLoaded: Boolean = false,
        val releases: List<ReleaseItem> = emptyList(),
        val releasesLoaded: Boolean = false,
        val ops: List<OpsItem> = emptyList(),
        val opsLoaded: Boolean = false,
        val buildMode: BuildMode = BuildMode.REMOTE,
        val buildModeIsDefault: Boolean = true,
        val mirrorActive: Boolean = false,
        val localBuild: LocalBuildState = LocalBuildState(),
        val message: String = ""
    )

    @Volatile private var activeBuildTab: String? = null
    private var buildPollJob: Job? = null

    private companion object {
        const val TAG = "ProjectHub"
        const val DEFAULT_LOCAL_TASK = ":app:assembleDebug"
        const val PREFS = "project_hub"
        const val KEY_X = "geom_x"
        const val KEY_Y = "geom_y"
        const val KEY_W = "geom_w"
        const val KEY_H = "geom_h"
        const val KEY_BUILD_MODE = "build_mode"
        const val DIRECT_API = "https://api.github.com"
        const val MIRROR_API = "https://api.kkgithub.com"
        const val GH_PROXY = "https://gh-proxy.com/"
        const val RELEASE_WORKFLOW = "android-release.yml"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(
        UiState(
            buildMode = runCatching {
                BuildMode.valueOf(prefs.getString(KEY_BUILD_MODE, BuildMode.REMOTE.name).orEmpty())
            }.getOrDefault(BuildMode.REMOTE)
        )
    )
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch {
            terminal.tabFinishedEvents.collect { ev ->
                val tab = activeBuildTab ?: return@collect
                if (ev.tabId != tab) return@collect
                onLocalBuildFinished(ev)
            }
        }
    }

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

    /** 浮窗打开时调用：已加载过则跳过；否则等当前工作区就绪后自动识别仓库并拉数据。 */
    fun ensureLoaded() {
        if (_state.value.info != null || _state.value.loading) return
        detectRepoAndLoad()
    }

    /** 自动识别当前工作区仓库（.git/config remote），然后并行加载各页数据。 */
    fun detectRepoAndLoad() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, message = "") }
            // WorkspaceRepository.current 在应用启动阶段可能尚未就绪（DB/DataStore 异步），
            // 这里挂起等待首个非空值，避免 VM 早创建时一次识别失败后页面永久空白。
            val workspace = workspaceRepository.current.filterNotNull().first()
            if (workspace.path.isBlank()) {
                _state.update { it.copy(loading = false, detected = true, message = "当前没有可用工作区") }
                return@launch
            }
            val config = withContext(Dispatchers.IO) {
                // 本地工作区 path 是真实绝对路径（WorkspaceRepository 用 File.absolutePath），
                // 优先直读；远程工作区再走 FileAccessProvider；最后兜底容器 ~ 路径。
                runCatching { java.io.File(workspace.path, ".git/config").readText() }.getOrNull()
                    ?: runCatching { fileAccess.readFile("${workspace.path}/.git/config") }.getOrNull()
                    ?: runCatching { fileAccess.readFile("~/workspace/${workspace.name}/.git/config") }.getOrNull()
            }
            val repo = parseRepoFromGitConfig(config)
            if (repo == null) {
                _state.update {
                    it.copy(
                        loading = false,
                        detected = true,
                        info = RepoInfo(
                            fullName = workspace.name,
                            description = if (config == null) {
                                "未识别到 GitHub 远端：.git/config 读取失败（路径 ${workspace.path}）"
                            } else {
                                "未识别到 GitHub 远端：.git/config 中没有 github.com 地址（已读取 ${config.length} 字符）"
                            },
                            defaultBranch = "-",
                            localPath = workspace.path
                        ),
                        message = ""
                    )
                }
                return@launch
            }
            _state.update { it.copy(repo = repo, detected = true) }
            refresh()
            refreshRuns(silent = true)
            refreshReleases()
            refreshOps()
        }
    }

    /** 下拉刷新（与记忆图谱同交互）：全量静默刷新，1.2s 后收起。 */
    fun pullRefresh() {
        if (_state.value.quietRefreshing) return
        // 仓库尚未识别时（工作区加载晚于本 VM、或首次识别失败）下拉即重走自动识别
        if (_state.value.repo.isBlank()) {
            detectRepoAndLoad()
            return
        }
        _state.update { it.copy(quietRefreshing = true) }
        viewModelScope.launch {
            refresh()
            refreshRuns(silent = true)
            refreshReleases()
            refreshOps()
            kotlinx.coroutines.delay(1200)
            _state.update { it.copy(quietRefreshing = false) }
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
                    val info = JSONObject(get("/repos/$repo", token))
                    val commitsArr = JSONArray(get("/repos/$repo/commits?per_page=8", token))
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
                        JSONObject(get("/repos/$repo/readme", token))
                            .optString("content").replace("\n", "")
                    }.getOrDefault("")
                    val readme = if (readmeRaw.isBlank()) "" else runCatching {
                        String(android.util.Base64.decode(readmeRaw, android.util.Base64.DEFAULT)).take(1600)
                    }.getOrDefault("")
                    val localPath = workspaceRepository.current.value?.path.orEmpty()
                    Triple(
                        RepoInfo(
                            fullName = info.optString("full_name", repo),
                            description = info.optString("description"),
                            defaultBranch = info.optString("default_branch", "main"),
                            localPath = localPath
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
                _state.update { it.copy(loading = false, message = friendlyError("拉取", e)) }
            }
        }
    }

    fun refreshRuns(silent: Boolean = false) {
        val repo = _state.value.repo
        if (repo.isBlank()) return
        viewModelScope.launch {
            if (!silent) _state.update { it.copy(loading = true) }
            val token = token() ?: return@launch
            runCatching {
                withContext(Dispatchers.IO) {
                    // GitHub 该端点返回对象 {"total_count":..,"workflow_runs":[..]}，不是裸数组
                    val arr = JSONObject(get("/repos/$repo/actions/runs?per_page=12", token))
                        .optJSONArray("workflow_runs") ?: JSONArray()
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.let { r ->
                            RunItem(
                                id = r.optLong("id"),
                                title = r.optString("display_title").ifBlank { r.optString("name") },
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
                _state.update { it.copy(loading = false, runsLoaded = true, message = friendlyError("CI", e)) }
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
                    post("/repos/$repo/actions/runs/$id/$action", token)
                }
            }.onSuccess {
                _state.update { it.copy(message = okMessage) }
                viewModelScope.launch {
                    kotlinx.coroutines.delay(1500)
                    refreshRuns(silent = true)
                }
            }.onFailure { e ->
                _state.update { it.copy(message = friendlyError("操作", e)) }
            }
        }
    }

    fun refreshReleases() {
        val repo = _state.value.repo
        if (repo.isBlank()) return
        viewModelScope.launch {
            val token = token() ?: return@launch
            runCatching {
                withContext(Dispatchers.IO) {
                    val arr = JSONArray(get("/repos/$repo/releases?per_page=10", token))
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
                                }.sortedByDescending { it.name.endsWith(".apk") }
                            )
                        }
                    }
                }
            }.onSuccess { releases ->
                _state.update { it.copy(releases = releases, releasesLoaded = true) }
            }.onFailure { e ->
                FileLogger.w(TAG, "拉取 Releases 失败", e)
                _state.update { it.copy(releasesLoaded = true, message = friendlyError("Releases", e)) }
            }
        }
    }

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
            val url = if (_state.value.mirrorActive) GH_PROXY + asset.url else asset.url
            val request = DownloadManager.Request(Uri.parse(url))
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

    fun refreshOps() {
        val repo = _state.value.repo
        if (repo.isBlank()) return
        viewModelScope.launch {
            val token = token() ?: return@launch
            runCatching {
                withContext(Dispatchers.IO) {
                    val issuesArr = JSONArray(get("/repos/$repo/issues?state=all&per_page=30", token))
                    (0 until issuesArr.length()).mapNotNull { i ->
                        issuesArr.optJSONObject(i)?.let { o ->
                            OpsItem(
                                number = o.optInt("number"),
                                title = o.optString("title"),
                                isPull = o.has("pull_request"),
                                user = o.optJSONObject("user")?.optString("login").orEmpty(),
                                updatedAt = parseIsoMs(o.optString("updated_at")),
                                htmlUrl = o.optString("html_url"),
                                isOpen = o.optString("state") == "open"
                            )
                        }
                    }.sortedWith(compareByDescending<OpsItem> { it.isOpen }.thenByDescending { it.updatedAt })
                }
            }.onSuccess { ops ->
                _state.update { it.copy(ops = ops, opsLoaded = true) }
            }.onFailure { e ->
                FileLogger.w(TAG, "拉取 Issues/PR 失败", e)
                _state.update { it.copy(opsLoaded = true, message = friendlyError("事务", e)) }
            }
        }
    }

    /** 构建：远端→workflow_dispatch；本地→容器内 gradle。 */
    fun startBuild() {
        when (_state.value.buildMode) {
            BuildMode.REMOTE -> dispatchBuild()
            BuildMode.LOCAL -> startLocalBuild()
        }
    }

    fun setLocalBuildTask(task: String) {
        _state.update { it.copy(localBuild = it.localBuild.copy(task = task)) }
    }

    fun cancelLocalBuild() {
        val tab = activeBuildTab ?: return
        if (terminal.writeBytesToTab(tab, 3)) {
            _state.update { it.copy(message = "已发送中断，等待 gradle 退出…") }
        }
    }

    private fun startLocalBuild() {
        val info = _state.value.info
        if (info == null || info.localPath.isBlank()) {
            _state.update { it.copy(message = "未识别到本地项目目录，无法本地构建") }
            return
        }
        if (_state.value.localBuild.running || activeBuildTab != null) return
        viewModelScope.launch {
            val containerDir = pathMapper.toContainerPath(info.localPath)
            if (containerDir.isNullOrBlank()) {
                _state.update { it.copy(message = "无法定位项目容器路径") }
                return@launch
            }
            val script = buildLocalGradleScript(containerDir, _state.value.localBuild.task)
            val tabId = try {
                terminal.startBackgroundCommand(
                    script,
                    title = "本地构建 ${info.fullName}",
                    notify = true
                )
            } catch (t: Throwable) {
                _state.update { it.copy(message = "启动本地构建失败：${t.message.orEmpty()}".trim()) }
                return@launch
            }
            activeBuildTab = tabId
            _state.update {
                it.copy(
                    localBuild = LocalBuildState(
                        running = true,
                        task = _state.value.localBuild.task,
                        startedAt = System.currentTimeMillis()
                    )
                )
            }
            pollLocalBuildOutput(tabId)
        }
    }

    private fun pollLocalBuildOutput(tabId: String) {
        buildPollJob?.cancel()
        buildPollJob = viewModelScope.launch {
            while (isActive && activeBuildTab == tabId) {
                delay(1_500)
                val out = withContext(Dispatchers.IO) { terminal.getTabOutput(tabId) }
                if (out != null) {
                    val tail = out.lines().filter { it.isNotBlank() }.takeLast(16)
                    _state.update { s ->
                        if (s.localBuild.running) s.copy(localBuild = s.localBuild.copy(logTail = tail)) else s
                    }
                }
            }
        }
    }

    private suspend fun onLocalBuildFinished(ev: TabFinishedEvent) {
        activeBuildTab = null
        buildPollJob?.cancel()
        buildPollJob = null
        val tail = ev.tailOutput?.lines()?.filter { it.isNotBlank() }?.takeLast(30)
            ?: emptyList()
        val artifacts = if (ev.exitCode == 0) scanLocalArtifacts() else emptyList()
        _state.update {
            it.copy(
                localBuild = it.localBuild.copy(
                    running = false,
                    exitCode = ev.exitCode,
                    finishedAt = System.currentTimeMillis(),
                    logTail = tail.ifEmpty { it.localBuild.logTail },
                    artifacts = artifacts
                )
            )
        }
        if (ev.exitCode == 0) {
            _state.update { it.copy(message = "本地构建完成，产物 ${artifacts.size} 个") }
        } else {
            _state.update { it.copy(message = "本地构建失败（退出码 ${ev.exitCode}），详见日志") }
        }
    }

    private suspend fun scanLocalArtifacts(): List<LocalArtifact> = withContext(Dispatchers.IO) {
        val localPath = _state.value.info?.localPath.orEmpty()
        if (localPath.isBlank()) return@withContext emptyList()
        val outputs = File(localPath, "app/build/outputs")
        if (!outputs.isDirectory) return@withContext emptyList()
        outputs.walkTopDown()
            .filter { f -> f.isFile && (f.extension == "apk" || f.extension == "aab") }
            .sortedByDescending { it.lastModified() }
            .take(10)
            .map { LocalArtifact(name = it.name, path = it.absolutePath, sizeBytes = it.length()) }
            .toList()
    }

    /** 容器内构建脚本：cd 项目目录，解析 JDK/SDK 后跑 gradlew；路径均已预校验存在。 */
    private fun buildLocalGradleScript(containerDir: String, task: String): String {
        val trimmedTask = task.trim().ifBlank { DEFAULT_LOCAL_TASK }
        val jvm = listOf(
            "/usr/lib/jvm/default-jvm",
            "/usr/lib/jvm/java-17-openjdk-arm64",
            "/usr/lib/jvm/java-17-openjdk",
            "/usr/lib/jvm/java-17-openjdk-amd64"
        ).firstOrNull { p -> pathMapper.toHostFile(p)?.isDirectory == true }
        val sdk = listOf(
            "/root/android/sdk",
            "/opt/android-sdk",
            "~/Android/Sdk"
        ).firstOrNull { p -> pathMapper.toHostFile(p)?.isDirectory == true }
        val envPrefix = buildString {
            if (jvm != null) append("JAVA_HOME=")
            if (jvm != null) append(jvm)
            if (sdk != null) append(if (isEmpty()) "" else " ")
            if (sdk != null) append("ANDROID_HOME=")
            if (sdk != null) append(if (sdk == "~/Android/Sdk") "\$HOME/Android/Sdk" else sdk)
        }
        return "cd $containerDir && " +
            (if (envPrefix.isNotEmpty()) "$envPrefix " else "") +
            "sh ./gradlew -p . $trimmedTask --no-configuration-cache --no-watch-fs --console=plain"
    }

    fun setBuildMode(mode: BuildMode, makeDefault: Boolean) {
        _state.update { it.copy(buildMode = mode, buildModeIsDefault = makeDefault) }
        if (makeDefault) {
            prefs.edit().putString(KEY_BUILD_MODE, mode.name).apply()
        }
    }

    private fun dispatchBuild() {
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
                    post("/repos/$repo/actions/workflows/$RELEASE_WORKFLOW/dispatches", token, body)
                }
            }.onSuccess {
                _state.update {
                    it.copy(loading = false, message = "已触发远端构建（Android Release），CI 页可看进度")
                }
                viewModelScope.launch {
                    kotlinx.coroutines.delay(4000)
                    refreshRuns(silent = true)
                }
            }.onFailure { e ->
                FileLogger.w(TAG, "触发远端构建失败", e)
                _state.update { it.copy(loading = false, message = friendlyError("触发构建", e)) }
            }
        }
    }

    private fun apiUrl(path: String): String =
        (if (_state.value.mirrorActive) MIRROR_API else DIRECT_API) + path

    /** POST 与 GET 同策略：直连失败自动切镜像重试一次。 */
    private fun post(path: String, token: String, body: String? = null) {
        val bases = if (_state.value.mirrorActive) listOf(MIRROR_API) else listOf(DIRECT_API, MIRROR_API)
        var lastError: Throwable? = null
        bases.forEach { base ->
            runCatching {
                val request = Request.Builder()
                    .url(base + path)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/vnd.github+json")
                    .post((body ?: "").toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} ${resp.message}")
                }
                if (base == MIRROR_API && !_state.value.mirrorActive) {
                    _state.update { it.copy(mirrorActive = true) }
                }
                return
            }.onFailure { lastError = it }
        }
        throw (lastError ?: IllegalStateException("网络不可用"))
    }

    /** 直连失败自动切镜像重试一次（国内网络无法直连 api.github.com 的兜底）。 */
    private fun get(path: String, token: String): String {
        val bases = if (_state.value.mirrorActive) listOf(MIRROR_API) else listOf(DIRECT_API, MIRROR_API)
        var lastError: Throwable? = null
        bases.forEach { base ->
            runCatching {
                val request = Request.Builder()
                    .url(base + path)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/vnd.github+json")
                    .build()
                return client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    if (base == MIRROR_API && !_state.value.mirrorActive) {
                        _state.update { it.copy(mirrorActive = true) }
                    }
                    resp.body?.string().orEmpty()
                }
            }.onFailure { lastError = it }
        }
        throw (lastError ?: IllegalStateException("网络不可用"))
    }

    private fun friendlyError(action: String, e: Throwable): String {
        val msg = e.message.orEmpty()
        return if (msg.contains("failed to connect", true) || msg.contains("timeout", true) ||
            msg.contains("UnknownHost", true) || msg.contains("connect", true)
        ) {
            "${action}失败：网络无法连接 GitHub（已尝试直连与镜像），请检查网络/代理"
        } else {
            "${action}失败：${msg.take(60)}"
        }
    }

    private suspend fun token(): String? =
        credentialRepository.findForHost("github.com")?.token?.takeIf { it.isNotBlank() }

    /** 从 .git/config 解析 github.com 的 owner/repo（兼容 ssh 与 https）。 */
    private fun parseRepoFromGitConfig(config: String?): String? {
        if (config.isNullOrBlank()) return null
        fun toRepo(url: String): String? = when {
            url.startsWith("git@github.com:") ->
                url.removePrefix("git@github.com:").removeSuffix(".git")
            url.contains("github.com/") ->
                url.substringAfter("github.com/").removeSuffix(".git")
            else -> null
        }
        fun valid(repo: String?) = repo != null && repo.isNotBlank() && repo.count { it == '/' } == 1

        // 优先 [remote "origin"] 段（避免上游/镜像 remote 抢占，如 jieapi/AiCode）
        val originBlock = Regex("""\[remote\s+"origin"\]([\s\S]*?)(?=\n\[|\z)""")
            .find(config)?.groupValues?.get(1)
        val originUrl = originBlock?.let { Regex("""url\s*=\s*(\S+)""").find(it)?.groupValues?.get(1) }
        toRepo(originUrl.orEmpty())?.let { if (valid(it)) return it }

        Regex("""url\s*=\s*(\S+)""").findAll(config).forEach { match ->
            val repo = toRepo(match.groupValues[1])
            if (valid(repo)) return repo
        }
        return null
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
