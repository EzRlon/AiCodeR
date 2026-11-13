package com.aicode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Transaction
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: AgentMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<AgentMessageEntity>)

    @Query("SELECT * FROM agent_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesBySession(sessionId: String): Flow<List<AgentMessageEntity>>

    @Query("SELECT * FROM (SELECT * FROM agent_messages WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT :limit) ORDER BY timestamp ASC")
    fun getMessagesBySessionPaged(sessionId: String, limit: Int): Flow<List<AgentMessageEntity>>

    /** 一次性读取（非 Flow），用于跨请求重建上下文历史。 */
    @Query("SELECT * FROM agent_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesBySessionOnce(sessionId: String): List<AgentMessageEntity>

    @Query("DELETE FROM agent_messages WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("UPDATE agent_messages SET isCompacted = 1, compactedBySummaryId = :summaryId WHERE sessionId = :sessionId AND id IN (:headIds) AND isContextExcluded = 0")
    suspend fun markMessagesCompactedByIds(sessionId: String, headIds: List<String>, summaryId: String)

    @Query("UPDATE chat_sessions SET lastInputTokens = 0 WHERE id = :sessionId")
    suspend fun resetLastInputTokens(sessionId: String)

    @Transaction
    suspend fun commitCompaction(
        sessionId: String,
        headIds: List<String>,
        messages: List<AgentMessageEntity>,
        summaryId: String
    ) {
        headIds.chunked(900).forEach { ids ->
            markMessagesCompactedByIds(sessionId, ids, summaryId)
        }
        insertAll(messages)
        resetLastInputTokens(sessionId)
    }

    @Query("DELETE FROM agent_messages WHERE sessionId = :sessionId AND (isCompactionMarker = 1 OR isContextSummary = 1)")
    suspend fun deleteCompactionMessages(sessionId: String)

    @Query("UPDATE agent_messages SET isCompacted = 0, compactedBySummaryId = NULL WHERE sessionId = :sessionId AND timestamp < :cutoff AND isCompacted = 1 AND isContextExcluded = 0 AND isContextSummary = 0 AND isCompactionMarker = 0")
    suspend fun restoreCompactedMessagesBefore(sessionId: String, cutoff: Long)

    @Transaction
    suspend fun rewindConversation(sessionId: String, cutoff: Long) {
        deleteCompactionMessages(sessionId)
        restoreCompactedMessagesBefore(sessionId, cutoff)
        deleteMessagesFromTimestamp(sessionId, cutoff)
        resetLastInputTokens(sessionId)
    }

    @Query("DELETE FROM agent_messages")
    suspend fun deleteAllMessages()

    @Query("SELECT * FROM agent_messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: String): AgentMessageEntity?

    /** 会话是否已有任何消息（LIMIT 1 快速判断，避免全量读取）。 */
    @Query("SELECT EXISTS(SELECT 1 FROM agent_messages WHERE sessionId = :sessionId LIMIT 1)")
    suspend fun hasMessages(sessionId: String): Boolean

    @Query("UPDATE agent_messages SET content = :content WHERE id = :id")
    suspend fun updateMessageContent(id: String, content: String)

    @Query("DELETE FROM agent_messages WHERE id = :id")
    suspend fun deleteMessageById(id: String)

    @Query("DELETE FROM agent_messages WHERE sessionId = :sessionId AND timestamp >= :cutoffTimestamp")
    suspend fun deleteMessagesFromTimestamp(sessionId: String, cutoffTimestamp: Long)

    @Query("DELETE FROM agent_messages WHERE sessionId = :sessionId AND timestamp > :cutoffTimestamp")
    suspend fun deleteMessagesAfterTimestamp(sessionId: String, cutoffTimestamp: Long)

    /**
     * 把残留的「执行中」工具行（content 以占位标记开头）批量收尾为「已中断」。
     * 用于冷启动：上次进程被杀时正在执行的工具不可能仍在跑，否则其占位行会永久显示转圈。
     * 返回受影响的行数。
     */
    @Query("UPDATE agent_messages SET content = :interruptedContent, isError = 1 WHERE role = :toolRole AND content LIKE :pendingPrefix")
    suspend fun markPendingToolsInterrupted(
        toolRole: String,
        pendingPrefix: String,
        interruptedContent: String
    ): Int

    @Query("SELECT * FROM agent_messages WHERE content LIKE '%' || :query || '%' ORDER BY timestamp ASC")
    suspend fun searchMessages(query: String): List<AgentMessageEntity>

    /**
     * recall 全文检索（FTS4）：从消息档案取回历史片段——含被上下文压缩归档的原文（只标 isCompacted 未删）。
     * [match] 为 FTS4 MATCH 表达式，由调用方把检索词转成带引号的安全形式。
     */
    @Query(
        """
        SELECT m.id AS messageId, m.sessionId AS sessionId, s.title AS sessionTitle,
               m.role AS role, m.content AS content, m.timestamp AS timestamp
        FROM agent_messages_fts
        JOIN agent_messages m ON m.rowid = agent_messages_fts.docid
        JOIN chat_sessions s ON s.id = m.sessionId
        WHERE agent_messages_fts MATCH :match
          AND m.isContextSummary = 0 AND m.isCompactionMarker = 0
          AND s.deletedAt IS NULL
        ORDER BY m.timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun recallSearch(match: String, limit: Int): List<ChatSearchMatch>

    @Query(
        """
        SELECT * FROM (
            SELECT m.id AS messageId, m.sessionId AS sessionId, s.title AS sessionTitle,
                   m.role AS role, m.content AS content, m.timestamp AS timestamp
            FROM agent_messages_fts
            JOIN agent_messages m ON m.rowid = agent_messages_fts.docid
            JOIN chat_sessions s ON s.id = m.sessionId
            WHERE agent_messages_fts MATCH :match
              AND m.isContextSummary = 0 AND m.isCompactionMarker = 0
              AND s.deletedAt IS NULL
        ) WHERE sessionId = :sessionId
        ORDER BY timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun recallSearchInSession(match: String, sessionId: String, limit: Int): List<ChatSearchMatch>

    @RewriteQueriesToDropUnusedColumns
    @Query(
        """
        SELECT * FROM (
            SELECT m.id AS messageId, m.sessionId AS sessionId, s.title AS sessionTitle,
                   m.role AS role, m.content AS content, m.timestamp AS timestamp,
                   s.workspacePath AS workspacePath
            FROM agent_messages_fts
            JOIN agent_messages m ON m.rowid = agent_messages_fts.docid
            JOIN chat_sessions s ON s.id = m.sessionId
            WHERE agent_messages_fts MATCH :match
              AND m.isContextSummary = 0 AND m.isCompactionMarker = 0
              AND s.deletedAt IS NULL
        ) WHERE workspacePath = :workspacePath
        ORDER BY timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun recallSearchInWorkspace(match: String, workspacePath: String, limit: Int): List<ChatSearchMatch>

    /**
     * recall 的 CJK 降级路径：FTS4 simple tokenizer 把连续汉字切成单个整体 token，中文子串
     * （如「崩溃」查「发生崩溃了」）不可能命中，含中文的查询只能走 LIKE 模糊匹配。
     * [likeQuery] 已由调用方转义 LIKE 通配符（! % _），配合 ESCAPE '!'；排除压缩摘要/标记行
     * （recall 语义是取回原文，不是取回 AI 生成的摘要）。
     */
    @Query(
        """
        SELECT m.id AS messageId, m.sessionId AS sessionId, s.title AS sessionTitle,
               m.role AS role, m.content AS content, m.timestamp AS timestamp
        FROM agent_messages m
        JOIN chat_sessions s ON s.id = m.sessionId
        WHERE m.isContextSummary = 0 AND m.isCompactionMarker = 0
          AND s.deletedAt IS NULL
          AND m.content LIKE '%' || :likeQuery || '%' ESCAPE '!' 
        ORDER BY m.timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun recallLikeAll(likeQuery: String, limit: Int): List<ChatSearchMatch>

    @Query(
        """
        SELECT m.id AS messageId, m.sessionId AS sessionId, s.title AS sessionTitle,
               m.role AS role, m.content AS content, m.timestamp AS timestamp
        FROM agent_messages m
        JOIN chat_sessions s ON s.id = m.sessionId
        WHERE m.sessionId = :sessionId
          AND m.isContextSummary = 0 AND m.isCompactionMarker = 0
          AND s.deletedAt IS NULL
          AND m.content LIKE '%' || :likeQuery || '%' ESCAPE '!' 
        ORDER BY m.timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun recallLikeInSession(likeQuery: String, sessionId: String, limit: Int): List<ChatSearchMatch>

    @Query(
        """
        SELECT m.id AS messageId, m.sessionId AS sessionId, s.title AS sessionTitle,
               m.role AS role, m.content AS content, m.timestamp AS timestamp
        FROM agent_messages m
        JOIN chat_sessions s ON s.id = m.sessionId
        WHERE s.workspacePath = :workspacePath
          AND m.isContextSummary = 0 AND m.isCompactionMarker = 0
          AND s.deletedAt IS NULL
          AND m.content LIKE '%' || :likeQuery || '%' ESCAPE '!' 
        ORDER BY m.timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun recallLikeInWorkspace(likeQuery: String, workspacePath: String, limit: Int): List<ChatSearchMatch>

    /**
     * 跨会话搜索某工作区下的聊天记录：命中用户 / 助手正文，排除已压缩与内部摘要行。
     * [escapedQuery] 已由调用方转义 LIKE 通配符（`!` `%` `_`），配合 SQL 里的 ESCAPE '!'。
     */
    @Query(
        """
        SELECT m.id AS messageId,
               m.sessionId AS sessionId,
               s.title AS sessionTitle,
               m.role AS role,
               m.content AS content,
               m.timestamp AS timestamp
        FROM agent_messages m
        JOIN chat_sessions s ON s.id = m.sessionId
        WHERE s.workspacePath = :workspacePath
          AND s.deletedAt IS NULL
          AND m.isCompacted = 0
          AND m.isContextSummary = 0
          AND m.isCompactionMarker = 0
          AND m.role IN ('USER', 'ASSISTANT')
          AND m.content LIKE '%' || :escapedQuery || '%' ESCAPE '!'
        ORDER BY m.timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun searchInWorkspace(workspacePath: String, escapedQuery: String, limit: Int): List<ChatSearchMatch>

    /** 指定会话中时间戳不早于 [timestamp] 的消息条数（含并列时间戳），供定位时确定所需分页上限。 */
    @Query("SELECT COUNT(*) FROM agent_messages WHERE sessionId = :sessionId AND timestamp >= :timestamp")
    suspend fun countMessagesFromTimestamp(sessionId: String, timestamp: Long): Int

    /** 记忆图谱：各会话的消息数与最后活动时间（一次聚合全表，IO 线程调用）。 */
    @Query(
        """
        SELECT m.sessionId AS sessionId, COUNT(*) AS messageCount, MAX(m.timestamp) AS lastActiveAt
        FROM agent_messages m JOIN chat_sessions s ON s.id = m.sessionId
        WHERE s.deletedAt IS NULL
        GROUP BY m.sessionId
        """
    )
    suspend fun sessionActivity(): List<SessionActivityRow>

    /** 记忆图谱详情卡：指定会话最后一条消息正文（无则 null）。 */
    @Query("SELECT content FROM agent_messages WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT 1")
    suspend fun lastMessageContent(sessionId: String): String?

    /** 定时任务结果摘要：指定会话最后一条有内容的 assistant 正文（无则 null）——避免全量载入会话消息取末条。 */
    @Query("SELECT content FROM agent_messages WHERE sessionId = :sessionId AND role = 'ASSISTANT' AND content != '' ORDER BY timestamp DESC LIMIT 1")
    suspend fun lastAssistantContent(sessionId: String): String?

    @Query("SELECT * FROM agent_messages ORDER BY timestamp ASC")
    suspend fun getAllOnce(): List<AgentMessageEntity>

    /** 分页读取（keyset：按 timestamp,id 字典序取 [limit] 条），供备份流式导出。 */
    @Query("SELECT * FROM agent_messages WHERE timestamp > :lastTimestamp OR (timestamp = :lastTimestamp AND id > :lastId) ORDER BY timestamp ASC, id ASC LIMIT :limit")
    suspend fun getPageAfter(lastTimestamp: Long, lastId: String, limit: Int): List<AgentMessageEntity>

    /** 按会话分页读取（keyset），供单会话备份流式导出。 */
    @Query("SELECT * FROM agent_messages WHERE sessionId = :sessionId AND (timestamp > :lastTimestamp OR (timestamp = :lastTimestamp AND id > :lastId)) ORDER BY timestamp ASC, id ASC LIMIT :limit")
    suspend fun getPageBySessionAfter(sessionId: String, lastTimestamp: Long, lastId: String, limit: Int): List<AgentMessageEntity>

    /**
     * 各会话消息正文占用的字节数（降序取前 [limit] 个），供存储空间页拆解「聊天记录」构成。
     *
     * `LENGTH(CAST(x AS BLOB))` 取的是 UTF-8 字节数——直接 `LENGTH(x)` 对文本返回字符数，中文会少算三分之二。
     * 只统计几个大字段，因此是估算值：不含索引、页对齐与 WAL 开销，必然小于数据库文件本身。
     */
    @Query(
        """
        SELECT m.sessionId AS sessionId,
               s.title AS title,
               COUNT(*) AS messageCount,
               SUM(
                   LENGTH(CAST(m.content AS BLOB))
                   + LENGTH(CAST(IFNULL(m.toolCallsJson, '') AS BLOB))
                   + LENGTH(CAST(IFNULL(m.toolArgs, '') AS BLOB))
                   + LENGTH(CAST(IFNULL(m.reasoning, '') AS BLOB))
                   + LENGTH(CAST(IFNULL(m.thinkingBlocksJson, '') AS BLOB))
                   + LENGTH(CAST(IFNULL(m.attachmentsJson, '') AS BLOB))
               ) AS bytes
        FROM agent_messages m
        LEFT JOIN chat_sessions s ON s.id = m.sessionId
        WHERE s.deletedAt IS NULL
        GROUP BY m.sessionId
        ORDER BY bytes DESC
        LIMIT :limit
        """
    )
    suspend fun sessionStorageUsage(limit: Int): List<SessionStorageUsage>

    /**
     * 交付物页数据源：AI 产出的附件（USER 行是用户上传，不算交付物），按当前工作区过滤
     * （附件的容器路径总是按当前工作区解析，跨工作区行会指错文件）。
     * 只投影解析附件所需的三列，避免把 content/reasoning 等大字段读进内存。
     */
    @Query(
        "SELECT m.role AS role, m.timestamp AS timestamp, m.attachmentsJson AS attachmentsJson, " +
            "m.id AS messageId " +
            "FROM agent_messages m JOIN chat_sessions s ON s.id = m.sessionId " +
            "WHERE s.workspacePath = :workspacePath AND s.deletedAt IS NULL AND m.role != 'USER' " +
            "AND m.attachmentsJson IS NOT NULL AND m.attachmentsJson != '[]' " +
            "ORDER BY m.timestamp DESC LIMIT 300"
    )
    suspend fun messagesWithAttachments(workspacePath: String): List<AttachmentMessageRow>

    /** 交付物删除后回写该行剩余附件（空列表写 null，让查询条件直接排除该行）。 */
    @Query("UPDATE agent_messages SET attachmentsJson = :attachmentsJson WHERE id = :id")
    suspend fun updateAttachmentsJson(id: String, attachmentsJson: String?)

    /** 按 id 读取附件列（删除交付物时先取原值再过滤，避免整行读出大字段）。 */
    @Query("SELECT attachmentsJson FROM agent_messages WHERE id = :id")
    suspend fun attachmentsJsonOf(id: String): String?
}

/** 交付物页的消息附件投影。 */
data class AttachmentMessageRow(
    val role: String,
    val timestamp: Long,
    val attachmentsJson: String?,
    /** 所属消息 id：删除交付物时用于回写该行的附件列表。 */
    val messageId: String
)

/** 跨会话聊天记录搜索的命中投影（[AgentMessageDao.searchInWorkspace] 的投影）。 */
data class ChatSearchMatch(
    val messageId: String,
    val sessionId: String,
    val sessionTitle: String,
    val role: String,
    val content: String,
    val timestamp: Long
)

/** 单个会话的消息占用估算（[AgentMessageDao.sessionStorageUsage] 的投影）。 */
data class SessionStorageUsage(
    val sessionId: String,
    val title: String?,
    val messageCount: Int,
    val bytes: Long
)

/** 记忆图谱的会话活动聚合（[AgentMessageDao.sessionActivity] 的投影）。 */
data class SessionActivityRow(
    val sessionId: String,
    val messageCount: Int,
    val lastActiveAt: Long
)
