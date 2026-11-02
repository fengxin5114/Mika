package com.wangzan101.mika.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.backup.BackupArchive
import com.wangzan101.mika.core.backup.BackupCipher
import com.wangzan101.mika.core.backup.BackupCrypto
import com.wangzan101.mika.core.backup.BackupError
import com.wangzan101.mika.core.backup.BackupException
import com.wangzan101.mika.core.backup.BackupFormat
import com.wangzan101.mika.core.backup.BackupKdf
import com.wangzan101.mika.core.backup.BackupManifest
import com.wangzan101.mika.core.backup.BackupPayload
import com.wangzan101.mika.core.backup.ImportReport
import com.wangzan101.mika.core.backup.ImportReportBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/**
 * 导出/导入的编排:临时文件、进度、全局互斥、SAF 读写、快照与回滚。
 *
 * 所有写文件的操作都在 IO 线程上;界面只读 [busy]、[progress] 和 [lastReport]。
 * 恢复完成后进程里的单例仍是旧值,所以界面要提示重启。
 */
class BackupRuntime private constructor(private val context: Context) {
    private val app = context.applicationContext
    private val mutex = Mutex()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _progress = MutableStateFlow<BackupProgress?>(null)
    val progress: StateFlow<BackupProgress?> = _progress.asStateFlow()

    private val _lastReport = MutableStateFlow<ImportReport?>(null)
    val lastReport: StateFlow<ImportReport?> = _lastReport.asStateFlow()

    private val _lastSnapshot = MutableStateFlow<File?>(null)
    val lastSnapshot: StateFlow<File?> = _lastSnapshot.asStateFlow()

    suspend fun inspect(uri: Uri): BackupManifest = withContext(Dispatchers.IO) {
        val temp = File(app.cacheDir, "mika-inspect-${System.currentTimeMillis()}.zip")
        try {
            copyFromUri(uri, temp)
            BackupArchive.readManifest(temp)
        } finally {
            temp.delete()
        }
    }

