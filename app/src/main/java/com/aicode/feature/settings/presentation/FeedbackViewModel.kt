package com.aicode.feature.settings.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.feature.settings.data.repository.FeedbackOutbox
import com.aicode.feature.settings.data.repository.FeedbackSubmitter
import com.aicode.feature.settings.domain.feedback.FeedbackDraft
import com.aicode.feature.settings.domain.feedback.FeedbackPayload
import com.aicode.feature.settings.domain.feedback.FeedbackReportBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 反馈页动作：本地构建匿名载荷 → 自动提交到项目收集页；失败进入本地发件箱。
 * 不打开浏览器、不要求 GitHub 登录。
 */
@HiltViewModel
class FeedbackViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val submitter: FeedbackSubmitter,
    private val outbox: FeedbackOutbox
) : ViewModel() {

    data class UiState(
        val submitting: Boolean = false,
        /** 一次性提示（已提交 / 已入队）；界面 Toast 后调用 [consumeMessage]。 */
        val message: String? = null,
        /** 本地待提交条数，给用户一个「没有丢」的可见反馈。 */
        val pending: Int = 0
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { submitter.flushOutbox() }
            refreshPending()
        }
    }

    fun submit(draft: FeedbackDraft) {
        if (_state.value.submitting) return
        _state.update { it.copy(submitting = true) }
        viewModelScope.launch {
            val info = outbox.deviceInfo()
            // Issue 正文跟随当前界面语言：英文用户收到的 Issue 不该是中文正文（review P2）。
            val english = context.resources.configuration.locales[0].language != "zh"
            val payload = FeedbackPayload(
                installId = outbox.installId(),
                kind = draft.kind.name,
                title = FeedbackReportBuilder.buildTitle(draft, english),
                body = FeedbackReportBuilder.composeBody(draft, english),
                appVersion = appVersion(),
                device = info.device,
                manufacturer = info.manufacturer,
                android = info.android,
                abi = info.abi,
                createdAt = System.currentTimeMillis()
            )
            val result = submitter.submit(payload)
            _state.update {
                it.copy(
                    submitting = false,
                    message = context.getString(
                        if (result == FeedbackSubmitter.Result.SUBMITTED) {
                            R.string.settings_feedback_submitted
                        } else {
                            R.string.settings_feedback_queued
                        }
                    )
                )
            }
            refreshPending()
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    private suspend fun refreshPending() {
        val pending = runCatching { outbox.pending().size }.getOrDefault(0)
        _state.update { it.copy(pending = pending) }
    }

    private fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")
}
