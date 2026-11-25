package com.aicode.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.aicode.core.util.FileLogger
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 敏感数据的对称加密：密钥存放在 Android Keystore（不可导出，有 TEE 时受硬件保护），
 * 数据用 AES-256-GCM 加密。用于私钥文件与数据库里的密码/API Key 等字段。
 *
 * 密钥与设备绑定：清除应用数据、换机直拷数据目录后密文不可解，属预期代价；
 * 跨设备迁移走备份（备份导出为明文、导入时按新设备密钥重新加密）。
 *
 * 明文兼容：解密入口对非密文输入原样返回，便于旧明文数据的平滑迁移。
 */
object KeystoreCipher {
    private const val TAG = "KeystoreCipher"
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "aicode_secret_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12

    /** 字符串密文前缀；不含此前缀的值按旧明文处理。 */
    private const val STRING_PREFIX = "AICODEENC1:"

    /** 字节密文文件头；用于区分加密文件与旧明文文件。 */
    private val BYTE_MAGIC = "AICODEENC1\u0000".toByteArray(Charsets.US_ASCII)

    /** getEntry 重试次数与间隔基数：扛 keystore 服务瞬时异常（升级后首次启动最易触发）。 */
    private const val GET_ENTRY_RETRY = 3

    /** containsAlias 重试次数（与 getEntry 同级）。 */
    private const val ALIAS_CHECK_RETRY = 3
    private const val RETRY_DELAY_MS = 120L

    /**
     * 主密钥哨兵：首次生成主密钥前写入 filesDir，永不主动删除。
     *
     * 这是「本机曾经生成过主密钥」的唯一可信证据——keystore 服务本身不可信：
     * 同一设备已实证 getEntry 会构造垃圾条目（3edafc2），containsAlias 也可能在服务
     * 瞬时异常时说谎返回 false。哨兵存在时，任何「alias 不可见」状态一律拒绝重建：
     * 若为说谎，重建会覆盖真实密钥；若为主密钥真丢失，存量密文本就不可解，重建只会
     * 用新密钥加密后续数据，掩盖丢失并让后续保存静默覆盖死密文——两者都不能发生。
     */
    private const val SENTINEL_FILE = ".keystore-masterkey-created"

    @Volatile
    private var appContext: Context? = null

    /** 必须在 Application.onCreate 最先调用；未初始化前加解密一律拒绝执行（宁可报错不可误重建）。 */
    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private val lock = Any()

    /**
     * 主密钥重建决策：唯一允许重建的组合是「alias 不可见且本机从未生成过主密钥」。
     * 其余任何组合——特别是 alias 不可见但哨兵存在（keystore 说谎或主密钥丢失）——一律拒绝。
     */
    internal fun shouldRegenerate(aliasVisible: Boolean, sentinelExists: Boolean): Boolean =
        !aliasVisible && !sentinelExists

    private fun sentinelFile(): File {
        val ctx = appContext ?: throw IllegalStateException(
            "KeystoreCipher 未初始化：拒绝在无法确认主密钥归属时操作 keystore"
        )
        return File(ctx.filesDir, SENTINEL_FILE)
    }

    private fun sentinelExists(): Boolean = sentinelFile().exists()

    /** 尽力补写哨兵（拿到「曾有主密钥」证据时调用，自愈老版本升级上来的安装）。 */
    private fun markSentinel() {
        runCatching {
            val f = sentinelFile()
            if (!f.exists()) {
                f.parentFile?.mkdirs()
                f.writeText("createdAt=${System.currentTimeMillis()}\n")
            }
        }.onFailure { FileLogger.w(TAG, "补写主密钥哨兵失败（本次不影响加解密）", it) }
    }

    /** 严格写哨兵（生成新密钥前）：写不进去就拒绝生成，否则未来会再次误判「首次使用」。 */
    private fun markSentinelStrict(): Boolean = runCatching {
        val f = sentinelFile()
        if (!f.exists()) {
            f.parentFile?.mkdirs()
            f.writeText("createdAt=${System.currentTimeMillis()}\n")
        }
        true
    }.getOrElse {
        FileLogger.e(TAG, "写入主密钥哨兵失败：拒绝生成新密钥以免未来误重建", it)
        false
    }

