package com.aicode.feature.workspace.domain

import com.aicode.core.watch.WorkspaceWatchContext
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** [WorkspaceWatchContext] 的 feature 侧实现：把工作区/容器相关仓库适配给 core 的 FileChangeHub。 */
@Singleton
class WorkspaceWatchContextImpl @Inject constructor(
    private val workspaceRepository: WorkspaceRepository,
    private val containerInstaller: ContainerInstaller,
    private val pathMapper: WorkspacePathMapper,
    private val executionModeHolder: ExecutionModeHolder
) : WorkspaceWatchContext {

    override val workspacePathFlow: Flow<String> =
        workspaceRepository.current.map { workspaceRepository.currentPath() }

    override fun currentWorkspacePath(): String = workspaceRepository.currentPath()

    override fun isRemoteMode(): Boolean =
        executionModeHolder.currentMode() == ExecutionMode.REMOTE_SSH

    override fun toHostFile(containerPath: String): File = pathMapper.toHostFile(containerPath)

    override fun toContainerPath(hostPath: String): String = pathMapper.toContainerPath(hostPath)

    override val aicodeHostDir: File get() = containerInstaller.aicodeDir
}
