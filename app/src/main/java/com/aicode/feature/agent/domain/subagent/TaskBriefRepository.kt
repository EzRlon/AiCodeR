package com.aicode.feature.agent.domain.subagent

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider
import javax.inject.Inject
import javax.inject.Singleton

/** 一份任务书模板：名称 + 正文（纯 Markdown，无 frontmatter）。 */
data class TaskBriefTemplate(
    val name: String,
    val body: String
)

/**
 * 任务书模板仓库：`~/.aicode/task-briefs/<名称>.md`（全局）与 `<项目>/.aicode/task-briefs/<名称>.md`（项目级，
 * 同名覆盖全局）。派发子代理时可用 `task(brief=...)` 引用，模板正文前置到任务书，省去每次重写五要素。
 *
 * 与子代理定义同为「目录里一个 md 一个条目」的约定，但模板无 frontmatter：整篇正文即模板。
 */
@Singleton
class TaskBriefRepository @Inject constructor(
    private val fileAccess: FileAccessProvider
) {
    /** 全部模板（项目级覆盖全局），按名称排序。 */
    fun listAll(projectRoot: String): List<TaskBriefTemplate> {
        val byName = LinkedHashMap<String, TaskBriefTemplate>()
        scan(GLOBAL_ROOT).forEach { byName[it.name.lowercase()] = it }
        if (projectRoot.isNotBlank()) scan(projectRoot.trimEnd('/') + PROJECT_SUFFIX).forEach {
            byName[it.name.lowercase()] = it
        }
        return byName.values.sortedBy { it.name.lowercase() }
    }

    /** 按名称取模板正文（忽略大小写）；不存在返回 null。 */
    fun find(projectRoot: String, name: String): TaskBriefTemplate? =
        listAll(projectRoot).firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun scan(root: String): List<TaskBriefTemplate> = runCatching {
        if (!fileAccess.isDirectory(root)) return@runCatching emptyList()
        fileAccess.listFiles(root)
            .filter { !it.isDirectory && it.name.endsWith(".md", ignoreCase = true) }
            .mapNotNull { entry ->
                val path = "$root/${entry.name}"
                val body = runCatching { fileAccess.readFile(path) }.getOrNull()?.trim()
                if (body.isNullOrEmpty()) null
                else TaskBriefTemplate(name = entry.name.removeSuffix(".md"), body = body)
            }
    }.getOrElse { e ->
        FileLogger.w(TAG, "扫描任务书模板失败: $root", e)
        emptyList()
    }

    companion object {
        private const val TAG = "TaskBriefRepository"
        const val GLOBAL_ROOT = "~/.aicode/task-briefs"
        const val PROJECT_SUFFIX = "/.aicode/task-briefs"

        /** 模板正文前置进任务书时的分隔标题。 */
        const val BRIEF_HEADER = "【任务书模板】"
    }
}
