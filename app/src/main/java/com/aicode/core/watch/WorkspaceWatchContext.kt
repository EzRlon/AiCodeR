package com.aicode.core.watch

import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * FileChangeHub 对「工作区/容器视角」的最小依赖面（依赖倒置）。
 *
 * core 层不能反向依赖 feature：工作区仓库、容器安装器、路径映射与执行模式都属于业务层，
 * 由 feature/workspace 实现本接口并在 DI 装配时注入。
 */
interface WorkspaceWatchContext {
    /** 当前工作区路径流（工作区切换时发射新值）。 */
    val workspacePathFlow: Flow<String>

    /** 当前工作区宿主路径。 */
    fun currentWorkspacePath(): String

    /** 是否远程 SSH 模式（宿主没有对应目录，文件监听退化）。 */
    fun isRemoteMode(): Boolean

    /** 容器路径 → 宿主真实路径（不存在时返回按映射推断的 File）。 */
    fun toHostFile(containerPath: String): File

    /** 宿主路径 → 容器视角路径。 */
    fun toContainerPath(hostPath: String): String

    /** 容器内 `~/.aicode` 对应的宿主机目录。 */
    val aicodeHostDir: File
}
