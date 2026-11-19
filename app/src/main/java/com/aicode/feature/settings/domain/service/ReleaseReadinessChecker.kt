package com.aicode.feature.settings.domain.service

import android.content.Context
import com.aicode.R
import com.aicode.feature.settings.domain.model.ReadinessItem
import com.aicode.feature.settings.domain.model.ReleaseReadiness
import com.aicode.feature.settings.domain.model.SIGNING_CONFIG_NAMES
import com.aicode.feature.workspace.domain.FileAccessProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.StringReader
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 发布就绪检查：回答「现在打正式包，够不够」——而不是只列密钥文件。
 *
 * 关键修正（本轮）：Gradle 实际读取的是**项目里的 `app/keystore.properties`**
 * （见 app/build.gradle.kts 的 signingConfigs），旧实现只找「证书同目录」的配置，
 * 会把「证书在统一密钥库、口令配置在项目里」这种正常布局误判为未就绪。
 * 现在优先检查项目配置，并解析 `storeFile` 验证它指向的证书是否真的存在。
 *
 * 检查项：
 * 1. 证书存在（`.jks`/`.keystore`）；
 * 2. 口令配置存在（项目 `app/keystore.properties` 优先，其次证书同目录）；
 * 3. 配置含 storeFile/storePassword/keyAlias/keyPassword；
 * 4. `storeFile` 按项目目录解析后存在；
 * 5. alias 是否在证书里（证书读不了时记为「无法自动验证」，不阻断）；
 * 6. `keyPassword`、签名方案与证书元数据：用 Android 内置 KeyStore API 真实验证
 *    （加载证书 + getKey 试钥 + 读证书条目），零外部依赖——keytool 只存在于容器里，
 *    App 进程执行不了，用它永远只剩「未验证」。
 */
