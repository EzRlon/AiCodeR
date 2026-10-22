package com.aicode.feature.store.domain

import android.content.Context
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 单条扫描命中：[source] 为命中文本来源（文件名 / 字段名），二进制命中 [line] 为 0。 */
data class ScanHit(
    val severity: ScanLevel,
    val category: String,
    val rule: String,
    val snippet: String,
    val line: Int,
    val source: String = ""
)

/** 命中级别：WARN 可疑（确认后可装）；BLOCK 高危（需显式二次放行）。 */
enum class ScanLevel { WARN, BLOCK }

/** 扫描结论：PASS 无命中（用户无感）；WARN / BLOCK 携带命中列表交 UI 展示。 */
sealed class ScanResult {
    abstract val hits: List<ScanHit>

    object Pass : ScanResult() {
        override val hits: List<ScanHit> = emptyList()
    }

    data class Warn(override val hits: List<ScanHit>) : ScanResult()
    data class Block(override val hits: List<ScanHit>) : ScanResult()
}

@Serializable
internal data class StoreScanRule(
    val id: String,
    val level: String,
    val category: String,
    val name: String,
    val pattern: String,
    val ignoreCase: Boolean = true
)

@Serializable
internal data class StoreScanRuleFile(val version: Int = 0, val rules: List<StoreScanRule> = emptyList())

/**
 * 商店安装安全扫描（纯本地正则 + 启发式，零网络零模型）：
 * 技能 / MCP / 专家团写盘前统一过一道内容扫描，命中分级 WARN / BLOCK 交 UI 二次确认。
 * 特征库在 assets/store-scan-rules.json；解析失败回退内置最小规则集，保证扫描不因 assets 缺失而失效。
 */
