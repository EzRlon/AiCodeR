package com.aicode.feature.workspace.presentation.remote

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.settings.SettingsDivider
import com.aicode.core.ui.settings.SettingsGroup
import com.aicode.core.ui.settings.SettingsGroupHeader
import com.aicode.core.ui.settings.SettingsRow
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Folder
import compose.icons.feathericons.Globe
import compose.icons.feathericons.Info
import compose.icons.feathericons.Server
import kotlinx.coroutines.launch

/**
 * 远程管理页：局域网 HTTP 服务的开关、访问地址与状态提示；
 * 网页端（assets/remote/index.html）由电脑浏览器打开，管理范围为手机外部存储根。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteManagerScreen(
    onNavigateBack: () -> Unit,
    viewModel: RemoteManagerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val manager = viewModel.httpServerManager
    val isRunning by manager.isRunning.collectAsStateWithLifecycle()
    val serverUrl by manager.serverUrl.collectAsStateWithLifecycle()
    val errorMessage by manager.errorMessage.collectAsStateWithLifecycle()

    var storageGranted by remember { mutableStateOf(hasStoragePermission(context)) }
    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        storageGranted = hasStoragePermission(context)
        if (storageGranted) {
            scope.launch { manager.toggleServer() }
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.remote_mgr_permission_needed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.remote_mgr_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            FeatherIcons.ArrowLeft,
                            contentDescription = stringResource(R.string.common_back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            SettingsGroupHeader(text = stringResource(R.string.remote_mgr_usage_title))
            SettingsGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = 11.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        FeatherIcons.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp).padding(top = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(Spacing.md))
                    Column {
                        Text(
                            text = "• " + stringResource(R.string.remote_mgr_usage_item_1),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "• " + stringResource(R.string.remote_mgr_usage_item_2),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Text(
                            text = "• " + stringResource(R.string.remote_mgr_usage_item_3),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Text(
                            text = "• " + stringResource(R.string.remote_mgr_no_auth_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            SettingsGroupHeader(text = stringResource(R.string.remote_mgr_title))
            SettingsGroup {
                SettingsRow(
                    icon = FeatherIcons.Server,
                    title = stringResource(R.string.remote_mgr_title),
                    subtitle = if (isRunning) {
                        stringResource(R.string.remote_mgr_running, serverUrl)
                    } else {
                        stringResource(R.string.remote_mgr_not_running)
                    },
                    trailing = {
                        AppSwitch(
                            checked = isRunning,
                            onCheckedChange = { wantOn ->
                                when {
                                    !wantOn -> scope.launch { manager.toggleServer() }
                                    !storageGranted -> storageLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.READ_EXTERNAL_STORAGE,
                                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                                        )
                                    )
                                    else -> scope.launch { manager.toggleServer() }
                                }
                            }
                        )
                    }
                )
                if (isRunning) {
                    SettingsDivider()
                    SettingsRow(
                        icon = FeatherIcons.Globe,
                        title = serverUrl,
                        subtitle = stringResource(R.string.remote_mgr_copy_address),
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("remote_manager", serverUrl)))
                            }
                            Toast.makeText(
                                context,
                                context.getString(R.string.remote_mgr_address_copied),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Folder,
                    title = stringResource(R.string.remote_mgr_scope_note, manager.rootPath)
                )
                errorMessage?.let { error ->
                    SettingsDivider()
                    SettingsRow(
                        icon = FeatherIcons.AlertCircle,
                        title = stringResource(R.string.remote_mgr_error, error)
                    )
                }
            }
        }
    }
}

// 与设置页 checkStorageGranted 同语义（任一权限即可进入；写能力缺失时由服务端错误兜底）。
private fun hasStoragePermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED
