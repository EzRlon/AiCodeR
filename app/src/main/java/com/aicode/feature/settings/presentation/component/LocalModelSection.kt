package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandResult
import com.aicode.feature.settings.domain.LocalLlamaServerManager
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 引擎安装状态。NOT_READY 表示容器未就绪（未安装/未启动），无法操作。 */
enum class LocalEngineState { CHECKING, NOT_READY, NOT_INSTALLED, INSTALLED }

/** 一条已下载到容器内的 gguf 模型文件。 */
data class LocalModelFile(
    val fileName: String,
    val sizeBytes: Long
) {
    /** llama-server 加载后对外暴露的模型 id = 文件名去 .gguf 后缀。 */
    val modelName: String get() = fileName.removeSuffix(".gguf")
}

/** 推荐模型条目：GGUF 从 hf-mirror 下载（国内可达，支持断点续传）。 */
data class RecommendedLocalModel(
    val label: String,
    val fileName: String,
    val approxBytes: Long,
    val url: String
)

private val RECOMMENDED_MODELS = listOf(
    RecommendedLocalModel(
        label = "Qwen3.5-0.8B (Q4_K_M)",
        fileName = "Qwen3.5-0.8B-Q4_K_M.gguf",
        approxBytes = 532_517_120L,
        url = "https://hf-mirror.com/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf"
    ),
    RecommendedLocalModel(
        label = "Qwen3.5-4B (Q4_K_M)",
        fileName = "Qwen3.5-4B-Q4_K_M.gguf",
        approxBytes = 2_740_937_888L,
        url = "https://hf-mirror.com/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf"
    ),
    RecommendedLocalModel(
        label = "Qwen3-8B (Q4_K_M)",
        fileName = "Qwen3-8B-Q4_K_M.gguf",
        approxBytes = 5_027_784_512L,
        url = "https://hf-mirror.com/unsloth/Qwen3-8B-GGUF/resolve/main/Qwen3-8B-Q4_K_M.gguf"
    )
)

/**
 * 本地模型（llama.cpp 离线推理）区块 ViewModel。
 *
 * 容器内引擎安装复用 ~/.aicode/lib/scenarios/localmodel.sh（provision.sh --install
 * localmodel 触发）；模型存 ~/.aicode/local-llm/models/；服务以 nohup 后台方式
 * 启动 llama-server（OpenAI 兼容 /v1，127.0.0.1:8080）。
 */
