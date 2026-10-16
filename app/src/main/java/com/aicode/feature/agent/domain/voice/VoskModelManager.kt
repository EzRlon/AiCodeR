package com.aicode.feature.agent.domain.voice

import android.content.Context
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 离线语音模型（Vosk 中文小模型）的下载与安装管理。
 *
 * 模型不随 APK 分发：首次使用「离线识别」时按需下载（约 42MB）→ 解压到
 * `filesDir/vosk-model-cn/` → 校验结构（含 `am/` 目录）后标记就绪。
 *
 * 下载与解压全程在 IO 线程，状态经 [state] 流式上报（设置页显示进度）。失败可重试；
 * 安装采用「先解压临时目录、就绪校验通过后才替换正式目录」的顺序，失败不会破坏既有模型。
 * 两个涉及删除的目录均为 App 独占（filesDir 下自建），且 `java.util.zip` 解压不会把
 * zip 的符号链接条目还原为真实链接（落地为普通文件），删除无跟随链接的风险。
 */
@Singleton
class VoskModelManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    /** 模型安装状态。 */
    sealed interface ModelState {
        /** 未下载。 */
        data object Missing : ModelState

        /** 下载中：[percent] 0-100，-1 表示未知总大小。 */
        data class Downloading(val percent: Int) : ModelState

        /** 解压安装中。 */
        data object Installing : ModelState

        /** 就绪：[path] 为模型目录路径。 */
        data class Ready(val path: String) : ModelState

        /** 失败（可重试）。 */
        data object Failed : ModelState
    }

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<ModelState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 下载进行中门闩（startDownload 可能被快速重复触发）。 */
    @Volatile
    private var downloading = false

    /** 下载复用同一 client（连接池）；超时按大文件放宽。 */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private fun modelDir(): File = File(context.filesDir, MODEL_DIR_NAME)

    private fun initialState(): ModelState =
        if (isModelReady(context)) ModelState.Ready(modelDir().absolutePath) else ModelState.Missing

    /** 发起下载+安装（进行中/已就绪时忽略重复调用；失败后可再次调用重试）。 */
    fun startDownload() {
        if (downloading || _state.value is ModelState.Ready) return
        downloading = true
        scope.launch {
            val tmpZip = File(context.cacheDir, "vosk-model-cn-download.zip")
            val tmpDir = File(context.filesDir, MODEL_DIR_NAME + "-tmp")
            try {
                _state.value = ModelState.Downloading(-1)
                download(tmpZip)
                _state.value = ModelState.Installing
                extractTo(tmpZip, tmpDir)
                install(tmpDir)
                _state.value = ModelState.Ready(modelDir().absolutePath)
                FileLogger.i(TAG, "离线语音模型已就绪")
            } catch (e: Exception) {
                FileLogger.w(TAG, "离线语音模型安装失败", e)
                tmpDir.deleteRecursively()
                _state.value = ModelState.Failed
            } finally {
                downloading = false
                tmpZip.delete()
            }
        }
    }

    private fun download(dest: File) {
        httpClient.newCall(Request.Builder().url(MODEL_URL).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("empty body")
            val total = body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0) {
                            val percent = ((copied * 100) / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                _state.value = ModelState.Downloading(percent)
                            }
                        }
                        // 完整性兜底：Content-Length 已知时校验总字节数
                        if (total > 0 && copied != total) {
                            throw IOException("下载不完整：$copied / $total 字节")
                        }
                    }
                }
            }
        }
    }

    /** 解压 zip 到 [target]：剥掉压缩包顶层目录；条目路径做穿越防护；校验含 `am/`。 */
    private fun extractTo(zipFile: File, target: File) {
        target.deleteRecursively()
        target.mkdirs()
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zip ->
            var entry = zip.nextEntry
            var sawModelDir = false
            while (entry != null) {
                // "vosk-model-small-cn-0.22/am/xxx" → "am/xxx"；根级条目（无 /）忽略
                val stripped = entry.name.replace('\\', '/').substringAfter('/', "").trim('/')
                if (stripped.isNotEmpty() && ".." !in stripped.split('/')) {
                    val out = File(target, stripped)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { output -> zip.copyTo(output) }
                    }
                    if (stripped == "am" || stripped.startsWith("am/")) sawModelDir = true
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
            if (!sawModelDir) throw IOException("模型内容校验失败：缺少 am/ 目录")
        }
    }

    /** 就绪校验通过后用 [staged] 替换正式目录（先移旧、失败可回滚；在此之前旧模型保持可用）。 */
    private fun install(staged: File) {
        val dir = modelDir()
        val old = File(context.filesDir, MODEL_DIR_NAME + "-old")
        old.deleteRecursively()
        if (dir.exists()) dir.renameTo(old)
        if (!staged.renameTo(dir)) {
            // 回滚：把旧目录放回
            if (old.exists()) old.renameTo(dir)
            throw IOException("模型目录替换失败")
        }
        old.deleteRecursively()
    }

    companion object {
        private const val TAG = "VoskModel"

        /** 模型目录名（相对 filesDir）。 */
        const val MODEL_DIR_NAME = "vosk-model-cn"

        /** 模型是否已就绪（目录结构校验；设置页与离线会话共用）。 */
        fun isModelReady(context: Context): Boolean =
            File(File(context.filesDir, MODEL_DIR_NAME), "am").isDirectory

        /** 官方中文小模型（约 42MB）。 */
        const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"

        /** 展示用大小（MB）。 */
        const val MODEL_SIZE_MB = 42
    }
}
