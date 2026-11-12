package com.aicode.feature.workspace.domain.remote.http

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.core.util.SafeDelete
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 远程管理的 HTTP 服务端（NanoHTTPD）。
 *
 * 提供内嵌网页（assets/remote/index.html）与文件管理 API：
 * `/api/list`、`/api/download`、`/api/upload`、`/api/delete`、`/api/rename`、`/api/mkdir`。
 * 仅面向局域网；每次启动由 [RemoteHttpServerManager] 生成随机 [token]，所有请求必须携带
 * （网页地址 `?t=<token>` / `X-Auth-Token` 头），防止同网段任意设备直连接口操作 `/sdcard`。
 * 所有路径操作经 [resolve] 限制在 [root] 子树内，删除走 [SafeDelete]（不跟随符号链接）。
 */
internal class RemoteHttpServer(
    private val context: Context,
    port: Int,
    private val root: File,
    private val token: String
) : NanoHTTPD(port) {

    private companion object {
        const val TAG = "RemoteHttpServer"
        const val PAGE_ASSET = "remote/index.html"
        const val JSON_MIME = "application/json; charset=utf-8"
        const val MAX_JSON_BODY = 1024 * 1024L

        /** 写接口防 CSRF：跨站页面无法携带自定义头（no-cors 简单请求受限），同源网页端自行带上。 */
        const val CSRF_HEADER = "x-requested-with"
        const val CSRF_VALUE = "AiCodeRemote"

        /** 访问令牌的查询参数名与请求头名。 */
        const val TOKEN_PARAM = "t"
        const val TOKEN_HEADER = "x-auth-token"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: ""
        return try {
            // 鉴权：网页与全部 API 都要求令牌（查询参数或请求头），防同网段任意设备直连。
            // 首页不带令牌时返回提示页，其余接口返回 403 JSON。
            if (!isAuthorized(session)) {
                return if (session.method == Method.GET && (uri == "/" || uri == "/index.html")) {
                    tokenHintPage()
                } else {
                    jsonError(Response.Status.FORBIDDEN, "访问令牌缺失或不匹配，请从 App 显示的完整地址打开")
                }
            }
            // 写接口要求自定义头，防跨站请求（CSRF）。
            if (session.method == Method.POST && session.headers[CSRF_HEADER] != CSRF_VALUE) {
                return jsonError(Response.Status.FORBIDDEN, "缺少写操作校验头，已拒绝（防止跨站请求）")
            }
            when {
                session.method == Method.GET && (uri == "/" || uri == "/index.html") -> serveIndexPage()
                session.method == Method.GET && uri == "/api/list" -> serveList(session)
                session.method == Method.GET && uri == "/api/download" -> serveDownload(session)
                session.method == Method.POST && uri == "/api/upload" -> serveUpload(session)
                session.method == Method.POST && uri == "/api/delete" -> serveDelete(session)
                session.method == Method.POST && uri == "/api/rename" -> serveRename(session)
                session.method == Method.POST && uri == "/api/mkdir" -> serveMkdir(session)
                else -> jsonError(Response.Status.NOT_FOUND, "未知接口")
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "处理请求失败 [$uri]: ${e.message}", e)
            jsonError(Response.Status.INTERNAL_ERROR, e.message ?: "内部错误")
        }
    }

    private fun serveIndexPage(): Response = try {
        val html = context.assets.open(PAGE_ASSET).use { it.readBytes() }
        newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            html.inputStream(),
            html.size.toLong()
        )
    } catch (e: Exception) {
        FileLogger.e(TAG, "读取内嵌网页失败: ${e.message}", e)
        jsonError(Response.Status.INTERNAL_ERROR, "内嵌网页资源缺失")
    }

    private fun serveList(session: IHTTPSession): Response {
        val target = resolve(session.param("path")) ?: return pathNotFound()
        if (!target.isDirectory) return jsonError(Response.Status.BAD_REQUEST, "不是目录")
        val children = target.listFiles()
            ?: return jsonError(Response.Status.INTERNAL_ERROR, "无法读取目录（可能缺少存储权限）")
        val entries = children
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            .map { f ->
                JSONObject().apply {
                    put("name", f.name)
                    put("dir", f.isDirectory)
                    put("size", if (f.isDirectory) -1L else f.length())
                    put("mtime", f.lastModified())
                }
            }
        return jsonOk(
            JSONObject().apply {
                put("path", pathOf(target))
                put("entries", JSONArray(entries))
            }
        )
    }

    private fun serveDownload(session: IHTTPSession): Response {
        val target = resolve(session.param("path")) ?: return pathNotFound()
        if (!target.isFile) return jsonError(Response.Status.NOT_FOUND, "文件不存在")
        val stream = FileInputStream(target)
        val response = newFixedLengthResponse(
            Response.Status.OK,
            guessMimeType(target.name),
            stream,
            target.length()
        )
        val encoded = URLEncoder.encode(target.name, "UTF-8").replace("+", "%20")
        response.addHeader("Content-Disposition", "attachment; filename*=UTF-8''$encoded")
        return response
    }

    private fun serveUpload(session: IHTTPSession): Response {
        val dir = resolve(session.param("dir")) ?: return pathNotFound()
        if (!dir.isDirectory) return jsonError(Response.Status.BAD_REQUEST, "目标目录不存在")
        val rawName = session.param("name").ifBlank {
            runCatching {
                URLDecoder.decode(session.headers["x-file-name"].orEmpty(), "UTF-8")
            }.getOrDefault("")
        }
        val name = sanitizeName(rawName)
            ?: return jsonError(Response.Status.BAD_REQUEST, "文件名不合法")
        val contentLength = session.headers["content-length"]?.toLongOrNull()
            ?: return jsonError(Response.Status.BAD_REQUEST, "缺少 Content-Length，请通过网页上传")
        if (contentLength < 0) return jsonError(Response.Status.BAD_REQUEST, "Content-Length 不合法")

        val file = uniqueFile(dir, name)
        val copied = session.inputStream.use { input ->
            file.outputStream().use { output ->
                copyBounded(input, output, contentLength)
            }
        }
        if (copied < contentLength) {
            file.delete()
            return jsonError(Response.Status.BAD_REQUEST, "上传数据不完整（连接中断），已清理残件")
        }
        return jsonOk(
            JSONObject().apply {
                put("ok", true)
                put("name", file.name)
                put("size", file.length())
            }
        )
    }

    private fun serveDelete(session: IHTTPSession): Response {
        val body = readJsonBody(session) ?: return jsonError(Response.Status.BAD_REQUEST, "请求体不是合法 JSON")
        val target = resolve(body.optString("path")) ?: return pathNotFound()
        if (pathOf(target) == "/") return jsonError(Response.Status.BAD_REQUEST, "不能删除根目录")
        val ok = SafeDelete.deleteTreeOrFalse(target)
        return if (ok) {
            jsonOk(JSONObject().apply { put("ok", true) })
        } else {
            jsonError(Response.Status.INTERNAL_ERROR, "删除失败（可能没有权限）")
        }
    }

    private fun serveRename(session: IHTTPSession): Response {
        val body = readJsonBody(session) ?: return jsonError(Response.Status.BAD_REQUEST, "请求体不是合法 JSON")
        val target = resolve(body.optString("path")) ?: return pathNotFound()
        if (pathOf(target) == "/") return jsonError(Response.Status.BAD_REQUEST, "不能重命名根目录")
        val newName = sanitizeName(body.optString("newName"))
            ?: return jsonError(Response.Status.BAD_REQUEST, "新名称不合法")
        val dest = File(target.parentFile, newName)
        if (dest.exists()) return jsonError(Response.Status.CONFLICT, "已存在同名项")
        if (!target.renameTo(dest)) return jsonError(Response.Status.INTERNAL_ERROR, "重命名失败")
        return jsonOk(
            JSONObject().apply {
                put("ok", true)
                put("name", newName)
            }
        )
    }

    private fun serveMkdir(session: IHTTPSession): Response {
        val body = readJsonBody(session) ?: return jsonError(Response.Status.BAD_REQUEST, "请求体不是合法 JSON")
        val target = resolve(body.optString("path")) ?: return pathNotFound()
        if (target.exists()) return jsonError(Response.Status.CONFLICT, "已存在同名项")
        if (!target.mkdirs()) return jsonError(Response.Status.INTERNAL_ERROR, "创建文件夹失败")
        return jsonOk(JSONObject().apply { put("ok", true) })
    }

    // ── 工具 ──

    /** 校验请求携带的令牌：查询参数 `?t=` 或 `X-Auth-Token` 头任一匹配即通过（常量时间比较）。 */
    private fun isAuthorized(session: IHTTPSession): Boolean {
        val provided = session.parameters[TOKEN_PARAM]?.firstOrNull()
            ?: session.headers[TOKEN_HEADER]
            ?: return false
        return MessageDigest.isEqual(
            provided.toByteArray(StandardCharsets.UTF_8),
            token.toByteArray(StandardCharsets.UTF_8)
        )
    }

    /** 未带令牌访问首页时的提示页（不泄露任何文件信息）。 */
    private fun tokenHintPage(): Response {
        val html = """
            <!DOCTYPE html><html lang="zh-CN"><head><meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>需要访问令牌</title></head>
            <body style="font:14px/1.6 sans-serif;display:flex;align-items:center;justify-content:center;min-height:80vh;margin:0">
            <div style="max-width:420px;padding:24px;text-align:center">
              <p><b>缺少访问令牌</b></p>
              <p>请从 App「远程管理」页面显示的<b>完整地址</b>（含 ?t= 参数）打开本页面。</p>
            </div></body></html>
        """.trimIndent()
        return newFixedLengthResponse(
            Response.Status.FORBIDDEN,
            "text/html; charset=utf-8",
            html
        )
    }

    private fun IHTTPSession.param(name: String): String =
        parameters[name]?.firstOrNull().orEmpty()

    private fun pathNotFound(): Response = jsonError(Response.Status.NOT_FOUND, "路径不存在或无权访问")

    private fun jsonOk(body: JSONObject): Response =
        newFixedLengthResponse(Response.Status.OK, JSON_MIME, body.toString())

    private fun jsonError(status: Response.Status, message: String): Response =
        newFixedLengthResponse(
            status,
            JSON_MIME,
            JSONObject().apply {
                put("ok", false)
                put("error", message)
            }.toString()
        )

    /** 把请求里的路径解析为 [root] 内的文件：经过规范化校验，越出子树或非法时返回 null。 */
    private fun resolve(requested: String?): File? {
        if (requested == null || requested.contains('\u0000')) return null
        return try {
            val rel = requested.trim().trim('/')
            val f = File(root, rel).canonicalFile
            val rootCanonical = root.canonicalFile
            if (f.path == rootCanonical.path || f.path.startsWith(rootCanonical.path + File.separator)) {
                f
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 文件相对根目录的展示路径（以 / 开头；根目录为 "/"）。 */
    private fun pathOf(file: File): String {
        val rel = runCatching {
            file.canonicalFile.path.removePrefix(root.canonicalFile.path)
        }.getOrDefault("")
        return if (rel.isEmpty()) "/" else rel
    }

    private fun sanitizeName(raw: String): String? {
        val name = raw.trim()
        if (name.isEmpty() || name == "." || name == "..") return null
        if (name.any { it == '/' || it == '\\' || it == '\u0000' }) return null
        return name
    }

    /** 目标已存在时自动追加 " (n)" 后缀，避免静默覆盖用户文件。 */
    private fun uniqueFile(dir: File, name: String): File {
        val f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = File(dir, "$base ($i)$ext")
            if (!candidate.exists()) return candidate
            i++
        }
    }

    private fun readJsonBody(session: IHTTPSession): JSONObject? {
        val len = session.headers["content-length"]?.toLongOrNull() ?: return null
        if (len <= 0 || len > MAX_JSON_BODY) return null
        return runCatching {
            JSONObject(String(readFully(session.inputStream, len), StandardCharsets.UTF_8))
        }.getOrNull()
    }

    private fun readFully(input: InputStream, length: Long): ByteArray {
        val out = ByteArrayOutputStream(minOf(length, 64 * 1024L).toInt())
        val buf = ByteArray(16 * 1024)
        var remaining = length
        while (remaining > 0) {
            val read = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (read <= 0) break
            out.write(buf, 0, read)
            remaining -= read
        }
        return out.toByteArray()
    }

    private fun copyBounded(input: InputStream, output: OutputStream, length: Long): Long {
        val buf = ByteArray(64 * 1024)
        var remaining = length
        while (remaining > 0) {
            val read = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (read <= 0) break
            output.write(buf, 0, read)
            remaining -= read
        }
        return length - remaining
    }

    private fun guessMimeType(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "txt", "md", "log" -> "text/plain; charset=utf-8"
            "json" -> JSON_MIME
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "pdf" -> "application/pdf"
            "apk" -> "application/vnd.android.package-archive"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
}