@HiltViewModel
class LocalModelViewModel @Inject constructor(
    private val commandEngine: CommandEngine,
    private val providerRepository: AIProviderRepository,
    private val llamaServerManager: LocalLlamaServerManager
) : ViewModel() {

    companion object {
        const val SERVER_BASE_URL = "http://127.0.0.1:8080/v1"
        private const val ENGINE_MARKER = "~/.aicode/local-llm/.installed"
        private const val MODEL_DIR = "~/.aicode/local-llm/models"

        /** 模型文件名只允许安全字符，防止拼进 shell 命令。 */
        fun safeFileName(name: String): Boolean =
            name.length in 5..128 && name.endsWith(".gguf") && !name.startsWith(".") &&
                name.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }

        /** 从 gguf 直链提取文件名（去 query）；不合法返回 null。 */
        fun fileNameFromUrl(url: String): String? {
            val clean = url.substringBefore('?')
            if (!clean.startsWith("https://") && !clean.startsWith("http://")) return null
            if (!clean.endsWith(".gguf")) return null
            val name = clean.substringAfterLast('/')
            return if (safeFileName(name)) name else null
        }

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1_000_000_000L -> String.format("%.1f GB", bytes / 1_000_000_000.0)
            bytes >= 1_000_000L -> String.format("%.0f MB", bytes / 1_000_000.0)
            bytes >= 1_000L -> String.format("%.0f KB", bytes / 1_000.0)
            else -> "${bytes}B"
        }
    }

    private val _engineState = MutableStateFlow(LocalEngineState.CHECKING)
    val engineState: StateFlow<LocalEngineState> = _engineState.asStateFlow()

    /** 服务是否在线（/v1/models 返回 200）。 */
    private val _serverRunning = MutableStateFlow(false)
    val serverRunning: StateFlow<Boolean> = _serverRunning.asStateFlow()

    /** 深度思考开关：关（默认）简单问题直接回答；开则模型先生成推理，适合复杂/逻辑问题。 */
    private val _deepThinking = MutableStateFlow(false)
    val deepThinking: StateFlow<Boolean> = _deepThinking.asStateFlow()

    private val _selectedModel = MutableStateFlow<String?>(null)
    val selectedModel: StateFlow<String?> = _selectedModel.asStateFlow()

    private val _localModels = MutableStateFlow<List<LocalModelFile>>(emptyList())
    val localModels: StateFlow<List<LocalModelFile>> = _localModels.asStateFlow()

    /** 正在执行的原子操作标识（install/start/stop/register/delete:<name>），null 空闲。 */
    private val _busyAction = MutableStateFlow<String?>(null)
    val busyAction: StateFlow<String?> = _busyAction.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _registeredProviderName = MutableStateFlow<String?>(null)
    val registeredProviderName: StateFlow<String?> = _registeredProviderName.asStateFlow()

    /** 下载进度：fileName -> (已下载字节, 估算总字节)。 */
    val downloadProgress = mutableStateMapOf<String, Pair<Long, Long>>()

    private var serverProbeJob: Job? = null

    init {
        refresh()
    }

    fun selectModel(name: String) {
        _selectedModel.value = name
    }

    /** 切换深度思考开关；运行中切换时提示需重启服务生效。 */
    fun toggleDeepThinking() {
        _deepThinking.value = !_deepThinking.value
        if (_serverRunning.value) {
            _message.value = "深度思考已${if (_deepThinking.value) "开启" else "关闭"}，重启服务后生效"
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    /** 探测容器与引擎状态；已安装时刷新模型列表并探测服务。 */
    fun refresh() {
        if (_busyAction.value != null) return
        viewModelScope.launch {
            _engineState.value = LocalEngineState.CHECKING
            val notReady = commandEngine.notReadyHint()
            if (notReady != null) {
                _engineState.value = LocalEngineState.NOT_READY
                _message.value = notReady
                return@launch
            }
            val result = commandEngine.runCommandSyncWithExit(
                "test -f $ENGINE_MARKER && echo ok || echo no",
                timeoutMs = 30_000L
            )
            val installed = result.exitCode == 0 && result.output.contains("ok")
            _engineState.value = if (installed) LocalEngineState.INSTALLED else LocalEngineState.NOT_INSTALLED
            if (installed) {
                refreshModels()
                probeServer()
            } else {
                _serverRunning.value = false
                _localModels.value = emptyList()
            }
        }
    }

    /** 一键安装 llama.cpp 引擎（非交互模式自动跳过可选组件；最长 30 分钟）。 */
    fun installEngine() {
        if (_busyAction.value != null) return
        viewModelScope.launch {
            _busyAction.value = "install"
            _message.value = null
            val result = commandEngine.runCommandSyncWithExit(
                "sh ~/.aicode/provision.sh --install localmodel",
                timeoutMs = 1_800_000L
            )
            _busyAction.value = null
            if (result.exitCode == 0) {
                _engineState.value = LocalEngineState.INSTALLED
                refreshModels()
            } else {
                _message.value = "引擎安装失败（退出码 ${result.exitCode ?: "未知"}）：${lastLine(result.output)}"
                refresh()
            }
        }
    }

    /** 刷新已下载模型列表（解析 ls -l；文件名安全校验防止命令注入）。 */
    private fun refreshModels() {
        viewModelScope.launch {
            // ls -l 的时间列随 locale 变化（中文「11月22日 13:57」两段、英文三段），
            // 按固定下标截文件名会把日期拼进去，再被 safeFileName 拒掉 →
            // 模型列表恒空、启动服务按钮永远置灰；find -printf 的「大小\t文件名」
            // 两列与 locale 无关，解析稳定。
            val result = commandEngine.runCommandSyncWithExit(
                "mkdir -p $MODEL_DIR && find $MODEL_DIR -maxdepth 1 -type f -name '*.gguf' -printf '%s\\t%f\\n'",
                timeoutMs = 30_000L
            )
            val models = result.output.lineSequence().mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size != 2) return@mapNotNull null
                val size = parts[0].toLongOrNull() ?: return@mapNotNull null
                if (safeFileName(parts[1])) LocalModelFile(fileName = parts[1], sizeBytes = size) else null
            }.sortedBy { it.fileName }.toList()
            _localModels.value = models
            if (_selectedModel.value == null) _selectedModel.value = models.firstOrNull()?.modelName
        }
    }

    /** 下载推荐模型：curl 断点续传（-C -），后台轮询已下载字节更新进度。 */
    fun downloadModel(item: RecommendedLocalModel) {
        if (_busyAction.value != null || downloadProgress.containsKey(item.fileName)) return
        if (!safeFileName(item.fileName)) return
        viewModelScope.launch {
            _message.value = null
            downloadProgress[item.fileName] = 0L to item.approxBytes
            val pollJob = launch {
                while (downloadProgress.containsKey(item.fileName)) {
                    val probe = commandEngine.runCommandSyncWithExit(
                        "stat -c %s $MODEL_DIR/${item.fileName} || echo 0",
                        timeoutMs = 30_000L
                    )
                    val bytes = probe.output.trim().toLongOrNull() ?: 0L
                    if (bytes > 0) downloadProgress[item.fileName] = bytes to item.approxBytes
                    delay(2_000L)
                }
            }
            val result = commandEngine.runCommandSyncWithExit(
                "mkdir -p $MODEL_DIR && curl -L -C - --fail -o $MODEL_DIR/${item.fileName} ${item.url}",
                timeoutMs = 1_800_000L
            )
            pollJob.cancel()
            downloadProgress.remove(item.fileName)
            if (result.exitCode == 0) {
                refreshModels()
            } else {
                _message.value = "下载失败：${item.fileName}（已下载部分保留，重试自动续传）"
            }
        }
    }

    /** 自定义 gguf 直链下载：返回错误提示，null 表示已开始下载。 */
    fun downloadCustomModel(url: String): String? {
        val trimmed = url.trim()
        val fileName = fileNameFromUrl(trimmed)
            ?: return "链接需以 http(s) 开头且文件名为 .gguf 结尾（仅字母数字与 ._-）"
        if (_busyAction.value != null) return "有操作进行中，请稍候"
        if (downloadProgress.containsKey(fileName)) return "该模型正在下载中"
        if (_localModels.value.any { it.fileName == fileName }) return "该模型已存在"
        downloadModel(
            RecommendedLocalModel(
                label = fileName,
                fileName = fileName,
                approxBytes = 0L,
                url = trimmed
            )
        )
        return null
    }

    /** 删除已下载模型。 */
    fun deleteModel(model: LocalModelFile) {
        if (_busyAction.value != null || !safeFileName(model.fileName)) return
        viewModelScope.launch {
            _busyAction.value = "delete:${model.fileName}"
            commandEngine.runCommandSyncWithExit("rm -f $MODEL_DIR/${model.fileName}", timeoutMs = 30_000L)
            if (_selectedModel.value == model.modelName) _selectedModel.value = null
            _busyAction.value = null
            refreshModels()
        }
    }

    /** 启动 llama-server：App 直接持有容器进程（与 CLI 引擎同机制），App 侧直连探测最多 30 秒。 */
    fun startServer() {
        val modelName = _selectedModel.value ?: return
        if (_busyAction.value != null) return
        if (!safeFileName("$modelName.gguf")) return
        viewModelScope.launch {
            _busyAction.value = "start"
            _message.value = null
            if (llamaServerManager.probe()) {
                _serverRunning.value = true
                _busyAction.value = null
                _message.value = "服务已在运行（127.0.0.1:8080）"
                return@launch
            }
            val startError = llamaServerManager.start("$modelName.gguf", deepThinking = _deepThinking.value)
            if (startError != null) {
                _busyAction.value = null
                _message.value = startError
                return@launch
            }
            var ready = false
            var waitedMs = 0L
            while (!ready && waitedMs < 30_000L) {
                delay(500L)
                waitedMs += 500L
                ready = probeServer()
            }
            _busyAction.value = null
            if (!ready) {
                val tail = llamaServerManager.recentLog().takeLast(5).joinToString("\n")
                _message.value = "服务未就绪（30 秒超时）。日志末尾：${tail.takeLast(300).ifBlank { "无输出" }}"
            }
        }
    }

    /** 停止 llama-server：自持实例直接 destroy；终端等外部方式启动的实例 pkill 兜底。 */
    fun stopServer() {
        if (_busyAction.value != null) return
        viewModelScope.launch {
            _busyAction.value = "stop"
            llamaServerManager.stopSelf()
            commandEngine.runCommandSyncWithExit("pkill -f llama-server || true", timeoutMs = 30_000L)
            delay(500L)
            _serverRunning.value = false
            _busyAction.value = null
        }
    }

    /** App 侧直连 /v1/models（容器无独立网络栈，127.0.0.1 直通）；成功时更新 serverRunning。 */
    private suspend fun probeServer(): Boolean {
        val ok = llamaServerManager.probe()
        _serverRunning.value = ok
        return ok
    }

    /** 把本地服务注册为 OpenAI 兼容供应商（apiKey 留空）；同 baseUrl+模型则跳过。 */
    fun registerProvider() {
        val modelName = _selectedModel.value ?: return
        if (_busyAction.value != null) return
        viewModelScope.launch {
            _busyAction.value = "register"
            val providers = providerRepository.getAllProviders().first()
            val duplicate = providers.any { it.baseUrl == SERVER_BASE_URL && it.defaultModel == modelName }
            if (duplicate) {
                _message.value = "已存在相同配置的本地模型供应商（$modelName），无需重复添加"
                _busyAction.value = null
                return@launch
            }
            val provider = AIProviderConfig(
                id = UUID.randomUUID().toString(),
                name = "本地模型（llama.cpp）",
                type = ProviderType.OPENAI,
                apiKey = "",
                baseUrl = SERVER_BASE_URL,
                defaultModel = modelName,
                models = listOf(modelName)
            )
            providerRepository.saveProvider(provider)
            _registeredProviderName.value = modelName
            _busyAction.value = null
        }
    }

    private fun lastLine(output: String): String =
        output.lineSequence()
            .lastOrNull { it.isNotBlank() }
            ?.replace(Regex("\\u001B\\[[0-9;]*[A-Za-z]"), "")
            ?.take(160)
            ?: "无输出"
}