@Singleton
class StoreInstallScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private class CompiledRule(val def: StoreScanRule, val level: ScanLevel, val regex: Regex)

    private val json = Json { ignoreUnknownKeys = true }

    private val ruleFile: StoreScanRuleFile by lazy {
        runCatching {
            val raw = context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
            json.decodeFromString(StoreScanRuleFile.serializer(), raw)
        }.getOrElse { e ->
            FileLogger.w(TAG, "加载商店扫描特征库失败，回退内置最小规则集", e)
            BUILTIN_RULES
        }
    }

    /** 特征库版本（随 assets JSON 维护）。 */
    val ruleVersion: Int get() = ruleFile.version

    private val rules: List<CompiledRule> by lazy {
        ruleFile.rules.mapNotNull { def ->
            runCatching {
                val opts = if (def.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
                CompiledRule(def, ScanLevel.valueOf(def.level), Regex(def.pattern, opts))
            }.getOrElse { e ->
                FileLogger.w(TAG, "扫描规则编译失败（跳过）: ${def.id}", e)
                null
            }
        }
    }

    /**
     * 扫描技能的整包文件（相对路径 → 字节）：文本文件按 UTF-8 严格解码后过全部规则；
     * 解码失败的按二进制处理（跳过文本规则，仅做零宽字节与高熵检查，图片等良性格式直接放行）。
     * 纯内存正则；单文件超 1MB 时调用方应切到 Dispatchers.Default。
     */
    fun scanFiles(files: Map<String, ByteArray>): ScanResult {
        val hits = ArrayList<ScanHit>()
        for ((name, bytes) in files) {
            val text = runCatching { decodeText(bytes) }.getOrNull()
            if (text != null) {
                hits += scanText(name, text)
            } else {
                hits += scanBinary(name, bytes)
            }
        }
        return merge(hits)
    }

    /** 扫描结构化文本（来源名 → 正文），用于 MCP 条目（名称/描述/远程地址）与专家团定义。 */
    fun scanTexts(texts: List<Pair<String, String>>): ScanResult {
        val hits = ArrayList<ScanHit>()
        for ((source, content) in texts) {
            if (content.isNotBlank()) hits += scanText(source, content)
        }
        return merge(hits)
    }

    private fun scanText(source: String, text: String): List<ScanHit> {
        val hits = ArrayList<ScanHit>()
        val seen = HashSet<String>()
        collectHits(source, text, seen, hits)

        // 同形字符混淆：归一化后能命中而原文命不中的，按 BLOCK 上报
        val normalized = normalizeHomoglyphs(text)
        if (normalized != text) {
            val normHits = ArrayList<ScanHit>()
            collectHits(source, normalized, HashSet(), normHits)
            for (hit in normHits) {
                if (seen.add("homoglyph|${hit.rule}|${hit.line}") && hits.size < MAX_HITS) {
                    hits += hit.copy(rule = "同形字符混淆（${hit.rule}）", severity = ScanLevel.BLOCK)
                }
            }
        }

        val body = text.removePrefix("\uFEFF")
        val zeroWidth = body.count { it in ZERO_WIDTH_CHARS }
        if (zeroWidth > 0 && hits.size < MAX_HITS) {
            hits += ScanHit(
                severity = ScanLevel.BLOCK,
                category = "隐藏载荷",
                rule = "零宽字符",
                snippet = "含 $zeroWidth 个零宽字符（肉眼不可见，常用于夹带隐藏指令）",
                line = 0,
                source = source
            )
        }

        // 大块 base64：逐行找长串 base64 形态（隐藏载荷经典手法）
        body.lines().forEachIndexed { idx, line ->
            if (hits.size >= MAX_HITS) return@forEachIndexed
            BASE64_BLOB_REGEX.findAll(line).forEach { m ->
                hits += ScanHit(
                    severity = ScanLevel.BLOCK,
                    category = "隐藏载荷",
                    rule = "大块 Base64 编码",
                    snippet = clip(m.value),
                    line = idx + 1,
                    source = source
                )
            }
        }

        // 凭据形态 + 外发指令组合（同一段文本内同时出现才报）
        val cred = CREDENTIAL_REGEX.find(body)
        if (cred != null && EXFIL_REGEX.containsMatchIn(body) && hits.size < MAX_HITS) {
            hits += ScanHit(
                severity = ScanLevel.BLOCK,
                category = "凭据外发",
                rule = "凭据形态 + 外发指令",
                snippet = clip(cred.value),
                line = body.lineOf(cred.range.first),
                source = source
            )
        }
        return hits
    }

    private fun collectHits(source: String, text: String, seen: MutableSet<String>, out: MutableList<ScanHit>) {
        for ((idx, line) in text.lines().withIndex()) {
            if (out.size >= MAX_HITS) return
            for (rule in rules) {
                rule.regex.findAll(line).forEach { m ->
                    if (out.size >= MAX_HITS) return
                    val key = "${rule.def.id}|$idx"
                    if (seen.add(key)) {
                        out += ScanHit(
                            severity = rule.level,
                            category = rule.def.category,
                            rule = rule.def.name,
                            snippet = clip(m.value),
                            line = idx + 1,
                            source = source
                        )
                    }
                }
            }
        }
    }

    private fun scanBinary(name: String, bytes: ByteArray): List<ScanHit> {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in BENIGN_BINARY_EXTS) return emptyList()
        val hits = ArrayList<ScanHit>()

        // 零宽字符的 UTF-8 字节序列；BOM 只在文件头合法，出现在其余位置按隐藏载荷处理
        val zwSequences = listOf(
            byteArrayOf(0xE2.toByte(), 0x80.toByte(), 0x8B.toByte()),
            byteArrayOf(0xE2.toByte(), 0x80.toByte(), 0x8C.toByte()),
            byteArrayOf(0xE2.toByte(), 0x80.toByte(), 0x8D.toByte()),
            byteArrayOf(0xE2.toByte(), 0x81.toByte(), 0xA0.toByte()),
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        )
        var zw = 0
        for (seq in zwSequences) {
            var from = 0
            while (true) {
                val at = indexOf(bytes, seq, from) ?: break
                if (seq[0] == 0xEF.toByte() && at == 0) {
                    from = at + 1
                    continue
                }
                zw++
                from = at + 1
            }
        }
        if (zw > 0) {
            hits += ScanHit(
                severity = ScanLevel.BLOCK,
                category = "隐藏载荷",
                rule = "二进制内容中的零宽字符",
                snippet = "$name 含 $zw 处零宽字节序列",
                line = 0,
                source = name
            )
        }

        val entropy = shannonEntropy(bytes)
        if (bytes.size >= ENTROPY_MIN_BYTES && entropy >= ENTROPY_THRESHOLD) {
            hits += ScanHit(
                severity = ScanLevel.WARN,
                category = "隐藏载荷",
                rule = "高熵二进制内容",
                snippet = "$name（${bytes.size / 1024} KB）熵值 ${"%.2f".format(entropy)}，疑似加密或编码载荷",
                line = 0,
                source = name
            )
        }
        return hits
    }

    private fun merge(hits: List<ScanHit>): ScanResult = when {
        hits.any { it.severity == ScanLevel.BLOCK } ->
            ScanResult.Block(hits.sortedByDescending { it.severity.ordinal })
        hits.isNotEmpty() -> ScanResult.Warn(hits)
        else -> ScanResult.Pass
    }

    private fun decodeText(bytes: ByteArray): String {
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
            .takeIf { !it.contains('\u0000') }
            ?: throw CharacterCodingException()
    }

    private fun normalizeHomoglyphs(text: String): String = buildString(text.length) {
        for (ch in text) append(HOMOGLYPHS[ch] ?: ch)
    }

    private fun clip(s: String): String = s.replace(WHITESPACE_REGEX, " ").trim().take(SNIPPET_MAX)

    private fun String.lineOf(offset: Int): Int {
        var line = 1
        for (i in 0 until offset.coerceAtMost(length)) if (this[i] == '\n') line++
        return line
    }

    private fun shannonEntropy(bytes: ByteArray): Double {
        val freq = IntArray(256)
        for (b in bytes) freq[b.toInt() and 0xFF]++
        var h = 0.0
        for (f in freq) {
            if (f == 0) continue
            val p = f.toDouble() / bytes.size
            h -= p * (ln(p) / ln(2.0))
        }
        return h
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int? {
        var i = from
        while (i <= hay.size - needle.size) {
            var j = 0
            while (j < needle.size && hay[i + j] == needle[j]) j++
            if (j == needle.size) return i
            i++
        }
        return null
    }

    private companion object {
        const val TAG = "StoreInstallScanner"
        const val ASSET_FILE = "store-scan-rules.json"
        const val MAX_HITS = 40
        const val SNIPPET_MAX = 80
        const val ENTROPY_MIN_BYTES = 512
        const val ENTROPY_THRESHOLD = 7.5

        val WHITESPACE_REGEX = Regex("\\s+")
        val BASE64_BLOB_REGEX = Regex("[A-Za-z0-9+/]{400,}={0,2}")
        val CREDENTIAL_REGEX = Regex(
            "\\b(sk-[A-Za-z0-9_-]{20,}|ghp_[A-Za-z0-9]{30,}|gho_[A-Za-z0-9]{30,}|" +
                "github_pat_[A-Za-z0-9_]{30,}|xox[bpars]-[A-Za-z0-9-]{10,}|AKIA[0-9A-Z]{16}|" +
                "Bearer\\s+[A-Za-z0-9._-]{25,}|-----BEGIN [A-Z ]*PRIVATE KEY-----)"
        )
        val EXFIL_REGEX = Regex(
            "(?i)\\b(curl|wget)\\b|requests\\.(post|put|get)|fetch\\s*\\(|httpclient|okhttp|urlopen|POST\\s+https?:"
        )
        val ZERO_WIDTH_CHARS = charArrayOf('\u200B', '\u200C', '\u200D', '\u2060', '\uFEFF')

        /** 图片 / 字体等常见良性二进制格式：不参与熵与零宽检查，避免误报。 */
        val BENIGN_BINARY_EXTS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "ico", "bmp", "svg",
            "woff", "woff2", "ttf", "otf", "eot", "pdf", "zip", "gz", "mp3", "mp4", "wav"
        )

        /** 西里尔 / 希腊字母中与拉丁字母同形的常见字符 → 拉丁归一化映射。 */
        val HOMOGLYPHS = mapOf(
            'а' to 'a', 'е' to 'e', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'х' to 'x', 'у' to 'y',
            'А' to 'A', 'В' to 'B', 'Е' to 'E', 'К' to 'K', 'М' to 'M', 'Н' to 'H', 'О' to 'O',
            'Р' to 'P', 'С' to 'C', 'Т' to 'T', 'Х' to 'X', 'У' to 'Y',
            'ο' to 'o', 'α' to 'a', 'ε' to 'e', 'ι' to 'i', 'κ' to 'k', 'ρ' to 'p', 'τ' to 't',
            'υ' to 'u', 'χ' to 'x', 'Α' to 'A', 'Β' to 'B', 'Ε' to 'E', 'Ζ' to 'Z', 'Η' to 'H',
            'Κ' to 'K', 'Μ' to 'M', 'Ν' to 'N', 'Ο' to 'O', 'Ρ' to 'P', 'Τ' to 'T', 'Υ' to 'Y', 'Χ' to 'X'
        )

        /** assets 缺失 / 损坏时的最小内置规则集（只保最高危特征，保证扫描不失效）。 */
        val BUILTIN_RULES = StoreScanRuleFile(
            version = -1,
            rules = listOf(
                StoreScanRule(
                    id = "builtin-override",
                    level = "BLOCK",
                    category = "诱导覆盖系统规则",
                    name = "覆盖系统指令",
                    pattern = "(忽略|无视)之前?(的)?(所有)?(指令|规则)|\\b(disregard|ignore)\\b[^.\\n]{0,20}\\b(previous|prior|above)\\b"
                ),
                StoreScanRule(
                    id = "builtin-fake-tag",
                    level = "BLOCK",
                    category = "伪造系统标签",
                    name = "伪造成系统容器标签",
                    pattern = "</?\\s*system-reminder\\s*>|<\\s*/?\\s*(system|claude)\\s*>"
                ),
                StoreScanRule(
                    id = "builtin-privilege",
                    level = "WARN",
                    category = "提权措辞",
                    name = "要求自动批准 / 绕过确认",
                    pattern = "(自动批准|绕过(权限|确认)|静默放行|\\b(auto.?approve|bypass (the )?permission)\\b)"
                ),
                StoreScanRule(
                    id = "builtin-sensitive-path",
                    level = "WARN",
                    category = "敏感路径访问",
                    name = "引用密钥 / 凭据文件路径",
                    pattern = "(\\.aicode/keys|keystore\\.(jks|keystore)|id_rsa|\\.env\\b)"
                )
            )
        )
    }
}
