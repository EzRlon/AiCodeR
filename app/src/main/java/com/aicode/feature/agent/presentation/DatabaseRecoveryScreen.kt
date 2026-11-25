package com.aicode.feature.agent.presentation

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Environment
import android.os.Process
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicode.feature.agent.data.local.database.AgentDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DatabaseBootScreen(darkTheme: Boolean) {
    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
fun DatabaseRecoveryScreen(
    error: String,
    database: AgentDatabase,
    darkTheme: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dbFile = remember { context.getDatabasePath(AgentDatabase.DATABASE_NAME) }
    var rawVersion by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        rawVersion = withContext(Dispatchers.IO) { readRawVersion(dbFile) }
    }
    val appVersion = remember { appVersionLabel(context) }
    var backupPath by remember { mutableStateOf<String?>(null) }
    var backupError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("数据库无法打开", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                val schema = AgentDatabase.SCHEMA_VERSION
                val rawInt = rawVersion?.toIntOrNull()
                Text(
                    when {
                        rawVersion == null -> "正在读取手机上的数据库版本…"
                        rawInt == null -> "无法读取手机上的数据库版本，当前 App 数据库版本为 $schema。"
                        rawInt > schema ->
                            "手机上的数据版本（$rawVersion）高于当前 App 的数据库版本（$schema）。" +
                                "通常是用旧版本覆盖了新版本。应用已停止继续写入，避免数据被破坏。"
                        rawInt < schema ->
                            "手机上的数据版本（$rawVersion）低于当前 App 的数据库版本（$schema），" +
                                "本应自动升级；如果停在这里，说明升级迁移失败。"
                        else ->
                            "手机上的数据版本（$rawVersion）与当前 App 的数据库版本（$schema）一致，" +
                                "但仍无法打开；请先备份坏库再重置。"
                    }
                )
                Text("App 版本：$appVersion", style = MaterialTheme.typography.bodySmall)
                Text("数据库文件：${dbFile.absolutePath}", style = MaterialTheme.typography.bodySmall)
                Text("错误：$error", style = MaterialTheme.typography.bodySmall)

                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            backupPath = withContext(Dispatchers.IO) { backupDatabase(context, dbFile, error) }
                            backupError = if (backupPath == null) "备份失败：请检查存储权限/空间，或先不要重置。" else null
                            busy = false
                        }
                    },
                    enabled = !busy
                ) {
                    Text(if (busy) "备份中…" else "备份数据库和日志")
                }
                backupPath?.let {
                    Text("已备份到：$it", style = MaterialTheme.typography.bodySmall)
                }
                backupError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                OutlinedButton(onClick = { confirmReset = true }, enabled = !busy) {
                    Text("重置数据库（保留坏库）")
                }
                Text(
                    "重置会先把坏库改名为 .broken-时间保留，再用空数据库启动；供应商 key 等数据需要从备份或坏库恢复。",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = { Process.killProcess(Process.myPid()) }) {
                    Text("退出")
                }
            }
        }

        if (confirmReset) {
            AlertDialog(
                onDismissRequest = { confirmReset = false },
                title = { Text("确认重置数据库？") },
                text = { Text("会先把当前数据库保留为 .broken 文件，然后清空启动。供应商 key 等数据需要从备份或坏库恢复。") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmReset = false
                        scope.launch {
                            busy = true
                            val moved = withContext(Dispatchers.IO) { resetDatabase(database, dbFile) }
                            busy = false
                            if (!moved) backupError = "重置失败：坏库无法改名，未删除任何文件。"
                        }
                    }) { Text("确认重置") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmReset = false }) { Text("取消") }
                }
            )
        }
    }
}

private fun readRawVersion(dbFile: File): String {
    return runCatching {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.version.toString()
        } finally {
            db.close()
        }
    }.getOrNull() ?: "无法读取"
}

private fun appVersionLabel(context: Context): String {
    return runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
        "${info.versionName ?: "?"} ($code)"
    }.getOrNull() ?: "未知"
}

@Suppress("DEPRECATION")
private fun backupDatabase(context: Context, dbFile: File, error: String): String? {
    return runCatching {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val sources = listOf(
            dbFile,
            File(dbFile.path + "-wal"),
            File(dbFile.path + "-shm")
        ).filter { it.exists() }
        val external = File(context.getExternalFilesDir(null), "recovery/AiCodeR-db-$stamp").apply { mkdirs() }
        sources.forEach { it.copyTo(File(external, it.name), overwrite = true) }
        File(external, "error.txt").writeText(
            "$error\n\n数据库版本：${readRawVersion(dbFile)}\nApp：${appVersionLabel(context)}"
        )

        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val publicDir = File(downloads, "AiCodeR-recovery-$stamp")
        val publicOk = runCatching { publicDir.mkdirs() || publicDir.isDirectory }.getOrDefault(false)
        if (publicOk) {
            sources.forEach { it.copyTo(File(publicDir, it.name), overwrite = true) }
            File(external, "error.txt").copyTo(File(publicDir, "error.txt"), overwrite = true)
            publicDir.absolutePath
        } else {
            external.absolutePath
        }
    }.getOrNull()
}

private fun resetDatabase(database: AgentDatabase, dbFile: File): Boolean {
    runCatching { database.close() }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    var ok = true
    listOf(
        dbFile,
        File(dbFile.path + "-wal"),
        File(dbFile.path + "-shm")
    ).forEach { file ->
        if (file.exists()) {
            val target = File(file.parentFile, file.name + ".broken-$stamp")
            if (!runCatching { file.renameTo(target) }.getOrDefault(false)) ok = false
        }
    }
    if (ok) Process.killProcess(Process.myPid())
    return ok
}
