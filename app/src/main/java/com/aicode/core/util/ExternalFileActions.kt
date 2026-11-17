package com.aicode.core.util

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.core.content.FileProvider
import com.aicode.R
import java.io.File

/**
 * 宿主系统级的文件操作：保存到共享存储（下载/AiCodeR）、用 MT 管理器打开、
 * 在文件管理器中查看、分享（FileProvider 授权 URI）。
 *
 * 「用 XX 打开 / 跳转」的产品语义 = 先由调用方把文件准备成宿主可访问的本地文件
 * （[prepareShareable] 处理 FileProvider 目录树之外的路径），再发起对应 Intent。
 */
object ExternalFileActions {

    /** MT 管理器包名。 */
    private const val MT_PACKAGE = "bin.mt.plus"

    /** 共享存储中的保存子目录（下载/AiCodeR）。 */
    private const val SAVE_DIR = "AiCodeR"

    private const val SHARE_CACHE_DIR = "sent_files"

    /** MT 管理器是否已安装。 */
    fun isMtInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(MT_PACKAGE, 0) }.isSuccess

    /** 文件是否在 FileProvider 声明的目录树内（可直接授权）；不能就抛异常，故按能否取到 URI 判定。 */
    fun isShareable(context: Context, file: File): Boolean = shareableUri(context, file) != null

    /**
     * 把无法直接授权的文件（外部本地目录工作区、自定义挂载源、SD 卡等）复制进 cacheDir 再分享。
     * 目标目录按源路径散列分隔：既避开同名文件互相覆盖，也不把散列值挂进分享出去的文件名。
     */
    fun copyToShareCache(context: Context, source: File): File? = runCatching {
        val dir = File(File(context.cacheDir, SHARE_CACHE_DIR), source.absolutePath.hashCode().toString())
        dir.mkdirs()
        val target = File(dir, source.name.ifBlank { "file" })
        source.copyTo(target, overwrite = true)
        target
    }.getOrNull()

    /** 取得可分享文件：能直接授权就原样返回，否则拷进 cache；都失败返回 null。 */
    fun prepareShareable(context: Context, file: File): File? =
        if (isShareable(context, file)) file else copyToShareCache(context, file)

    /** 取 FileProvider 授权 URI；失败返回 null。 */
    fun shareableUri(context: Context, file: File): Uri? =
        runCatching { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file) }.getOrNull()

    /** 用 MT 管理器打开（看/改文件；MT 打开后可看到其所在目录）。未安装或启动失败返回 false。 */
    fun openWithMt(context: Context, file: File, mimeType: String = "*/*"): Boolean {
        val host = ensureHostCopy(context, file) ?: return false
        val uri = prepareShareable(context, host)?.let { shareableUri(context, it) } ?: return false
        val type = mimeType.ifBlank { "*/*" }
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .setPackage(MT_PACKAGE)
        if (runCatching { context.startActivity(view) }.isSuccess) return true
        val send = Intent(Intent.ACTION_SEND)
            .setType(type)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .setPackage(MT_PACKAGE)
        return runCatching { context.startActivity(send) }.isSuccess
    }

    /**
     * 在文件管理器中查看：先把文件落到真机存储，再用系统文件管理器的文档 URI 直接定位到它所在的文件夹
     * （`content://com.android.externalstorage.documents/document/primary%3ADownload%2FAiCodeR`），
     * 跳过去就是真机存储页；文档 UI 不能处理时退回用文件自身的 MIME 打开。
     */
    fun viewInFileManager(context: Context, file: File, mimeType: String = "*/*") {
        val host = ensureHostCopy(context, file)
        if (host == null) {
            Toast.makeText(context, context.getString(R.string.file_action_failed), Toast.LENGTH_SHORT).show()
            return
        }
        host.parentFile?.let { dir ->
            documentUri(dir)?.let { dirUri ->
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(dirUri, DocumentsContract.Document.MIME_TYPE_DIR)
                if (runCatching { context.startActivity(intent) }.isSuccess) return
            }
        }
        val uri = prepareShareable(context, host)?.let { shareableUri(context, it) }
        if (uri != null) {
            val open = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeType.ifBlank { "*/*" })
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (runCatching { context.startActivity(open) }.isSuccess) return
        }
        Toast.makeText(context, context.getString(R.string.file_action_failed), Toast.LENGTH_SHORT).show()
    }

    /** 共享存储内路径的 DocumentsUI 文档 URI（primary 卷）；不在共享存储返回 null。 */
    private fun documentUri(file: File): Uri? = runCatching {
        val root = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        val path = file.absolutePath
        if (!path.startsWith("$root/")) return@runCatching null
        DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:" + path.substring(root.length + 1)
        )
    }.getOrNull()

    /** 文件是否已落在真机共享存储里（宿主文件管理器/MT 能直接看到）。 */
    private fun isInSharedStorage(file: File): Boolean {
        val root = runCatching { Environment.getExternalStorageDirectory().absolutePath }.getOrNull() ?: return false
        return file.absolutePath.startsWith(root.trimEnd('/') + "/")
    }

    /**
     * 容器内文件先落到真机 `下载/AiCodeR/`，返回宿主可见的那份副本（已在共享存储则原样返回）。
     * MT / 文件管理器只能看见宿主文件系统，把容器路径直接交给它们等于点了没反应。
     */
    fun ensureHostCopy(context: Context, file: File): File? =
        if (isInSharedStorage(file)) file else exportToSharedFile(context, file, file.name)

    /** 分享文件（系统分享面板）。 */
    fun shareFile(context: Context, file: File, mimeType: String = "*/*") {
        val uri = shareableUri(context, file)
        if (uri == null) {
            Toast.makeText(context, context.getString(R.string.file_action_failed), Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeType.ifBlank { "*/*" })
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching {
            context.startActivity(Intent.createChooser(send, context.getString(R.string.file_action_share)))
        }.onFailure {
            Toast.makeText(context, context.getString(R.string.file_action_failed), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 保存到手机共享存储 `下载/AiCodeR/`。targetSdk 28（legacy 外部存储）下直写，再经媒体扫描
     * 让文件管理器立即可见；同名文件自动加 `-2`、`-3` 后缀。成功返回展示路径，失败返回 null。
     * 展示路径用下载目录的实际物理名（dir.name，如 Download），与实际存放位置一致。
     */
    fun exportToShared(context: Context, file: File, displayName: String): String? = runCatching {
        val target = exportToSharedFile(context, file, displayName) ?: return@runCatching null
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
        "${sharedDir().name}/$SAVE_DIR/${target.name}"
    }.getOrNull()

    /** 共享存储里的导出目录：`下载/AiCodeR/`（真机存储，不在容器内）。 */
    fun sharedDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SAVE_DIR)

    /**
     * 把容器内的文件复制一份到 `下载/AiCodeR/`，返回真机上的副本 File。
     * 交付页要求文件真正落到真机存储（而不是容器映射），MT 管理器/文件管理器才能打开。
     * 同名且同大小视为已导出，直接返回现有副本，避免反复拷贝大文件。
     */
    fun exportToSharedFile(context: Context, file: File, displayName: String): File? = runCatching {
        val dir = sharedDir()
        dir.mkdirs()
        val existing = findExported(displayName.ifBlank { file.name.ifBlank { "file" } })
        if (existing != null && existing.length() == file.length()) return@runCatching existing
        val target = uniqueFile(dir, displayName.ifBlank { file.name.ifBlank { "file" } })
        file.copyTo(target, overwrite = false)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
        target
    }.getOrNull()

    /** 在导出目录里找同名副本（含 `-2`/`-3` 去重后缀），取最新一份；没有则 null。 */
    /**
     * 找出 `下载/AiCodeR` 下与 [displayName] 同名的**全部**变体（唯一名会追加 -1/-2 后缀）。
     * 彻底删除交付物时用：只删最新一份会留下历史副本继续占空间。
     */
    fun findExportedAll(displayName: String): List<File> = runCatching {
        val dir = sharedDir()
        if (!dir.isDirectory) return@runCatching emptyList()
        val stem = displayName.substringBeforeLast('.', displayName)
        val ext = if (displayName.contains('.')) "." + displayName.substringAfterLast('.') else ""
        dir.listFiles { f -> f.isFile && (f.name == displayName || f.name.startsWith("$stem-")) }
            ?.filter { ext.isBlank() || it.name.endsWith(ext) }
            ?.toList()
            .orEmpty()
    }.getOrDefault(emptyList())

    fun findExported(displayName: String): File? = runCatching {
        val dir = sharedDir()
        if (!dir.isDirectory) return@runCatching null
        val stem = displayName.substringBeforeLast('.', displayName)
        val ext = if (displayName.contains('.')) "." + displayName.substringAfterLast('.') else ""
        dir.listFiles { f -> f.isFile && (f.name == displayName || f.name.startsWith("$stem-")) }
            ?.filter { ext.isBlank() || it.name.endsWith(ext) }
            ?.maxByOrNull { it.lastModified() }
    }.getOrNull()

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val stem = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var index = 2
        while (candidate.exists()) {
            candidate = File(dir, "$stem-$index$ext")
            index++
        }
        return candidate
    }
}
