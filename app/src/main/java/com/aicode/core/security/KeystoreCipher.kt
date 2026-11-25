package com.aicode.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.aicode.core.util.FileLogger
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
    private const val RETRY_DELAY_MS = 120L

    private val lock = Any()

    private fun secretKey(): SecretKey = synchronized(lock) {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        // 存在性判定必须用 containsAlias，不能信 getEntry 的结果：部分系统（实测 API 37）keystore
        // 服务瞬时异常时 getEntry 会误构造 TrustedCertificateEntry(null) 抛 NPE。**读取失败 ≠ 密钥
        // 不存在**——若据此走 generateKey，会静默覆盖同 alias 的真实密钥，全部存量密文（供应商
        // API Key 等）永久解不开，即「升级安装后密钥丢失」的根因（3edafc2 引入，本版修复）。
        val aliasPresent = try {
            keyStore.containsAlias(ALIAS)
        } catch (e: Exception) {
            // 连存在性都查不了：keystore 服务不可用。向上抛（读路径按解密失败兜底、密文原样保留），
            // 绝不在此状态下生成新密钥——那等于拿新钥匙换掉可能还完好的旧钥匙。
            throw IllegalStateException("keystore 服务不可用：无法确认密钥存在性，拒绝重建", e)
        }
        if (aliasPresent) {
            // 真实密钥还在：短暂重试扛服务瞬时异常；重试耗尽仍失败则向上抛。
            // 宁可本次解密失败（密文完好保留在库里，服务恢复后即可解），绝不重建覆盖。
            var lastError: Exception? = null
            repeat(GET_ENTRY_RETRY) { attempt ->
                try {
                    return when (val entry = keyStore.getEntry(ALIAS, null)) {
                        is KeyStore.SecretKeyEntry -> entry.secretKey
                        // 条目在但不是 AES 密钥（真坏条目，存量密文本就无对应密钥可解）：此时重建无损
                        else -> {
                            FileLogger.w(TAG, "alias=$ALIAS 条目类型异常(${entry?.javaClass?.simpleName})，重建密钥")
                            regenerate(keyStore)
                        }
                    }
                } catch (e: Exception) {
                    lastError = e
                    runCatching { Thread.sleep(RETRY_DELAY_MS shl attempt) }
                }
            }
            throw IllegalStateException(
                "密钥读取连续 $GET_ENTRY_RETRY 次失败（keystore 服务瞬时异常），拒绝重建以免销毁真实密钥",
                lastError
            )
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
        return try {
            val body = Base64.getDecoder().decode(value.removePrefix(STRING_PREFIX))
            String(decryptBytes(body), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            FileLogger.w(TAG, "解密失败：密文格式非法", e)
            ""
        }
    }
}
