package com.aicode.feature.agent.domain.team

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 专家团定义仓库，聚合全局与项目级两级来源；同名定义项目级优先（与子代理、技能两级配置一致）。
 *
 * 全局定义 `~/.aicode/teams`、项目级定义 `<workspace>/.aicode/teams`，两者都经来源层
 * 以容器路径访问，跟随当前执行环境（本地宿主目录或远程 SSH 工作区）。
 */
@Singleton
class TeamRepository @Inject constructor(
    private val globalSource: GlobalDirectoryTeamSource,
    private val projectSource: ProjectDirectoryTeamSource
) {
    /** 全部专家团（含来源作用域），按名称排序。 */
    fun listAll(): List<TeamDefinitionEntry> {
        val projectDefs = projectSource.listDefinitions()
        val globalDefs = globalSource.listDefinitions()
            .filterNot { g -> projectDefs.any { it.name.equals(g.name, ignoreCase = true) } }
        return (projectDefs.map { TeamDefinitionEntry(it, TeamDefinitionScope.PROJECT) } +
            globalDefs.map { TeamDefinitionEntry(it, TeamDefinitionScope.GLOBAL) })
            .sortedBy { it.definition.name.lowercase() }
    }

    /** 按名称解析专家团（忽略大小写）；不存在时返回 null。 */
    fun resolve(name: String?): TeamDefinition? {
        if (name.isNullOrBlank()) return null
        return listAll().firstOrNull { it.definition.name.equals(name, ignoreCase = true) }?.definition
    }
}
