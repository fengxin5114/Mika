package com.wangzan101.mika.backup

import com.wangzan101.mika.core.backup.BackupFormat
import com.wangzan101.mika.core.memory.MemoryItem
import com.wangzan101.mika.core.memory.MemoryKind
import com.wangzan101.mika.core.memory.MemorySource
import com.wangzan101.mika.core.memory.MemoryStatus
import com.wangzan101.mika.core.memory.ProfileEntry
import com.wangzan101.mika.core.memory.ProfileStatus
import com.wangzan101.mika.core.replay.Route
import com.wangzan101.mika.core.replay.RouteCodec
import com.wangzan101.mika.core.trigger.TriggerSnapshot
import com.wangzan101.mika.core.trigger.TriggerWindow
import com.wangzan101.mika.core.trigger.TriggerWindowState
import com.wangzan101.mika.memory.EncryptedMemoryFile
import com.wangzan101.mika.memory.FileProfileBackend
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RestoreApplierTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = testKey()

    @Test
    fun mergesSettingsConversationsWebAndAppAccessSafely() {
        val filesDir = tmp.newFolder("files")
        val extracted = tmp.newFolder("extracted")
        fun write(root: File, rel: String, text: String) {
            File(root, rel).apply { parentFile.mkdirs() }.writeText(text)
        }

        // settings: 已有 theme_mode 不覆盖,缺的 capsule_mode 补上
        write(extracted, "data/settings/prefs_feature_flags.json", """{"theme_mode":"Dark","capsule_mode":"Always"}""")
        val feature = FakePrefs(mapOf("theme_mode" to "Light"))

        // 对话:导入的比目标新,替换
        write(filesDir, "conversations/a.json", "[]")
        write(extracted, "data/conversations/a.json", """[{"userText":"new","status":"succeeded"}]""")
        File(extracted, "data/conversations/a.json").setLastModified(File(filesDir, "conversations/a.json").lastModified() + 10_000)

        // 站点:保留 challengedAt/challenge,loggedIn 强制 false,lastVisit 取较大值
        write(filesDir, "web/sites.json", """[{"host":"x","lastVisitMs":20,"loggedIn":true,"challengedAt":7,"challenge":"captcha"}]""")
        write(extracted, "data/web/sites.json", """[{"host":"x","lastVisitMs":10,"loggedIn":true}]""")

        // App 访问:blocked 并集,shareAllowed 交集
        write(filesDir, "app-access.json", """{"version":1,"blocked":["b"],"shareAllowed":["s1","s2"],"classifications":{}}""")
        write(extracted, "data/automation/app-access.json", """{"version":1,"blocked":["a"],"shareAllowed":["s1"],"classifications":{}}""")

        // 定时任务:已有 t0 保留,新 t1 导入,窗口保留
        val existing = TriggerSnapshot(listOf(trigger("t0")), emptyList())
        val triggers = MemoryTriggers(existing)
        val imported = TriggerSnapshot(
            listOf(trigger("t0"), trigger("t1")),
            listOf(TriggerWindow("t1", 11, 12, state = TriggerWindowState.Delivered)),
        )
        write(extracted, "data/automation/triggers.json", Json.encodeToString(TriggerSnapshot.serializer(), imported))

        val manifest = testManifest(listOf(BackupFormat.SETTINGS, BackupFormat.CONVERSATIONS, BackupFormat.WEB, BackupFormat.AUTOMATION))
        val vault = SecretVault(File(filesDir, "settings"), { null }, {}, { testCipher(key) })
        RestoreApplier(
            filesDir, extracted, manifest, vault, feature, FakePrefs(), triggers, testCipher(key),
        ).apply()

        assertEquals("Light", feature.map["theme_mode"])
        assertEquals("Always", feature.map["capsule_mode"])
        assertTrue(File(filesDir, "conversations/a.json").readText().contains("\"new\""))

        val site = Json.parseToJsonElement(File(filesDir, "web/sites.json").readText()).jsonArray.single().jsonObject
        assertEquals(20, site["lastVisitMs"]!!.jsonPrimitive.content.toInt())
        assertEquals("false", site["loggedIn"]!!.jsonPrimitive.content)
        assertEquals("7", site["challengedAt"]!!.jsonPrimitive.content)
        assertEquals("captcha", site["challenge"]!!.jsonPrimitive.content)

        val access = Json.parseToJsonElement(File(filesDir, "app-access.json").readText()).jsonObject
        assertEquals(setOf("a", "b"), access["blocked"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals(setOf("s1"), access["shareAllowed"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())

        assertEquals(1, triggers.restores)
        assertEquals(listOf("t0", "t1"), triggers.value.triggers.map { it.id })
        assertEquals(listOf("t1"), triggers.value.windows.map { it.triggerId })
    }

    @Test
    fun currentConversationFileIsNotImported() {
        val filesDir = tmp.newFolder("files-current")
        val extracted = tmp.newFolder("extracted-current")
        File(extracted, "data/conversations").mkdirs()
        File(extracted, "data/conversations/current").writeText("a")
        File(filesDir, "conversations").mkdirs()
        File(filesDir, "conversations/current").writeText("keep")
        val manifest = testManifest(listOf(BackupFormat.CONVERSATIONS))
        val vault = SecretVault(File(filesDir, "settings"), { null }, {}, { testCipher(key) })
        RestoreApplier(filesDir, extracted, manifest, vault, FakePrefs(), FakePrefs(), MemoryTriggers(), testCipher(key)).apply()
        assertEquals("keep", File(filesDir, "conversations/current").readText())
        assertFalse(File(filesDir, "conversations/a.json").exists())
    }

    @Test
    fun mergesMemoryAndProfileByUpdatedAt() {
        val filesDir = tmp.newFolder("files-mem")
        val extracted = tmp.newFolder("extracted-mem")
        val cipher = testCipher(key)
        EncryptedMemoryFile(filesDir, cipher, { 1L }).save(listOf(
            MemoryItem("m1", MemoryKind.fact, "old", emptyList(), MemorySource("manual", atMs = 1), MemoryStatus.active, createdAt = 1, updatedAt = 1),
        ))
        FileProfileBackend(File(filesDir, "memory/profile.bin"), cipher).write(listOf(
            ProfileEntry("p1", "old", 1, ProfileStatus.active),
        ))
        val items = listOf(
            MemoryItem("m1", MemoryKind.fact, "new", emptyList(), MemorySource("manual", atMs = 2), MemoryStatus.active, createdAt = 1, updatedAt = 2),
            MemoryItem("m2", MemoryKind.preference, "added", emptyList(), MemorySource("manual", atMs = 2), MemoryStatus.active, createdAt = 2, updatedAt = 2),
        )
        val profile = listOf(
            ProfileEntry("p1", "new", 2, ProfileStatus.active),
            ProfileEntry("p2", "added", 2, ProfileStatus.active),
        )
        File(extracted, "data/memory").mkdirs()
        File(extracted, "data/memory/items.json").writeText(Json.encodeToString(ListSerializer(MemoryItem.serializer()), items))
        File(extracted, "data/memory/profile.json").writeText(Json.encodeToString(ListSerializer(ProfileEntry.serializer()), profile))
        val manifest = testManifest(listOf(BackupFormat.MEMORY))
        val vault = SecretVault(File(filesDir, "settings"), { null }, {}, { cipher })
        RestoreApplier(filesDir, extracted, manifest, vault, FakePrefs(), FakePrefs(), MemoryTriggers(), cipher).apply()

        val merged = EncryptedMemoryFile(filesDir, cipher, { 1L }).load().associateBy { it.id }
        assertEquals("new", merged["m1"]!!.content)
        assertEquals("added", merged["m2"]!!.content)
        val mergedProfile = FileProfileBackend(File(filesDir, "memory/profile.bin"), cipher).read().associateBy { it.id }
        assertEquals("new", mergedProfile["p1"]!!.text)
        assertEquals("added", mergedProfile["p2"]!!.text)
    }

    @Test
    fun mergesSkillsKnowledgeRoutesApprovalsAndWorkspace() {
        val filesDir = tmp.newFolder("files-misc")
        val extracted = tmp.newFolder("extracted-misc")
        fun write(root: File, rel: String, text: String) {
            File(root, rel).apply { parentFile.mkdirs() }.writeText(text)
        }

        write(extracted, "data/skills/personal/s1/SKILL.md", "new")
        write(extracted, "data/skills/personal/s1/meta.json", "{}")
        write(extracted, "data/skills/pending/p1/meta.json", "{}")
        write(extracted, "data/skills/archive/a1/meta.json", "{}")
        File(extracted, "data/skills/personal/s1/meta.json").setLastModified(2_000)
        write(filesDir, "skills/personal/s1/SKILL.md", "old")
        write(filesDir, "skills/personal/s1/meta.json", "{}")
        File(filesDir, "skills/personal/s1/meta.json").setLastModified(1_000)

        write(extracted, "data/knowledge/app-drafts/d1/APP.md", "draft")
        write(extracted, "data/knowledge/personal/apps/x/NOTES.md", "imported")
        write(filesDir, "personal/apps/x/NOTES.md", "keep")

        val route = Route(
            id = "r1", template = "do {0}", slots = 1, packages = listOf("com.x"),
            appVersions = emptyMap(), steps = emptyList(), lastUsedMs = 1,
        )
        write(extracted, "data/automation/routes/com.x/r1.json", RouteCodec.encode(route))

        write(filesDir, "approvals.json", """{"autonomous":true}""")
        write(extracted, "data/automation/approvals.json", """{"autonomous":false}""")

        write(filesDir, "sandbox/work/c-1/a.txt", "keep")
        write(extracted, "data/workspace/c-1/a.txt", "new")
        write(extracted, "data/workspace/c-1/b.txt", "new")

        val manifest = testManifest(listOf(BackupFormat.SKILLS, BackupFormat.KNOWLEDGE, BackupFormat.AUTOMATION, BackupFormat.WORKSPACE))
        val vault = SecretVault(File(filesDir, "settings"), { null }, {}, { testCipher(key) })
        RestoreApplier(filesDir, extracted, manifest, vault, FakePrefs(), FakePrefs(), MemoryTriggers(), testCipher(key)).apply()

        assertEquals("new", File(filesDir, "skills/personal/s1/SKILL.md").readText())
        assertTrue(File(filesDir, "skills/pending/p1/meta.json").isFile)
        assertTrue(File(filesDir, "skills/archive/a1/meta.json").isFile)
        assertTrue(File(filesDir, "knowledge/app-drafts/d1/APP.md").isFile)
        assertEquals("keep", File(filesDir, "personal/apps/x/NOTES.md").readText())
        assertTrue(File(filesDir, "routes/com.x/r1.json").isFile)
        assertEquals("false", Json.parseToJsonElement(File(filesDir, "approvals.json").readText()).jsonObject["autonomous"]!!.jsonPrimitive.content)
        assertEquals("keep", File(filesDir, "sandbox/work/c-1/a.txt").readText())
        assertEquals("new", File(filesDir, "sandbox/work/c-1/b.txt").readText())
    }

    @Test
    fun restoresProviderAndRawSecrets() {
        val filesDir = tmp.newFolder("files-secrets")
        val extracted = tmp.newFolder("extracted-secrets")
        File(extracted, "secrets.json").writeText(
            """{"version":1,"providers":{"p1":"sk-1"},"mcpToken":"token","netProxy":"http://x"}""",
        )
        val cipher = testCipher(key)
        val vault = SecretVault(File(filesDir, "settings"), { null }, {}, { cipher })
        val manifest = testManifest(listOf(BackupFormat.SECRETS))
        val report = RestoreApplier(filesDir, extracted, manifest, vault, FakePrefs(), FakePrefs(), MemoryTriggers(), cipher).apply()

        val keys = Json.parseToJsonElement(File(filesDir, "settings/provider-keys.json").readText()).jsonObject
        assertEquals("sk-1", String(cipher.decrypt(java.util.Base64.getDecoder().decode(keys["p1"]!!.jsonPrimitive.content))))
        assertEquals("token", String(cipher.decrypt(File(filesDir, "settings/mcp-token.bin").readBytes())))
        assertEquals("http://x", String(cipher.decrypt(File(filesDir, "settings/net-proxy.bin").readBytes())))
        assertEquals(3, report.section(BackupFormat.SECRETS)!!.added)
    }

    @Test
    fun mergesProvidersByImportedId() {
        val filesDir = tmp.newFolder("files-providers")
        val extracted = tmp.newFolder("extracted-providers")
        File(filesDir, "settings").mkdirs()
        File(filesDir, "settings/providers.json").writeText("""{"services":[{"id":"p1","name":"old"}],"providers":[],"uses":{}}""")
        File(extracted, "data/settings").mkdirs()
        File(extracted, "data/settings/providers.json").writeText("""{"services":[{"id":"p1","name":"new"},{"id":"p2","name":"two"}],"providers":[],"uses":{}}""")
        val manifest = testManifest(listOf(BackupFormat.SETTINGS))
        val vault = SecretVault(File(filesDir, "settings"), { null }, {}, { testCipher(key) })
        RestoreApplier(filesDir, extracted, manifest, vault, FakePrefs(), FakePrefs(), MemoryTriggers(), testCipher(key)).apply()
        val services = Json.parseToJsonElement(File(filesDir, "settings/providers.json").readText()).jsonObject["services"]!!.jsonArray
            .associate { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(mapOf("p1" to "new", "p2" to "two"), services)
    }
}
