package com.wangzan101.mika.backup

import android.content.Context
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.settings.KeystoreKeyCipher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

data class SecretExport(val json: JsonObject, val unavailable: List<String>)

/** 密钥回写的结果:导入了多少、因为目标已有不同值而保留了多少、写不进去的项。 */
data class SecretRestoreResult(
    val unavailable: List<String> = emptyList(),
    val imported: Int = 0,
    val keptExisting: Int = 0,
)

/**
 * 把散在各把 Keystore 别名下的秘密集中解密成 `secrets.json`(明文只在加密包里出现),导入时再逐项写回目标设备。
 *
 * 不处理 `mika-clip-backup`:那是任务异常退出后的剪贴板留底,跟着设备走,恢复它没有意义。
 */
class SecretVault(
    private val settingsDir: File,
    private val speechGet: () -> String?,
    private val speechPut: (String?) -> Unit,
    private val cipherFor: (String) -> KeyCipher = { KeystoreKeyCipher(it) },
) {
    constructor(context: Context) : this(
        File(context.filesDir, "settings"),
        { context.getSharedPreferences(FeatureFlags.PREFS, Context.MODE_PRIVATE).getString(SPEECH_KEY, null) },
        { value ->
            val edit = context.getSharedPreferences(FeatureFlags.PREFS, Context.MODE_PRIVATE).edit()
            if (value == null) edit.remove(SPEECH_KEY) else edit.putString(SPEECH_KEY, value)
            edit.commit()
        },
    )

    fun export(): SecretExport {
        val unavailable = mutableListOf<String>()
        val providers = LinkedHashMap<String, String>()
        val keysFile = File(settingsDir, PROVIDER_KEYS_FILE)
        if (keysFile.isFile) {
            val raw = runCatching { Json.parseToJsonElement(keysFile.readText()).jsonObject }.getOrNull()
            if (raw == null) {
                unavailable += "providers"
            } else {
                for ((id, value) in raw) {
                    val plain = runCatching {
                        val encrypted = Base64.getDecoder().decode(value.jsonPrimitive.content)
                        String(cipherFor(PROVIDER_ALIAS).decrypt(encrypted), Charsets.UTF_8)
                    }.getOrNull()
                    if (plain == null) unavailable += "provider:$id" else providers[id] = plain
                }
            }
        }

        val json = buildJsonObject {
            put("version", 1)
            if (providers.isNotEmpty()) {
                put("providers", buildJsonObject { providers.forEach { (id, key) -> put(id, key) } })
            }
            raw(MCP_TOKEN, MCP_ALIAS, "mcp", unavailable)?.let { put("mcpToken", it) }
            raw(RELAY, RELAY_ALIAS, "relay", unavailable)?.let { put("relay", it) }
            (raw(NET_PROXY, NET_PROXY_ALIAS, "net-proxy", unavailable)
                ?: raw(LEGACY_NET_PROXY, LEGACY_NET_PROXY_ALIAS, "net-proxy", unavailable))?.let { put("netProxy", it) }
            raw(DEVICE_ACCESS, DEVICE_ACCESS_ALIAS, "device-access", unavailable)?.let { put("deviceAccess", it) }
            val spoken = speech()
            if (spoken != null) put("speechDoubao", spoken)
            else if (speechGet() != null) unavailable += "speech"
        }
        return SecretExport(json, unavailable)
    }

    /**
     * 把 `secrets.json` 写回目标设备。文件里没有的秘密保持原样。
     *
     * 服务商 Key 的规则:目标已经有同一服务商、且值和备份不同时保留目标现有的(避免半年前的旧 Key
     * 把用户后来轮换的 Key 盖掉),只有目标没有或值相同才写入。其它 token / 凭据仍以导入为准。
     *
     * @return 写不进去的项,以及导入 / 保留的计数。
     */
    fun restore(json: JsonObject): SecretRestoreResult {
        val unavailable = mutableListOf<String>()
        var imported = 0
        var keptExisting = 0

        val importedProviders = json["providers"]?.jsonObject
        if (importedProviders != null && importedProviders.isNotEmpty()) {
            val merged = LinkedHashMap<String, String>()
            val existing = runCatching {
                Json.parseToJsonElement(File(settingsDir, PROVIDER_KEYS_FILE).readText()).jsonObject
            }.getOrNull()
            existing?.forEach { (id, value) -> merged[id] = value.jsonPrimitive.content }

            val existingPlain = LinkedHashMap<String, String>()
            existing?.forEach { (id, value) ->
                runCatching {
                    val encrypted = Base64.getDecoder().decode(value.jsonPrimitive.content)
                    String(cipherFor(PROVIDER_ALIAS).decrypt(encrypted), Charsets.UTF_8)
                }.onSuccess { existingPlain[id] = it }
            }

            for ((id, value) in importedProviders) {
                val plain = value.jsonPrimitive.content
                val old = existingPlain[id]
                if (old != null && old != plain) {
                    keptExisting++
                    continue
                }
                runCatching {
                    merged[id] = Base64.getEncoder().encodeToString(cipherFor(PROVIDER_ALIAS).encrypt(plain.toByteArray(Charsets.UTF_8)))
                    imported++
                }.onFailure { unavailable += "provider:$id" }
            }
            runCatching {
                writeAtomic(File(settingsDir, PROVIDER_KEYS_FILE),
                    buildJsonObject { merged.forEach { (id, encrypted) -> put(id, encrypted) } }.toString().toByteArray())
            }.onFailure { unavailable += "providers" }
        }

        write(MCP_TOKEN, MCP_ALIAS, "mcpToken", json, unavailable)
        write(RELAY, RELAY_ALIAS, "relay", json, unavailable)
        write(NET_PROXY, NET_PROXY_ALIAS, "netProxy", json, unavailable)
        if (json.containsKey("netProxy")) File(settingsDir, LEGACY_NET_PROXY).delete()
        write(DEVICE_ACCESS, DEVICE_ACCESS_ALIAS, "deviceAccess", json, unavailable)

        json["speechDoubao"]?.let { value ->
            runCatching {
                val encrypted = cipherFor(SPEECH_ALIAS).encrypt(value.jsonPrimitive.content.toByteArray(Charsets.UTF_8))
                speechPut(Base64.getEncoder().encodeToString(encrypted))
            }.onFailure { unavailable += "speech" }
        }
        return SecretRestoreResult(unavailable, imported, keptExisting)
    }

    /** 当前有没有可撤销的语音凭据;只用于判断,不返回秘密本身。 */
    fun hasSpeech(): Boolean = speechGet() != null

    private fun raw(file: String, alias: String, label: String, unavailable: MutableList<String>): String? {
        val f = File(settingsDir, file)
        if (!f.isFile) return null
        return runCatching { String(cipherFor(alias).decrypt(f.readBytes()), Charsets.UTF_8) }
            .onFailure { unavailable += label }
            .getOrNull()
    }

    private fun speech(): String? {
        val stored = speechGet() ?: return null
        return runCatching {
            val encrypted = Base64.getDecoder().decode(stored)
            String(cipherFor(SPEECH_ALIAS).decrypt(encrypted), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun write(file: String, alias: String, key: String, json: JsonObject, unavailable: MutableList<String>) {
        val value = json[key]?.jsonPrimitive?.content ?: return
        runCatching { writeAtomic(File(settingsDir, file), cipherFor(alias).encrypt(value.toByteArray(Charsets.UTF_8))) }
            .onFailure { unavailable += key }
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            bytes.fill(0)
            tmp.delete()
        }
    }

    private companion object {
        const val PROVIDER_KEYS_FILE = "provider-keys.json"
        const val PROVIDER_ALIAS = "mika-provider-keys"
        const val MCP_TOKEN = "mcp-token.bin"
        const val MCP_ALIAS = "mika-mcp-token"
        const val RELAY = "remote-relay.bin"
        const val RELAY_ALIAS = "mika-remote-relay"
        const val NET_PROXY = "net-proxy.bin"
        const val NET_PROXY_ALIAS = "mika-net-proxy"
        const val LEGACY_NET_PROXY = "container-proxy.bin"
        const val LEGACY_NET_PROXY_ALIAS = "mika-container-proxy"
        const val DEVICE_ACCESS = "device-access.bin"
        const val DEVICE_ACCESS_ALIAS = "mika-device-access"
        const val SPEECH_KEY = "speech_doubao_credentials_encrypted"
        const val SPEECH_ALIAS = "mika-speech-keys"
    }
}
