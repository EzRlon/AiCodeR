package com.aicode.feature.workspace.domain

/**
 * 文件访问抽象的事实源在 core/io/FileAccessProvider.kt（core 层不得反向依赖 feature；
 * Markdown 图片加载等 core UI 需要该能力）。此处 typealias 保兼容既有 import 路径。
 */
typealias FileAccessProvider = com.aicode.core.io.FileAccessProvider

/** @see com.aicode.core.io.FileEntry */
typealias FileEntry = com.aicode.core.io.FileEntry

/** @see com.aicode.core.io.isValidFileEntryName */
fun isValidFileEntryName(name: String): Boolean =
    com.aicode.core.io.isValidFileEntryName(name)

/** @see com.aicode.core.io.sha256Fingerprint */
internal fun sha256Fingerprint(bytes: ByteArray): String =
    com.aicode.core.io.sha256Fingerprint(bytes)
