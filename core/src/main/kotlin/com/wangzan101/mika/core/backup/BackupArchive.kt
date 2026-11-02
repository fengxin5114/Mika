package com.wangzan101.mika.core.backup

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class ExtractedEntry(val name: String, val file: File, val bytes: Long)

/**
 * 备份容器的 zip 读写:外层只有 manifest.json + payload.bin;内层是 data/... 和可选的 secrets.json。
 * 所有路径在写入和读出时都校验,防止 zip slip、重复条目和解压炸弹。
 */
object BackupArchive {
    /** 写 zip 到任意输出流(流式加密时用);不在这里关闭/删除 [out]。 */
    fun writeZip(out: OutputStream, block: (ZipWriter) -> Unit) {
        ZipOutputStream(BufferedOutputStream(out)).use { zip -> block(ZipWriter(zip)) }
    }

    /** 写 zip;失败时删掉没写完的文件。 */
    fun writeZip(file: File, block: (ZipWriter) -> Unit) {
        file.parentFile?.mkdirs()
        try {
            FileOutputStream(file).use { out -> writeZip(out, block) }
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
    }

    fun writeContainer(file: File, manifest: ByteArray, payload: File) {
        writeZip(file) { zip ->
            zip.addBytes(BackupFormat.MANIFEST_ENTRY, manifest)
            zip.addFile(BackupFormat.PAYLOAD_ENTRY, payload)
        }
    }

    fun readManifest(file: File): BackupManifest = open(file).use { zip ->
        val entry = zip.getEntry(BackupFormat.MANIFEST_ENTRY)
            ?: throw BackupException(BackupError.NotMikaBackup, "manifest.json is missing")
        if (entry.size > BackupFormat.MAX_MANIFEST_BYTES) throw BackupException(BackupError.TooLarge, "manifest is too large")
        val bytes = zip.getInputStream(entry).use { readLimited(it, BackupFormat.MAX_MANIFEST_BYTES) }
        BackupManifest.decode(bytes).validate()
    }

    /** 把 payload.bin 原样取到 [dest],返回字节数。校验 CRC 和大小。 */
    fun extractPayload(file: File, dest: File, checkCancelled: () -> Unit = {}): Long = open(file).use { zip ->
        val entry = zip.getEntry(BackupFormat.PAYLOAD_ENTRY)
            ?: throw BackupException(BackupError.NotMikaBackup, "payload.bin is missing")
        copyEntry(zip, entry, dest, limit = BackupFormat.MAX_ENTRY_BYTES, checkCancelled = checkCancelled)
    }

    /** 解出内层 zip 的全部文件到 [destDir];返回写出的文件。 */
    fun extractAll(file: File, destDir: File, checkCancelled: () -> Unit = {}): List<ExtractedEntry> = open(file).use { zip ->
        val root = destDir.canonicalFile
        if (!root.isDirectory && !root.mkdirs()) throw IOException("cannot create ${root.path}")
        val rootPrefix = root.path + File.separator
        val seen = HashSet<String>()
        val out = ArrayList<ExtractedEntry>()
        var total = 0L
        var count = 0
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            checkCancelled()
            val entry = entries.nextElement()
            if (entry.isDirectory || entry.name.endsWith('/')) continue
            if (++count > BackupFormat.MAX_ENTRIES) throw BackupException(BackupError.TooLarge, "too many entries")
            val name = validateInnerName(entry.name)
            if (!seen.add(name)) throw BackupException(BackupError.Corrupt, "duplicate entry $name")
            val size = entry.size
            if (size > BackupFormat.MAX_ENTRY_BYTES) throw BackupException(BackupError.TooLarge, "entry is too large")
            if (size > BackupFormat.RATIO_CHECK_BYTES && entry.compressedSize > 0 &&
                size / entry.compressedSize > BackupFormat.MAX_COMPRESSION_RATIO
            ) {
                throw BackupException(BackupError.TooLarge, "suspicious compression ratio")
            }
            total += maxOf(0L, size)
            if (total > BackupFormat.MAX_TOTAL_BYTES) throw BackupException(BackupError.TooLarge, "archive is too large")
            val target = File(root, name).canonicalFile
            if (!target.path.startsWith(rootPrefix)) throw BackupException(BackupError.UnsafePath, "entry escapes the archive")
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".part")
            try {
                copyEntry(zip, entry, tmp, limit = BackupFormat.MAX_ENTRY_BYTES, checkCancelled = checkCancelled)
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (t: Throwable) {
                tmp.delete()
                throw t
            }
            if (entry.time > 0) target.setLastModified(entry.time)
            out += ExtractedEntry(name, target, target.length())
        }
        out
    }

