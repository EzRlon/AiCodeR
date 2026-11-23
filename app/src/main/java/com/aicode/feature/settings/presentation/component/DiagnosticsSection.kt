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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 诊断与自测区块 ViewModel：环境收集 + 项目自测清单执行。 */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val collector: DiagnosticsCollector,
    private val commandEngine: com.aicode.feature.agent.domain.container.CommandEngine
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

    /** L1 安装态体检：列出设备上由母程序生成的子程序。 */
    fun listSubprograms() {
        viewModelScope.launch {
            _subprogramReport.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                collector.listInstalledSubprograms()
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
                subprogramReport?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

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
