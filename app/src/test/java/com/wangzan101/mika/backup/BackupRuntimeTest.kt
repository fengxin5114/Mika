package com.wangzan101.mika.backup

import com.wangzan101.mika.core.backup.BackupArchive
import com.wangzan101.mika.core.backup.BackupCipher
import com.wangzan101.mika.core.backup.BackupCrypto
import com.wangzan101.mika.core.backup.BackupFormat
import com.wangzan101.mika.core.backup.BackupKdf
import com.wangzan101.mika.core.backup.BackupManifest
import com.wangzan101.mika.core.backup.BackupPayload
import com.wangzan101.mika.core.trigger.TriggerSnapshot
import com.wangzan101.mika.core.trigger.TriggerWindow
import com.wangzan101.mika.core.trigger.TriggerWindowState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

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
    fun countMismatchIsReported() {
        val backup = File(tmp.root, "counts.zip")
        writeBackup(
            backup, "correct horse",
            counts = mapOf(BackupFormat.COUNT_CONVERSATIONS to 99L),
            macCounts = mapOf(BackupFormat.COUNT_CONVERSATIONS to 99L),
        )
        val target = tmp.newFolder("counts-target")
        val report = runtime(target, tmp.newFolder("counts-cache"), FakePrefs(), MemoryTriggers())
            .restoreFromFile("correct horse".toCharArray(), backup)
        assertTrue(report.warnings.any { it.startsWith("counts:") })
    }

    @Test
    fun countsMacTamperIsReported() {
        val backup = File(tmp.root, "mac.zip")
        writeBackup(
            backup, "correct horse",
            counts = mapOf(BackupFormat.COUNT_CONVERSATIONS to 1L),
            macCounts = mapOf(BackupFormat.COUNT_CONVERSATIONS to 99L),
        )
        val target = tmp.newFolder("mac-target")
        val report = runtime(target, tmp.newFolder("mac-cache"), FakePrefs(), MemoryTriggers())
            .restoreFromFile("correct horse".toCharArray(), backup)
        assertTrue(report.warnings.contains("counts-mac"))
    }

    @Test
    fun applyFailureRollsBackTarget() {
        val source = tmp.newFolder("fail-source")
        val sourceTrigger = MemoryTriggers(TriggerSnapshot(listOf(trigger("t1")), emptyList()))
        val sourceRuntime = runtime(source, tmp.newFolder("fail-source-cache"), FakePrefs(), sourceTrigger)
        val backup = File(tmp.root, "fail.zip")
        sourceRuntime.exportToFile("correct horse".toCharArray(), BackupOptions(), backup)

        val target = tmp.newFolder("fail-target")
        File(target, "conversations").mkdirs()
        File(target, "conversations/keep.json").writeText("keep")
        val failing = object : BackupTriggers {
            override fun snapshot() = TriggerSnapshot(emptyList(), emptyList())
            override fun restore(snapshot: TriggerSnapshot) = throw IllegalStateException("boom")
        }
        val targetRuntime = BackupRuntime(
            target, tmp.newFolder("fail-cache"), SecretVault(File(target, "settings"), { null }, {}, { cipher }),
            FakePrefs(), FakePrefs(), failing, cipher, { false }, null,
        )
        val failure = runCatching { targetRuntime.restoreFromFile("correct horse".toCharArray(), backup) }.exceptionOrNull()
        assertTrue(failure is com.wangzan101.mika.core.backup.BackupException)
        assertTrue(targetRuntime.lastReport.value?.rolledBack == true)
        assertEquals("keep", File(target, "conversations/keep.json").readText())
        assertFalse(File(target, "conversations/a.json").exists())
    }

    private fun writeBackup(
        file: File,
        passphrase: String,
        counts: Map<String, Long>,
        macCounts: Map<String, Long>,
    ) {
        val work = tmp.newFolder()
        val inner = File(work, "inner.zip")
        BackupArchive.writeZip(inner) { writer ->
            writer.addBytes("data/conversations/a.json", "[]".toByteArray())
        }
        val salt = BackupCrypto.newSalt()
        val iv = BackupCrypto.newIv()
        val placeholder = BackupManifest(
            appVersion = "0.1.33",
            exportedAt = "2026-11-02T00:00:00Z",
            kdf = BackupKdf(salt = Base64.getEncoder().encodeToString(salt)),
            cipher = BackupCipher(iv = Base64.getEncoder().encodeToString(iv)),
            sections = listOf(BackupFormat.CONVERSATIONS),
            counts = emptyMap(),
            payload = BackupPayload(bytes = 0, sha256 = "0".repeat(64)),
        )
        val key = BackupCrypto.deriveKey(passphrase.toCharArray(), salt, BackupFormat.DEFAULT_ITERATIONS)
        val payload = File(work, "payload.bin")
        payload.outputStream().use { out ->
            BackupCrypto.encrypt(inner.inputStream(), out, key, iv, placeholder.aadBytes())
        }
        val final = placeholder.copy(
            counts = counts,
            countsMac = BackupCrypto.countsMacBase64(key, macCounts),
            payload = BackupPayload(bytes = payload.length(), sha256 = BackupManifest.hex(BackupCrypto.sha256(payload))),
        )
        BackupArchive.writeContainer(file, BackupManifest.encode(final), payload)
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
