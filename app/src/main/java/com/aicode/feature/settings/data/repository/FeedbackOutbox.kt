package com.aicode.feature.settings.data.repository

import android.content.Context
import android.os.Build
import com.aicode.core.util.FileLogger
import com.aicode.feature.settings.domain.feedback.FeedbackPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 反馈本地发件箱：网络不可用/端点未配置时先落盘，绝不因提交失败丢内容。
 * 提交成功即删除对应文件；下次 App 启动或下次提交前自动重试。
 */
@Singleton
class FeedbackOutbox @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "FeedbackOutbox"
        const val DIR = "feedback-outbox"
        const val PREFS = "feedback_identity"
        const val KEY_INSTALL_ID = "install_id"
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun installId(): String {
        prefs.getString(KEY_INSTALL_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL_ID, id).apply()
        return id
    }

    fun deviceInfo(): DeviceInfo = DeviceInfo(
        device = Build.MODEL.orEmpty(),
        manufacturer = Build.MANUFACTURER.orEmpty(),
        android = Build.VERSION.RELEASE.orEmpty(),
        abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
    )

    data class DeviceInfo(val device: String, val manufacturer: String, val android: String, val abi: String)

    suspend fun enqueue(payload: FeedbackPayload): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val file = File(dir, "${System.currentTimeMillis()}-${UUID.randomUUID()}.json")
            file.writeText(json.encodeToString(payload))
            file
        }.onFailure { FileLogger.w(TAG, "反馈入队失败", it) }.getOrNull()
    }

    suspend fun pending(): List<File> = withContext(Dispatchers.IO) {
        runCatching {
            File(context.filesDir, DIR).listFiles()
                ?.filter { it.isFile && it.extension == "json" }
                ?.sortedBy { it.name }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    suspend fun read(file: File): FeedbackPayload? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString<FeedbackPayload>(file.readText()) }
            .onFailure { FileLogger.w(TAG, "读取待提交反馈失败: ${file.name}", it) }
            .getOrNull()
    }

    suspend fun delete(file: File) = withContext(Dispatchers.IO) {
        runCatching { file.delete() }
    }
}
