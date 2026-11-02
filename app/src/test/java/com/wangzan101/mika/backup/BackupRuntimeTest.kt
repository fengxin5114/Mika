package com.wangzan101.mika.backup

import com.wangzan101.mika.core.trigger.TriggerSnapshot
import com.wangzan101.mika.core.trigger.TriggerWindow
import com.wangzan101.mika.core.trigger.TriggerWindowState
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackupRuntimeTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = testKey()
    private val cipher = testCipher(key)

    private fun runtime(filesDir: File, cacheDir: File, prefs: FakePrefs, triggers: MemoryTriggers): BackupRuntime =
        BackupRuntime(
            filesDir = filesDir,
            cacheDir = cacheDir,
            secrets = SecretVault(File(filesDir, "settings"), { null }, {}, { cipher }),
            featurePrefs = prefs,
            languagePrefs = FakePrefs(mapOf("tag" to "zh")),
            triggers = triggers,
            memoryCipher = cipher,
            busyWithTasks = { false },
            contentResolver = null,
        )

    @Test
    fun exportThenRestoreRoundTripsSettingsConversationSecretsAndTriggers() {
        val source = tmp.newFolder("source")
        val sourceCache = tmp.newFolder("source-cache")
        File(source, "settings").mkdirs()
        File(source, "settings/providers.json").writeText("""{"services":[],"providers":[],"uses":{}}""")
        File(source, "settings/provider-keys.json").writeText(
            """{"p1":"${java.util.Base64.getEncoder().encodeToString(cipher.encrypt("sk-source".toByteArray()))}"}""",
        )
        File(source, "conversations").mkdirs()
        File(source, "conversations/a.json").writeText("""[{"userText":"hello","status":"succeeded"}]""")
        val sourcePrefs = FakePrefs(mapOf("theme_mode" to "Dark"))
        val sourceTriggers = MemoryTriggers(TriggerSnapshot(
            listOf(trigger("t1")),
            listOf(TriggerWindow("t1", 1, 2, state = TriggerWindowState.Delivered)),
        ))
        val sourceRuntime = runtime(source, sourceCache, sourcePrefs, sourceTriggers)
        val backup = File(tmp.root, "backup.zip")
        val exported = sourceRuntime.exportToFile("correct horse".toCharArray(), BackupOptions(), backup)
        assertTrue(backup.isFile)
        assertEquals(1L, exported.manifest.counts["conversations"])
        assertTrue(exported.manifest.countsMac.isNotEmpty())
        assertTrue(sourceCache.listFiles { f -> f.name.startsWith("mika-backup-work-") }.orEmpty().isEmpty())

        val target = tmp.newFolder("target")
        val targetCache = tmp.newFolder("target-cache")
        val targetPrefs = FakePrefs()
        val targetTriggers = MemoryTriggers()
        val targetRuntime = runtime(target, targetCache, targetPrefs, targetTriggers)
        val report = targetRuntime.restoreFromFile("correct horse".toCharArray(), backup)

        assertEquals("Dark", targetPrefs.map["theme_mode"])
        assertTrue(File(target, "conversations/a.json").isFile)
        val keys = kotlinx.serialization.json.Json.parseToJsonElement(File(target, "settings/provider-keys.json").readText()).jsonObject
        assertEquals("sk-source", String(cipher.decrypt(java.util.Base64.getDecoder().decode(keys["p1"]!!.jsonPrimitive.content))))
        assertEquals(listOf("t1"), targetTriggers.value.triggers.map { it.id })
        assertEquals(1, targetTriggers.value.windows.size)
        assertTrue(report.warnings.isEmpty())
    }

    @Test
    fun cleanupStaleRemovesOnlyBackupTemps() {
        val cache = tmp.newFolder("cleanup-cache")
        File(cache, "mika-backup-work-1/inner.zip").apply { parentFile.mkdirs() }.writeText("plaintext")
        File(cache, "mika-restore-work-2/payload.zip").apply { parentFile.mkdirs() }.writeText("plaintext")
        File(cache, "mika-backup-3.zip").writeText("x")
        File(cache, "keep.txt").writeText("keep")
        BackupRuntime.cleanupStale(cache)
        assertFalse(File(cache, "mika-backup-work-1").exists())
        assertFalse(File(cache, "mika-restore-work-2").exists())
        assertFalse(File(cache, "mika-backup-3.zip").exists())
        assertTrue(File(cache, "keep.txt").isFile)
    }

    @Test
    fun onlyEmptyDocumentsAreDeletedAfterFailure() {
        assertTrue(BackupRuntime.shouldDeleteEmptyDocument(0L))
        assertFalse(BackupRuntime.shouldDeleteEmptyDocument(1L))
        assertFalse(BackupRuntime.shouldDeleteEmptyDocument(null))
    }

    @Test
    fun wrongPassphraseDoesNotTouchTarget() {
        val source = tmp.newFolder("source2")
        val sourceCache = tmp.newFolder("source-cache2")
        File(source, "conversations").mkdirs()
        File(source, "conversations/a.json").writeText("[]")
        val runtime = runtime(source, sourceCache, FakePrefs(), MemoryTriggers())
        val backup = File(tmp.root, "backup2.zip")
        runtime.exportToFile("correct horse".toCharArray(), BackupOptions(), backup)

        val target = tmp.newFolder("target2")
        File(target, "conversations").mkdirs()
        File(target, "conversations/keep.json").writeText("keep")
        val targetRuntime = runtime(target, tmp.newFolder("target-cache2"), FakePrefs(), MemoryTriggers())
        val failure = runCatching { targetRuntime.restoreFromFile("wrong pass".toCharArray(), backup) }.exceptionOrNull()
        assertTrue(failure is com.wangzan101.mika.core.backup.BackupException)
        assertEquals("keep", File(target, "conversations/keep.json").readText())
        assertTrue(File(target, "conversations/keep.json").isFile)
    }
}
