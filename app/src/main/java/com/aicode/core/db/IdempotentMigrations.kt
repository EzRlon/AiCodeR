package com.aicode.core.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aicode.core.util.FileLogger

/**
 * 幂等版 61→62（替代 `62_add_annotations.sql` 执行）。
 *
 * 背景：本项目曾有一批「中间开发版」使用旧编号——60=annotations、61=session_team；
 * 上游合并后编号重排为 62=annotations、63=session_team（官方 60=checkpoint 指纹、61=连接排序）。
 * 曾装过中间版的设备 Room 认为 60、61 已执行，官方 60/61 的两列（fingerprint / sort_order）
 * 会缺失——在这里按列存在性补齐；对正常设备（官方 60/61 已跑过）这两步是 no-op。
 * 最终 schema 与 `app/schemas/.../63.json` 一致。
 */
internal class Migration61To62 : Migration(61, 62) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `annotations` (" +
                "`id` TEXT NOT NULL, `type` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                "`imagePath` TEXT NOT NULL, `marksJson` TEXT NOT NULL, `note` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        addColumnIfMissing(
            db, "checkpoint_file_snapshots", "fingerprint",
            "ALTER TABLE checkpoint_file_snapshots ADD COLUMN fingerprint TEXT"
        )
        addColumnIfMissing(
            db, "remote_connections", "sort_order",
            "ALTER TABLE remote_connections ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0"
        )
    }
}

/**
 * 幂等版 62→63（替代 `63_add_session_team.sql` 执行）：按列存在性补 teamName。
 * 旧编号 61（session_team）已跑过的设备在此为 no-op；正常设备执行一次 ALTER。
 */
internal class Migration62To63 : Migration(62, 63) {
    override fun migrate(db: SupportSQLiteDatabase) {
        addColumnIfMissing(
            db, "chat_sessions", "teamName",
            "ALTER TABLE chat_sessions ADD COLUMN teamName TEXT"
        )
    }
}

/**
 * 幂等版 63→64（替代 `64_add_message_intent_type.sql` 执行）：按列存在性补 intentType。
 */
internal class Migration63To64 : Migration(63, 64) {
    override fun migrate(db: SupportSQLiteDatabase) {
        addColumnIfMissing(
            db, "agent_messages", "intentType",
            "ALTER TABLE agent_messages ADD COLUMN intentType TEXT"
        )
    }
}

/**
 * 幂等版 64→65（替代 `65_add_permission_audit.sql` 执行）：建授权记录表。
 * 纯新建表，天然可重复执行。
 */
internal class Migration64To65 : Migration(64, 65) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS permission_audit (" +
                "id TEXT PRIMARY KEY NOT NULL, " +
                "timestamp INTEGER NOT NULL, " +
                "sessionId TEXT NOT NULL, " +
                "toolName TEXT NOT NULL, " +
                "summary TEXT NOT NULL, " +
                "decision TEXT NOT NULL, " +
                "source TEXT NOT NULL, " +
                "reason TEXT)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_permission_audit_timestamp ON permission_audit(timestamp)"
        )
    }
}

/**
 * 幂等版 66→67（替代 `67_add_message_fts.sql` 执行）：建 agent_messages 的 FTS4 影子表。
 * 不能用 SQL 文件轨：SqlScriptSplitter 不识别 CREATE TRIGGER 的 BEGIN…END 块，触发器体内的分号
 * 会被当语句边界拆断，覆盖升级首启必崩。execSQL 逐条执行不受影响。
 * 表结构与触发器名、DDL 与编译时 Room 导出的 schemas/67.json 完全一致（触发器是 Room 对
 * contentEntity FTS 表的官方同步触发器，只在全新建库时由 Room 创建，旧库迁移必须显式补建）。
 */
internal class Migration66To67 : Migration(66, 67) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS `agent_messages_fts` USING FTS4(`content` TEXT NOT NULL, content=`agent_messages`)"
        )
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_agent_messages_fts_BEFORE_UPDATE BEFORE UPDATE ON `agent_messages` BEGIN DELETE FROM `agent_messages_fts` WHERE `docid`=OLD.`rowid`; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_agent_messages_fts_BEFORE_DELETE BEFORE DELETE ON `agent_messages` BEGIN DELETE FROM `agent_messages_fts` WHERE `docid`=OLD.`rowid`; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_agent_messages_fts_AFTER_UPDATE AFTER UPDATE ON `agent_messages` BEGIN INSERT INTO `agent_messages_fts`(`docid`, `content`) VALUES (NEW.`rowid`, NEW.`content`); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_agent_messages_fts_AFTER_INSERT AFTER INSERT ON `agent_messages` BEGIN INSERT INTO `agent_messages_fts`(`docid`, `content`) VALUES (NEW.`rowid`, NEW.`content`); END")
        // 存量回填不放迁移内：rebuild 全量 tokenize 大库秒级、迁移在主线程会黑屏——
        // 由 AIEditorApp 启动后在后台协程幂等执行（FTS 行数 < 消息行数即 rebuild）
    }
}

