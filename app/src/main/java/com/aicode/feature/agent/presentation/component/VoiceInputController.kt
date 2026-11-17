package com.aicode.feature.agent.presentation.component

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 系统语音识别实现（微信式「按住说话」用）：基于系统 [SpeechRecognizer]（谷歌或厂商语音服务），不跳出 App。
 *
 * 生命周期约定（均在主线程调用）：
 * - [start] 开始识别；[finish] 停止并等待最终结果（[onResult] 异步到达）；[cancel] 直接取消；
 * - [destroy] 释放识别器（组合退出时调用）；识别器按需创建、复用。
 *
 * 需要 RECORD_AUDIO 权限（调用前由 UI 层检查/请求）；未授权或设备无识别服务时
 * [start] 会立即回调 [onError]（[SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS] 等）。
 */
internal class VoiceInputController(private val context: Context) : VoiceSession {

    override var onResult: ((String) -> Unit)? = null
    override var onError: ((Int) -> Unit)? = null

    private var recognizer: SpeechRecognizer? = null

    override fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    override fun start() {
        if (!isAvailable()) {
            onError?.invoke(VoiceSession.ERROR_UNAVAILABLE)
            return
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(listener)
            }
        }
        recognizer?.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // 不指定 EXTRA_LANGUAGE：交给系统识别器按系统语言处理
            }
        )
    }

    /** 停止收音：触发最终结果回调（[onResult] 异步到达）。 */
    override fun finish() {
        recognizer?.stopListening()
    }

    /** 取消本次识别（上滑取消）：不产生任何结果回调。 */
    override fun cancel() {
        recognizer?.cancel()
    }

    override fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            onResult?.invoke(text)
        }

        override fun onError(error: Int) {
            onError?.invoke(error)
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
