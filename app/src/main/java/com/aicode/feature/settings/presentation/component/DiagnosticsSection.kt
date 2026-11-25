package com.aicode.feature.settings.presentation.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicode.feature.agent.presentation.AIAgentViewModel
import com.aicode.feature.settings.domain.DiagnosticsCollector
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 诊断与自测区块 ViewModel：环境收集 + 项目自测 + 子程序调试通道管理。 */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val collector: DiagnosticsCollector,
    private val commandEngine: com.aicode.feature.agent.domain.container.CommandEngine,
    private val subprogramRegistry: com.aicode.feature.settings.domain.SubprogramRegistry,
    private val mcpConfigRepository: com.aicode.feature.agent.domain.mcp.McpConfigRepository
) : ViewModel() {

    companion object {
        private const val SELFTEST_PATH = "~/workspace/.aicode/selftest.json"
    }

    private val _collecting = MutableStateFlow(false)
    val collecting: StateFlow<Boolean> = _collecting.asStateFlow()

    private val _report = MutableStateFlow<String?>(null)
    val report: StateFlow<String?> = _report.asStateFlow()

    private val _selfTestRunning = MutableStateFlow(false)
    val selfTestRunning: StateFlow<Boolean> = _selfTestRunning.asStateFlow()

    private val _selfTestReport = MutableStateFlow<String?>(null)
    val selfTestReport: StateFlow<String?> = _selfTestReport.asStateFlow()

    private val _logMessage = MutableStateFlow<String?>(null)
    val logMessage: StateFlow<String?> = _logMessage.asStateFlow()

    private val _subprogramReport = MutableStateFlow<String?>(null)
    val subprogramReport: StateFlow<String?> = _subprogramReport.asStateFlow()

    /** 结构化体检结果：（包名，是否同签名），供一键添加。 */
    private val _installedSubPackages = MutableStateFlow<List<Pair<String, Boolean>>>(emptyList())
    val installedSubPackages: StateFlow<List<Pair<String, Boolean>>> = _installedSubPackages.asStateFlow()

    /** L1 安装态体检：列出设备上由母程序生成的子程序。 */
    fun listSubprograms() {
        viewModelScope.launch {
            val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                collector.discoverInstalledSubprograms()
            }
            _installedSubPackages.value = found
            _subprogramReport.value = collector.listInstalledSubprograms()
        }
    }

    // ── 子程序调试通道（MCP 注册表）──

    private val _subprograms = MutableStateFlow<List<com.aicode.feature.settings.domain.SubprogramRegistry.Subprogram>>(emptyList())
    val subprograms: StateFlow<List<com.aicode.feature.settings.domain.SubprogramRegistry.Subprogram>> =
        _subprograms.asStateFlow()

    private val _subHealth = mutableStateMapOf<String, Boolean?>()
    val subHealth: Map<String, Boolean?> get() = _subHealth

    private val _subOpMessage = MutableStateFlow<String?>(null)
    val subOpMessage: StateFlow<String?> = _subOpMessage.asStateFlow()

    fun refreshSubprograms() {
        _subprograms.value = subprogramRegistry.load()
    }

    /**
     * 添加子程序：port/token 传 null 时自动分配（端口从 41000 起顺延取未占用，token 随机 UUID），
     * name 传空串时自动取包名末段——用户只需填一个包名。
     */
    fun addSubprogram(name: String, packageName: String, port: Int? = null, token: String? = null): String? {
        val pkg = packageName.trim()
        if (pkg.isBlank()) return "包名不能为空"
        val list = subprogramRegistry.load().toMutableList()
        val finalName = name.trim().ifBlank { pkg.substringAfterLast('.') }
        if (list.any { it.name == finalName }) return "同名子程序已存在"
        val finalPort = port ?: run {
            val used = list.map { it.port }.toSet()
            generateSequence(41000) { it + 1 }.first { it !in used }
        }
        val finalToken = token?.trim()?.takeIf { it.isNotBlank() }
            ?: java.util.UUID.randomUUID().toString().replace("-", "")
        list.add(com.aicode.feature.settings.domain.SubprogramRegistry.Subprogram(finalName, pkg, finalPort, finalToken))
        subprogramRegistry.save(list)
        refreshSubprograms()
        return null
    }

    fun removeSubprogram(name: String) {
        subprogramRegistry.save(subprogramRegistry.load().filterNot { it.name == name })
        // 同步移除已接入的全局 MCP 条目
        viewModelScope.launch {
            runCatching {
                val servers = mcpConfigRepository.getGlobalServers().filterNot { it.name == "subprogram-$name" }
                mcpConfigRepository.setGlobalServers(servers)
            }
            refreshSubprograms()
            _subOpMessage.value = "已移除 $name（含 MCP 接入）"
        }
    }

    /** 健康检查：容器内 curl 探活子程序 MCP 端点。 */
    /** 按包名找到注册条目并探活（一键添加后立即检查）。 */
    fun checkSubprogramByName(packageName: String) {
        subprograms.value.firstOrNull { it.packageName == packageName }?.let { checkSubprogram(it) }
    }

    fun checkSubprogram(sub: com.aicode.feature.settings.domain.SubprogramRegistry.Subprogram) {
        viewModelScope.launch {
            _subHealth[sub.name] = null
            // MIUI 等激进后台管控会杀掉切后台的子程序前台服务：检查前跨 App 重新拉起
            //（母程序在前台时 startForegroundService 合法；extras 名与模板 EXTRA_PORT/EXTRA_TOKEN 对应）。
            runCatching {
                appContext.startForegroundService(
                    android.content.Intent().setClassName(sub.packageName, "com.aicoder.app.mcp.SubprogramMcpService")
                        .putExtra("port", sub.port)
                        .putExtra("token", sub.token)
                )
            }
            kotlinx.coroutines.delay(2000)
            val ok = runCatching { subprogramRegistry.check(sub) }.getOrDefault(false)
            _subHealth[sub.name] = ok
            _subOpMessage.value = if (!ok) {
                "${sub.name}：不在线（端口/token/服务未启动？可在子程序工作区会话集成调试通道）"
            } else {
                // 探活在线即自动接入 MCP（幂等：已接入不重写，避免触发无谓的 MCP 重连）。
                val already = runCatching {
                    mcpConfigRepository.getGlobalServers().any { it.name == "subprogram-${sub.port}" }
                }.getOrDefault(false)
                if (!already) runCatching { attachInternal(sub) }
                "${sub.name}：在线${if (already) "（MCP 已接入" else "，已自动接入 MCP"}，工具前缀 mcp__subprogram-${sub.port}__）"
            }
        }
    }

    /** 写全局 mcp.json（幂等，同条目重写）。返回是否成功。 */
    private suspend fun attachInternal(sub: com.aicode.feature.settings.domain.SubprogramRegistry.Subprogram): Boolean = runCatching {
        val servers = mcpConfigRepository.getGlobalServers().toMutableList()
        val cfg = com.aicode.feature.agent.domain.mcp.McpServerConfig(
            // MCP 工具名只允许 ASCII，用端口做唯一后缀（避免中文名违规）
            name = "subprogram-${sub.port}",
            url = "http://127.0.0.1:${sub.port}/mcp",
            headers = mapOf("Authorization" to "Bearer ${sub.token}")
        )
        servers.removeAll { it.name == cfg.name }
        servers.add(cfg)
        mcpConfigRepository.setGlobalServers(servers)
    }.isSuccess

    /** 接入 MCP：写全局 mcp.json（AI 即得以 mcp__subprogram-名__tool 调用子程序工具）。 */
    fun attachSubprogramToMcp(sub: com.aicode.feature.settings.domain.SubprogramRegistry.Subprogram) {
        viewModelScope.launch {
            val ok = attachInternal(sub)
            _subOpMessage.value = if (ok) {
                "${sub.name} 已接入 MCP（工具前缀 mcp__subprogram-${sub.port}__），可在会话中直接调用"
            } else {
                "接入失败：写入 mcp.json 未成功"
            }
        }
    }

    /** 清理今天之前的旧日志（外部专属存储，App 原生删除）。 */
    fun cleanOldLogs() {
        viewModelScope.launch {
            _logMessage.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                collector.cleanOldLogs()
            }
        }
    }

    fun collect() {
        if (_collecting.value) return
        viewModelScope.launch {
            _collecting.value = true
            _report.value = runCatching { collector.collect() }
                .getOrElse { "收集失败：${it.message ?: "未知错误"}" }
            _collecting.value = false
        }
    }

    /**
     * 项目自测：读项目根 .aicode/selftest.json（名称+验证命令清单，约定见 AGENTS.md
     * 第三节），逐条串行执行并输出 ✓/✗ 证据报告；对所有项目通用（不限于母程序）。
     */
    fun runSelfTest() {
        if (_selfTestRunning.value) return
        viewModelScope.launch {
            _selfTestRunning.value = true
            _selfTestReport.value = runCatching { executeSelfTest() }
                .getOrElse { "自测失败：${it.message ?: "未知错误"}" }
            _selfTestRunning.value = false
        }
    }

    private suspend fun executeSelfTest(): String {
        val raw = commandEngine.runCommandSyncWithExit(
            "cat $SELFTEST_PATH",
            timeoutMs = 30_000L
        )
        if (raw.exitCode != 0) {
            return "未找到自测清单（$SELFTEST_PATH）。\n\n按 AGENTS.md 第三节约定，项目动手前应先创建清单：\n" +
                "{\n  \"project\": \"项目名\",\n  \"items\": [\n    { \"name\": \"编译\", \"command\": \"...\", \"timeoutSec\": 900 }\n  ]\n}"
        }
        val root = org.json.JSONObject(raw.output)
        val project = root.optString("project").ifBlank { "当前项目" }
        val items = root.optJSONArray("items")
            ?: throw IllegalStateException("清单缺少 items 数组")
        val sb = StringBuilder()
        sb.appendLine("# 项目自测报告：$project")
        sb.appendLine()
        var passed = 0
        val total = items.length()
        for (i in 0 until total) {
            val obj = items.optJSONObject(i) ?: continue
            val name = obj.optString("name").ifBlank { "第 ${i + 1} 项" }
            val command = obj.optString("command")
            if (command.isBlank()) continue
            val timeoutSec = obj.optLong("timeoutSec", 600L).coerceIn(10L, 1_800L)
            val started = System.currentTimeMillis()
            val result = commandEngine.runCommandSyncWithExit(command, timeoutMs = timeoutSec * 1_000)
            val elapsed = (System.currentTimeMillis() - started) / 1_000
            val ok = result.exitCode == 0
            if (ok) passed++
            sb.appendLine("## ${if (ok) "✅" else "❌"} $name（${elapsed}s，退出码 ${result.exitCode ?: "未知"}）")
            if (!ok) {
                val tail = result.output.trim().takeLast(600).ifBlank { "（无输出）" }
                sb.appendLine("```")
                sb.appendLine(tail)
                sb.appendLine("```")
            }
        }
        sb.appendLine()
        sb.appendLine("**结果：$passed / $total 通过**" + if (passed == total) "，可交付。" else "，存在未通过项，不得交付。")
        return sb.toString()
    }
}