/**
 * 幂等版 67→68（替代 `68_add_session_trash.sql` 执行）：chat_sessions 加回收站标记列。
 * 可空，null = 正常会话；非 null = 入回收站的时间戳（30 天后由彻底清除流程处理）。
 */
internal class Migration67To68 : Migration(67, 68) {
    override fun migrate(db: SupportSQLiteDatabase) {
        addColumnIfMissing(
            db,
            "chat_sessions",
            "deletedAt",
            "ALTER TABLE `chat_sessions` ADD COLUMN `deletedAt` INTEGER"
        )
    }
}

/**
 * 幂等版 68→69（替代 `69_add_fix_ledger.sql` 执行）：新建修复账本表。
 * CREATE TABLE IF NOT EXISTS 本身幂等；列集与 Room 导出的 69.json 一致。
 */
internal class Migration68To69 : Migration(68, 69) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `fix_ledger` (" +
                "`id` TEXT NOT NULL, `title` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                "`note` TEXT NOT NULL, `sourceAnnotationId` TEXT, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
    }
}

/**
 * 幂等版 69→70（替代 `70_add_session_engine.sql` 执行）：chat_sessions 加会话级引擎列。
 * 可空，null = 本体内置工作流；"claude_code" = CLI 桥接（引擎切换一期）。
 */
internal class Migration69To70 : Migration(69, 70) {
    override fun migrate(db: SupportSQLiteDatabase) {
        addColumnIfMissing(
            db,
            "chat_sessions",
            "engine",
            "ALTER TABLE `chat_sessions` ADD COLUMN `engine` TEXT"
        )
    }
}

/**
 * 幂等版 70→71（替代 `71_add_memory_graph.sql` 执行）：记忆图谱 v2 节点/边两张新表。
 * CREATE TABLE IF NOT EXISTS + CREATE INDEX IF NOT EXISTS 本身幂等；列集与 Room 导出的 71.json 一致。
 */
internal class Migration70To71 : Migration(70, 71) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `graph_nodes` (" +
                "`id` TEXT NOT NULL, `workspacePath` TEXT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                "`summary` TEXT NOT NULL, `sourceRefId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`) )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_graph_nodes_workspacePath` ON `graph_nodes` (`workspacePath`)" )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_graph_nodes_workspacePath_type_sourceRefId` ON `graph_nodes` (`workspacePath`, `type`, `sourceRefId`)" )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `graph_edges` (" +
                "`id` TEXT NOT NULL, `workspacePath` TEXT NOT NULL, `fromNodeId` TEXT NOT NULL, `toNodeId` TEXT NOT NULL, " +
                "`relation` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`) )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_graph_edges_workspacePath` ON `graph_edges` (`workspacePath`)" )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_graph_edges_fromNodeId` ON `graph_edges` (`fromNodeId`)" )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_graph_edges_toNodeId` ON `graph_edges` (`toNodeId`)" )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_graph_edges_workspacePath_fromNodeId_toNodeId_relation` ON `graph_edges` (`workspacePath`, `fromNodeId`, `toNodeId`, `relation`)" )
    }
}

/** 71 → 72：运行时失败事件表（1.7 失败埋点）。全新建表，无需列存在性检查。 */
internal class Migration71To72 : Migration(71, 72) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `failure_events` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` TEXT, `kind` TEXT NOT NULL, " +
                "`source` TEXT, `detail` TEXT, `createdAt` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_failure_events_createdAt` ON `failure_events` (`createdAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_failure_events_kind` ON `failure_events` (`kind`)")
    }
}

/**
 * 幂等版 72→73（替代 `73_add_graph_temporal.sql` 执行）：记忆图谱加时间轴与作用域列。
 *
 * 时间轴：事实不覆盖、只标失效（Graphiti/Zep 的双时间语义）——旧结论被新结论取代时写
 * `invalidAt`，检索默认过滤失效项、UI 灰显可回溯，避免 AI 把过期结论当现状。
 * 作用域：全局记忆跨工作区只存一份（scope=GLOBAL、workspacePath 为空串），
 * 避免同一记忆在每个工作区各生成一份重复节点。
 */
