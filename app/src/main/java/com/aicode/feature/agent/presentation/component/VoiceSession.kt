package com.aicode.feature.agent.presentation.component

import android.content.Context
import com.aicode.feature.settings.data.repository.VoiceEngine

/**
 * 语音识别会话的统一接口：系统识别（[VoiceInputController]）与离线识别（[OfflineVoiceSession]）
 * 两个实现共用同一套「按住说话」交互（start → finish / cancel）。
 *
 * 回调均在主线程触发：成功 [onResult] 给最终文本；[onPartial] 在识别过程中持续给出
 * 中间文本（可能为空串，UI 用它驱动实时文字气泡）；失败 [onError] 给错误码。
 */
internal interface VoiceSession {
    var onResult: ((String) -> Unit)?
    var onPartial: ((String) -> Unit)?
    var onError: ((Int) -> Unit)?

    /** 当前引擎是否可用（系统：设备有识别服务；离线：模型已就绪）。 */
    fun isAvailable(): Boolean

    /** 开始识别；不可用时立即回调 [onError]。 */
    fun start()

    /** 结束收音并等待最终结果（异步到达 [onResult]）。 */
    fun finish()

    /** 取消本次识别（不产生结果回调）。 */
    fun cancel()

    /** 释放资源（组合退出 / 引擎切换时调用）。 */
    fun destroy()

    companion object {
        /** 设备/系统无可用语音识别服务（INFO 之外的本地错误码，与系统错误码不冲突）。 */
        const val ERROR_UNAVAILABLE = -1000

        /** 离线引擎的模型尚未就绪（未下载或安装失败）。 */
        const val ERROR_MODEL_MISSING = -1001

        /** 离线引擎运行中出现内部错误（音频设备 / 模型异常）。 */
        const val ERROR_ENGINE_FAILED = -1002

        /** 按引擎创建会话实例。 */
        fun create(context: Context, engine: VoiceEngine): VoiceSession = when (engine) {
            VoiceEngine.SYSTEM -> VoiceInputController(context)
            VoiceEngine.OFFLINE -> OfflineVoiceSession(context)
        }
    }
}
