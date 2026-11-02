package com.wangzan101.mika.backup

import com.wangzan101.mika.settings.AesGcmKeyCipher
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class SecretVaultTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AesGcmKeyCipher { key }
    private fun base64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun decoded(encoded: String) = String(cipher.decrypt(Base64.getDecoder().decode(encoded)), Charsets.UTF_8)

    private fun vault(dir: File, speech: () -> String?, put: (String?) -> Unit, c: (String) -> com.wangzan101.mika.settings.KeyCipher = { cipher }) =
        SecretVault(dir, speech, put, c)

    @Test
    fun exportDecryptsAllSecrets() {
        val dir = tmp.newFolder("settings")
        File(dir, "provider-keys.json").writeText("""{"p1":"${base64(cipher.encrypt("sk-test".toByteArray()))}"}""")
        File(dir, "mcp-token.bin").writeBytes(cipher.encrypt("token".toByteArray()))
        File(dir, "remote-relay.bin").writeBytes(cipher.encrypt("""{"gateway":"gw"}""".toByteArray()))
        File(dir, "net-proxy.bin").writeBytes(cipher.encrypt("http://u:p@host:8080".toByteArray()))
        File(dir, "device-access.bin").writeBytes(cipher.encrypt("""{"enabled":true}""".toByteArray()))

        var speech: String? = base64(cipher.encrypt("""{"apiKey":"k"}""".toByteArray()))
        val exported = vault(dir, { speech }, { speech = it }).export()

        assertEquals("sk-test", exported.json["providers"]!!.jsonObject["p1"]!!.jsonPrimitive.content)
        assertEquals("token", exported.json["mcpToken"]!!.jsonPrimitive.content)
        assertEquals("""{"gateway":"gw"}""", exported.json["relay"]!!.jsonPrimitive.content)
        assertEquals("http://u:p@host:8080", exported.json["netProxy"]!!.jsonPrimitive.content)
        assertEquals("""{"enabled":true}""", exported.json["deviceAccess"]!!.jsonPrimitive.content)
        assertEquals("""{"apiKey":"k"}""", exported.json["speechDoubao"]!!.jsonPrimitive.content)
        assertEquals(emptyList<String>(), exported.unavailable)
    }

    @Test
    fun missingFilesAreSkipped() {
        val dir = tmp.newFolder("empty")
        var speech: String? = null
        val exported = vault(dir, { speech }, { speech = it }).export()
        assertEquals(null, exported.json["providers"])
        assertEquals(null, exported.json["mcpToken"])
        assertEquals(null, exported.json["speechDoubao"])
        assertTrue(exported.unavailable.isEmpty())
    }

    @Test
    fun unavailableCipherIsReportedButOtherSecretsSurvive() {
        val dir = tmp.newFolder("partial")
        File(dir, "provider-keys.json").writeText("""{"p1":"${base64(cipher.encrypt("sk-test".toByteArray()))}"}""")
        File(dir, "mcp-token.bin").writeBytes(cipher.encrypt("token".toByteArray()))

        val exported = vault(dir, { null }, {}, { alias ->
            if (alias == "mika-mcp-token") error("keystore unavailable") else cipher
        }).export()

        assertEquals("sk-test", exported.json["providers"]!!.jsonObject["p1"]!!.jsonPrimitive.content)
        assertEquals(null, exported.json["mcpToken"])
        assertTrue(exported.unavailable.contains("mcp"))
    }

    @Test
    fun restoreWritesBackWithTargetCipherAndKeepsExistingEntries() {
        val source = tmp.newFolder("source")
        File(source, "provider-keys.json").writeText("""{"p1":"${base64(cipher.encrypt("sk-test".toByteArray()))}"}""")
        File(source, "mcp-token.bin").writeBytes(cipher.encrypt("token".toByteArray()))
        var speech: String? = base64(cipher.encrypt("""{"apiKey":"k"}""".toByteArray()))
        val exported = vault(source, { speech }, { speech = it }).export()

        val target = tmp.newFolder("target")
        File(target, "provider-keys.json").writeText("""{"old":"${base64(cipher.encrypt("old-key".toByteArray()))}"}""")
        var restoredSpeech: String? = null
        vault(target, { restoredSpeech }, { restoredSpeech = it }).restore(exported.json)

        val keys = File(target, "provider-keys.json").readText()
            .let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject }
        assertEquals("old-key", decoded(keys["old"]!!.jsonPrimitive.content))
        assertEquals("sk-test", decoded(keys["p1"]!!.jsonPrimitive.content))
        assertEquals("token", String(cipher.decrypt(File(target, "mcp-token.bin").readBytes())))
        assertEquals("""{"apiKey":"k"}""", String(cipher.decrypt(Base64.getDecoder().decode(restoredSpeech))))
    }

    @Test
    fun keepsExistingProviderKeyWhenItDiffers() {
        val target = tmp.newFolder("target-keep")
        File(target, "provider-keys.json").writeText("""{"p1":"${base64(cipher.encrypt("new-key".toByteArray()))}"}""")
        val imported = kotlinx.serialization.json.buildJsonObject {
            put("version", 1)
            put("providers", kotlinx.serialization.json.buildJsonObject { put("p1", "old-key") })
        }
        val result = vault(target, { null }, {}).restore(imported)
        assertEquals(1, result.keptExisting)
        assertEquals(0, result.imported)
        val keys = kotlinx.serialization.json.Json.parseToJsonElement(File(target, "provider-keys.json").readText()).jsonObject
        assertEquals("new-key", decoded(keys["p1"]!!.jsonPrimitive.content))
    }

    @Test
    fun importsProviderKeyWhenTargetHasNone() {
        val target = tmp.newFolder("target-new")
        val imported = kotlinx.serialization.json.buildJsonObject {
            put("version", 1)
            put("providers", kotlinx.serialization.json.buildJsonObject { put("p1", "sk-new") })
        }
        val result = vault(target, { null }, {}).restore(imported)
        assertEquals(1, result.imported)
        assertEquals(0, result.keptExisting)
        val keys = kotlinx.serialization.json.Json.parseToJsonElement(File(target, "provider-keys.json").readText()).jsonObject
        assertEquals("sk-new", decoded(keys["p1"]!!.jsonPrimitive.content))
    }

    @Test
    fun restoreSkipsMissingSecretsAndDoesNotTouchClipboard() {
        val target = tmp.newFolder("target2")
        File(target, "mcp-token.bin").writeBytes(cipher.encrypt("keep".toByteArray()))
        var speech: String? = null
        val vault = vault(target, { speech }, { speech = it })
        vault.restore(kotlinx.serialization.json.buildJsonObject { put("version", 1) })
        assertEquals("keep", String(cipher.decrypt(File(target, "mcp-token.bin").readBytes())))
        assertFalse(File(target, "clip-backup.bin").exists())
    }
}
