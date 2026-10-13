package com.aicode.feature.agent.domain.team

import com.aicode.feature.workspace.domain.FileAccessProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局编队定义来源：`~/.aicode/teams`，跨项目共享。
 * 以容器路径经 [FileAccessProvider] 访问，跟随当前执行环境（本地宿主目录或远程 SSH 服务器）。
 */
@Singleton
class GlobalDirectoryTeamSource @Inject constructor(
    private val fileAccess: FileAccessProvider
) : TeamDefinitionSource {

    val teamsRoot: String = "~/.aicode/teams"

    override fun listDefinitions(): List<TeamDefinition> =
        TeamDirectoryScanner.scan(fileAccess, teamsRoot)
}