/**
 * 「本地模型」设置区块主界面：引擎状态卡 + 推荐模型 + 已下载模型 + 本地服务。
 * 文案硬编码中文（App 本身中文，与 MultiLlmWorkbenchScreen 同惯例）。
 */
@Composable
fun LocalModelSection(viewModel: LocalModelViewModel) {
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val serverRunning by viewModel.serverRunning.collectAsStateWithLifecycle()
    val selectedModel by viewModel.selectedModel.collectAsStateWithLifecycle()
    val localModels by viewModel.localModels.collectAsStateWithLifecycle()
    val busyAction by viewModel.busyAction.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val registeredProviderName by viewModel.registeredProviderName.collectAsStateWithLifecycle()
    val deepThinking by viewModel.deepThinking.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "在容器内离线运行大模型：安装 llama.cpp 引擎，下载 GGUF 模型，启动本地 OpenAI 兼容服务（127.0.0.1:8080/v1），并可一键注册为 App 供应商。全程数据不出设备。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        EngineStatusCard(
            engineState = engineState,
            busyAction = busyAction,
            onInstall = viewModel::installEngine,
            onRefresh = viewModel::refresh
        )

        if (engineState == LocalEngineState.INSTALLED) {
            RecommendedModelsCard(
                localModels = localModels,
                busyAction = busyAction,
                downloadProgress = viewModel.downloadProgress,
                onDownload = viewModel::downloadModel,
                onDownloadCustom = viewModel::downloadCustomModel
            )
            DownloadedModelsCard(
                localModels = localModels,
                selectedModel = selectedModel,
                busyAction = busyAction,
                onSelect = viewModel::selectModel,
                onDelete = viewModel::deleteModel
            )
            ServerCard(
                serverRunning = serverRunning,
                selectedModel = selectedModel,
                busyAction = busyAction,
                registeredProviderName = registeredProviderName,
                deepThinking = deepThinking,
                onToggleDeepThinking = viewModel::toggleDeepThinking,
                onStart = viewModel::startServer,
                onStop = viewModel::stopServer,
                onRegister = viewModel::registerProvider
            )
        }

        message?.let { text ->
            MessageCard(text = text, onDismiss = viewModel::clearMessage)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun EngineStatusCard(
    engineState: LocalEngineState,
    busyAction: String?,
    onInstall: () -> Unit,
    onRefresh: () -> Unit
) {
    LocalCard {
        Text("引擎状态", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (dotColor, label) = when (engineState) {
                LocalEngineState.CHECKING -> MaterialTheme.colorScheme.outline to "检查中…"
                LocalEngineState.NOT_READY -> MaterialTheme.colorScheme.error to "容器未就绪"
                LocalEngineState.NOT_INSTALLED -> MaterialTheme.colorScheme.outline to "未安装"
                LocalEngineState.INSTALLED -> Color(0xFF4CAF50) to "已安装（llama.cpp）"
            }
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(dotColor, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (engineState == LocalEngineState.NOT_INSTALLED) {
                Button(onClick = onInstall, enabled = busyAction == null) {
                    if (busyAction == "install") {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (busyAction == "install") "正在安装…" else "一键安装引擎")
                }
            }
            OutlinedButton(onClick = onRefresh, enabled = busyAction == null) {
                Text("刷新状态")
            }
        }
    }
}

@Composable
private fun RecommendedModelsCard(
    localModels: List<LocalModelFile>,
    busyAction: String?,
    downloadProgress: Map<String, Pair<Long, Long>>,
    onDownload: (RecommendedLocalModel) -> Unit,
    onDownloadCustom: (String) -> String?
) {
    var showCustomDialog by remember { mutableStateOf(false) }
    var customUrl by remember { mutableStateOf("") }
    var customError by remember { mutableStateOf<String?>(null) }

    LocalCard {
        Text("推荐模型", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            "从 hf-mirror 下载 GGUF 模型（支持断点续传）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        RECOMMENDED_MODELS.forEach { item ->
            val downloaded = localModels.any { it.fileName == item.fileName }
            val progress = downloadProgress[item.fileName]
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = when {
                                downloaded -> "已下载"
                                progress != null ->
                                    if (progress.second > 0) {
                                        "${formatBytes(progress.first)} / ${formatBytes(progress.second)}"
                                    } else {
                                        "已下载 ${formatBytes(progress.first)}"
                                    }
                                else -> "约 ${formatBytes(item.approxBytes)}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    when {
                        downloaded -> Text(
                            "已就绪",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF4CAF50)
                        )
                        progress != null -> CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        else -> TextButton(
                            onClick = { onDownload(item) },
                            enabled = busyAction == null
                        ) {
                            Text("下载")
                        }
                    }
                }
                if (progress != null && progress.second > 0) {
                    LinearProgressIndicator(
                        progress = {
                            (progress.first.toFloat() / progress.second).coerceIn(0f, 1f)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                }
            }
        }
        downloadProgress.keys
            .filter { key -> RECOMMENDED_MODELS.none { it.fileName == key } }
            .forEach { fileName ->
                val progress = downloadProgress[fileName]
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                fileName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = if (progress != null && progress.second > 0) {
                                    "${formatBytes(progress.first)} / ${formatBytes(progress.second)}"
                                } else {
                                    "已下载 ${formatBytes(progress?.first ?: 0L)}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    if (progress != null && progress.second > 0) {
                        LinearProgressIndicator(
                            progress = {
                                (progress.first.toFloat() / progress.second).coerceIn(0f, 1f)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                        )
                    }
                }
            }
        TextButton(onClick = { showCustomDialog = true }, enabled = busyAction == null) {
            Text("+ 自定义模型链接")
        }
    }

    if (showCustomDialog) {
        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            title = { Text("自定义模型链接") },
            text = {
                Column {
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = {
                            customUrl = it
                            customError = null
                        },
                        label = { Text("gguf 直链（http/https，支持 hf-mirror）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    customError?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val error = onDownloadCustom(customUrl)
                    if (error == null) {
                        showCustomDialog = false
                        customUrl = ""
                        customError = null
                    } else {
                        customError = error
                    }
                }) {
                    Text("下载")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun DownloadedModelsCard(
    localModels: List<LocalModelFile>,
    selectedModel: String?,
    busyAction: String?,
    onSelect: (String) -> Unit,
    onDelete: (LocalModelFile) -> Unit
) {
    LocalCard {
        Text("已下载模型", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        if (localModels.isEmpty()) {
            Text(
                "还没有已下载的模型",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            localModels.forEach { model ->
                val selected = model.modelName == selectedModel
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = busyAction == null) { onSelect(model.modelName) }
                        .padding(vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .border(
                                width = if (selected) 5.dp else 1.5.dp,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                                shape = CircleShape
                            )
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            model.modelName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            formatBytes(model.sizeBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "删除",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clickable(enabled = busyAction == null) { onDelete(model) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            if (selectedModel != null) {
                Text(
                    "已选：$selectedModel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun ServerCard(
    serverRunning: Boolean,
    selectedModel: String?,
    busyAction: String?,
    registeredProviderName: String?,
    deepThinking: Boolean,
    onToggleDeepThinking: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRegister: () -> Unit
) {
    LocalCard {
        Text("本地服务", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        if (serverRunning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline,
                        CircleShape
                    )
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (serverRunning) "运行中：127.0.0.1:8080（OpenAI 兼容 /v1）" else "未运行",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("深度思考", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "关（默认）：简单问题直接回答，实测快约 10 倍；开：先生成推理，适合复杂/逻辑问题。切换后需重启服务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = deepThinking, onCheckedChange = { onToggleDeepThinking() })
        }
        if (serverRunning) {
            Text(
                if (deepThinking) {
                    "当前：深度思考模式（复杂问题质量更高，但每条回复先生成推理，速度约每秒几字）"
                } else {
                    "当前：直接回答模式（简单问题秒回，实测约 28 字/秒）"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!serverRunning) {
                Button(
                    onClick = onStart,
                    enabled = busyAction == null && selectedModel != null
                ) {
                    if (busyAction == "start") {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (busyAction == "start") "启动中…" else "启动服务")
                }
            } else {
                Button(
                    onClick = onStop,
                    enabled = busyAction == null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(if (busyAction == "stop") "停止中…" else "停止服务")
                }
            }
            if (serverRunning && selectedModel != null) {
                OutlinedButton(onClick = onRegister, enabled = busyAction == null) {
                    Text(if (busyAction == "register") "注册中…" else "注册为供应商")
                }
            }
        }
        if (registeredProviderName != null) {
            Text(
                "已注册供应商「本地模型（llama.cpp）」，默认模型 $registeredProviderName，可在「模型供应商」中查看",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF4CAF50)
            )
        }
    }
}

@Composable
private fun MessageCard(text: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "知道了",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .padding(start = 12.dp)
            )
        }
    }
}

@Composable
private fun LocalCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            content()
        }
    }
}
