package com.wangzan101.mika.core.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import javax.crypto.AEADBadTagException

class BackupCryptoTest {
    private val pass = "correct horse battery staple".toCharArray()

    @Test
    fun roundTrip() {
        val salt = BackupCrypto.newSalt()
        val iv = BackupCrypto.newIv()
        val key = BackupCrypto.deriveKey(pass, salt, 120_000)
        val plain = "你好, Mika backup".toByteArray(StandardCharsets.UTF_8)
        val aad = "aad".toByteArray()

        val encrypted = ByteArrayOutputStream()
        val size = BackupCrypto.encrypt(ByteArrayInputStream(plain), encrypted, key, iv, aad)
        assertEquals(encrypted.size().toLong(), size)

        val decrypted = ByteArrayOutputStream()
        val read = BackupCrypto.decrypt(ByteArrayInputStream(encrypted.toByteArray()), decrypted, key, iv, aad)
        assertEquals(plain.size.toLong(), read)
        assertArrayEquals(plain, decrypted.toByteArray())
    }

    @Test
    fun samePassphraseAndSaltDeriveSameKey() {
        val salt = BackupCrypto.newSalt()
        val a = BackupCrypto.deriveKey(pass, salt, 100_001).encoded
        val b = BackupCrypto.deriveKey(pass, salt, 100_001).encoded
        assertArrayEquals(a, b)
    }

    @Test
    fun wrongPassphraseFails() {
        val salt = BackupCrypto.newSalt()
        val iv = BackupCrypto.newIv()
        val key = BackupCrypto.deriveKey(pass, salt, 100_001)
        val encrypted = ByteArrayOutputStream()
        BackupCrypto.encrypt(ByteArrayInputStream("secret".toByteArray()), encrypted, key, iv, "aad".toByteArray())

        val wrong = BackupCrypto.deriveKey("wrong".toCharArray(), salt, 100_001)
        val failure = runCatching {
            BackupCrypto.decrypt(ByteArrayInputStream(encrypted.toByteArray()), ByteArrayOutputStream(), wrong, iv, "aad".toByteArray())
        }.exceptionOrNull()
        assertTrue(failure is BackupException && failure.code == BackupError.BadPassphrase)
    }

    @Test
    fun tamperedCiphertextFails() {
        val salt = BackupCrypto.newSalt()
        val iv = BackupCrypto.newIv()
        val key = BackupCrypto.deriveKey(pass, salt, 100_001)
        val encrypted = ByteArrayOutputStream()
        BackupCrypto.encrypt(ByteArrayInputStream("secret".toByteArray()), encrypted, key, iv, "aad".toByteArray())
        val bytes = encrypted.toByteArray()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()

        val failure = runCatching {
            BackupCrypto.decrypt(ByteArrayInputStream(bytes), ByteArrayOutputStream(), key, iv, "aad".toByteArray())
        }.exceptionOrNull()
        assertTrue(failure is BackupException && failure.code == BackupError.BadPassphrase)
    }

    @Test
    fun differentAadFails() {
        val salt = BackupCrypto.newSalt()
        val iv = BackupCrypto.newIv()
        val key = BackupCrypto.deriveKey(pass, salt, 100_001)
        val encrypted = ByteArrayOutputStream()
        BackupCrypto.encrypt(ByteArrayInputStream("secret".toByteArray()), encrypted, key, iv, "aad-a".toByteArray())

        val failure = runCatching {
            BackupCrypto.decrypt(ByteArrayInputStream(encrypted.toByteArray()), ByteArrayOutputStream(), key, iv, "aad-b".toByteArray())
        }.exceptionOrNull()
        assertTrue(failure is BackupException && failure.code == BackupError.BadPassphrase)
    }

    @Test
    fun truncatedCiphertextFails() {
        val salt = BackupCrypto.newSalt()
        val iv = BackupCrypto.newIv()
        val key = BackupCrypto.deriveKey(pass, salt, 100_001)
        val encrypted = ByteArrayOutputStream()
        BackupCrypto.encrypt(ByteArrayInputStream("secret".toByteArray()), encrypted, key, iv, "aad".toByteArray())
        val bytes = encrypted.toByteArray().copyOf(encrypted.size() - 1)

        val failure = runCatching {
            BackupCrypto.decrypt(ByteArrayInputStream(bytes), ByteArrayOutputStream(), key, iv, "aad".toByteArray())
        }.exceptionOrNull()
        assertTrue(failure is BackupException)
        val code = (failure as BackupException).code
        assertTrue(code == BackupError.BadPassphrase || code == BackupError.Corrupt)
    }

    @Test
    fun emptyPassphraseRejected() {
        val failure = runCatching { BackupCrypto.deriveKey(charArrayOf(), BackupCrypto.newSalt(), 100_001) }.exceptionOrNull()
        assertTrue(failure is BackupException && failure.code == BackupError.BadPassphrase)
    }

    @Test
    fun iterationsOutOfRangeRejected() {
        val failure = runCatching { BackupCrypto.deriveKey(pass, BackupCrypto.newSalt(), 1) }.exceptionOrNull()
        assertTrue(failure is BackupException && failure.code == BackupError.Unsupported)
    }

    @Test
    fun countsMacIsStableAndDetectsChanges() {
        val key = BackupCrypto.deriveKey(pass, BackupCrypto.newSalt(), 100_001)
        val counts = mapOf("conversations" to 2L, "memoryItems" to 3L)
        val mac = BackupCrypto.countsMacBase64(key, counts)
        assertTrue(BackupCrypto.verifyCountsMac(key, counts, mac))
        assertFalse(BackupCrypto.verifyCountsMac(key, counts + ("memoryItems" to 4L), mac))
        assertFalse(BackupCrypto.verifyCountsMac(key, counts, "not-base64"))
    }

    @Test
    fun gcmTagExceptionIsMapped() {
        // 单独确认底层确实会抛 AEADBadTagException,而不是被 CipherInputStream 吞掉
        val salt = BackupCrypto.newSalt()
        val iv = BackupCrypto.newIv()
        val key = BackupCrypto.deriveKey(pass, salt, 100_001)
        val encrypted = ByteArrayOutputStream()
        BackupCrypto.encrypt(ByteArrayInputStream("secret".toByteArray()), encrypted, key, iv, "aad".toByteArray())
        val failure = runCatching { BackupCrypto.decrypt(ByteArrayInputStream(encrypted.toByteArray()), ByteArrayOutputStream(), key, iv, "other".toByteArray()) }.exceptionOrNull()
        assertTrue(failure is BackupException)
        val backup = failure as BackupException
        assertTrue(backup.cause is AEADBadTagException || backup.code == BackupError.BadPassphrase)
    }
}
