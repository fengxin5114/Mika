package com.wangzan101.mika.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class BackupManifestTest {
    private fun manifest(
        format: Int = BackupFormat.VERSION,
        iterations: Int = 120_000,
        salt: ByteArray = ByteArray(16) { 1 },
        iv: ByteArray = ByteArray(12) { 2 },
        sections: List<String> = listOf(BackupFormat.SETTINGS, BackupFormat.CONVERSATIONS),
        countsMac: String = "",
    ) = BackupManifest(
        format = format,
        appVersion = "0.1.33",
        exportedAt = "2026-11-02T00:00:00Z",
        kdf = BackupKdf(iterations = iterations, salt = Base64.getEncoder().encodeToString(salt)),
        cipher = BackupCipher(iv = Base64.getEncoder().encodeToString(iv)),
        sections = sections,
        counts = mapOf("conversations" to 42L),
        countsMac = countsMac,
        payload = BackupPayload(bytes = 100, sha256 = "a".repeat(64)),
    )

    @Test
    fun roundTrip() {
        val m = manifest().validate()
        val copy = BackupManifest.decode(BackupManifest.encode(m))
        assertEquals(m, copy)
        assertEquals(m.aadBytes().toList(), copy.aadBytes().toList())
    }

    @Test
    fun newerFormatRejected() {
        val failure = runCatching { manifest(format = BackupFormat.VERSION + 1).validate() }.exceptionOrNull()
        assertTrue(failure is BackupException && failure.code == BackupError.NewerFormat)
    }

    @Test
    fun badSaltRejected() {
        val failure = runCatching { manifest(salt = ByteArray(8)).validate() }.exceptionOrNull()
        assertTrue(failure is BackupException && failure.code == BackupError.Corrupt)
    }

    @Test
    fun badIvRejected() {
        val failure = runCatching { manifest(iv = ByteArray(4)).validate() }.exceptionOrNull()
        assertTrue(failure is BackupException && failure.code == BackupError.Corrupt)
    }

    @Test
    fun unknownSectionRejected() {
        val failure = runCatching { manifest(sections = listOf("everything")).validate() }.exceptionOrNull()
        assertTrue(failure is BackupException && failure.code == BackupError.Unsupported)
    }

    @Test
    fun duplicateSectionRejected() {
        val failure = runCatching { manifest(sections = listOf(BackupFormat.SETTINGS, BackupFormat.SETTINGS)).validate() }.exceptionOrNull()
        assertTrue(failure is BackupException && failure.code == BackupError.Corrupt)
    }

    @Test
    fun iterationsOutOfRangeRejected() {
        val failure = runCatching { manifest(iterations = 10).validate() }.exceptionOrNull()
        assertTrue(failure is BackupException && failure.code == BackupError.Unsupported)
    }

    @Test
    fun badCountsMacRejected() {
        val failure = runCatching { manifest(countsMac = "not-base64").validate() }.exceptionOrNull()
        assertTrue(failure is BackupException && failure.code == BackupError.Corrupt)
        val short = runCatching { manifest(countsMac = Base64.getEncoder().encodeToString(ByteArray(8))).validate() }.exceptionOrNull()
        assertTrue(short is BackupException && short.code == BackupError.Corrupt)
        manifest(countsMac = Base64.getEncoder().encodeToString(ByteArray(32))).validate()
    }

    @Test
    fun aadIgnoresSectionOrder() {
        val a = manifest(sections = listOf(BackupFormat.SETTINGS, BackupFormat.CONVERSATIONS)).aadBytes()
        val b = manifest(sections = listOf(BackupFormat.CONVERSATIONS, BackupFormat.SETTINGS)).aadBytes()
        assertTrue(a.contentEquals(b))
    }
}
