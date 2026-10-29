package com.aicode.feature.agent.domain.team

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 专家团定义仓库，聚合全局与项目级两级来源；同名定义项目级优先（与子代理、技能两级配置一致）。
 *
 * 全局定义 `~/.aicode/teams`、项目级定义 `<workspace>/.aicode/teams`，两者都经来源层
 * 以容器路径访问，跟随当前执行环境（本地宿主目录或远程 SSH 工作区）。
 *
 * 段内顺序沿用各来源的扫描结果（按安装先后），不做二次排序：商店新装的团队因此落在列表末尾。
 * 被禁用的专家团不进入 [listAll]，会话选择器因此看不到；设置页传 `includeDisabled = true` 单独取得全量。
 */
@Singleton
class TeamRepository @Inject constructor(
    private val globalSource: GlobalDirectoryTeamSource,
    private val projectSource: ProjectDirectoryTeamSource,
    private val config: TeamConfigRepository,
    private val fileAccess: FileAccessProvider
) {
    /** 专家团列表：项目级在前，全局在后，段内按安装先后；[includeDisabled] 为 true 时连同已禁用的返回。 */
    fun listAll(includeDisabled: Boolean = false): List<TeamDefinitionEntry> {
        val disabled = if (includeDisabled) emptySet() else config.disabledNames()
        val projectDefs = projectSource.listDefinitions()
        val globalDefs = globalSource.listDefinitions()
            .filterNot { g -> projectDefs.any { it.name.equals(g.name, ignoreCase = true) } }
        return (projectDefs.map { TeamDefinitionEntry(it, TeamDefinitionScope.PROJECT) } +
            globalDefs.map { TeamDefinitionEntry(it, TeamDefinitionScope.GLOBAL) })
            .filterNot { it.definition.name.lowercase() in disabled }
    }

    /** 按名称解析专家团（忽略大小写）；不存在或已禁用时返回 null。 */
    fun resolve(name: String?): TeamDefinition? {
        if (name.isNullOrBlank()) return null
        return listAll().firstOrNull { it.definition.name.equals(name, ignoreCase = true) }?.definition
    }

    /** 删除一个专家团定义文件（成员专家定义保留，可能被其它专家团共用）；返回是否删除成功。 */
    fun delete(filePath: String?): Boolean {
        if (filePath.isNullOrBlank()) return false
        return runCatching {
            fileAccess.delete(filePath)
            // FileAccessProvider.delete 走 File.delete() 且丢弃返回值，失败不抛：
            // 不复查会让 UI 在文件仍在时报「已删除」。
            !fileAccess.isFile(filePath)
        }.getOrElse {
            FileLogger.w(TAG, "删除专家团定义失败: $filePath", it)
            false
        }
    }

    private companion object {
        const val TAG = "TeamRepository"
    }
}
