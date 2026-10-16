package com.aicode.core.util

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import com.aicode.R
import java.io.File

/**
 * 宿主系统级的文件操作：保存到共享存储（下载/AiCode）、用 MT 管理器打开、
 * 在文件管理器中查看、分享（FileProvider 授权 URI）。
 *
 * 「用 XX 打开 / 跳转」的产品语义 = 先由调用方把文件准备成宿主可访问的本地文件
 * （[prepareShareable] 处理 FileProvider 目录树之外的路径），再发起对应 Intent。
 */
object ExternalFileActions {

    /** MT 管理器包名。 */
    private const val MT_PACKAGE = "bin.mt.plus"

    /** 共享存储中的保存子目录（下载/AiCode）。 */
    private const val SAVE_DIR = "AiCode"

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
    fun openWithMt(context: Context, file: File): Boolean {
        val uri = shareableUri(context, file) ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .setPackage(MT_PACKAGE)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** 在文件管理器中查看：先试打开所在目录（resource/folder），失败回退打开文件本身。 */
    fun viewInFileManager(context: Context, file: File) {
        val parent = file.parentFile
        if (parent != null) {
            val folderUri = shareableUri(context, parent)
            if (folderUri != null) {
                val folderIntent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(folderUri, "resource/folder")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (runCatching { context.startActivity(folderIntent) }.isSuccess) return
            }
        }
        val uri = shareableUri(context, file) ?: return
        val fallback = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(fallback) }.onFailure {
            Toast.makeText(context, context.getString(R.string.file_action_failed), Toast.LENGTH_SHORT).show()
        }
    }

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
     * 保存到手机共享存储 `下载/AiCode/`。targetSdk 28（legacy 外部存储）下直写，再经媒体扫描
     * 让文件管理器立即可见；同名文件自动加 `-2`、`-3` 后缀。成功返回展示路径，失败返回 null。
     * 展示路径用下载目录的实际物理名（dir.name，如 Download），与实际存放位置一致。
     */
    fun exportToShared(context: Context, file: File, displayName: String): String? = runCatching {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SAVE_DIR)
        dir.mkdirs()
        val target = uniqueFile(dir, displayName.ifBlank { file.name.ifBlank { "file" } })
        file.copyTo(target, overwrite = false)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
        "${dir.name}/$SAVE_DIR/${target.name}"
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