@Singleton
class ReleaseReadinessChecker @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val fileAccess: FileAccessProvider
) {
    companion object {
        /** 配套配置的候选文件名（按序找第一个存在的）；与密钥扫描 / 导出清单共用同一份定义。 */
        private val CONFIG_CANDIDATES = SIGNING_CONFIG_NAMES

        /** 配置必须包含的四要素。 */
        private val REQUIRED_ENTRIES = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    }

    private enum class AliasCheck { VERIFIED, MISMATCH, UNKNOWN }

    suspend fun check(
        jksPath: String?,
        projectConfigPath: String? = null,
        projectName: String? = null
    ): ReleaseReadiness {
        if (jksPath == null) {
            return ReleaseReadiness(
                ready = false,
                projectName = projectName,
                items = listOf(
                    ReadinessItem(
                        key = ReleaseReadiness.KEY_CERT,
                        passed = false,
                        detail = context.getString(R.string.dev_keys_readiness_detail_no_cert),
                        nextStep = context.getString(R.string.dev_keys_readiness_next_no_cert)
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

        // ── 口令配置：项目 app/keystore.properties 优先（Gradle 真正读取的位置）──
        val parent = jksPath.substringBeforeLast('/', missingDelimiterValue = "")
        val jksStem = jksPath.substringAfterLast('/').substringBeforeLast('.')
        val candidates = buildList {
            projectConfigPath?.takeIf { it.isNotBlank() }?.let { add(it) }
            CONFIG_CANDIDATES.forEach { add("$parent/$it") }
            add("$parent/$jksStem.properties")
        }
        val configPath = candidates.firstOrNull { fileAccess.exists(it) }
        if (configPath == null) {
            items += ReadinessItem(
                key = ReleaseReadiness.KEY_CONFIG,
                passed = false,
                detail = context.getString(R.string.dev_keys_readiness_detail_no_config),
                nextStep = context.getString(R.string.dev_keys_readiness_next_no_config)
            )
            return ReleaseReadiness(
                ready = false,
                items = items,
                keystorePath = jksPath,
                projectName = projectName
            )
        }
        // Gradle 只读项目 app/keystore.properties；根目录或证书同目录的配置只能用于迁移，
        // 不能据此判定「正式包会签名」，否则会出现页面绿、构建实际 unsigned 的假绿。
        val isGradleConfig = projectConfigPath != null && configPath == projectConfigPath
        items += ReadinessItem(
            key = ReleaseReadiness.KEY_CONFIG,
            passed = isGradleConfig,
            detail = if (isGradleConfig) {
                context.getString(
                    R.string.dev_keys_readiness_detail_config_ok,
                    configPath.substringAfterLast('/')
                )
            } else {
                context.getString(
                    R.string.dev_keys_readiness_detail_config_misplaced,
                    configPath.substringAfterLast('/')
                )
            },
            nextStep = if (isGradleConfig) {
                null
            } else {
                context.getString(R.string.dev_keys_readiness_next_config_misplaced)
            }
        )

        // ── 配置四要素 ──
        val content = runCatching { fileAccess.readFile(configPath) }.getOrNull()
        if (content == null) {
            items += ReadinessItem(
                key = ReleaseReadiness.KEY_ENTRIES,
                passed = false,
                detail = context.getString(R.string.dev_keys_readiness_detail_config_unreadable),
                nextStep = context.getString(R.string.dev_keys_readiness_next_config_unreadable)
            )
            return ReleaseReadiness(
                ready = false,
                items = items,
                keystorePath = jksPath,
                configPath = configPath,
                projectName = projectName
            )
        }
        val config = parseKeystoreProperties(content)
        val missing = REQUIRED_ENTRIES.filter { config[it].isNullOrBlank() }
        if (missing.isNotEmpty()) {
            items += ReadinessItem(
                key = ReleaseReadiness.KEY_ENTRIES,
                passed = false,
                detail = context.getString(
                    R.string.dev_keys_readiness_detail_missing_entries,
                    missing.joinToString(", ")
                ),
                nextStep = context.getString(R.string.dev_keys_readiness_next_missing_entries)
            )
            return ReleaseReadiness(
                ready = false,
                items = items,
                keystorePath = jksPath,
                configPath = configPath,
                config = config,
                projectName = projectName
            )
        }
        items += ReadinessItem(
            key = ReleaseReadiness.KEY_ENTRIES,
            passed = true,
            detail = context.getString(R.string.dev_keys_readiness_detail_entries_ok)
        )

        // ── storeFile 解析：Gradle 的 file(...) 以 app 模块目录为基准 ──
        val storeRaw = config["storeFile"].orEmpty()
        val usesHomePath = storeRaw.trim().startsWith("~/")
        val storePath = resolveStoreFile(storeRaw, configPath)
        // Gradle 的 file() 不展开 ~；用 ~ 的配置在 App 里看得到、构建时却找不到，必须判失败。
        val storeExists = !usesHomePath && storePath != null &&
            runCatching { fileAccess.exists(storePath) }.getOrDefault(false)
        items += ReadinessItem(
            key = ReleaseReadiness.KEY_STORE_FILE,
            passed = storeExists,
            detail = when {
                usesHomePath -> context.getString(R.string.dev_keys_readiness_detail_store_tilde)
                storeExists -> context.getString(
                    R.string.dev_keys_readiness_detail_store_ok,
                    storePath!!.substringAfterLast('/')
                )
                else -> context.getString(
                    R.string.dev_keys_readiness_detail_store_missing,
                    storeRaw
                )
            },
            nextStep = when {
                usesHomePath -> context.getString(R.string.dev_keys_readiness_next_store_tilde)
                storeExists -> null
                else -> context.getString(R.string.dev_keys_readiness_next_store_missing)
            }
        )

        // ── alias 与证书交叉验证 ──
        when (val alias = verifyAlias(jksPath, config)) {
            AliasCheck.VERIFIED -> items += ReadinessItem(
                key = ReleaseReadiness.KEY_ALIAS,
                passed = true,
                detail = context.getString(
                    R.string.dev_keys_readiness_detail_alias_ok,
                    config["keyAlias"]
                )
            )
            AliasCheck.MISMATCH -> items += ReadinessItem(
                key = ReleaseReadiness.KEY_ALIAS,
                passed = false,
                detail = context.getString(
                    R.string.dev_keys_readiness_detail_alias_mismatch,
                    config["keyAlias"]
                ),
                nextStep = context.getString(R.string.dev_keys_readiness_next_alias_mismatch)
            )
            AliasCheck.UNKNOWN -> items += ReadinessItem(
                key = ReleaseReadiness.KEY_ALIAS,
                passed = false,
                blocking = false,
                detail = context.getString(
                    R.string.dev_keys_readiness_detail_alias_unknown,
                    config["keyAlias"]
                ),
                nextStep = context.getString(R.string.dev_keys_readiness_next_alias_unknown)
            )
        }

        // keyPassword / 签名方案 / 证书元数据：有 keytool 就真实验证，不伪造结论；
        // 无 keytool 才退回「未验证」（不阻断）。
        val facts = verifyKeystoreFacts(jksPath, config)
        items += ReadinessItem(
            key = ReleaseReadiness.KEY_KEY_PASSWORD,
            passed = facts?.keyPasswordVerified == true,
            blocking = facts?.keyPasswordVerified == false,
            detail = when {
                facts == null -> context.getString(R.string.dev_keys_readiness_detail_key_password)
                facts.keyPasswordVerified == true -> context.getString(
                    R.string.dev_keys_readiness_detail_key_password_ok
                )
                else -> context.getString(R.string.dev_keys_readiness_detail_key_password_bad)
            },
            nextStep = when {
                facts?.keyPasswordVerified == false -> context.getString(
                    R.string.dev_keys_readiness_next_key_password_bad
                )
                facts == null -> context.getString(R.string.dev_keys_readiness_next_key_password)
                else -> null
            }
        )

        // 签名方案：从证书解析实际签名算法。
        items += ReadinessItem(
            key = ReleaseReadiness.KEY_SIGNING_SCHEME,
            passed = facts?.signingAlgorithm != null,
            blocking = false,
            detail = if (facts?.signingAlgorithm != null) {
                context.getString(
                    R.string.dev_keys_readiness_detail_signing_scheme_ok,
                    facts.signingAlgorithm!!
                )
            } else {
                context.getString(R.string.dev_keys_readiness_detail_signing_scheme)
            },
            nextStep = if (facts?.signingAlgorithm != null) {
                null
            } else {
                context.getString(R.string.dev_keys_readiness_next_signing_scheme)
            }
        )

        // 证书元数据：从证书解析有效期。
        items += ReadinessItem(
            key = ReleaseReadiness.KEY_CERT_META,
            passed = facts?.certValidUntil != null,
            blocking = false,
            detail = if (facts?.certValidUntil != null) {
                context.getString(
                    R.string.dev_keys_readiness_detail_cert_meta_ok,
                    facts.certValidUntil!!
                )
            } else {
                context.getString(R.string.dev_keys_readiness_detail_cert_meta)
            },
            nextStep = if (facts?.certValidUntil != null) {
                null
            } else {
                context.getString(R.string.dev_keys_readiness_next_cert_meta)
            }
        )

        return ReleaseReadiness(
            ready = items.all { it.passed || !it.blocking },
            items = items,
            keystorePath = jksPath,
            configPath = configPath,
            config = config,
            projectName = projectName,
            storeFileRaw = storeRaw,
            storeFilePath = storePath
        )
    }

    /** 解析 `.properties` 形态的签名配置（支持转义、续行与 `!` 注释，与 Gradle 同语义）。 */
    internal fun parseKeystoreProperties(content: String): Map<String, String> = runCatching {
        val properties = Properties()
        properties.load(StringReader(content))
        properties.stringPropertyNames().associateWith { properties.getProperty(it).trim() }
    }.getOrDefault(emptyMap())

    /**
     * 把配置里的 storeFile 解析成容器路径：
     * - 绝对路径原样；`~/` 交给 FileAccessProvider 展开；
     * - 相对路径以 app 模块目录为基准（与 Gradle `file(...)` 一致），兼容配置放在项目根的情况。
     */
    internal fun resolveStoreFile(raw: String, configPath: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        if (value.startsWith("/") || value.startsWith("~/")) return value
        val configDir = configPath.substringBeforeLast('/', missingDelimiterValue = "")
        if (configDir.isEmpty()) return null
        val dirs = if (configDir.substringAfterLast('/') == "app") {
            listOf(configDir)
        } else {
            listOf("$configDir/app", configDir)
        }
        return dirs.asSequence()
            .map { dir -> "$dir/${value.removePrefix("./")}" }
            .firstOrNull { runCatching { fileAccess.exists(it) }.getOrDefault(false) }
            ?: "$configDir/${value.removePrefix("./")}"
    }

    /**
     * 用 Android 内置 KeyStore API 真实验证别名 / keyPassword / 签名算法 / 证书有效期：
     * - 加载证书（先 PKCS12 后 JKS）：storePassword 错误时打不开；
     * - `getKey` 试钥：keyPassword 错误抛 UnrecoverableKeyException；
     * - `X509Certificate`：直接读签名算法与有效期。
     * 文件复制失败时返回 null（按「未验证」处理，不阻断）。
     */
    private data class KeystoreFacts(
        val storeOpenable: Boolean?,
        val aliasPresent: Boolean?,
        val keyPasswordVerified: Boolean?,
        val signingAlgorithm: String?,
        val certValidUntil: String?
    )

    private fun verifyKeystoreFacts(jksPath: String, config: Map<String, String>): KeystoreFacts? {
        val local = runCatching { fileAccess.copyToLocal(jksPath) }.getOrNull() ?: return null
        // 证书本体读不到（storeFile 缺失 / 远程复制失败）时全部按「未验证」处理，
        // 不误报成「口令错误」；storeFile 缺失由 KEY_STORE_FILE 项单独报错。
        if (!local.exists()) return null
        val storePass = config["storePassword"].orEmpty()
        val keyPass = config["keyPassword"].orEmpty()
        val alias = config["keyAlias"].orEmpty()
        // PKCS12 是 keytool 现代默认；老 .jks 是 JKS。加载失败两种类型都试过再判不可用。
        val store = openKeystore(local, storePass)
        if (store == null) {
            return KeystoreFacts(
                storeOpenable = false,
                aliasPresent = null,
                keyPasswordVerified = null,
                signingAlgorithm = null,
                certValidUntil = null
            )
        }
        val aliasPresent = runCatching { store.isKeyEntry(alias) }.getOrNull()
        // keyPassword 错误时 getKey 抛 UnrecoverableKeyException；别名不存在同样取不到私钥。
        val keyOk = runCatching {
            store.getKey(alias, keyPass.toCharArray())
            true
        }.getOrDefault(false)
        val cert = runCatching {
            store.getCertificate(alias) as? X509Certificate
        }.getOrNull()
        return KeystoreFacts(
            storeOpenable = true,
            aliasPresent = aliasPresent,
            keyPasswordVerified = keyOk,
            signingAlgorithm = cert?.sigAlgName,
            certValidUntil = cert?.notAfter?.let {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).format(it)
            }
        )
    }

    private fun openKeystore(file: java.io.File, storePass: String): KeyStore? {
        for (type in listOf("PKCS12", "JKS")) {
            val ks = runCatching { KeyStore.getInstance(type) }.getOrNull() ?: continue
            val loaded = runCatching {
                file.inputStream().use { ks.load(it, storePass.toCharArray()) }
                ks
            }.getOrNull()
            if (loaded != null) return loaded
        }
        return null
    }

    /**
     * 验证 alias 是否在证书里。
     * 区分「证书读不了」（UNKNOWN，不阻断）与「证书能打开但别名不对 / 口令错」（MISMATCH）。
     */
    private fun verifyAlias(jksPath: String, config: Map<String, String>): AliasCheck {
        val facts = verifyKeystoreFacts(jksPath, config) ?: return AliasCheck.UNKNOWN
        return when {
            facts.storeOpenable == false -> AliasCheck.MISMATCH
            facts.aliasPresent == true -> AliasCheck.VERIFIED
            else -> AliasCheck.MISMATCH
        }
    }
}