internal class Migration72To73 : Migration(72, 73) {
    override fun migrate(db: SupportSQLiteDatabase) {
        addColumnIfMissing(db, "graph_nodes", "scope", "ALTER TABLE `graph_nodes` ADD COLUMN `scope` TEXT NOT NULL DEFAULT 'PROJECT'")
        addColumnIfMissing(db, "graph_nodes", "validFrom", "ALTER TABLE `graph_nodes` ADD COLUMN `validFrom` INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(db, "graph_nodes", "invalidAt", "ALTER TABLE `graph_nodes` ADD COLUMN `invalidAt` INTEGER")
        addColumnIfMissing(db, "graph_nodes", "sourceSessionId", "ALTER TABLE `graph_nodes` ADD COLUMN `sourceSessionId` TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_graph_nodes_invalidAt` ON `graph_nodes` (`invalidAt`)")
        addColumnIfMissing(db, "graph_edges", "validAt", "ALTER TABLE `graph_edges` ADD COLUMN `validAt` INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(db, "graph_edges", "invalidAt", "ALTER TABLE `graph_edges` ADD COLUMN `invalidAt` INTEGER")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_graph_edges_invalidAt` ON `graph_edges` (`invalidAt`)")
    }
}

/**
 * 幂等版 73→74（替代 `74_add_session_cached_tokens.sql` 执行）：会话累计缓存命中输入。
 *
 * 与 `totalInputTokens` 同口径（总量已含缓存），该列只是子集，用于在记忆图谱/会话卡片上
 * 直接展示缓存命中率，避免每次再从消息表回算。
 */
internal class Migration73To74 : Migration(73, 74) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val hadColumn = columnExists(db, "chat_sessions", "totalCachedInputTokens")
        addColumnIfMissing(
            db, "chat_sessions", "totalCachedInputTokens",
            "ALTER TABLE `chat_sessions` ADD COLUMN `totalCachedInputTokens` INTEGER NOT NULL DEFAULT 0"
        )
        if (hadColumn) return // 列已存在说明回填过，避免重复累加

        // 回填历史 Claude 会话：旧桥接把 input_tokens（未含缓存）记进 totalInputTokens，
        // 缓存命中单独存在消息行里。把两者合并成新口径，老会话在记忆图谱上才能与内置引擎可比。
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE `agent_messages` SET `inputTokens` = `inputTokens` + `cachedInputTokens` " +
                    "WHERE `cachedInputTokens` > 0 AND `sessionId` IN " +
                    "(SELECT `id` FROM `chat_sessions` WHERE `engine` = 'claude_code')"
            )
            db.execSQL(
                "UPDATE `chat_sessions` SET " +
                    "`totalCachedInputTokens` = COALESCE((SELECT SUM(m.`cachedInputTokens`) FROM `agent_messages` m " +
                    "WHERE m.`sessionId` = `chat_sessions`.`id`), 0), " +
                    "`totalInputTokens` = `totalInputTokens` + COALESCE((SELECT SUM(m.`cachedInputTokens`) " +
                    "FROM `agent_messages` m WHERE m.`sessionId` = `chat_sessions`.`id`), 0) " +
                    "WHERE `engine` = 'claude_code'"
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

/** 74→75：chat_sessions 增加 `engineMode`（引擎原生工作模式 id，null = 跟随引擎默认档）。 */
internal class Migration74To75 : Migration(74, 75) {
    override fun migrate(db: SupportSQLiteDatabase) {
        addColumnIfMissing(db, "chat_sessions", "engineMode", "ALTER TABLE `chat_sessions` ADD COLUMN `engineMode` TEXT")
    }
}

/**
 * 75→76：调用记录新增「实际发出的思考参数」与「降级前用户选的档位」。
 *
 * `reasoningWire` 记录适配器真正会发给服务端的思考档位（含 `minimal→low` 归一与白名单钳制后的结果）；
 * `reasoningClampedFrom` 只在发生降级时记下用户原本选的档位。两者与既有的 `reasoningEffort`
 * （用户所选）对照，即可在 Token 统计页分辨「选了但服务端没照做」「档位被静默降级」这类问题。
 */
internal class Migration75To76 : Migration(75, 76) {
    override fun migrate(db: SupportSQLiteDatabase) {
        addColumnIfMissing(db, "llm_call_records", "reasoningWire", "ALTER TABLE `llm_call_records` ADD COLUMN `reasoningWire` TEXT")
        addColumnIfMissing(db, "llm_call_records", "reasoningClampedFrom", "ALTER TABLE `llm_call_records` ADD COLUMN `reasoningClampedFrom` TEXT")
    }
}

/**
 * 76→77：新建改动账本表（跨会话记录「改了什么、为什么、怎么验的、什么状态」）。
 *
 * CREATE TABLE IF NOT EXISTS 本身幂等；列集与 Room 导出的 77.json 一致。
 * 与 `fix_ledger` 分工：那张只记来自标注的修复，本表覆盖 feat/fix/docs 等全部改动，
 * 并按 workspacePath 隔离——长期项目里靠它回答「哪些改好了、哪些没做」。
 */
internal class Migration76To77 : Migration(76, 77) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `change_ledger` (" +
                "`id` TEXT NOT NULL, `workspacePath` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, `status` TEXT NOT NULL, `humanSummary` TEXT NOT NULL, " +
                "`agentDetail` TEXT NOT NULL, `evidence` TEXT NOT NULL, `filesJson` TEXT NOT NULL, " +
                "`sessionId` TEXT, `sourceAnnotationId` TEXT, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `verifiedAt` INTEGER, PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_change_ledger_workspacePath` ON `change_ledger` (`workspacePath`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_change_ledger_updatedAt` ON `change_ledger` (`updatedAt`)")
    }
}

