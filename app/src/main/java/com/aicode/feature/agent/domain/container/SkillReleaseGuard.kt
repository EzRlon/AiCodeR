package com.aicode.feature.agent.domain.container

import com.aicode.feature.workspace.domain.sha256Fingerprint
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * 内置技能释放的**用户改动保护**。
 *
 * 背景：内置技能随 App 版本分发，升级时要整体重写同名目录（先删再释放），这样版本间删减/
 * 移动的文件才不会残留。代价是**用户改过的同名内置技能会被静默覆盖**——既有取舍，但会丢用户数据。
 *
 * 这里改成「按内容指纹判断」：释放前算一次目标目录的内容指纹，与上次释放时记下的指纹比对——
 * - 一致（用户没动过）→ 照常整体重释放，技能随版本更新；
 * - 不一致（用户改过）→ **跳过并上报**，不覆盖用户的改动；
 * - 目标不存在 → 正常释放（新技能）。
 *
 * **无记录但目录已存在**是最危险的一种：可能是用户自建的同名技能（绝不能删），也可能是本机制
 * 上线前旧版本释放的技能。二者无法区分，故一律按「用户改动」跳过，只在该技能名称首次随本机制
 * 释放时才建立记录——代价是旧版已释放的技能这一轮不更新，换来的是不丢用户数据（更保守、可接受）。
 *
 * 指纹清单落在 skills 目录**外**（`skills.builtin_manifest.json` 与 `.builtin_skills_version` 同级，
 * 理由同版本戳：放进 skills 会随备份一起走，从同版本备份恢复时指纹会「意外对上」，反而跳过应做的更新）。
 *
 * 纯文件 IO + 纯函数，不依赖 Android Context，便于单测。
 */
internal object SkillReleaseGuard {

    /** 单次判断的结论。 */
    enum class Decision {
        /** 目标不存在 → 直接释放（新技能 / 新增技能）。 */
        RELEASE_NEW,

        /** 目标是内置版且用户没改过 → 整体重释放，随版本更新。 */
        RELEASE_UPDATE,

        /** 目标已被改动、或来源不明 → 跳过，不覆盖。 */
        SKIP_USER_MODIFIED
    }

    /**
     * 决定某个内置技能目录该不该释放。
     *
     * @param dest 目标技能目录（可能不存在）
     * @param recordedFingerprint 上次释放时记录的指纹；无记录传 null
     */
    fun decide(dest: File, recordedFingerprint: String?): Decision = when {
        !exists(dest) -> Decision.RELEASE_NEW
        recordedFingerprint == null -> Decision.SKIP_USER_MODIFIED
        fingerprintsEqual(directoryFingerprint(dest), recordedFingerprint) -> Decision.RELEASE_UPDATE
        else -> Decision.SKIP_USER_MODIFIED
    }

    /**
     * 目录内容指纹：对「相对路径 + 文件内容」按名排序后整体做 SHA-256。
     *
     * 只算常规文件的内容与相对路径；目录本身、符号链接的目标、文件权限都不参与——
     * 权限不随 assets 保留（APK 不存执行位），若参与会让每次升级都误判为「用户改过」。
     * 目录不存在或不可读时返回空串，调用方按「无法判定」处理（见 [fingerprintsEqual]）。
     */
    fun directoryFingerprint(dir: File): String {
        val parts = mutableListOf<String>()
        collectForFingerprint(dir, "", parts)
        if (parts.isEmpty()) return ""
        return sha256Fingerprint(parts.joinToString("\n").toByteArray(Charsets.UTF_8))
    }

    /** 指纹是否「确定一致」：任一侧为空（空目录 / 不可读）都当不一致，落入「跳过」这一保守侧。 */
    fun fingerprintsEqual(a: String, b: String): Boolean =
        a.isNotEmpty() && b.isNotEmpty() && a == b

    private fun collectForFingerprint(dir: File, prefix: String, out: MutableList<String>) {
        val children = runCatching { dir.listFiles() }.getOrNull() ?: run {
            out += "U:$prefix"
            return
        }
        for (child in children.sortedBy { it.name }) {
            val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            when {
                // 符号链接不进指纹：内容不在本目录树内，读它可能下钻到目录外。
                Files.isSymbolicLink(child.toPath()) -> out += "L:$relative"
                isRealDirectory(child) -> collectForFingerprint(child, relative, out)
                child.isFile -> {
                    val bytes = runCatching { child.readBytes() }.getOrNull()
                    out += if (bytes == null) "U:$relative" else "F:$relative:${sha256Fingerprint(bytes)}"
                }
            }
        }
    }

    /** 真目录判定（NOFOLLOW），与 SafeDelete 同一口径：符号链接即便指向目录也不算目录。 */
    private fun isRealDirectory(file: File): Boolean = runCatching {
        file.isDirectory && !Files.isSymbolicLink(file.toPath())
    }.getOrDefault(false)

    /** 目录是否存在（NOFOLLOW）。 */
    fun exists(dir: File): Boolean = Files.exists(dir.toPath(), LinkOption.NOFOLLOW_LINKS)
}
