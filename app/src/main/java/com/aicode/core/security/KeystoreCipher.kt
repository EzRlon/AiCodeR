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

    private val lock = Any()

    private fun secretKey(): SecretKey = synchronized(lock) {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        // 部分 Android 版本（实测 API 37 设备）在 keystore 服务异常时，getEntry 会把密钥条目
        // 误判为证书条目并构造 TrustedCertificateEntry(null) 抛 NPE，直接炸崩调用线程；
        // 这里按「密钥不可用」处理，走下方重建路径。
        val existing = runCatching {
            (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        }.getOrNull()
        existing ?: run {
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
            runCatching { generator.generateKey() }.getOrElse {
                // alias 被坏条目占用时生成会失败：清掉后重试一次
                runCatching { keyStore.deleteEntry(ALIAS) }
                generator.generateKey()
            }
        }
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
            // 密文与当前 Keystore 密钥不匹配（密钥丢失后按新密钥重建）或数据损坏。
            // 返回空值而非抛出，避免单个坏字段把整条读取 Flow 带崩。
            FileLogger.w(TAG, "解密失败：密文与当前 Keystore 密钥不匹配或已损坏", e)
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
