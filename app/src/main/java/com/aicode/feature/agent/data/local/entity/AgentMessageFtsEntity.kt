package com.aicode.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Fts4

/**
 * agent_messages 的 FTS4 全文检索影子表（外部内容表）：上下文压缩只标 isCompacted 不删原文，
 * 本表让 recall 工具能跨压缩取回历史消息原文。索引数据由迁移 67 建立的触发器与原表保持同步。
 */
@Fts4(contentEntity = AgentMessageEntity::class)
@Entity(tableName = "agent_messages_fts")
data class AgentMessageFtsEntity(
    val content: String
)
