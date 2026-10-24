package com.aicode.feature.agent.domain.permission

import androidx.annotation.StringRes
import com.aicode.R

/**
 * 工具权限档位（工具授权中心的「自动允许 / 每次询问 / 禁止」）。
 *
 * 语义（与权限引擎的对应关系见 ToolPermissionPolicyEngine）：
 * - [AUTO]：普通调用直接放行。仍保留系统级兜底（灾难性删除防护、Shizuku 高危、命令不可静态判定）。
 * - [ASK]：走常规评估链后询问用户（安全白名单与已记忆规则命中时依旧免问）。
 * - [DENY]：直接拒绝（优先级最高，压过 PLAN/AUTO 模式与一切内置规则白名单）。
 */
enum class ToolPermissionTier(val key: String) {
    AUTO("auto"), ASK("ask"), DENY("deny");

    companion object {
        fun fromKey(key: String?): ToolPermissionTier? = entries.firstOrNull { it.key == key }
    }
}

/** 工具授权页内的分组。 */
enum class ToolPermissionGroup(@param:StringRes val labelRes: Int) {
    FILES(R.string.perm_group_files),
    TERMINAL(R.string.perm_group_terminal),
    NETWORK(R.string.perm_group_network),
    MEDIA(R.string.perm_group_media),
    AGENT(R.string.perm_group_agent),
    EXTEND(R.string.perm_group_extend)
}

/** 单个工具的展示元数据与默认档位。 */
data class ToolPermissionInfo(
    val toolName: String,
    @param:StringRes val labelRes: Int,
    @param:StringRes val descRes: Int,
    val group: ToolPermissionGroup,
    val defaultTier: ToolPermissionTier
)

/**
 * 预置授权档案（v1 轻量版）：一键把一组档位覆盖应用到全局工具档位。
 * 供「离开模式」等场景提前铺好授权；与定时任务的预授权清单对接留待 v3。
 */
data class ToolPermissionProfile(
    val id: String,
    @param:StringRes val labelRes: Int,
    @param:StringRes val descRes: Int,
    /** toolName → tier；应用档案 = 先恢复默认、再覆盖该表。空表表示全部恢复默认。 */
    val overrides: Map<String, ToolPermissionTier>
)

/**
 * 工具授权目录：全部内置工具的中文名称、用途说明、分组与默认档位；
 * 以及预置授权档案。UI（授权中心）与权限引擎（默认档位兜底）共用。
 *
 * 默认档位与各工具 `permissionPolicy` 现状精确对齐（只读/低危=自动允许、写/系统类=每次询问），
 * 应用目录默认值时不会改变任何现有行为；用户手动调整或应用档案才产生差异。
 */
object ToolPermissionCatalog {

