package com.aicode.feature.settings.domain.model

/** 密钥文件的来源位置。 */
enum class KeySource { LIBRARY, SSH, WORKSPACE }

/** 一次发布就绪检查的完整结论（逐项 + 总判定 + 关键路径，供打包发布资产复用）。 */
data class ReleaseReadiness(
    val ready: Boolean,
    val items: List<ReadinessItem>,
    /** 检查中命中的证书路径；未找到证书为 null。 */
    val keystorePath: String? = null,
    /** 配套的口令配置路径；未找到为 null。 */
    val configPath: String? = null,
    /** 解析出的配置（含口令，**仅供打包发布资产时原样带走**，UI 不展示明文）。 */
    val config: Map<String, String> = emptyMap()
) {
    companion object {
        const val KEY_CERT = "cert"
        const val KEY_CONFIG = "config"
        const val KEY_ENTRIES = "entries"
        const val KEY_ALIAS = "alias"
    }
}

/** 就绪检查的单项结果。 */
data class ReadinessItem(
    /** 检查项标识（[ReleaseReadiness.KEY_*]）。 */
    val key: String,
    val passed: Boolean,
    /** 一句话结论（大白话，直接展示）。 */
    val detail: String
)

/** 解析后的签名配置（供打包资产与交叉验证使用；不含展示逻辑）。 */
typealias KeystoreConfig = Map<String, String>


/** 密钥文件的类别标签（展示用）：发布签名 / SSH / 其他。 */
enum class KeyCategory { RELEASE, SSH, OTHER }

/** 签名密钥类文件的扩展名（工作区扫描过滤与类别判定共用）。 */
val KEY_FILE_SUFFIXES = listOf(".jks", ".keystore", ".p12", ".p8")

/**
 * 发布签名配套的口令配置文件名（与 [com.aicode.feature.settings.domain.service.ReleaseReadinessChecker]
 * 同口径）：证书本身签不出包，`storeFile / storePassword / keyAlias / keyPassword` 跟着一起备份才算完备。
 * 只按精确文件名判定，避免把 `gradle.properties` 等普通 properties 混进密钥清单。
 */
val SIGNING_CONFIG_NAMES = listOf("keystore.properties", "signing.properties")

/** 是否为签名配套的口令配置（精确文件名，不区分大小写）。 */
fun isSigningConfigName(name: String): Boolean =
    name.trim().lowercase() in SIGNING_CONFIG_NAMES

/**
 * 扫描到的一个密钥文件条目。
 *
 * [containerPath] 为容器视角路径（展示、复制、后续操作共用）；
 * [fingerprint] 为 `sha256:<hex>` 内容指纹，超大文件不计算（null）。
 */
data class KeyFile(
    val name: String,
    val containerPath: String,
    val source: KeySource,
    val category: KeyCategory,
    val size: Long,
    val lastModified: Long,
    val fingerprint: String?,
    /** 该条目是签名配套的口令配置（而非证书本体）：发布就绪检查挑证书时必须排除。 */
    val isSigningConfig: Boolean = false,
)

/**
 * 按来源与文件名特征判定类别：SSH 目录一律 SSH；签名/证书类扩展名归发布；
 * 常见 SSH 密钥命名（id_rsa 等）归 SSH；其余归其他。
 */
fun classifyKeyFile(source: KeySource, name: String): KeyCategory {
    if (source == KeySource.SSH) return KeyCategory.SSH
    val lower = name.lowercase()
    if (KEY_FILE_SUFFIXES.any { lower.endsWith(it) }) return KeyCategory.RELEASE
    // 口令配置与证书同属发布签名材料：「导出全部」漏了它，发布当天同样签不出包
    if (isSigningConfigName(lower)) return KeyCategory.RELEASE
    if (lower.endsWith(".pub") ||
        lower.startsWith("id_rsa") ||
        lower.startsWith("id_ed25519") ||
        lower.startsWith("id_ecdsa") ||
        lower.startsWith("id_dsa")
    ) {
        return KeyCategory.SSH
    }
    return KeyCategory.OTHER
}
