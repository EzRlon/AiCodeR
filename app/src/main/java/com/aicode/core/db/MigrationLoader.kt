package com.aicode.core.db

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aicode.core.util.FileLogger

class FileMigration(
    val version: Int,
    val scriptName: String,
    val sqlStatements: List<String>
) : Migration(version - 1, version) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 整段迁移包在事务里：任一条语句失败整体回滚，避免留半迁移状态
        db.beginTransaction()
        try {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS migration_history (" +
                        "version INTEGER PRIMARY KEY, " +
                        "script_name TEXT, " +
                        "executed_at INTEGER)"
            )
            for (sql in sqlStatements) {
                db.execSQL(sql)
            }
            db.execSQL(
                // OR REPLACE：非正常降级（旧版 fallbackToDestructiveMigration 只 DROP Room 实体表、
                // 按旧 schema 重建并把 user_version 置回旧值，migration_history 不是实体表因而留存）
                // 会让同一版本被重放，普通 INSERT 撞 version 主键。记录以最后一次执行为准。
                "INSERT OR REPLACE INTO migration_history (version, script_name, executed_at) VALUES (?, ?, ?)",
                arrayOf<Any>(version, scriptName, System.currentTimeMillis())
            )
            db.setTransactionSuccessful()
        } catch (t: Throwable) {
            FileLogger.e("MigrationLoader", "Migration failed: $scriptName", t)
            throw t
        } finally {
            db.endTransaction()
        }
        FileLogger.i("MigrationLoader", "Applied migration: $scriptName")
    }
}

object MigrationLoader {
    fun loadMigrations(context: Context): Array<Migration> {
        val assetManager = context.assets
        val migrationsDir = "migrations"
        val files = runCatching { assetManager.list(migrationsDir) }.getOrNull() ?: emptyArray()
        
        val migrations = mutableListOf<Migration>()
        
        // File format: {version}_{description}.sql, e.g., "7_add_workspace_path.sql"
        for (fileName in files) {
            if (!fileName.endsWith(".sql")) continue
            
            val versionStr = fileName.substringBefore('_')
            val version = versionStr.toIntOrNull() ?: continue
            
            val sqlContent = runCatching {
                assetManager.open("$migrationsDir/$fileName").bufferedReader().use { it.readText() }
            }.getOrNull() ?: continue
            
            val statements = SqlScriptSplitter.split(sqlContent)
            
            migrations.add(FileMigration(version, fileName, statements))
        }

        // 62 起改用幂等 Kotlin 版执行（SQL 文件保留作审计与编号校验）：兼容曾装过
        // 68（会话回收站）同属这一轨：ALTER ADD COLUMN 要按列存在性幂等补，见 Migration67To68。
        // 旧编号 60/61（annotations/session_team）中间开发版的设备——那类设备缺官方
        // 60/61 两列，需要在 62/63 里按列存在性补齐；正常设备行为等价。见 IdempotentMigrations.kt。
        migrations.removeAll { it is FileMigration && (it.version == 62 || it.version == 63 || it.version == 64 || it.version == 65 || it.version == 67 || it.version == 68 || it.version == 69 || it.version == 70 || it.version == 71 || it.version == 72 || it.version == 73 || it.version == 74 || it.version == 75 || it.version == 76 || it.version == 77 || it.version == 78 || it.version == 79) }
        migrations.add(Migration61To62())
        migrations.add(Migration62To63())
        migrations.add(Migration63To64())
        migrations.add(Migration64To65())
        migrations.add(Migration66To67())
        migrations.add(Migration67To68())
        migrations.add(Migration68To69())
        migrations.add(Migration69To70())
        migrations.add(Migration70To71())
        migrations.add(Migration71To72())
        migrations.add(Migration72To73())
        migrations.add(Migration73To74())
        migrations.add(Migration74To75())
        migrations.add(Migration75To76())
        migrations.add(Migration76To77())
        migrations.add(Migration77To78())
        migrations.add(Migration78To79())

        return migrations.toTypedArray()
    }
}
