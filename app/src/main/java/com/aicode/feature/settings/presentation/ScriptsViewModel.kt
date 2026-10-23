package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.domain.script.ScriptInfo
import com.aicode.feature.agent.domain.script.ScriptRepository
import com.aicode.feature.agent.domain.script.ScriptRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 一次脚本运行在界面上的状态：运行中显示进度，结束后展示输出与退出码。 */
data class ScriptRunState(
    val running: Boolean = false,
    val output: String = "",
    val exitCode: Int? = null
)

/**
 * 脚本页状态：[scripts] 为脚本站清单，[runs] 按脚本名保存本次进入页面后的运行结果
 * （输出内联展示在对应卡片下方）；[unavailableReason] 非空表示当前执行环境不支持脚本。
 */
data class ScriptsUiState(
    val scripts: List<ScriptInfo> = emptyList(),
    val loading: Boolean = true,
    val unavailableReason: String? = null,
    val runs: Map<String, ScriptRunState> = emptyMap()
)

/** 脚本页的失败提示类型，供界面弹 Toast。 */
enum class ScriptsError { CREATE_FAILED, DELETE_FAILED, RUN_FAILED }

/**
 * 脚本页：列出 ~/.aicode/scripts/ 下的脚本，支持新建（建模板后交给文件编辑器）、运行与删除。
 *
 * 读写都是磁盘 IO，统一放 IO 线程；运行与解释器选择交给 [ScriptRunner]，与 `run_script` 工具同源。
 */
@HiltViewModel
class ScriptsViewModel @Inject constructor(
    private val repository: ScriptRepository,
    private val runner: ScriptRunner
) : ViewModel() {

    private val _state = MutableStateFlow(ScriptsUiState())
    val state: StateFlow<ScriptsUiState> = _state.asStateFlow()

    private val _error = MutableStateFlow<ScriptsError?>(null)
    val error: StateFlow<ScriptsError?> = _error.asStateFlow()

    /**
     * 新建成功后待打开的脚本名。界面据此跳转编辑器——不能在建文件的同时就跳，
     * 否则编辑器可能先读到「文件还不存在」而报错。
     */
    private val _pendingOpen = MutableStateFlow<String?>(null)
    val pendingOpen: StateFlow<String?> = _pendingOpen.asStateFlow()

    init {
        refresh()
    }

    fun consumeError() {
        _error.value = null
    }

    fun consumePendingOpen() {
        _pendingOpen.value = null
    }

    /** 脚本在容器内的路径，交给文件编辑器打开用。 */
    fun containerPath(name: String): String = repository.containerPath(name)

    fun refresh() {
        val reason = repository.unavailableReason()
        if (reason != null) {
            _state.value = ScriptsUiState(unavailableReason = reason, loading = false)
            return
        }
        viewModelScope.launch {
            val scripts = withContext(Dispatchers.IO) { repository.list() }
            _state.update { state ->
                // 丢掉已不在清单里的运行结果：脚本可能被 AI 或定时任务删掉，
                // 而运行收尾的状态更新可能落在删除之后，不清会留下一份永远无法关闭的旧输出。
                val names = scripts.mapTo(mutableSetOf()) { it.name }
                state.copy(
                    scripts = scripts,
                    loading = false,
                    unavailableReason = null,
                    runs = state.runs.filterKeys { it in names }
                )
            }
        }
    }

    /** 按 [name] 写入起始模板，成功后置 [pendingOpen] 让界面打开编辑器。 */
    fun create(name: String) {
        // 远程模式下不能建：写的是宿主目录，而编辑器打开的是远端路径，会变成「建了看不到也打不开」。
        if (repository.unavailableReason() != null) {
            _error.value = ScriptsError.CREATE_FAILED
            return
        }
        viewModelScope.launch {
            val created = withContext(Dispatchers.IO) {
                runCatching { repository.save(name, templateFor(name)).name }.getOrNull()
            }
            if (created == null) {
                _error.value = ScriptsError.CREATE_FAILED
                return@launch
            }
            refresh()
            _pendingOpen.value = created
        }
    }

    /** 在容器内运行一次脚本，把输出与退出码留在 [ScriptsUiState.runs] 里内联展示。 */
    fun run(name: String) {
        if (repository.unavailableReason() != null) {
            _error.value = ScriptsError.RUN_FAILED
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(runs = it.runs + (name to ScriptRunState(running = true))) }
            val target = withContext(Dispatchers.IO) { runner.target(name) }
            val result = target?.let { runCatching { runner.run(it, RUN_TIMEOUT_MS) }.getOrNull() }
            if (target == null || result == null) {
                _state.update { it.copy(runs = it.runs - name) }
                _error.value = ScriptsError.RUN_FAILED
                return@launch
            }
            // 容器未就绪时引擎把引导文案当输出返回，原样展示，不当作退出码。
            val hint = if (result.exitCode == null) runner.notReadyHint() else null
            _state.update {
                it.copy(
                    runs = it.runs + (
                        target.scriptName to ScriptRunState(
                            output = hint ?: result.output,
                            exitCode = if (hint != null) null else result.exitCode
                        )
                        )
                )
            }
            refresh()
        }
    }

    fun delete(name: String) {
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) { repository.delete(name) }
            if (!deleted) _error.value = ScriptsError.DELETE_FAILED
            _state.update { it.copy(runs = it.runs - name) }
            refresh()
        }
    }

    /** 新建脚本的起始内容：按后缀给 shebang 与一条可运行示例，用户随后在编辑器里改写。 */
    private fun templateFor(name: String): String = when (ScriptRepository.resolveInterpreter(name)) {
        "python3" -> "#!/usr/bin/env python3\n\nprint(\"hello from $name\")\n"
        "node" -> "#!/usr/bin/env node\n\nconsole.log(\"hello from $name\")\n"
        else -> "#!/bin/bash\n\necho \"hello from $name\"\n"
    }

    private companion object {
        /** 从界面运行脚本的超时（毫秒），与 `Bash` 工具的默认超时一致。 */
        const val RUN_TIMEOUT_MS = 120_000L
    }
}
