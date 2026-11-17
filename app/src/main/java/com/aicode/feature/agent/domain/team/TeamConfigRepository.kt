package com.aicode.feature.agent.domain.team

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.skill.SkillConfigRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 专家团启停配置，支持全局 + 项目级两级：
 * - 全局：当前执行环境的 `~/.aicode/teams.json`（跨项目保留）；
 * - 项目级：`<workspace>/.aicode/teams.json`（随工作区走）。
 *
 * 格式与技能配置一致（`{"disabled": ["团名"]}`），仅存「禁用名单」；生效禁用集合 = 全局 + 项目并集。
 * 每次读取都从磁盘加载，外部手工编辑即时生效。
 */
@Singleton
class TeamConfigRepository @Inject constructor(
    private val projectAicodeRoot: ProjectAicodeRoot,
    private val fileAccess: FileAccessProvider
) {
    /** 当前生效的禁用专家团名集合（全局 + 项目并集，归一化为小写）。 */
    fun disabledNames(): Set<String> {
        val global = runCatching {
            if (fileAccess.isFile(GLOBAL_CONFIG_PATH)) {
                SkillConfigRepository.parseDisabled(fileAccess.readFile(GLOBAL_CONFIG_PATH))
            } else {
                emptySet()
            }
        }.getOrElse {
            FileLogger.w(TAG, "读取 $CONFIG_FILE 失败: ${it.message}")
            emptySet()
        }
        val project = SkillConfigRepository.readDisabled(projectFile())
        return (global + project).map { it.lowercase() }.toSet()
    }

    /** 在指定作用域的配置中启用/禁用某个专家团。 */
    fun setDisabled(name: String, disabled: Boolean, scope: TeamDefinitionScope) {
        val names = (
            if (scope == TeamDefinitionScope.GLOBAL) {
                runCatching {
                    if (fileAccess.isFile(GLOBAL_CONFIG_PATH)) {
                        SkillConfigRepository.parseDisabled(fileAccess.readFile(GLOBAL_CONFIG_PATH))
                    } else {
                        emptySet()
                    }
                }.getOrElse { emptySet() }
            } else {
                SkillConfigRepository.readDisabled(projectFile())
            }
            ).toMutableSet()
        if (disabled) names.add(name) else names.remove(name)
        if (scope == TeamDefinitionScope.GLOBAL) {
            fileAccess.writeFile(GLOBAL_CONFIG_PATH, SkillConfigRepository.serializeDisabled(names))
        } else {
            SkillConfigRepository.writeDisabled(projectFile(), names)
        }
        // 启用时同时清掉另一作用域的名单：同一个团名可能两处都被禁，只清一处会让开关看起来无效。
        if (!disabled) clearDisabled(name, scope)
    }

    /** 把团名从两个作用域的禁用名单中移除（启用开关与删除定义后调用）；[skipScope] 为已单独处理过的作用域。 */
    fun clearDisabled(name: String, skipScope: TeamDefinitionScope? = null) {
        if (skipScope != TeamDefinitionScope.GLOBAL) {
            val global = runCatching {
                if (fileAccess.isFile(GLOBAL_CONFIG_PATH)) {
                    SkillConfigRepository.parseDisabled(fileAccess.readFile(GLOBAL_CONFIG_PATH))
                } else {
                    emptySet()
                }
            }.getOrElse { emptySet() }.toMutableSet()
            if (global.remove(name)) {
                fileAccess.writeFile(GLOBAL_CONFIG_PATH, SkillConfigRepository.serializeDisabled(global))
            }
        }
        if (skipScope != TeamDefinitionScope.PROJECT) {
            val project = SkillConfigRepository.readDisabled(projectFile()).toMutableSet()
            if (project.remove(name)) {
                SkillConfigRepository.writeDisabled(projectFile(), project)
            }
        }
    }

    private fun projectFile(): File = File(projectAicodeRoot.current(), CONFIG_FILE)

    private companion object {
        const val TAG = "TeamConfigRepository"
        const val CONFIG_FILE = "teams.json"
        const val GLOBAL_CONFIG_PATH = "~/.aicode/$CONFIG_FILE"
    }
}
