package com.aicode.feature.agent.domain.model

/**
 * 会话消息角色。领域与数据层的权威定义；UI 层经 [com.aicode.feature.agent.presentation.MessageRole]
 * 的 typealias 引用（保持既有 import 不变）。
 */
enum class MessageRole {
    USER, ASSISTANT, TOOL
}
