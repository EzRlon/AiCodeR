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