    /** containsAlias 带重试：期间出现过异常 → 结果不可信，按「未知」向上抛，绝不当作「不存在」。 */
    private fun containsAliasWithRetry(keyStore: KeyStore): Boolean {
        var sawError = false
        repeat(ALIAS_CHECK_RETRY) { attempt ->
            try {
                if (keyStore.containsAlias(ALIAS)) return true
            } catch (e: Exception) {
                sawError = true
                FileLogger.w(TAG, "查询密钥存在性失败（第 ${attempt + 1} 次）：${e.message}")
            }
            if (attempt < ALIAS_CHECK_RETRY - 1) runCatching { Thread.sleep(RETRY_DELAY_MS shl attempt) }
        }
        if (sawError) throw IllegalStateException("keystore 服务异常：无法确认主密钥存在性，拒绝任何重建")
        return false
    }

    private fun secretKey(): SecretKey = synchronized(lock) {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        // 存在性判定必须用 containsAlias，不能信 getEntry 的结果：部分系统（实测 API 37）keystore
        // 服务瞬时异常时 getEntry 会误构造 TrustedCertificateEntry(null) 抛 NPE。**读取失败 ≠ 密钥
        // 不存在**——若据此走 generateKey，会静默覆盖同 alias 的真实密钥，全部存量密文（供应商
        // API Key 等）永久解不开，即「升级安装后密钥丢失」的根因（3edafc2 引入，本版修复）。
        val aliasPresent = containsAliasWithRetry(keyStore)
        if (aliasPresent) {
            // 别名存在 = 真实密钥还在。此时**任何**读取失败（抛异常或返回 null）都只能向上抛，
            // 绝不允许走到 regenerate——那会覆盖真实密钥、让全部存量密文永久解不开。
            // 宁可本次解密失败（密文完好保留在库里，keystore 服务恢复后即可解）。
            var lastError: Exception? = null
            repeat(GET_ENTRY_RETRY) { attempt ->
                try {
                    val entry = keyStore.getEntry(ALIAS, null)
                    if (entry is KeyStore.SecretKeyEntry) {
                        // 自愈：老版本升级上来的安装没有哨兵，成功读到密钥即补写
                        markSentinel()
                        return entry.secretKey
                    }
                    // 返回 null 或非 SecretKeyEntry：视为读取失败（不是「真坏条目可重建」）。
                    // 历史上正是「按坏条目重建」这一步销毁了用户全部供应商密钥。
                    lastError = IllegalStateException(
                        "alias=$ALIAS 已存在但读取结果为 ${entry?.javaClass?.simpleName ?: "null"}，拒绝重建"
                    )
                } catch (e: Exception) {
                    lastError = e
                }
                FileLogger.w(TAG, "密钥读取第 ${attempt + 1} 次失败：${lastError?.message}")
                runCatching { Thread.sleep(RETRY_DELAY_MS shl attempt) }
            }
            throw IllegalStateException(
                "密钥读取连续 $GET_ENTRY_RETRY 次失败（keystore 服务异常），拒绝重建以免销毁真实密钥",
                lastError
            )
        }
        // alias 不可见。只有「本机从未生成过主密钥」才允许生成；哨兵存在时一律拒绝——
        // containsAlias 说谎会销毁真实密钥，主密钥真丢失时重建也只能掩盖数据丢失。
        val sentinelPresent = sentinelExists()
        if (!shouldRegenerate(aliasVisible = false, sentinelExists = sentinelPresent)) {
            throw IllegalStateException(
                "keystore 中主密钥不可见，但本机曾生成过主密钥（哨兵存在）：疑似 keystore 服务异常或主密钥被系统清除。" +
                    "拒绝重建——重建会覆盖真实密钥，使全部存量密文（供应商 API Key 等）永久不可解。" +
                    "请重启设备后重试；若主密钥确已丢失，可在导出备份后清除应用数据重建（现存密文本就已不可解）。"
            )
        }
        // 首次使用：先落哨兵再生成——即便生成中途进程被杀，下次也不会误判为「首次」而反复重建。
        if (!markSentinelStrict()) {
            throw IllegalStateException("无法写入主密钥哨兵：拒绝生成新密钥以免未来误重建")
        }
        regenerate(keyStore)
    }

