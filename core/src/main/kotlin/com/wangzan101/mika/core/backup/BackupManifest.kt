package com.wangzan101.mika.core.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.Base64

@Serializable
data class BackupKdf(
    val algo: String = BackupFormat.PBKDF2,
    val iterations: Int = BackupFormat.DEFAULT_ITERATIONS,
    /** 16 字节 salt 的 base64。 */
    val salt: String,
)

@Serializable
data class BackupCipher(
    val algo: String = BackupFormat.AES_GCM,
    /** 12 字节 IV 的 base64。 */
    val iv: String,
    val tagBits: Int = BackupFormat.TAG_BITS,
)

@Serializable
data class BackupPayload(
    val entry: String = BackupFormat.PAYLOAD_ENTRY,
    val plaintext: String = BackupFormat.PAYLOAD_PLAINTEXT,
    /** 密文字节数。 */
    val bytes: Long,
    /** 密文的 SHA-256,小写十六进制。 */
    val sha256: String,
)

/**
 * 外层 zip 里的 `manifest.json`:明文,导入前先读它做预览;真正的内容在 `payload.bin` 里。
 * `sections` 是实际包含的分类;`counts` 只用于对账,导入时以包内文件为准。
 */
@Serializable
data class BackupManifest(
    val app: String = BackupFormat.APP,
    val format: Int = BackupFormat.VERSION,
    val appVersion: String,
    val exportedAt: String,
    val keyMode: String = BackupFormat.KEY_MODE_PASSPHRASE,
    val kdf: BackupKdf,
    val cipher: BackupCipher,
    val aad: String = BackupFormat.AAD_PREFIX,
    val sections: List<String>,
    val counts: Map<String, Long> = emptyMap(),
    /** counts 的 HMAC-SHA256(base64);空表示旧包没有这一项。 */
    val countsMac: String = "",
    val payload: BackupPayload,
) {
    /** AES-GCM 的附加认证数据;导入时按包里的字段重算,不直接用文件里的 `aad` 字段。 */
    fun aadBytes(): ByteArray = aadString(format, appVersion, exportedAt, sections).toByteArray(Charsets.UTF_8)

    fun saltBytes(): ByteArray = decodeBase64(kdf.salt, "salt")

    fun ivBytes(): ByteArray = decodeBase64(cipher.iv, "iv")

    /**
     * 校验格式和参数。不在这里解密,也不相信 `counts`。
     * @throws BackupException 类型由 [BackupError] 给出,界面据此显示。
     */
    fun validate(): BackupManifest {
        if (app != BackupFormat.APP) throw BackupException(BackupError.NotMikaBackup, "not a Mika backup")
        if (format !in 1..BackupFormat.VERSION) {
            throw BackupException(
                if (format > BackupFormat.VERSION) BackupError.NewerFormat else BackupError.Unsupported,
                "unsupported backup format $format",
            )
        }
        if (keyMode != BackupFormat.KEY_MODE_PASSPHRASE) {
            throw BackupException(BackupError.Unsupported, "unsupported key mode $keyMode")
        }
        if (kdf.algo != BackupFormat.PBKDF2) throw BackupException(BackupError.Unsupported, "unsupported KDF ${kdf.algo}")
        if (kdf.iterations !in BackupFormat.MIN_ITERATIONS..BackupFormat.MAX_ITERATIONS) {
            throw BackupException(BackupError.Unsupported, "KDF iterations out of range: ${kdf.iterations}")
        }
        if (saltBytes().size != BackupFormat.SALT_BYTES) throw BackupException(BackupError.Corrupt, "bad salt length")
        if (cipher.algo != BackupFormat.AES_GCM) throw BackupException(BackupError.Unsupported, "unsupported cipher ${cipher.algo}")
        if (cipher.tagBits != BackupFormat.TAG_BITS) throw BackupException(BackupError.Unsupported, "unsupported tag bits")
        if (ivBytes().size != BackupFormat.IV_BYTES) throw BackupException(BackupError.Corrupt, "bad IV length")
        if (aad != BackupFormat.AAD_PREFIX) throw BackupException(BackupError.Corrupt, "bad AAD marker")
        if (sections.isEmpty() || sections.distinct().size != sections.size) {
            throw BackupException(BackupError.Corrupt, "bad section list")
        }
        if (sections.any { it !in BackupFormat.ALL_SECTIONS }) {
            throw BackupException(BackupError.Unsupported, "unknown section")
        }
        if (payload.entry != BackupFormat.PAYLOAD_ENTRY || payload.plaintext != BackupFormat.PAYLOAD_PLAINTEXT) {
            throw BackupException(BackupError.Unsupported, "unsupported payload")
        }
        if (payload.bytes < 0) throw BackupException(BackupError.Corrupt, "bad payload size")
        if (payload.sha256.length != 64 || payload.sha256.any { it !in "0123456789abcdef" }) {
            throw BackupException(BackupError.Corrupt, "bad payload checksum")
        }
        if (countsMac.isNotEmpty()) {
            val mac = try {
                Base64.getDecoder().decode(countsMac)
            } catch (e: IllegalArgumentException) {
                throw BackupException(BackupError.Corrupt, "bad counts MAC", e)
            }
            if (mac.size != 32) throw BackupException(BackupError.Corrupt, "bad counts MAC length")
        }
        return this
    }

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

        fun aadString(format: Int, appVersion: String, exportedAt: String, sections: List<String>): String =
            "${BackupFormat.AAD_PREFIX}|$format|$appVersion|$exportedAt|${sections.sorted().joinToString(",")}"

        /** counts 的规范化文本;排序后逐行 `key=value`,用于 HMAC。 */
        fun countsCanonical(counts: Map<String, Long>): String =
            counts.toSortedMap().entries.joinToString("\n") { (k, v) -> "$k=$v" }

        fun decode(bytes: ByteArray): BackupManifest = try {
            json.decodeFromString(serializer(), bytes.toString(Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw BackupException(BackupError.NotMikaBackup, "manifest is not valid", e)
        } catch (e: IllegalArgumentException) {
            throw BackupException(BackupError.Corrupt, "manifest is not valid", e)
        }

        fun encode(manifest: BackupManifest): ByteArray =
            json.encodeToString(serializer(), manifest).toByteArray(Charsets.UTF_8)

        private fun decodeBase64(value: String, name: String): ByteArray = try {
            Base64.getDecoder().decode(value)
        } catch (e: IllegalArgumentException) {
            throw BackupException(BackupError.Corrupt, "bad $name", e)
        }

        fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
            for (b in bytes) {
                append(HEX[(b.toInt() shr 4) and 0xf])
                append(HEX[b.toInt() and 0xf])
            }
        }

        private const val HEX = "0123456789abcdef"
    }
}