/** 「诊断与自测」设置区块：环境收集 + 项目自测清单执行，报告可复制/分享/发到会话由 AI 诊断。 */
@Composable
fun DiagnosticsSection(viewModel: DiagnosticsViewModel) {
    val collecting by viewModel.collecting.collectAsStateWithLifecycle()
    val report by viewModel.report.collectAsStateWithLifecycle()
    val selfTestRunning by viewModel.selfTestRunning.collectAsStateWithLifecycle()
    val selfTestReport by viewModel.selfTestReport.collectAsStateWithLifecycle()
    val logMessage by viewModel.logMessage.collectAsStateWithLifecycle()
    val subprogramReport by viewModel.subprogramReport.collectAsStateWithLifecycle()
    val subprograms by viewModel.subprograms.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "一键收集设备、权限、容器与引擎环境信息，生成 Markdown 报告。全程零特殊权限：不依赖 root/Shizuku，只读本应用沙盒内与公共系统信息，数据不出设备（除非你主动分享）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::collect, enabled = !collecting) {
                if (collecting) {
                    CircularProgressIndicator(modifier = Modifier.width(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (collecting) "收集中…" else "一键收集")
            }
            OutlinedButton(onClick = viewModel::cleanOldLogs) {
                Text("清理旧日志")
            }
        }
        logMessage?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        report?.let { text ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("AiCodeR 诊断报告", text))
                    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                }) {
                    Text("复制")
                }
                OutlinedButton(onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                        putExtra(Intent.EXTRA_SUBJECT, "AiCodeR 诊断报告")
                    }
                    context.startActivity(Intent.createChooser(intent, "分享诊断报告"))
                }) {
                    Text("分享")
                }
                OutlinedButton(onClick = {
                    val activity = context as? ComponentActivity ?: return@OutlinedButton
                    val agentViewModel = androidx.lifecycle.ViewModelProvider(activity)[AIAgentViewModel::class.java]
                    agentViewModel.queueExternalText(text)
                    Toast.makeText(context, "已排入会话输入框，回到聊天页即可让 AI 诊断", Toast.LENGTH_LONG).show()
                }) {
                    Text("发到会话")
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        // ── L1 安装态体检：列出设备上由母程序生成的子程序 ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("已安装子程序体检", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    "零权限列出设备上由母程序生成的子程序（包名 com.aicoder.app.* 前缀或同签名）：版本、安装/更新时间。深度诊断由子程序内嵌的 MCP 调试通道提供（开发期注入）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = viewModel::listSubprograms) {
                    Text("体检子程序")
                }
                // 体检结果直接可一键添加：包名自动带出，端口/token 自动分配，零输入。
                val installedSubs by viewModel.installedSubPackages.collectAsStateWithLifecycle()
                val registered = remember(subprograms.size) { subprograms.map { it.packageName }.toSet() }
                installedSubs.forEach { (pkg, sameSig) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            pkg + if (sameSig) "（同签名）" else "",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        if (pkg in registered) {
                            Text(
                                "已注册",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            TextButton(onClick = {
                                val r = viewModel.addSubprogram("", pkg)
                                if (r == null) viewModel.checkSubprogramByName(pkg)
                            }) { Text("一键添加") }
                        }
                    }
                }
                subprogramReport?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // ── 子程序调试通道（MCP 注册表管理）──
        SubprogramDebugCard(viewModel = viewModel)

        // ── 项目自测：执行 .aicode/selftest.json 清单，输出逐项证据（对所有项目通用）──
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("项目自测", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    "执行当前项目根目录 .aicode/selftest.json 里的验证清单（编译/测试/打包等），逐项输出通过与否与耗时。清单由 AI 在项目初始化时生成，对任何项目通用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = viewModel::runSelfTest, enabled = !selfTestRunning) {
                    if (selfTestRunning) {
                        CircularProgressIndicator(modifier = Modifier.width(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (selfTestRunning) "自测执行中…（可能需要几分钟）" else "执行自测清单")
                }
            }
        }

        selfTestReport?.let { text ->
            SelfTestReportActions(text = text, context = context)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 子程序调试通道管理卡：注册表（名称/包名/端口/token）+ 健康检查 + 一键接入 MCP。
 * 子程序端由生成模板（.aicode/templates/subprogram-mcp/）内置 MCP server。
 */
@Composable
private fun SubprogramDebugCard(viewModel: DiagnosticsViewModel) {
    val subprograms by viewModel.subprograms.collectAsStateWithLifecycle()
    val subOpMessage by viewModel.subOpMessage.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.refreshSubprograms() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("子程序调试通道", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            "由母程序生成的子程序内置 MCP 调试通道（127.0.0.1 + token）。添加后可健康检查、接入 MCP，在会话中直接调用子程序的诊断工具。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (subprograms.isEmpty()) {
            Text("暂无已注册子程序", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            subprograms.forEach { sub ->
                val health = viewModel.subHealth[sub.name]
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(sub.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${sub.packageName} · 端口 ${sub.port}" +
                                (health?.let { if (it) " · 在线" else " · 不在线" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { viewModel.checkSubprogram(sub) }) { Text("检查") }
                    TextButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("subprogram token", sub.token))
                        Toast.makeText(context, "Token 已复制（子程序侧需一致）", Toast.LENGTH_SHORT).show()
                    }) { Text("Token") }
                    TextButton(onClick = { viewModel.attachSubprogramToMcp(sub) }) { Text("接入 MCP") }
                    Text(
                        text = "删除",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.clickable { viewModel.removeSubprogram(sub.name) }.padding(6.dp)
                    )
                }
                // 不在线 = 子程序侧多半还没集成调试通道：给出话术一键复制，粘到子程序工作区会话即可让 AI 集成。
                if (health == false) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "未在线：需先在子程序项目里集成调试通道",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            val cmd = "按 .aicode/templates/subprogram-mcp/README.md 集成子程序 MCP 调试通道：" +
                                "模板与 port/token 已在本项目 .aicode/ 下备好，集成完成后回填包名到 .aicode/subprograms.json，" +
                                "然后告诉我，我去母程序诊断页点检查。"
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("subprogram integration", cmd))
                            Toast.makeText(context, "集成指令已复制：粘贴到「${sub.name}」工作区会话发送即可", Toast.LENGTH_LONG).show()
                        }) { Text("复制集成指令") }
                    }
                }
            }
        }
        OutlinedButton(onClick = { showAdd = true }) { Text("+ 添加子程序") }
        subOpMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var pkg by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("添加子程序") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "只需填包名；端口与 Token 自动分配，名称留空自动取包名末段。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(value = name, onValueChange = { name = it; err = null }, label = { Text("名称（可留空）") }, singleLine = true)
                    OutlinedTextField(value = pkg, onValueChange = { pkg = it; err = null }, label = { Text("包名（必填）") }, singleLine = true)
                    err?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (pkg.isBlank()) {
                        err = "请填写包名"
                    } else {
                        viewModel.addSubprogram(name, pkg)?.let { err = it } ?: run { showAdd = false }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("取消") } }
        )
    }
}

/** 自测报告操作行：复制/分享/发到会话（与环境报告共用逻辑）。 */
@Composable
private fun SelfTestReportActions(text: String, context: Context) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("项目自测报告", text))
            Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }) {
            Text("复制")
        }
        OutlinedButton(onClick = {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, "项目自测报告")
            }
            context.startActivity(Intent.createChooser(intent, "分享自测报告"))
        }) {
            Text("分享")
        }
        OutlinedButton(onClick = {
            val activity = context as? ComponentActivity ?: return@OutlinedButton
            val agentViewModel = androidx.lifecycle.ViewModelProvider(activity)[AIAgentViewModel::class.java]
            agentViewModel.queueExternalText(text)
            Toast.makeText(context, "已排入会话输入框，回到聊天页即可让 AI 处理未通过项", Toast.LENGTH_LONG).show()
        }) {
            Text("发到会话")
        }
    }
}
