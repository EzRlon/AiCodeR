package com.aicode.feature.agent.presentation

import android.content.Context
import com.aicode.R
import com.aicode.feature.agent.domain.engine.AgentEngines
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.engine.ClaudeCodeBridge
import com.aicode.feature.agent.domain.engine.CodexBridge
import com.aicode.feature.agent.domain.engine.OpenCodeBridge
import com.aicode.feature.agent.domain.workflow.AgentEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 引擎分流：按会话所用引擎选择事件流来源。
 *
 * - Claude Code / Codex / OpenCode：先探测可用性与认证状态，异常时产出单条
 *   [AgentEvent.Failed]；正常则交给对应 CLI 桥接。
 * - 其他（内置）：回落到调用方传入的原生工作流 [nativeFlow]。
 *
 * 从 AIAgentViewModel.executeAgentRequestStream（655 行）中抽出的纯选择逻辑——
 * 不读写 VM 状态，输入齐备即可决定事件流，便于单测与后续扩展新引擎。
 */
@Singleton
class EngineTurnRouter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val claudeCodeBridge: ClaudeCodeBridge,
    private val codexBridge: CodexBridge,
    private val openCodeBridge: OpenCodeBridge
) {
    suspend fun buildEventFlow(
        engine: String?,
        sessionId: String,
        userRequest: String,
        /** 容器视角工作区路径；null 表示不指定（CLI 用默认工作区）。 */
        projectPath: String?,
        model: String?,
        providerId: String?,
        engineMode: String?,
        images: List<AgentImage>,
        attachments: List<AgentAttachment>,
        nativeFlow: () -> Flow<AgentEvent>
    ): Flow<AgentEvent> = when (engine) {
        AgentEngines.CLAUDE_CODE -> {
            val bridge = claudeCodeBridge
            if (!bridge.isAvailable()) {
                failed(R.string.engine_bridge_unavailable)
            } else {
                // 发送前认证预检：未登录/无可透传 key 时直接给引导，不空跑一回合才发现
                val authHint = bridge.authHint(providerId)
                if (authHint != null) {
                    failedMessage(authHint)
                } else {
                    bridge.runTurn(
                        sessionId = sessionId,
                        userRequest = userRequest,
                        projectPath = projectPath,
                        model = model,
                        resume = true,
                        providerId = providerId,
                        engineMode = engineMode,
                        images = images,
                        attachments = attachments
                    )
                }
            }
        }

        AgentEngines.CODEX -> {
            val bridge = codexBridge
            if (!bridge.isAvailable()) {
                failed(R.string.engine_codex_unavailable)
            } else {
                val authHint = bridge.authHint(providerId)
                if (authHint != null) {
                    failedMessage(authHint)
                } else {
                    bridge.runTurn(
                        sessionId = sessionId,
                        userRequest = userRequest,
                        projectPath = projectPath,
                        model = model,
                        resume = true,
                        providerId = providerId,
                        engineMode = engineMode,
                        images = images,
                        attachments = attachments
                    )
                }
            }
        }

        AgentEngines.OPENCODE -> {
            val bridge = openCodeBridge
            if (!bridge.isAvailable()) {
                failed(R.string.engine_opencode_unavailable)
            } else {
                val authHint = bridge.authHint(providerId)
                if (authHint != null) {
                    failedMessage(authHint)
                } else {
                    bridge.runTurn(
                        sessionId = sessionId,
                        userRequest = userRequest,
                        projectPath = projectPath,
                        model = model,
                        resume = true,
                        providerId = providerId,
                        engineMode = engineMode,
                        images = images,
                        attachments = attachments
                    )
                }
            }
        }

        else -> nativeFlow()
    }

    private fun failed(stringRes: Int): Flow<AgentEvent> = flow {
        emit(AgentEvent.Failed(error = context.getString(stringRes)))
    }

    private fun failedMessage(message: String): Flow<AgentEvent> = flow {
        emit(AgentEvent.Failed(error = message))
    }
}
