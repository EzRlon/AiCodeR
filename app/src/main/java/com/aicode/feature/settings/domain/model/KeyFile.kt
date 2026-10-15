package com.aicode.feature.settings.domain.model

/** 密钥文件的来源位置。 */
enum class KeySource { LIBRARY, SSH, WORKSPACE }

/** 密钥文件的类别标签（展示用）：发布签名 / SSH / 其他。 */
enum class KeyCategory { RELEASE, SSH, OTHER }

/** 签名密钥类文件的扩展名（工作区扫描过滤与类别判定共用）。 */
val KEY_FILE_SUFFIXES = listOf(".jks", ".keystore", ".p12", ".p8")

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
)

/**
 * 按来源与文件名特征判定类别：SSH 目录一律 SSH；签名/证书类扩展名归发布；
 * 常见 SSH 密钥命名（id_rsa 等）归 SSH；其余归其他。
 */
fun classifyKeyFile(source: KeySource, name: String): KeyCategory {
    if (source == KeySource.SSH) return KeyCategory.SSH
    val lower = name.lowercase()
    if (KEY_FILE_SUFFIXES.any { lower.endsWith(it) }) return KeyCategory.RELEASE
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
