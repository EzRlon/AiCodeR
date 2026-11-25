package com.aicode.feature.settings.domain

import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.core.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 容器内 llama-server 的 App 侧持有者。
 *
 * 经 [LinuxContainerEngine.startStdioProcess] 拉起（与 CLI 引擎同机制：App 直接持有 PRoot
 * 进程引用，存活期完全受控），不再走「同步命令 + nohup 后台」——同步命令返回后容器进程被
 * 回收，ptrace 下后台任务往往还没 exec 完就被连根清掉，服务起不来。
 *
 * stdout/stderr 由守护线程消费进环形缓冲：既把最近日志留给 UI 展示，也避免没人读管道
 * 写满 64KB 缓冲后 llama-server 写日志阻塞。
 */
@Singleton
class LocalLlamaServerManager @Inject constructor(
    private val containerEngine: LinuxContainerEngine
) {

    companion object {
        const val SERVER_PORT = 8080
        const val SERVER_BASE_URL = "http://127.0.0.1:8080/v1"

        /** 容器内路径（容器 HOME=/root，与场景脚本安装位置一致）。 */
        private const val LLAMA_SERVER_BIN = "/usr/local/bin/llama-server"
        private const val MODELS_DIR = "/root/.aicode/local-llm/models"
        private const val LOG_LINES_MAX = 60
        private const val PROBE_TIMEOUT_MS = 1_500
    }

    private val processRef = AtomicReference<Process?>(null)

    private val logBuffer = ArrayDeque<String>()

    /** 最近的服务日志（启动输出与运行期 stderr），供「服务未就绪」时展示末尾。 */
    fun recentLog(): List<String> = synchronized(logBuffer) { logBuffer.toList() }

    /** App 侧直接探测 /v1/models（容器无独立网络栈，127.0.0.1 直通）。 */
    suspend fun probe(): Boolean = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL("$SERVER_BASE_URL/models").openConnection() as HttpURLConnection
            conn.connectTimeout = PROBE_TIMEOUT_MS
            conn.readTimeout = PROBE_TIMEOUT_MS
            conn.responseCode == 200
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 拉起 llama-server（阻塞到进程已 spawn）。返回 null 表示已提交启动；
     * 返回字符串为错误说明（文件名校验失败/进程立刻退出等）。
     *
     * @param deepThinking 深度思考开关：关（默认）时 --reasoning off 直接回答——本地小模型
     * 思考质量差且带思考时生成速度只有约 1/10（实测 3 tok/s vs 28 tok/s）；开时走模型默认
     * （思考型模板会先生成推理），适合复杂/逻辑问题。
     */
    fun start(modelFileName: String, deepThinking: Boolean = false): String? {
        if (!modelFileName.endsWith(".gguf") || modelFileName.contains('/') || modelFileName.startsWith(".")) {
            return "模型文件名不合法：$modelFileName"
        }
        if (processRef.get()?.isAlive == true) return "服务已在运行中"

        synchronized(logBuffer) { logBuffer.clear() }
        val process = try {
            containerEngine.startStdioProcess(
                program = LLAMA_SERVER_BIN,
                programArgs = buildList {
                    add("-m"); add("$MODELS_DIR/$modelFileName")
                    add("--host"); add("127.0.0.1")
                    add("--port"); add(SERVER_PORT.toString())
                    if (!deepThinking) {
                        add("--reasoning"); add("off")
                    }
                },
                projectPath = null
            )
        } catch (e: Exception) {
            FileLogger.w(TAG, "llama-server 拉起失败", e)
            return "服务拉起失败：${e.message ?: e.javaClass.simpleName}"
        }
        processRef.set(process)
        startLogPump(process)
        return null
    }

    /** 停止自持实例；终端等外部方式启动的实例由调用方另行 pkill 兜底。 */
    fun stopSelf() {
        val process = processRef.getAndSet(null) ?: return
        runCatching { process.destroy() }
        runCatching {
            if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        }
    }

    /** 后台消费 stdout/stderr 进环形缓冲。 */
    private fun startLogPump(process: Process) {
        val pump = Runnable {
            val readers = listOfNotNull(
                runCatching { BufferedReader(InputStreamReader(process.inputStream)) }.getOrNull(),
                runCatching { BufferedReader(InputStreamReader(process.errorStream)) }.getOrNull()
            )
            readers.forEach { reader ->
                Thread {
                    try {
                        var line = reader.readLine()
                        while (line != null) {
                            appendLog(line)
                            line = reader.readLine()
                        }
                    } catch (_: Exception) {
                        // 进程退出/流关闭即结束
                    }
                }.apply { isDaemon = true; name = "llama-log-pump" }.start()
            }
        }
        Thread(pump).start()
    }

    private fun appendLog(line: String) {
        synchronized(logBuffer) {
            logBuffer.addLast(line)
            while (logBuffer.size > LOG_LINES_MAX) logBuffer.removeFirst()
        }
    }

    private val TAG = "LocalLlamaServer"
}