/**
 * 78→79：修复历史 Int 溢出留下的负值 token 累计。
 *
 * 会话累计原先用 Int 存，长会话累计超过 21 亿后回绕成负数（记忆图谱显示
 * 「-195776062」这种一长串）。实体侧已改为 Long，SQLite 的 INTEGER 本就是 64 位；
 * 这里把已经被写坏的负值归零，避免继续显示异常数字。
 */
internal class Migration78To79 : Migration(78, 79) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "UPDATE `chat_sessions` SET " +
                "`totalInputTokens` = MAX(`totalInputTokens`, 0), " +
                "`totalOutputTokens` = MAX(`totalOutputTokens`, 0), " +
                "`totalCachedInputTokens` = MAX(`totalCachedInputTokens`, 0), " +
                "`totalCacheCreationTokens` = MAX(`totalCacheCreationTokens`, 0)"
        )
    }
}

/**
 * 77→78：会话累计新增「缓存写入」列。
 *
 * 此前会话级累计只记缓存命中，缓存写入（cache_creation，按 1.25× 溢价计费）被错算进
 * 「全新输入」，记忆图谱构成条因此虚高。列与 `totalCachedInputTokens` 同为
 * `totalInputTokens` 的子集且互不重叠，累计时两者分开累加。
 */
internal class Migration77To78 : Migration(77, 78) {
    override fun migrate(db: SupportSQLiteDatabase) {
        addColumnIfMissing(db, "chat_sessions", "totalCacheCreationTokens", "ALTER TABLE `chat_sessions` ADD COLUMN `totalCacheCreationTokens` INTEGER NOT NULL DEFAULT 0")
    }
}

/** 列是否存在；检查失败按「已存在」处理（与 [addColumnIfMissing] 同一保守口径）。 */
private fun columnExists(db: SupportSQLiteDatabase, table: String, column: String): Boolean =
    runCatching {
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) {
                    found = true
                    break
                }
            }
            found
        }
    }.getOrDefault(true)

/** 列不存在才执行 DDL；检查失败按「已存在」处理并记日志（宁可 no-op，也不重复 ALTER 崩库）。 */
private fun addColumnIfMissing(db: SupportSQLiteDatabase, table: String, column: String, ddl: String) {
    val exists = runCatching {
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) {
                    found = true
                    break
                }
            }
            found
        }
    }.getOrElse { e ->
        FileLogger.w("MigrationLoader", "列存在性检查失败，跳过补列 $table.$column", e)
        true
    }
    if (!exists) db.execSQL(ddl)
}

/**
 * 79→80：消息级生成计时（上游 81d88b7c，本仓 v80 重编号）——
 * agent_messages 新增首包与生成窗口毫秒，供 Token 统计图表与消息耗时展示。
 */
internal class Migration79To80 : Migration(79, 80) {
    override fun migrate(db: SupportSQLiteDatabase) {
        addColumnIfMissing(db, "agent_messages", "ttfbMillis", "ALTER TABLE `agent_messages` ADD COLUMN `ttfbMillis` INTEGER")
        addColumnIfMissing(db, "agent_messages", "generationWindowMillis", "ALTER TABLE `agent_messages` ADD COLUMN `generationWindowMillis` INTEGER")
    }
}
