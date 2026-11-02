package com.wangzan101.mika.core.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun writesToOutputStream() {
        val out = ByteArrayOutputStream()
        BackupArchive.writeZip(out) { writer -> writer.addBytes("data/a.txt", "a".toByteArray()) }
        val zip = File(tmp.root, "stream.zip").apply { writeBytes(out.toByteArray()) }
        assertEquals(1, BackupArchive.extractAll(zip, File(tmp.root, "stream-out")).size)
    }

    @Test
    fun cancellationStopsExtraction() {
        val zip = File(tmp.root, "cancel.zip")
        BackupArchive.writeZip(zip) { writer -> writer.addBytes("data/a.txt", "a".toByteArray()) }
        val failure = runCatching {
            BackupArchive.extractAll(zip, File(tmp.root, "cancel-out")) {
                throw BackupException(BackupError.Cancelled, "cancelled")
            }
        }.exceptionOrNull()
        assertTrue(failure is BackupException && failure.code == BackupError.Cancelled)
    }

    @Test
    fun innerZipRoundTrip() {
        val zip = File(tmp.root, "inner.zip")
        val hello = "hello".toByteArray()
        BackupArchive.writeZip(zip) { writer ->
            writer.addBytes("data/settings/providers.json", "{}".toByteArray())
            writer.addBytes("data/conversations/abc.json", hello)
            writer.addBytes("secrets.json", "{\"version\":1}".toByteArray())
        }

        val outDir = File(tmp.root, "out")
        val entries = BackupArchive.extractAll(zip, outDir)
        assertEquals(3, entries.size)
        assertArrayEquals(hello, File(outDir, "data/conversations/abc.json").readBytes())
        assertTrue(File(outDir, "secrets.json").isFile)
    }

    @Test
    fun containerRoundTrip() {
        val payload = File(tmp.root, "payload.bin")
        payload.writeBytes(ByteArray(1024) { it.toByte() })
        val manifest = manifest()
        val container = File(tmp.root, "backup.zip")
        BackupArchive.writeContainer(container, BackupManifest.encode(manifest), payload)

        val read = BackupArchive.readManifest(container)
        assertEquals(BackupFormat.VERSION, read.format)
        assertEquals(manifest.sections, read.sections)

        val extracted = File(tmp.root, "payload-out.bin")
        assertEquals(1024L, BackupArchive.extractPayload(container, extracted))
        assertArrayEquals(payload.readBytes(), extracted.readBytes())
    }

    @Test
    fun nonZipIsNotMikaBackup() {
        val file = File(tmp.root, "not-a-zip")
        file.writeText("hello")
        val failure = runCatching { BackupArchive.readManifest(file) }.exceptionOrNull()
        assertTrue(failure is BackupException && failure.code == BackupError.NotMikaBackup)
    }

    @Test
    fun zipSlipRejected() {
        val zip = maliciousZip("data/../../evil.txt")
        val failure = runCatching { BackupArchive.extractAll(zip, File(tmp.root, "out")) }.exceptionOrNull()
        assertTrue(failure is BackupException && failure.code == BackupError.UnsafePath)
        assertTrue(!File(tmp.root.parentFile, "evil.txt").exists())
    }

    @Test
    fun absolutePathRejected() {
        val zip = maliciousZip("/etc/passwd")
        val failure = runCatching { BackupArchive.extractAll(zip, File(tmp.root, "out")) }.exceptionOrNull()
        assertTrue(failure is BackupException && failure.code == BackupError.UnsafePath)
    }

    @Test
    fun unexpectedEntryRejected() {
        val zip = maliciousZip("other/file.txt")
        val failure = runCatching { BackupArchive.extractAll(zip, File(tmp.root, "out")) }.exceptionOrNull()
        assertTrue(failure is BackupException && failure.code == BackupError.Unsupported)
    }

    @Test
    fun duplicateEntryRejectedByWriter() {
        val zip = File(tmp.root, "dup.zip")
        val failure = runCatching {
            BackupArchive.writeZip(zip) { writer ->
                writer.addBytes("data/a.txt", "a".toByteArray())
                writer.addBytes("data/a.txt", "b".toByteArray())
            }
        }.exceptionOrNull()
        assertTrue(failure is BackupException && failure.code == BackupError.Corrupt)
    }

    @Test
    fun innerEntryNamesMustStayInDataOrSecrets() {
        val ok = listOf("data/settings/providers.json", "data/a/b.txt", "secrets.json")
        ok.forEach { assertEquals(it, BackupArchive.validateInnerName(it)) }
        listOf("../a", "data/../a", "/a", "a/b", "data//a", "data/a/../b").forEach { bad ->
            assertTrue(bad, runCatching { BackupArchive.validateInnerName(bad) }.isFailure)
        }
    }

    @Test
    fun corruptedManifestIsRejected() {
        val file = File(tmp.root, "bad-manifest.zip")
        BackupArchive.writeContainer(file, "not json".toByteArray(), File(tmp.root, "payload.bin").apply { writeBytes(byteArrayOf(1)) })
        val failure = runCatching { BackupArchive.readManifest(file) }.exceptionOrNull()
        assertTrue(failure is BackupException && failure.code == BackupError.NotMikaBackup)
    }

    private fun maliciousZip(name: String): File {
        val file = File(tmp.root, "malicious-${name.hashCode()}.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write("owned".toByteArray())
            zip.closeEntry()
        }
        return file
    }

    private fun manifest(): BackupManifest = BackupManifest(
        appVersion = "0.1.33",
        exportedAt = "2026-11-02T00:00:00Z",
        kdf = BackupKdf(salt = java.util.Base64.getEncoder().encodeToString(ByteArray(16))),
        cipher = BackupCipher(iv = java.util.Base64.getEncoder().encodeToString(ByteArray(12))),
        sections = listOf(BackupFormat.SETTINGS),
        payload = BackupPayload(bytes = 1024, sha256 = "0".repeat(64)),
    )
}