    suspend fun export(passphrase: CharArray, options: BackupOptions, uri: Uri): ExportResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            _busy.value = true
            val temp = File(app.cacheDir, "mika-backup-${System.currentTimeMillis()}.zip")
            try {
                val result = exportToFile(passphrase, options, temp)
                _progress.value = BackupProgress(BackupStage.Writing)
                val out = app.contentResolver.openOutputStream(uri)
                    ?: throw BackupException(BackupError.Corrupt, "cannot open the destination")
                out.use { target -> temp.inputStream().use { it.copyTo(target, 64 * 1024) } }
                result
            } catch (t: Throwable) {
                deleteDocument(uri)
                throw t
            } finally {
                temp.delete()
                passphrase.fill('\u0000')
                _busy.value = false
                _progress.value = null
            }
        }
    }

    /** 导出到一个本地文件;测试和 SAF 写入共用。 */
    fun exportToFile(passphrase: CharArray, options: BackupOptions, target: File): ExportResult {
        requireSpace(32L shl 20)
        val work = File(app.cacheDir, "mika-backup-work-${System.currentTimeMillis()}")
        work.mkdirs()
        try {
            _progress.value = BackupProgress(BackupStage.Packing)
            val inner = File(work, "inner.zip")
            var collected: CollectResult? = null
            BackupArchive.writeZip(inner) { zip ->
                collected = BackupCollector(app, options) { _progress.value = it }.collect(zip, SecretVault(app))
            }
            val result = collected ?: throw BackupException(BackupError.Corrupt, "nothing was collected")

            val salt = BackupCrypto.newSalt()
            val iv = BackupCrypto.newIv()
            val exportedAt = Instant.now().toString()
            val manifest = BackupManifest(
                appVersion = BuildConfig.VERSION_NAME,
                exportedAt = exportedAt,
                kdf = BackupKdf(salt = java.util.Base64.getEncoder().encodeToString(salt)),
                cipher = BackupCipher(iv = java.util.Base64.getEncoder().encodeToString(iv)),
                sections = options.sections(),
                counts = result.counts,
                payload = BackupPayload(bytes = 0, sha256 = "0".repeat(64)),
            )

            _progress.value = BackupProgress(BackupStage.Encrypting)
            val payload = File(work, "payload.bin")
            val key = BackupCrypto.deriveKey(passphrase, salt, BackupFormat.DEFAULT_ITERATIONS)
            val bytes = payload.outputStream().use { out ->
                BackupCrypto.encrypt(inner.inputStream(), out, key, iv, manifest.aadBytes())
            }
            val sha = BackupManifest.hex(BackupCrypto.sha256(payload))
            val finalManifest = manifest.copy(payload = BackupPayload(bytes = bytes, sha256 = sha))

            BackupArchive.writeContainer(target, BackupManifest.encode(finalManifest), payload)
            return ExportResult(finalManifest, result.sections, result.warnings)
        } finally {
            work.deleteRecursively()
        }
    }

    suspend fun restore(passphrase: CharArray, uri: Uri): ImportReport = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (isBusyWithTasks()) throw BackupException(BackupError.Busy, "tasks are running")
            _busy.value = true
            val temp = File(app.cacheDir, "mika-restore-${System.currentTimeMillis()}.zip")
            try {
                copyFromUri(uri, temp)
                restoreFromFile(passphrase, temp)
            } finally {
                temp.delete()
                passphrase.fill('\u0000')
                _busy.value = false
                _progress.value = null
            }
        }
    }

    /** 从本地文件恢复;测试和 SAF 读取共用。 */
    fun restoreFromFile(passphrase: CharArray, file: File): ImportReport {
        if (isBusyWithTasks()) throw BackupException(BackupError.Busy, "tasks are running")
        requireSpace(file.length() * 2 + (64L shl 20))
        val work = File(app.cacheDir, "mika-restore-work-${System.currentTimeMillis()}")
        work.mkdirs()
        try {
            _progress.value = BackupProgress(BackupStage.Reading)
            val manifest = BackupArchive.readManifest(file)
            val payload = File(work, "payload.bin")
            val payloadBytes = BackupArchive.extractPayload(file, payload)
            if (payloadBytes != manifest.payload.bytes) throw BackupException(BackupError.Corrupt, "payload size mismatch")
            if (BackupManifest.hex(BackupCrypto.sha256(payload)) != manifest.payload.sha256) {
                throw BackupException(BackupError.Corrupt, "payload checksum mismatch")
            }

            _progress.value = BackupProgress(BackupStage.Decrypting)
            val key = BackupCrypto.deriveKey(passphrase, manifest.saltBytes(), manifest.kdf.iterations)
            val inner = File(work, "payload.zip")
            payload.inputStream().use { input ->
                inner.outputStream().use { out ->
                    BackupCrypto.decrypt(input, out, key, manifest.ivBytes(), manifest.aadBytes())
                }
            }

            _progress.value = BackupProgress(BackupStage.Extracting)
            val extracted = File(work, "extracted")
            BackupArchive.extractAll(inner, extracted)

            _progress.value = BackupProgress(BackupStage.Restoring)
            val snapshot = RestoreSnapshot(app, manifest.sections.contains(BackupFormat.WORKSPACE))
            snapshot.save()
            val builder = ImportReportBuilder()
            try {
                val report = RestoreApplier(app, extracted, manifest, SecretVault(app), builder).apply()
                RestoreSnapshot.prune(app.filesDir)
                _lastSnapshot.value = snapshot.dir
                _lastReport.value = report
                return report
            } catch (t: Throwable) {
                runCatching { snapshot.restore() }
                val failed = builder.build(rolledBack = true)
                _lastReport.value = failed
                throw BackupException(BackupError.Corrupt, "restore failed and was rolled back", t)
            }
        } finally {
            work.deleteRecursively()
            passphrase.fill('\u0000')
        }
    }

    /** 把最近一份快照复制回去;成功返回 true。 */
    suspend fun rollbackLatest(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val latest = _lastSnapshot.value ?: RestoreSnapshot.latest(app.filesDir) ?: return@withLock false
            _busy.value = true
            try {
                RestoreSnapshot(app, latest, includeWorkspace = File(latest, "sandbox/work").exists()).restore()
                _lastReport.value = _lastReport.value?.copy(rolledBack = true)
                true
            } finally {
                _busy.value = false
            }
        }
    }

    private fun isBusyWithTasks(): Boolean =
        (app as? MikaApp)?.taskState?.busy?.value == true

    private fun copyFromUri(uri: Uri, dest: File) {
        val input = app.contentResolver.openInputStream(uri)
            ?: throw BackupException(BackupError.Corrupt, "cannot open the backup")
        input.use { source -> dest.outputStream().use { source.copyTo(it, 64 * 1024) } }
    }

    private fun deleteDocument(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) }
    }

    private fun requireSpace(bytes: Long) {
        if (app.cacheDir.usableSpace < bytes + (64L shl 20)) {
            throw BackupException(BackupError.NoSpace, "not enough space")
        }
    }

    companion object {
        @Volatile private var instance: BackupRuntime? = null

        fun get(context: Context): BackupRuntime = instance ?: synchronized(this) {
            instance ?: BackupRuntime(context.applicationContext).also { instance = it }
        }
    }
}
