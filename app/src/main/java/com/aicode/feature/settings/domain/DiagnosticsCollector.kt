package com.aicode.feature.settings.domain

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.CommandEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 诊断与自测收集器：零特殊权限（无需 root/Shizuku/前台 Activity）。
 *
 * 架构依据：App 天然可读自己的沙盒与公共非敏感系统信息；AiCodeR 的 PRoot 容器
 * rootfs 位于应用私有目录内，容器内命令在权限上等同读自家文件——因此设备、权限、
 * 容器、应用四层所需信息全部可由本收集器取得，输出 Markdown 报告供 AI 诊断。
 */
@Singleton
class DiagnosticsCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val commandEngine: CommandEngine
) {

    suspend fun collect(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.appendLine("# AiCodeR 诊断报告")
        sb.appendLine()
        sb.appendLine("> 本报告由应用内自测收集器生成，不依赖 root/Shizuku，只含本应用可自查的信息。")
        sb.appendLine("> 生成时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())}")
        sb.appendLine()
        collectDevice(sb)
        collectPermissions(sb)
        collectApp(sb)
        collectContainer(sb)
        sb.toString()
    }

    /** 设备层：系统/屏幕/内存/存储/电池/网络（全部无需运行时权限）。 */
    private fun collectDevice(sb: StringBuilder) {
        sb.appendLine("## 设备")
        runCatching {
            sb.appendLine("- 机型：${Build.MANUFACTURER} ${Build.MODEL}（Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}）")
            sb.appendLine("- ABI：${Build.SUPPORTED_ABIS.joinToString("/")}")
            sb.appendLine("- 安全补丁级别：${Build.VERSION.SECURITY_PATCH}")
        }
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val totalGb = mem.totalMem / GB
            val availGb = mem.availMem / GB
            sb.appendLine("- 内存：可用 ${fmt(availGb)} / 共 ${fmt(totalGb)} GB（低内存阈值 ${fmt(mem.threshold / GB)} GB）")
        }
        runCatching {
            val stat = StatFs(context.filesDir.path)
            val total = stat.totalBytes / GB
            val avail = stat.availableBytes / GB
            sb.appendLine("- 应用私有存储：可用 ${fmt(avail)} / 共 ${fmt(total)} GB")
        }
        runCatching {
            context.getExternalFilesDir(null)?.let { ext ->
                val stat = StatFs(ext.path)
                sb.appendLine("- 外部专属存储：可用 ${fmt(stat.availableBytes / GB)} GB")
            }
        }
        runCatching {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            if (level >= 0 && scale > 0) {
                sb.appendLine("- 电池：${level * 100 / scale}%（${if (plugged != 0) "充电中" else "电池供电"}）")
            }
        }
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val type = when {
                caps == null -> "无网络"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
                else -> "其他"
            }
            // linkDownstreamBandwidthKbps 在移动数据下严重不准（常见报个位数 Mbps），只报连接类型。
            sb.appendLine("- 网络：$type")
        }
        sb.appendLine()
    }

    /** 权限层：本应用已授敏感权限与可选增强能力开关。 */
    private fun collectPermissions(sb: StringBuilder) {
        sb.appendLine("## 权限与能力")
        runCatching {
            val pm = context.packageManager
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            val granted = info.requestedPermissions.orEmpty().mapNotNull { perm ->
                val state = ContextCompat.checkSelfPermission(context, perm)
                if (state == PackageManager.PERMISSION_GRANTED) perm.substringAfterLast('.') else null
            }
            sb.appendLine("- 已授权权限：${if (granted.isEmpty()) "无" else granted.joinToString("、")}")
        }
        runCatching {
            sb.appendLine("- 悬浮窗（SYSTEM_ALERT_WINDOW）：${if (Settings.canDrawOverlays(context)) "已授权" else "未授权"}")
        }
        runCatching {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = ops.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
            sb.appendLine("- 使用统计（UsageStats）：${if (mode == android.app.AppOpsManager.MODE_ALLOWED) "已授权" else "未授权"}")
        }
        runCatching {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            sb.appendLine("- 无障碍服务（本应用）：${if (enabled.contains(context.packageName)) "已开启" else "未开启"}")
        }
        sb.appendLine()
    }

    /** 应用层：版本/目录占用。 */
    private fun collectApp(sb: StringBuilder) {
        sb.appendLine("## 应用")
        runCatching {
            val pm = context.packageManager
            val info = pm.getPackageInfo(context.packageName, 0)
            val longVer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                "${info.versionName} (${info.longVersionCode})"
            } else {
                "${info.versionName} (${info.versionCode})"
            }
            sb.appendLine("- 版本：$longVer，targetSdk ${info.applicationInfo?.targetSdkVersion ?: 0}")
        }
        runCatching {
            val logsDir = java.io.File(context.getExternalFilesDir(null), "logs")
            val aiLogsDir = java.io.File(context.getExternalFilesDir(null), "ai-logs")
            sb.appendLine("- 日志目录：logs ${dirSize(logsDir)} + ai-logs ${dirSize(aiLogsDir)}")
        }
        runCatching {
            val db = context.getDatabasePath("aicoder.db")
            if (db.exists()) sb.appendLine("- 主数据库：${dirSize(db.parentFile ?: db)}（含全部会话/账本）")
        }
        sb.appendLine()
    }

    /**
     * L1 安装态体检（零权限）：列出设备上由母程序生成的子程序——
     * 识别规则：包名前缀 com.aicoder.app.*（生成约定）或与母程序同签名（同套签名签署的家族应用）。
     * 能拿到：版本、首次安装时间、最近更新时间；私有数据/日志不可见（沙盒边界，由 L2 MCP 通道解决）。
     */
    fun listInstalledSubprograms(): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
        val found = discoverInstalledSubprograms()
        if (found.isEmpty()) {
            return "设备上暂未发现由母程序生成的子程序（识别规则：包名 com.aicoder.app.* 前缀或同签名）"
        }
        val rows = StringBuilder()
        for ((pkg, sameSig) in found) {
            val info = runCatching { context.packageManager.getPackageInfo(pkg, 0) }.getOrNull() ?: continue
            rows.appendLine(
                "- $pkg：v${info.versionName}（装于 ${fmt.format(info.firstInstallTime)}，" +
                    "更新 ${fmt.format(info.lastUpdateTime)}${if (sameSig) "，同签名" else ""}）"
            )
        }
        return "发现 ${found.size} 个子程序：\n$rows"
    }

    /** 结构化体检：包名 → 是否与母程序同签名，供诊断页一键添加子程序。 */
    fun discoverInstalledSubprograms(): List<Pair<String, Boolean>> {
        val pm = context.packageManager
        val selfSig = runCatching {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        }.getOrNull()
        val result = mutableListOf<Pair<String, Boolean>>()
        runCatching {
            for (info in pm.getInstalledPackages(PackageManager.GET_SIGNATURES)) {
                if (info.packageName == context.packageName) continue
                val isFamily = info.packageName.startsWith("com.aicoder.app.")
                val sameSig = selfSig != null &&
                    info.signatures?.any { it.toCharsString() == selfSig.toCharsString() } == true
                if (!isFamily && !sameSig) continue
                result += info.packageName to sameSig
            }
        }
        return result
    }

    /**
     * 清理旧日志：删除 logs/ai-logs 目录中今天之前的文件（当前正在写的今日日志保留），
     * 再做容量裁剪：总量超过 [MAX_LOG_TOTAL_BYTES] 时从最旧文件开始删（跳过各目录最新的一个）。
     * 返回摘要；日志目录在 App 外部专属存储，只能由 App 原生删除（容器无挂载）。
     */
    fun cleanOldLogs(): String {
        var files = 0
        var bytes = 0L
        val todayStart = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dirs = listOf("logs", "ai-logs").mapNotNull { context.getExternalFilesDir(it) }
        for (dir in dirs) {
            dir.listFiles().orEmpty().filter { it.isFile && it.lastModified() < todayStart }.forEach {
                bytes += it.length()
                if (it.delete()) files++
            }
        }
        val (trimmed, trimmedBytes) = trimByTotalSize(dirs)
        files += trimmed
        bytes += trimmedBytes
        return if (files == 0) "没有可清理的日志" else "已清理 $files 个日志文件，释放 ${dirSizeText(bytes)}"
    }

    /** 启动时静默执行一次日志清理（旧文件 + 容量裁剪），失败不扰启动。 */
    fun autoCleanLogsOnStartup() {
        runCatching { cleanOldLogs() }
            .onFailure { FileLogger.w(TAG, "启动自动清理日志失败", it) }
    }

    /**
     * 容量裁剪：logs+ai-logs 总量超过阈值时按 lastModified 升序删除，
     * 跳过各目录最新的一个文件（可能正在写）。返回（删除文件数，释放字节数）。
     */
    private fun trimByTotalSize(dirs: List<java.io.File>): Pair<Int, Long> {
        val all = dirs.flatMap { d -> d.listFiles().orEmpty().filter { it.isFile } }
        var total = all.sumOf { it.length() }
        if (total <= MAX_LOG_TOTAL_BYTES) return 0 to 0L
        var removed = 0
        var freed = 0L
        val newestPerDir = dirs.mapNotNull { d ->
            d.listFiles().orEmpty().filter { it.isFile }.maxByOrNull { f -> f.lastModified() }
        }
        for (f in all.sortedBy { it.lastModified() }) {
            if (total <= MAX_LOG_TOTAL_BYTES) break
            if (f in newestPerDir) continue
            val len = f.length()
            if (f.delete()) {
                total -= len
                freed += len
                removed++
            }
        }
        return removed to freed
    }

    private fun dirSizeText(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "${fmt(bytes / GB)} GB"
        bytes >= 1_000_000L -> "${bytes / 1_000_000} MB"
        else -> "$bytes B"
    }

    /** 容器层：发行版/工具链/引擎/占用/日志（CommandEngine，权限上等同读自家文件）。 */
    private suspend fun collectContainer(sb: StringBuilder) {
        sb.appendLine("## 容器（PRoot）")
        commandEngine.notReadyHint()?.let {
            sb.appendLine("- 状态：容器未就绪（$it），容器层信息跳过")
            sb.appendLine()
            return
        }
        suspend fun cmd(label: String, command: String, maxChars: Int = 400) {
            runCatching {
                val result = commandEngine.runCommandSyncWithExit(command, timeoutMs = 30_000L)
                val out = result.output.trim().take(maxChars).ifBlank { "（无输出，退出码 ${result.exitCode}）" }
                sb.appendLine("- $label：$out")
            }.onFailure {
                sb.appendLine("- $label：收集失败（${it.message}）")
            }
        }
        cmd("发行版", "head -n 2 /etc/os-release")
        cmd("node", "node --version 2>&1")
        cmd("java", "java -version 2>&1 | head -n 1")
        cmd("git", "git --version 2>&1")
        cmd("本地模型引擎", "test -f ~/.aicode/local-llm/.installed && echo llama.cpp 已安装 || echo 未安装")
        cmd("本地模型目录占用", "du -sh ~/.aicode/local-llm 2>/dev/null || echo 无")
        // 引擎安装判据用 CLI 存在性+版本号（配置目录各引擎不同：opencode 数据在 ~/.local/share/opencode，
        // 用 ~/.opencode 判存在性会误报未安装）；目录占用逐个存在才列出，不依赖整体退出码。
        cmd(
            "AI 引擎 CLI",
            "for c in claude codex opencode dsh; do p=\$(command -v \$c 2>/dev/null) && " +
                "v=\$(\$c --version 2>&1 | head -n 1) && echo \"\$c 已装：\$v\" || echo \"\$c 未安装\"; done"
        )
        cmd(
            "引擎目录占用",
            "for d in ~/.claude ~/.codex ~/.local/share/opencode ~/.dsh; do test -d \"\$d\" && du -sh \"\$d\"; done"
        )
        cmd("容器根目录占用", "du -sh /root/workspace 2>/dev/null || echo 无")
        cmd("provision 日志尾部", "tail -n 8 ~/.aicode/provision.log")
        sb.appendLine()
    }

    private fun dirSize(dir: java.io.File?): String {
        val f = dir ?: return "0 B"
        if (!f.exists()) return "0 B"
        val bytes = f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return when {
            bytes >= GB -> "${fmt(bytes / GB)} GB"
            bytes >= 1_000_000L -> "${bytes / 1_000_000} MB"
            else -> "$bytes B"
        }
    }

    private fun fmt(v: Long): String = String.format(java.util.Locale.CHINA, "%.1f", v.toDouble())
    private fun fmt(v: Double): String = String.format(java.util.Locale.CHINA, "%.1f", v)

    companion object {
        private const val TAG = "DiagnosticsCollector"
        private const val GB = 1_000_000_000L
        /** logs+ai-logs 总量超过此值时启动自动裁剪（曾出现 678MB 膨胀）。 */
        private const val MAX_LOG_TOTAL_BYTES = 200_000_000L
    }
}