    val all: List<ToolPermissionInfo> = listOf(
        // 文件与搜索
        ToolPermissionInfo("readFile", R.string.perm_tool_read_file, R.string.perm_tool_read_file_desc, ToolPermissionGroup.FILES, ToolPermissionTier.AUTO),
        ToolPermissionInfo("writeFile", R.string.perm_tool_write_file, R.string.perm_tool_write_file_desc, ToolPermissionGroup.FILES, ToolPermissionTier.ASK),
        ToolPermissionInfo("editFile", R.string.perm_tool_edit_file, R.string.perm_tool_edit_file_desc, ToolPermissionGroup.FILES, ToolPermissionTier.ASK),
        ToolPermissionInfo("list", R.string.perm_tool_list, R.string.perm_tool_list_desc, ToolPermissionGroup.FILES, ToolPermissionTier.AUTO),
        ToolPermissionInfo("search", R.string.perm_tool_search, R.string.perm_tool_search_desc, ToolPermissionGroup.FILES, ToolPermissionTier.AUTO),
        ToolPermissionInfo("sendFile", R.string.perm_tool_send_file, R.string.perm_tool_send_file_desc, ToolPermissionGroup.FILES, ToolPermissionTier.AUTO),
        // 终端与系统
        ToolPermissionInfo("Bash", R.string.perm_tool_bash, R.string.perm_tool_bash_desc, ToolPermissionGroup.TERMINAL, ToolPermissionTier.ASK),
        ToolPermissionInfo("run_script", R.string.perm_tool_run_script, R.string.perm_tool_run_script_desc, ToolPermissionGroup.TERMINAL, ToolPermissionTier.ASK),
        ToolPermissionInfo("terminal", R.string.perm_tool_terminal, R.string.perm_tool_terminal_desc, ToolPermissionGroup.TERMINAL, ToolPermissionTier.ASK),
        ToolPermissionInfo("Shizuku", R.string.perm_tool_shizuku, R.string.perm_tool_shizuku_desc, ToolPermissionGroup.TERMINAL, ToolPermissionTier.ASK),
        // 网络与浏览
        ToolPermissionInfo("websearch", R.string.perm_tool_websearch, R.string.perm_tool_websearch_desc, ToolPermissionGroup.NETWORK, ToolPermissionTier.AUTO),
        ToolPermissionInfo("webfetch", R.string.perm_tool_webfetch, R.string.perm_tool_webfetch_desc, ToolPermissionGroup.NETWORK, ToolPermissionTier.AUTO),
        ToolPermissionInfo("browser", R.string.perm_tool_browser, R.string.perm_tool_browser_desc, ToolPermissionGroup.NETWORK, ToolPermissionTier.AUTO),
        // 图像与媒体
        ToolPermissionInfo("viewImage", R.string.perm_tool_view_image, R.string.perm_tool_view_image_desc, ToolPermissionGroup.MEDIA, ToolPermissionTier.AUTO),
        ToolPermissionInfo("generateImage", R.string.perm_tool_generate_image, R.string.perm_tool_generate_image_desc, ToolPermissionGroup.MEDIA, ToolPermissionTier.ASK),
        // 智能体与协作
        ToolPermissionInfo("task", R.string.perm_tool_task, R.string.perm_tool_task_desc, ToolPermissionGroup.AGENT, ToolPermissionTier.ASK),
        ToolPermissionInfo("messageParent", R.string.perm_tool_message_parent, R.string.perm_tool_message_parent_desc, ToolPermissionGroup.AGENT, ToolPermissionTier.AUTO),
        ToolPermissionInfo("planMode", R.string.perm_tool_plan_mode, R.string.perm_tool_plan_mode_desc, ToolPermissionGroup.AGENT, ToolPermissionTier.ASK),
        ToolPermissionInfo("goalStatus", R.string.perm_tool_goal_status, R.string.perm_tool_goal_status_desc, ToolPermissionGroup.AGENT, ToolPermissionTier.AUTO),
        ToolPermissionInfo("summon_team", R.string.perm_tool_summon_team, R.string.perm_tool_summon_team_desc, ToolPermissionGroup.AGENT, ToolPermissionTier.AUTO),
        ToolPermissionInfo("todo", R.string.perm_tool_todo, R.string.perm_tool_todo_desc, ToolPermissionGroup.AGENT, ToolPermissionTier.AUTO),
        ToolPermissionInfo("fixledger", R.string.perm_tool_fixledger, R.string.perm_tool_fixledger_desc, ToolPermissionGroup.AGENT, ToolPermissionTier.AUTO),
        ToolPermissionInfo("askUserQuestion", R.string.perm_tool_ask_question, R.string.perm_tool_ask_question_desc, ToolPermissionGroup.AGENT, ToolPermissionTier.AUTO),
        // 记忆与扩展
        ToolPermissionInfo("memory", R.string.perm_tool_memory, R.string.perm_tool_memory_desc, ToolPermissionGroup.EXTEND, ToolPermissionTier.AUTO),
        ToolPermissionInfo("loadSkill", R.string.perm_tool_load_skill, R.string.perm_tool_load_skill_desc, ToolPermissionGroup.EXTEND, ToolPermissionTier.AUTO),
        ToolPermissionInfo("manageMcp", R.string.perm_tool_manage_mcp, R.string.perm_tool_manage_mcp_desc, ToolPermissionGroup.EXTEND, ToolPermissionTier.ASK)
    )

    private val byName = all.associateBy { it.toolName }

    fun infoOf(toolName: String): ToolPermissionInfo? = byName[toolName]

    /** 未收录的工具（如动态注册的 MCP 工具）保守回退「每次询问」。 */
    fun defaultTierOf(toolName: String): ToolPermissionTier =
        byName[toolName]?.defaultTier ?: ToolPermissionTier.ASK

    /**
     * 预置档案：
     * - 日常：全部恢复默认档位；
     * - 离开模式：写/执行类放开为自动允许（无人值守时持续推进），Shizuku 与 MCP 管理保持每次询问；
     * - 只读演示：写/执行类直接禁止（演示或观察场景防止任何改动），只读类保持自动允许。
     */
    val profiles: List<ToolPermissionProfile> = listOf(
        ToolPermissionProfile(
            id = "daily",
            labelRes = R.string.perm_profile_daily,
            descRes = R.string.perm_profile_daily_desc,
            overrides = emptyMap()
        ),
        ToolPermissionProfile(
            id = "away",
            labelRes = R.string.perm_profile_away,
            descRes = R.string.perm_profile_away_desc,
            overrides = mapOf(
                "writeFile" to ToolPermissionTier.AUTO,
                "editFile" to ToolPermissionTier.AUTO,
                "Bash" to ToolPermissionTier.AUTO,
                "run_script" to ToolPermissionTier.AUTO,
                "terminal" to ToolPermissionTier.AUTO,
                "generateImage" to ToolPermissionTier.AUTO,
                "task" to ToolPermissionTier.AUTO
            )
        ),
        ToolPermissionProfile(
            id = "readonly_demo",
            labelRes = R.string.perm_profile_readonly,
            descRes = R.string.perm_profile_readonly_desc,
            overrides = mapOf(
                "writeFile" to ToolPermissionTier.DENY,
                "editFile" to ToolPermissionTier.DENY,
                "Bash" to ToolPermissionTier.DENY,
                "run_script" to ToolPermissionTier.DENY,
                "terminal" to ToolPermissionTier.DENY,
                "Shizuku" to ToolPermissionTier.DENY,
                "generateImage" to ToolPermissionTier.DENY,
                "manageMcp" to ToolPermissionTier.DENY,
                "summon_team" to ToolPermissionTier.DENY,
                "task" to ToolPermissionTier.DENY
            )
        )
    )
}
