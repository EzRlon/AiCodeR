package com.aicode.feature.settings.domain.service

import com.aicode.feature.settings.domain.model.KeystoreConfig
import com.aicode.feature.settings.domain.model.ReleaseReadiness
import com.aicode.feature.settings.domain.model.ReadinessItem
import com.aicode.feature.settings.domain.model.SIGNING_CONFIG_NAMES
import com.aicode.feature.workspace.domain.FileAccessProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 发布就绪检查：回答「现在打正式包，够不够」——而不是只列密钥文件。
 *
 * 缘起：本项目发布曾踩坑——备份只带走了 `.jks` 证书，同目录的 `keystore.properties`
 * （storePassword/keyAlias/keyPassword）没跟上，正式包在发布当天签不出来。
 * 单个文件存在 ≠ 发布就绪；就绪 = **证书 + 配套口令配置 + 两者互相验证**全绿。
 *
 * 检查项：
 * 1. 证书存在（`.jks`/`.keystore`，取统一密钥库优先、工作区次之）；
 * 2. 配套配置存在（同目录 `keystore.properties`，或 jks 同名 `.properties`）；
 * 3. 配置可解析且含 storeFile/storePassword/keyAlias/keyPassword 四要素；
 * 4. 配置里的 alias 真实存在于证书（防「密码文件与证书对不上」——用 keytool 兜不了时降级为结构校验）。
 */
@Singleton
class ReleaseReadinessChecker @Inject constructor(
    private val fileAccess: FileAccessProvider
) {
    companion object {
        /** 配套配置的候选文件名（按序找第一个存在的）；与密钥扫描 / 导出清单共用同一份定义。 */
        private val CONFIG_CANDIDATES = SIGNING_CONFIG_NAMES

        /** 配置必须包含的四要素。 */
        private val REQUIRED_ENTRIES = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    }

    suspend fun check(jksPath: String?): ReleaseReadiness {
        if (jksPath == null) {
            return ReleaseReadiness(
                ready = false,
                items = listOf(
                    ReadinessItem(
                        key = ReleaseReadiness.KEY_CERT,
                        passed = false,
                        detail = "未找到签名证书（.jks / .keystore）"
                    )
                )
            )
        }
        val items = mutableListOf<ReadinessItem>()
        items += ReadinessItem(
            key = ReleaseReadiness.KEY_CERT,
            passed = true,
            detail = jksPath.substringAfterLast('/')
        )

        // ── 配套配置：jks 同目录按候选名找，找不到再看同名 .properties ──
        val parent = jksPath.substringBeforeLast('/', missingDelimiterValue = "")
        val jksStem = jksPath.substringAfterLast('/').substringBeforeLast('.')
        val configPath = CONFIG_CANDIDATES.firstOrNull { name ->
            fileAccess.exists("$parent/$name")
        } ?: "$jksStem.properties".takeIf { stem ->
            fileAccess.exists("$parent/$stem")
        }?.let { "$parent/$it" }

        if (configPath == null) {
            items += ReadinessItem(
                key = ReleaseReadiness.KEY_CONFIG,
                passed = false,
                detail = "缺 keystore.properties（storePassword/keyAlias/keyPassword）——只有证书签不出包，这正是本项目当年发布踩过的坑"
            )
            return ReleaseReadiness(ready = false, items = items, keystorePath = jksPath)
        }
        items += ReadinessItem(
            key = ReleaseReadiness.KEY_CONFIG,
            passed = true,
            detail = configPath.substringAfterLast('/')
        )

        // ── 配置四要素 ──
        val content = runCatching { fileAccess.readFile(configPath) }.getOrNull()
        if (content == null) {
            items += ReadinessItem(
                key = ReleaseReadiness.KEY_ENTRIES,
                passed = false,
                detail = "配置文件读取失败"
            )
            return ReleaseReadiness(ready = false, items = items, keystorePath = jksPath, configPath = configPath)
        }
        val config = parseKeystoreProperties(content)
        val missing = REQUIRED_ENTRIES.filter { config[it].isNullOrBlank() }
        if (missing.isNotEmpty()) {
            items += ReadinessItem(
                key = ReleaseReadiness.KEY_ENTRIES,
                passed = false,
                detail = "配置缺字段：${missing.joinToString(", ")}"
            )
            return ReleaseReadiness(ready = false, items = items, keystorePath = jksPath, configPath = configPath)
        }
        items += ReadinessItem(
            key = ReleaseReadiness.KEY_ENTRIES,
            passed = true,
            detail = "storeFile / storePassword / keyAlias / keyPassword 齐备"
        )

        // ── alias 与证书交叉验证：keytool 可用就真验，不可用降级为「已解析」半通过 ──
        val aliasOk = runCatching { verifyAlias(jksPath, config) }.getOrDefault(false)
        items += ReadinessItem(
            key = ReleaseReadiness.KEY_ALIAS,
            passed = aliasOk,
            detail = if (aliasOk) {
                "别名「${config["keyAlias"]}」已在证书中验证"
            } else {
                "别名「${config["keyAlias"]}」未能自动验证（容器内无 keytool 或证书不可读）；建议发布前手动跑一次 assembleRelease"
            }
        )

        return ReleaseReadiness(
            ready = items.all { it.passed },
            items = items,
            keystorePath = jksPath,
            configPath = configPath,
            config = config
        )
    }

    /** 解析 `.properties` 形态的签名配置（行内 `#` 注释与空行跳过）。 */
    internal fun parseKeystoreProperties(content: String): Map<String, String> =
        content.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
            .associate { line ->
                val idx = line.indexOf('=')
                line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }

    /**
     * 验证 alias 是否在证书里：容器内跑 `keytool -list`（PATH 里有 JDK 就能行）。
     * 失败（无 keytool / 密码不对 / 证书损坏）返回 false，调用方降级处理。
     */
    private fun verifyAlias(jksPath: String, config: Map<String, String>): Boolean {
        val local = runCatching { fileAccess.copyToLocal(jksPath) }.getOrNull() ?: return false
        return runCatching {
            val process = ProcessBuilder(
                "keytool", "-list", "-keystore", local.absolutePath,
                "-storepass", config["storePassword"].orEmpty()
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            val alias = config["keyAlias"].orEmpty()
            process.exitValue() == 0 && output.contains(alias, ignoreCase = true)
        }.getOrDefault(false)
    }
}
