package com.wangzan101.mika.core.backup

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 口令 → PBKDF2 → AES-256-GCM 的分块加解密。
 *
 * 不用 [javax.crypto.CipherInputStream]:部分安卓版本在认证标签不匹配时不抛异常,会把包静默截断。
 * 这里显式调用 [Cipher.update] 分块写,最后 [Cipher.doFinal] 校验;失败时由调用方删掉临时文件。
 */
object BackupCrypto {
    private const val CHUNK = 256 * 1024
    private val random = SecureRandom()

    fun newSalt(): ByteArray = ByteArray(BackupFormat.SALT_BYTES).also(random::nextBytes)

    fun newIv(): ByteArray = ByteArray(BackupFormat.IV_BYTES).also(random::nextBytes)

    fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        if (passphrase.isEmpty()) throw BackupException(BackupError.BadPassphrase, "empty passphrase")
        if (iterations !in BackupFormat.MIN_ITERATIONS..BackupFormat.MAX_ITERATIONS) {
            throw BackupException(BackupError.Unsupported, "KDF iterations out of range")
        }
        val spec = PBEKeySpec(passphrase, salt, iterations, BackupFormat.KEY_BITS)
        val encoded = try {
            SecretKeyFactory.getInstance(BackupFormat.PBKDF2).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return try {
            SecretKeySpec(encoded, "AES")
        } finally {
            encoded.fill(0)
        }
    }

    /** 把 [input] 加密写到 [output],返回密文字节数(不含 IV;IV 在 manifest 里)。 */
    fun encrypt(input: InputStream, output: OutputStream, key: SecretKey, iv: ByteArray, aad: ByteArray): Long {
        val cipher = cipher(Cipher.ENCRYPT_MODE, key, iv, aad)
        val buffer = ByteArray(CHUNK)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            val out = cipher.update(buffer, 0, n) ?: continue
            output.write(out)
            total += out.size
        }
        val tail = cipher.doFinal()
        output.write(tail)
        total += tail.size
        output.flush()
        return total
    }

    /** 把 [input] 解密写到 [output];口令错或数据被改时抛 [BackupError.BadPassphrase]。 */
    fun decrypt(input: InputStream, output: OutputStream, key: SecretKey, iv: ByteArray, aad: ByteArray): Long {
        val cipher = cipher(Cipher.DECRYPT_MODE, key, iv, aad)
        val buffer = ByteArray(CHUNK)
        var total = 0L
        try {
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                val out = cipher.update(buffer, 0, n) ?: continue
                output.write(out)
                total += out.size
            }
            val tail = cipher.doFinal()
            output.write(tail)
            total += tail.size
            output.flush()
        } catch (e: AEADBadTagException) {
            throw BackupException(BackupError.BadPassphrase, "wrong passphrase or tampered backup", e)
        } catch (e: javax.crypto.BadPaddingException) {
            throw BackupException(BackupError.BadPassphrase, "wrong passphrase or tampered backup", e)
        } catch (e: javax.crypto.IllegalBlockSizeException) {
            throw BackupException(BackupError.Corrupt, "truncated backup", e)
        }
        return total
    }

    fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(CHUNK)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest()
    }

    private fun cipher(mode: Int, key: SecretKey, iv: ByteArray, aad: ByteArray): Cipher = try {
        Cipher.getInstance(BackupFormat.AES_GCM).apply {
            init(mode, key, GCMParameterSpec(BackupFormat.TAG_BITS, iv))
            updateAAD(aad)
        }
    } catch (e: java.security.GeneralSecurityException) {
        throw BackupException(BackupError.Unsupported, "AES-GCM is unavailable", e)
    }
}
