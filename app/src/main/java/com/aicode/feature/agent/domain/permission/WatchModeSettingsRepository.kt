package com.aicode.feature.agent.domain.permission

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.watchModeDataStore by preferencesDataStore(name = "watch_mode_prefs")

/**
 * 「守夜守护」开关：无人值守（离开/睡觉）期间的安全守护层总开关。
 *
 * 开启后（守夜语义，见 unattended-security-plan）：
 * - 不可静态判定的命令：破坏性 → 拒绝 + 引导；非破坏性 → 放行 + 记录；
 * - 递归/通配删除：目标全在工作区/临时目录 → 放行 + 记录；否则拒绝 + 引导；
 * - 致命删除永不提权（禁用 elevate 弹窗路径）；
 * - Shizuku / MCP 管理 / hooks.json 写入 → 拒绝 + 记录；
 * - 计划审查自动通过（记录）；Token 预算耗尽不暂停 → 结束任务；
 * - 一切授权弹窗 10 分钟无人应答 → 自动拒绝 + 记录 + 通知（不无限等待）；
 * - AI 提问自动答复「用户不在场，请自主决策或跳过」。
 *
 * 仅存一个全局布尔；建议配合「离开模式」档案使用（进入清单见设计稿 §七）。
 */
@Singleton
class WatchModeSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val ARMED_KEY = booleanPreferencesKey("watch_mode_armed")
    }

    /** 当前开关流；未设置时默认关闭。 */
    val watchModeFlow: Flow<Boolean> = context.watchModeDataStore.data.map { it[ARMED_KEY] ?: false }

    /** 读取一次当前值（权限评估等热路径用）。 */
    suspend fun isArmed(): Boolean = watchModeFlow.first()

    suspend fun setArmed(armed: Boolean) {
        context.watchModeDataStore.edit { it[ARMED_KEY] = armed }
    }
}