    /** 在确认 alias 不存在（或条目确非 AES 密钥）时生成新密钥；同 alias 生成会覆盖旧条目。 */
    private fun regenerate(keyStore: KeyStore): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encryptBytes(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain)
        return BYTE_MAGIC + iv + ciphertext
    }

    fun isEncryptedBytes(bytes: ByteArray): Boolean =
        bytes.size >= BYTE_MAGIC.size + IV_LEN && BYTE_MAGIC.indices.all { bytes[it] == BYTE_MAGIC[it] }

    fun decryptBytes(encrypted: ByteArray): ByteArray {
        require(isEncryptedBytes(encrypted)) { "不是加密数据" }
        // 存量密文存在 = 本机必然生成过主密钥：先补写哨兵再碰 keystore。
        // 这样即便本次 containsAlias 说谎返回 false，下方 secretKey() 也会拒绝重建。
        markSentinel()
        val iv = encrypted.copyOfRange(BYTE_MAGIC.size, BYTE_MAGIC.size + IV_LEN)
        val ciphertext = encrypted.copyOfRange(BYTE_MAGIC.size + IV_LEN, encrypted.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
            // 密文与当前 Keystore 密钥不匹配或数据损坏。
            // 返回空值而非抛出，避免单个坏字段把整条读取 Flow 带崩。
            FileLogger.w(TAG, "解密失败：密文与当前 Keystore 密钥不匹配或已损坏", e)
            ByteArray(0)
        } catch (e: IllegalStateException) {
            // keystore 服务瞬时不可用（secretKey 拒绝重建而向上抛）：本次解不出，但密文仍完好
            // 保留在库里——服务恢复后即可正常解密，且 preserveUndecryptable 防线会阻止空值覆盖密文。
            FileLogger.w(TAG, "解密跳过：keystore 服务暂不可用，密文原样保留待恢复", e)
            ByteArray(0)
        }
    }

    fun isEncryptedString(value: String?): Boolean = value != null && value.startsWith(STRING_PREFIX)

    /**
     * 判断字符串密文是否解密失败（密文格式合法但 Keystore 密钥不匹配/数据损坏）。
     *
     * [decryptString] 对失败输入返回空串——调用方无法区分「本来没填」与「解密失败」。
     * 读-改-写路径（读出 → 用户编辑 → 写回）必须先用本方法检测：解密失败时**不得**把空串
     * 写回，否则一次无感保存就把原密文永久覆盖成空串（凭据丢失）。
     */
    fun isDecryptFailed(value: String?): Boolean {
        if (!isEncryptedString(value)) return false
        val body = runCatching {
            Base64.getDecoder().decode(value!!.removePrefix(STRING_PREFIX))
        }.getOrNull() ?: return true
        // 密文结构合法（magic + IV + 至少 16 字节 tag）但解密产出为空 = 解密失败。
        // decryptBytes 对失败返回 ByteArray(0)，对成功明文为空串时也不会经过此分支：
        // 明文空串加密后密文结构仍是完整 GCM 块，解密产出长度为 0 —— 空明文与失败在此不可区分，
        // 但空明文本就不值得加密（encryptString 空串原样返回），存量空明文不会带前缀，故安全。
        if (!isEncryptedBytes(body)) return true
        return decryptBytes(body).isEmpty()
    }

    /** 加密字符串；空串原样返回，避免把「未填写」变成非空密文。 */
    fun encryptString(plain: String): String {
        if (plain.isEmpty()) return plain
        return STRING_PREFIX + Base64.getEncoder().encodeToString(encryptBytes(plain.toByteArray(Charsets.UTF_8)))
    }

    /** 解密字符串；非密文（旧明文）原样返回。 */
    fun decryptString(value: String): String {
        if (!isEncryptedString(value)) return value
        markSentinel()
        return try {
            val body = Base64.getDecoder().decode(value.removePrefix(STRING_PREFIX))
            String(decryptBytes(body), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            FileLogger.w(TAG, "解密失败：密文格式非法", e)
            ""
        }
    }
}
