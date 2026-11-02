package com.wangzan101.mika.backup

import com.wangzan101.mika.core.backup.BackupFormat
import com.wangzan101.mika.core.trigger.TriggerSnapshot
import com.wangzan101.mika.core.trigger.TriggerWindow
import com.wangzan101.mika.core.trigger.TriggerWindowState
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
}