    /** 内层条目的名字规则:只允许 data 目录下的条目和根上的 secrets.json。 */
    fun validateInnerName(raw: String): String {
        if (raw.isEmpty() || raw.contains('\\') || raw.contains('\u0000')) {
            throw BackupException(BackupError.UnsafePath, "unsafe entry name")
        }
        if (raw.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(raw)) {
            throw BackupException(BackupError.UnsafePath, "absolute entry name")
        }
        val parts = raw.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) {
            throw BackupException(BackupError.UnsafePath, "unsafe entry path")
        }
        if (raw != "secrets.json" && !raw.startsWith("data/")) {
            throw BackupException(BackupError.Unsupported, "unexpected entry $raw")
        }
        return raw
    }

    private fun copyEntry(zip: ZipFile, entry: ZipEntry, dest: File, limit: Long, checkCancelled: () -> Unit): Long {
        dest.parentFile?.mkdirs()
        val crc = CRC32()
        var total = 0L
        zip.getInputStream(entry).use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkCancelled()
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > limit) throw BackupException(BackupError.TooLarge, "entry is too large")
                    crc.update(buffer, 0, n)
                    output.write(buffer, 0, n)
                }
                output.fd.sync()
            }
        }
        if (entry.size >= 0 && total != entry.size) throw BackupException(BackupError.Corrupt, "entry size mismatch")
        if (entry.crc >= 0 && crc.value != entry.crc) throw BackupException(BackupError.Corrupt, "entry checksum mismatch")
        return total
    }

    private fun open(file: File): ZipFile = try {
        ZipFile(file)
    } catch (e: ZipException) {
        throw BackupException(BackupError.NotMikaBackup, "not a zip file", e)
    } catch (e: IOException) {
        throw BackupException(BackupError.Corrupt, "cannot read the backup", e)
    }

    private fun readLimited(input: InputStream, max: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > max) throw BackupException(BackupError.TooLarge, "entry is too large")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}

/** 往 zip 里写条目;名字规则和 [BackupArchive.validateInnerName] 一致。 */
class ZipWriter(private val zip: ZipOutputStream) : Closeable {
    private val names = HashSet<String>()

    fun addBytes(name: String, bytes: ByteArray, time: Long = System.currentTimeMillis()) {
        val entry = newEntry(name, time)
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    fun addFile(name: String, file: File) {
        val now = if (file.lastModified() > 0) file.lastModified() else System.currentTimeMillis()
        val entry = newEntry(name, now)
        zip.putNextEntry(entry)
        file.inputStream().use { it.copyTo(zip, 64 * 1024) }
        zip.closeEntry()
    }

    private fun newEntry(name: String, time: Long): ZipEntry {
        val safe = if (name == BackupFormat.MANIFEST_ENTRY || name == BackupFormat.PAYLOAD_ENTRY) name
        else BackupArchive.validateInnerName(name)
        if (!names.add(safe)) throw BackupException(BackupError.Corrupt, "duplicate entry $safe")
        return ZipEntry(safe).apply { this.time = time }
    }

    override fun close() = zip.close()
}
