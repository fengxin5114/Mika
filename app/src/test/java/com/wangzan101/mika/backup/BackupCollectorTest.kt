package com.wangzan101.mika.backup

import com.wangzan101.mika.core.backup.BackupArchive
import com.wangzan101.mika.core.backup.BackupError
import com.wangzan101.mika.core.backup.BackupException
import com.wangzan101.mika.core.backup.BackupFormat
import com.wangzan101.mika.core.memory.MemoryItem
import com.wangzan101.mika.core.memory.MemoryKind
import com.wangzan101.mika.core.memory.MemorySource
import com.wangzan101.mika.core.memory.MemoryStatus
import com.wangzan101.mika.core.trigger.TriggerSnapshot
import com.wangzan101.mika.core.trigger.TriggerWindow
import com.wangzan101.mika.core.trigger.TriggerWindowState
import com.wangzan101.mika.memory.EncryptedMemoryFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackupCollectorTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = testKey()
    private val cipher = testCipher(key)

    private fun collector(filesDir: File, triggers: MemoryTriggers, check: () -> Unit = {}) = BackupCollector(
        filesDir = filesDir,
        featurePrefs = FakePrefs(mapOf("theme_mode" to "Dark", "parallel_max" to 2)),
        languagePrefs = FakePrefs(mapOf("tag" to "zh")),
        triggers = triggers,
        memoryCipher = cipher,
        options = BackupOptions(),
        checkCancelled = check,
    )

    @Test
    fun skipsBrokenConversationCurrentAndKeepsTriggerWindows() {
        val filesDir = tmp.newFolder("files")
        File(filesDir, "settings").mkdirs()
        File(filesDir, "settings/providers.json").writeText("{}")
        File(filesDir, "conversations").mkdirs()
        File(filesDir, "conversations/good.json").writeText("""[{"userText":"hi","status":"succeeded"}]""")
        File(filesDir, "conversations/bad.json").writeText("""[{"userText":""")
        File(filesDir, "conversations/current").writeText("good")
        File(filesDir, "conversations/good.title").writeText("hello")
        EncryptedMemoryFile(filesDir, cipher, { 1L }).save(listOf(
            MemoryItem(
                id = "m1", kind = MemoryKind.fact, content = "fact", keywords = emptyList(),
                source = MemorySource("manual", atMs = 1), status = MemoryStatus.active, createdAt = 1, updatedAt = 1,
            ),
        ))
        val triggers = MemoryTriggers(TriggerSnapshot(
            listOf(trigger("t1")),
            listOf(TriggerWindow("t1", 1, 2, state = TriggerWindowState.Delivered)),
        ))
        val secrets = SecretVault(File(filesDir, "settings"), { null }, {}, { cipher })
        File(filesDir, "settings/provider-keys.json").writeText(
            """{"p1":"${java.util.Base64.getEncoder().encodeToString(cipher.encrypt("sk".toByteArray()))}"}""",
        )

        val zip = File(tmp.root, "out.zip")
        var result: CollectResult? = null
        BackupArchive.writeZip(zip) { writer -> result = collector(filesDir, triggers).collect(writer, secrets) }

        val extracted = File(tmp.root, "extracted")
        val names = BackupArchive.extractAll(zip, extracted).map { it.name }.toSet()
        assertTrue("data/conversations/good.json" in names)
        assertTrue("data/conversations/good.title" in names)
        assertTrue("data/conversations/bad.json" !in names)
        assertTrue("data/conversations/current" !in names)
        assertTrue("secrets.json" in names)
        val counts = result!!.counts
        assertEquals(1L, counts[BackupFormat.COUNT_CONVERSATIONS])
        assertEquals(1L, counts[BackupFormat.COUNT_MEMORY_ITEMS])
        assertEquals(1L, counts[BackupFormat.COUNT_TRIGGERS])
        assertEquals(1L, counts[BackupFormat.COUNT_TRIGGER_WINDOWS])
        assertEquals(1L, counts[BackupFormat.COUNT_PROVIDER_KEYS])
        assertTrue(result!!.warnings.any { it.contains("bad.json") })
        val triggersJson = File(extracted, "data/automation/triggers.json").readText()
        assertTrue(triggersJson.contains("windows"))
    }

    @Test
    fun unreadableMemoryAbortsExport() {
        val filesDir = tmp.newFolder("bad-memory")
        File(filesDir, "memory").mkdirs()
        File(filesDir, "memory/memory.bin").writeBytes("not encrypted".toByteArray())
        val failure = runCatching {
            BackupArchive.writeZip(File(tmp.root, "bad.zip")) { writer ->
                collector(filesDir, MemoryTriggers()).collect(writer, null)
            }
        }.exceptionOrNull()
        assertTrue(failure is BackupException && failure.code == BackupError.UnreadableData)
    }

    @Test
    fun cancellationStopsCollection() {
        val filesDir = tmp.newFolder("cancel")
        val failure = runCatching {
            BackupArchive.writeZip(File(tmp.root, "cancel.zip")) { writer ->
                collector(filesDir, MemoryTriggers()) { throw BackupCancelled() }.collect(writer, null)
            }
        }.exceptionOrNull()
        assertTrue(failure is BackupCancelled)
    }

    @Test
    fun estimateCountsSourceBytes() {
        val filesDir = tmp.newFolder("estimate")
        File(filesDir, "conversations").mkdirs()
        File(filesDir, "conversations/a.json").writeText("12345")
        val estimate = BackupCollector.estimateBytes(filesDir, BackupOptions())
        assertTrue(estimate >= 5)
        assertFalse(estimate < 0)
    }
}
