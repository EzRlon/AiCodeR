package com.aicode.feature.agent.domain.team

import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 项目级专家团定义来源：`<projectRoot>/.aicode/teams/`，随工作区走，可 git 追踪。
 * 以容器路径经 [FileAccessProvider] 访问：本地映射到宿主工作区，远程经 SSH 落到远程工作区。
 */
@Singleton
class ProjectDirectoryTeamSource @Inject constructor(
    private val fileAccess: FileAccessProvider
) : TeamDefinitionSource {

    val teamsRoot: String = "${WorkspacePathMapper.CONTAINER_ROOT}/.aicode/teams"

    override fun listDefinitions(): List<TeamDefinition> =
        TeamDirectoryScanner.scan(fileAccess, teamsRoot)
}
