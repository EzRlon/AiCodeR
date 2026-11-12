package com.aicode.feature.workspace.domain.remote.http

import android.content.Context
import android.os.Environment
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 远程管理服务：手机端起局域网 HTTP 服务，电脑浏览器经网页管理手机文件（MT 管理器同款）。
 *
 * 生命周期对齐 [FtpServerManager]：单例 + StateFlow、随开关启停、进程存活期内有效
 * （前台通知为后续增强）。v1 管理根目录 = 外部存储根（/sdcard）。
 */
@Singleton
class RemoteHttpServerManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "RemoteHttpServerManager"

        /** 默认端口：避开 2121（内置 FTP）与常见开发端口（3000/5173/8000/8080）。 */
        const val DEFAULT_PORT = 8300

        /** 访问令牌长度（hex 字符数 = 128 位随机熵）。 */
        const val TOKEN_HEX_CHARS = 32
    }

    private var server: RemoteHttpServer? = null

    /** 本次服务启动期间有效的访问令牌（停止后失效，下次启动重新生成）。 */
    @Volatile
    private var accessToken: String? = null

    /** 启停互斥：快速连点开关时避免并发交错产生「已监听但显示未开启」的状态。 */
    private val switchLock = Mutex()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val rootPath: String by lazy { Environment.getExternalStorageDirectory().absolutePath }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "获取本机 IP 失败: ${e.message}", e)
        }
        return "127.0.0.1"
    }

    private fun updateServerUrl() {
        // URL 直接携带令牌：浏览器打开完整地址即可用，无需手动输入；令牌不落盘、停止即失效。
        val t = accessToken.orEmpty()
        _serverUrl.value = "http://${getLocalIpAddress()}:$DEFAULT_PORT/?t=$t"
    }

    suspend fun toggleServer(): Boolean = withContext(Dispatchers.IO) {
        switchLock.withLock {
            if (_isRunning.value) {
                stopServerInternal()
                false
            } else {
                startServerInternal()
            }
        }
    }

    suspend fun startServer(): Boolean = withContext(Dispatchers.IO) {
        switchLock.withLock {
            if (_isRunning.value) true else startServerInternal()
        }
    }

    suspend fun stopServer() = withContext(Dispatchers.IO) {
        switchLock.withLock {
            if (_isRunning.value) stopServerInternal()
        }
    }

    private fun startServerInternal(): Boolean {
        try {
            _errorMessage.value = null
            stopServerInternal()

            val root = File(rootPath)
            if (!root.canRead()) {
                _errorMessage.value = "无法读取外部存储，请先授予存储权限"
                _isRunning.value = false
                return false
            }

            val token = newToken()
            val srv = RemoteHttpServer(context, DEFAULT_PORT, root, token)
            accessToken = token
            srv.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = srv
            _isRunning.value = true
            updateServerUrl()
            FileLogger.i(TAG, "远程管理服务已启动: ${_serverUrl.value}, 根目录: $rootPath")
            return true
        } catch (e: Exception) {
            FileLogger.e(TAG, "启动远程管理服务失败: ${e.message}", e)
            _errorMessage.value = e.message ?: "启动失败，端口可能被占用"
            _isRunning.value = false
            return false
        }
    }

    private fun stopServerInternal() {
        try {
            server?.stop()
            server = null
            accessToken = null
            _isRunning.value = false
            FileLogger.i(TAG, "远程管理服务已停止")
        } catch (e: Exception) {
            FileLogger.e(TAG, "停止远程管理服务出错: ${e.message}", e)
        }
    }

    /** 生成 128 位随机 hex 令牌（SecureRandom，不落盘）。 */
    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_HEX_CHARS / 2)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
