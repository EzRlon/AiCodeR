package com.aicode.feature.agent.presentation.component

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.voice.VoskModelManager
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * 离线语音识别会话（Vosk 中文小模型）：[AudioRecord] 16kHz 单声道 PCM16 采集 →
 * 流式喂 [Recognizer]；[finish] 时取最终文本。模型按需懒加载并缓存复用（destroy 时释放）。
 *
 * 模型未就绪时 [isAvailable] 为 false、[start] 立即回调 [VoiceSession.ERROR_MODEL_MISSING]，
 * 由 UI 引导用户到设置中下载（见 [VoskModelManager]）。需要 RECORD_AUDIO 权限（UI 层先行检查）。
 */
internal class OfflineVoiceSession(private val context: Context) : VoiceSession {

    override var onResult: ((String) -> Unit)? = null
    override var onError: ((Int) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var record: AudioRecord? = null

    @Volatile
    private var running = false

    override fun isAvailable(): Boolean = VoskModelManager.isModelReady(context)

    override fun start() {
        if (running) return
        if (!isAvailable()) {
            onError?.invoke(VoiceSession.ERROR_MODEL_MISSING)
            return
        }
        running = true
        worker = scope.launch {
            try {
                val rec = recognizer ?: createRecognizer()
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                // 至少 1 秒缓冲（16000 采样 × 2 字节），避免采集线程被读阻塞拖慢
                val bufferBytes = maxOf(minBuffer, SAMPLE_RATE * 2)
                val audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes
                )
                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    audioRecord.release()
                    throw IllegalStateException("AudioRecord 初始化失败")
                }
                record = audioRecord
                audioRecord.startRecording()
                val shorts = ShortArray(2048)
                val bytes = ByteArray(shorts.size * 2)
                while (running) {
                    val read = audioRecord.read(shorts, 0, shorts.size)
                    if (read > 0) {
                        var j = 0
                        for (i in 0 until read) {
                            val v = shorts[i].toInt()
                            bytes[j++] = (v and 0xFF).toByte()
                            bytes[j++] = ((v shr 8) and 0xFF).toByte()
                        }
                        rec.acceptWaveForm(bytes, j)
                    }
                }
                audioRecord.runCatching { stop() }
            } catch (e: CancellationException) {
                // 取消/销毁路径：不当作错误上报
                throw e
            } catch (e: Exception) {
                FileLogger.w(TAG, "离线识别运行失败", e)
                running = false
                recognizer?.runCatching { reset() }
                withContext(Dispatchers.Main) { onError?.invoke(VoiceSession.ERROR_ENGINE_FAILED) }
            }
        }
    }

    override fun finish() {
        if (!running) return
        running = false
        // 先打断可能阻塞在 read 上的采集循环，join 才能尽快返回
        record?.runCatching { stop() }
        scope.launch {
            worker?.join()
            record?.runCatching { release() }
            record = null
            val active = recognizer ?: return@launch  // 销毁竞争：静默退出（不产生回调）
            val text = runCatching {
                JSONObject(active.finalResult).optString("text").trim()
            }.getOrDefault("")
            active.reset()
            withContext(Dispatchers.Main) { onResult?.invoke(text) }
        }
    }

    override fun cancel() {
        if (!running) return
        running = false
        record?.runCatching { stop() }
        scope.launch {
            worker?.join()
            record?.runCatching { release() }
            record = null
            recognizer?.reset()
        }
    }

    override fun destroy() {
        running = false
        // 先摘引用并打断可能阻塞的 read，再让清理协程等采集循环退出后释放——
        // 避免「释放 recognizer/model 时采集协程仍在调用它们」的竞态。
        val rec = record
        val recognizerToClose = recognizer
        val modelToClose = model
        record = null
        recognizer = null
        model = null
        rec?.runCatching { stop() }
        scope.launch {
            runCatching { worker?.cancelAndJoin() }
            runCatching { rec?.release() }
            runCatching { recognizerToClose?.close() }
            runCatching { modelToClose?.close() }
            scope.cancel()
        }
    }

    private fun createRecognizer(): Recognizer {
        val path = File(context.filesDir, VoskModelManager.MODEL_DIR_NAME).absolutePath
        val loaded = model ?: Model(path).also { model = it }
        return Recognizer(loaded, SAMPLE_RATE.toFloat()).also { recognizer = it }
    }

    private companion object {
        const val TAG = "OfflineVoice"
        const val SAMPLE_RATE = 16000
    }
}
