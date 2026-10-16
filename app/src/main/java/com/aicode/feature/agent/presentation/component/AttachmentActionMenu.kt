package com.aicode.feature.agent.presentation.component

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.core.content.ContextCompat
import com.aicode.R
import com.aicode.core.ui.AnchorMenuEntry
import com.aicode.core.util.ExternalFileActions
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.workspace.domain.FileAccessProvider
import compose.icons.FeatherIcons
import compose.icons.feathericons.Archive
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Download
import compose.icons.feathericons.ExternalLink
import compose.icons.feathericons.Folder
import compose.icons.feathericons.Share2
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 构建附件操作菜单条目（聊天文件卡片 / 测试包页共用）：
 * 打开或安装（APK）、用 MT 管理器打开（已安装时）、在文件管理器中查看、
 * 保存到手机（下载/AiCodeR）、分享、复制路径。
 *
 * 动作内自行解析本地文件（失效路径经 [resolveLocalFile] 重取），需要宿主可见的
 * 动作自动经 [ExternalFileActions.prepareShareable] 走缓存兜底。
 */
@Composable
internal fun attachmentActionEntries(
    context: Context,
    attachment: AgentAttachment,
    fileAccess: FileAccessProvider,
): List<AnchorMenuEntry> {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    // 组合期缓存：PackageManager 查询只在该菜单这次组合里执行一次，不随父级重组重复查询
    // （菜单打开期间聊天可能仍在流式刷新）；重开菜单会重新组合、重新查询。
    val mtInstalled = remember(context) { ExternalFileActions.isMtInstalled(context) }
    return buildList {
        // 打开 / 安装（APK 走安装引导，其它走系统打开——见 openSentAttachment）
        add(
            AnchorMenuEntry(
                icon = FeatherIcons.ExternalLink,
                labelRes = if (isApkName(attachment.fileName, attachment.mimeType)) {
                    R.string.file_action_install
                } else {
                    R.string.file_action_open
                }
            ) {
                scope.launch { openSentAttachment(context, attachment, fileAccess) }
            }
        )
        // 用 MT 管理器打开（仅在其已安装时出现）
        if (mtInstalled) {
            add(
                AnchorMenuEntry(icon = FeatherIcons.Archive, labelRes = R.string.file_action_mt) {
                    scope.launch {
                        val local = prepareLocal(context, attachment, fileAccess) ?: return@launch
                        if (!ExternalFileActions.openWithMt(context, local)) {
                            Toast.makeText(context, context.getString(R.string.file_action_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }
        // 在文件管理器中查看
        add(
            AnchorMenuEntry(icon = FeatherIcons.Folder, labelRes = R.string.file_action_filemanager) {
                scope.launch {
                    val local = prepareLocal(context, attachment, fileAccess) ?: return@launch
                    ExternalFileActions.viewInFileManager(context, local)
                }
            }
        )
        // 保存到手机（下载/AiCodeR）
        add(
            AnchorMenuEntry(icon = FeatherIcons.Download, labelRes = R.string.file_action_save) {
                scope.launch {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED
                    ) {
                        Toast.makeText(context, context.getString(R.string.file_action_need_storage), Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val local = withContext(Dispatchers.IO) { resolveLocalFile(attachment, fileAccess) }
                    if (local == null) {
                        Toast.makeText(context, context.getString(R.string.chat_open_file_missing), Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val saved = withContext(Dispatchers.IO) {
                        ExternalFileActions.exportToShared(context, local, attachment.fileName)
                    }
                    if (saved != null) {
                        Toast.makeText(context, context.getString(R.string.file_action_saved, saved), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, context.getString(R.string.file_action_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
        // 分享
        add(
            AnchorMenuEntry(icon = FeatherIcons.Share2, labelRes = R.string.file_action_share) {
                scope.launch {
                    val local = prepareLocal(context, attachment, fileAccess) ?: return@launch
                    ExternalFileActions.shareFile(context, local, attachment.mimeType)
                }
            }
        )
        // 复制路径（容器路径，可直接粘给 AI/终端）
        add(
            AnchorMenuEntry(icon = FeatherIcons.Copy, labelRes = R.string.file_action_copy_path) {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("path", attachment.containerPath)))
                    Toast.makeText(context, context.getString(R.string.file_action_path_copied), Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

/** 解析本地文件并准备为可分享副本；任一步失败给提示并返回 null。 */
private suspend fun prepareLocal(
    context: Context,
    attachment: AgentAttachment,
    fileAccess: FileAccessProvider,
): File? {
    val local = withContext(Dispatchers.IO) { resolveLocalFile(attachment, fileAccess) }
    if (local == null) {
        Toast.makeText(context, context.getString(R.string.chat_open_file_missing), Toast.LENGTH_SHORT).show()
        return null
    }
    val shareable = withContext(Dispatchers.IO) { ExternalFileActions.prepareShareable(context, local) }
    if (shareable == null) {
        Toast.makeText(context, context.getString(R.string.chat_open_file_unshareable), Toast.LENGTH_SHORT).show()
        return null
    }
    return shareable
}
