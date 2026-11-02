package com.wangzan101.mika.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
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
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.settings.KeystoreKeyCipher
import com.wangzan101.mika.task.TriggerRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 导出/导入的编排:临时文件、进度、全局互斥、SAF 读写、快照与回滚。
 *
 * 导出直接边收集边加密,内层 zip(含 secrets.json 明文)不落盘;`payload.bin` 仍然是临时文件。
 * 所有写文件的操作都在 IO 线程上;界面只读 [busy]、[progress] 和 [lastReport]。
 * 恢复完成后进程里的单例仍是旧值,所以界面要提示重启。
 */
class BackupRuntime internal constructor(
    private val filesDir: File,
    private val cacheDir: File,
    private val secrets: SecretVault,
    private val featurePrefs: BackupPrefs,
    private val languagePrefs: BackupPrefs,
    private val triggers: BackupTriggers,
    private val memoryCipher: KeyCipher,
    private val busyWithTasks: () -> Boolean,
    private val contentResolver: ContentResolver?,
) {
    private val mutex = Mutex()
    private val cancelled = AtomicBoolean(false)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _progress = MutableStateFlow<BackupProgress?>(null)
    val progress: StateFlow<BackupProgress?> = _progress.asStateFlow()

    private val _lastReport = MutableStateFlow<ImportReport?>(null)
    val lastReport: StateFlow<ImportReport?> = _lastReport.asStateFlow()

    private val _lastSnapshot = MutableStateFlow<File?>(null)
    val lastSnapshot: StateFlow<File?> = _lastSnapshot.asStateFlow()

    /** 用户按「取消」;下一次循环检查时抛 [BackupCancelled]。 */
    fun cancel() {
        cancelled.set(true)
    }

    suspend fun inspect(uri: Uri): BackupManifest = withContext(Dispatchers.IO) {
        val temp = File(cacheDir, "mika-inspect-${System.currentTimeMillis()}.zip")
        try {
            copyFromUri(uri, temp)
            BackupArchive.readManifest(temp)
        } catch (t: Throwable) {
            throw mapIo(t)
        } finally {
            temp.delete()
        }
    }

    suspend fun export(passphrase: CharArray, options: BackupOptions, uri: Uri): ExportResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            cancelled.set(false)
            _busy.value = true
            val temp = File(cacheDir, "mika-backup-${System.currentTimeMillis()}.zip")
            try {
                val result = exportToFile(passphrase, options, temp)
                _progress.value = BackupProgress(BackupStage.Writing)
                checkCancelled()
                val out = contentResolver?.openOutputStream(uri)
                    ?: throw BackupException(BackupError.Corrupt, "cannot open the destination")
                out.use { target -> temp.inputStream().use { it.copyTo(target, 64 * 1024) } }
                result
            } catch (t: Throwable) {
                // 只删本次新建的空文档;用户选了覆盖同名文件时不能把上一份备份删掉
                if (isDocumentEmpty(uri)) deleteDocument(uri)
                throw mapIo(t)
            } finally {
                temp.delete()
                passphrase.fill('\u0000')
                _busy.value = false
                _progress.value = null
            }
        }
    }

    /** 导出到一个本地文件;测试和 SAF 写入共用。 */
    internal fun exportToFile(passphrase: CharArray, options: BackupOptions, target: File): ExportResult {
        cancelled.set(false)
        val estimate = BackupCollector.estimateBytes(filesDir, options)
        requireSpace(estimate * 2 + (32L shl 20))
        val work = File(cacheDir, "mika-backup-work-${System.currentTimeMillis()}")
        work.mkdirs()
        try {
            _progress.value = BackupProgress(BackupStage.Packing)
            val innerZip = File(work, "payload.bin")
            val salt = BackupCrypto.newSalt()
            val iv = BackupCrypto.newIv()
            val exportedAt = Instant.now().toString()
            val sections = options.sections()
            val manifest = BackupManifest(
                appVersion = BuildConfig.VERSION_NAME,
                exportedAt = exportedAt,
                kdf = BackupKdf(salt = java.util.Base64.getEncoder().encodeToString(salt)),
                cipher = BackupCipher(iv = java.util.Base64.getEncoder().encodeToString(iv)),
                sections = sections,
                counts = emptyMap(),
                countsMac = "",
                payload = BackupPayload(bytes = 0, sha256 = "0".repeat(64)),
            )
            val key = BackupCrypto.deriveKey(passphrase, salt, BackupFormat.DEFAULT_ITERATIONS)

            var collected: CollectResult? = null
            _progress.value = BackupProgress(BackupStage.Encrypting)
            innerZip.outputStream().use { raw ->
                BackupCrypto.encryptingStream(raw, key, iv, manifest.aadBytes()).use { encrypted ->
                    BackupArchive.writeZip(encrypted) { zip ->
                        collected = BackupCollector(
                            filesDir, featurePrefs, languagePrefs, triggers, memoryCipher, options,
                            onProgress = { _progress.value = it },
                            checkCancelled = ::checkCancelled,
                        ).collect(zip, secrets)
                    }
                }
            }
            val result = collected ?: throw BackupException(BackupError.Corrupt, "nothing was collected")
            val sha = BackupManifest.hex(BackupCrypto.sha256(innerZip))
            val finalManifest = manifest.copy(
                counts = result.counts,
                countsMac = BackupCrypto.countsMacBase64(key, result.counts),
                payload = BackupPayload(bytes = innerZip.length(), sha256 = sha),
            )
            checkCancelled()
            _progress.value = BackupProgress(BackupStage.Writing)
            BackupArchive.writeContainer(target, BackupManifest.encode(finalManifest), innerZip)
            return ExportResult(finalManifest, result.sections, result.warnings)
        } finally {
            work.deleteRecursively()
        }
    }

    suspend fun restore(passphrase: CharArray, uri: Uri): ImportReport = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (busyWithTasks()) throw BackupException(BackupError.Busy, "tasks are running")
            cancelled.set(false)
            _busy.value = true
            val temp = File(cacheDir, "mika-restore-${System.currentTimeMillis()}.zip")
            try {
                copyFromUri(uri, temp)
                restoreFromFile(passphrase, temp)
            } catch (t: Throwable) {
                throw mapIo(t)
            } finally {
                temp.delete()
                passphrase.fill('\u0000')
                _busy.value = false
                _progress.value = null
            }
        }
    }

    /** 从本地文件恢复;测试和 SAF 读取共用。 */
    internal fun restoreFromFile(passphrase: CharArray, file: File): ImportReport {
        cancelled.set(false)
        if (busyWithTasks()) throw BackupException(BackupError.Busy, "tasks are running")
        requireSpace(file.length() * 3 + (64L shl 20))
        val work = File(cacheDir, "mika-restore-work-${System.currentTimeMillis()}")
        work.mkdirs()
        try {
            _progress.value = BackupProgress(BackupStage.Reading)
            val manifest = BackupArchive.readManifest(file)
            val payload = File(work, "payload.bin")
            val payloadBytes = BackupArchive.extractPayload(file, payload, ::checkCancelled)
            if (payloadBytes != manifest.payload.bytes) throw BackupException(BackupError.Corrupt, "payload size mismatch")
            if (BackupManifest.hex(BackupCrypto.sha256(payload)) != manifest.payload.sha256) {
                throw BackupException(BackupError.Corrupt, "payload checksum mismatch")
            }

            _progress.value = BackupProgress(BackupStage.Decrypting)
            val key = BackupCrypto.deriveKey(passphrase, manifest.saltBytes(), manifest.kdf.iterations)
            val inner = File(work, "payload.zip")
            payload.inputStream().use { input ->
                inner.outputStream().use { out ->
                    BackupCrypto.decrypt(input, out, key, manifest.ivBytes(), manifest.aadBytes(), ::checkCancelled)
                }
            }

            _progress.value = BackupProgress(BackupStage.Extracting)
            val extracted = File(work, "extracted")
            BackupArchive.extractAll(inner, extracted, ::checkCancelled)

            val builder = ImportReportBuilder()
            val actual = PackageCounter.count(extracted)
            val mismatches = PackageCounter.mismatches(manifest.counts, actual)
            if (mismatches.isNotEmpty()) builder.warn("counts:${mismatches.joinToString(",")}")
            if (manifest.countsMac.isNotEmpty() && !BackupCrypto.verifyCountsMac(key, manifest.counts, manifest.countsMac)) {
                builder.warn("counts-mac")
            }

            _progress.value = BackupProgress(BackupStage.Restoring)
            val snapshot = RestoreSnapshot(
                filesDir, RestoreSnapshot.newDir(filesDir), manifest.sections.contains(BackupFormat.WORKSPACE),
                featurePrefs, languagePrefs,
            )
            snapshot.save()
            try {
                val report = RestoreApplier(
                    filesDir, extracted, manifest, secrets, featurePrefs, languagePrefs, triggers, memoryCipher,
                    report = builder, checkCancelled = ::checkCancelled,
                ).apply()
                RestoreSnapshot.prune(filesDir)
                _lastSnapshot.value = snapshot.dir
                _lastReport.value = report
                return report
            } catch (t: Throwable) {
                runCatching { snapshot.restore() }
                _lastReport.value = builder.build(rolledBack = true)
                if (t is BackupCancelled) throw t
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
            val latest = _lastSnapshot.value ?: RestoreSnapshot.latest(filesDir) ?: return@withLock false
            _busy.value = true
            try {
                RestoreSnapshot(
                    filesDir, latest, File(latest, "sandbox/work").exists(), featurePrefs, languagePrefs,
                ).restore()
                _lastReport.value = _lastReport.value?.copy(rolledBack = true)
                true
            } finally {
                _busy.value = false
            }
        }
    }

    private fun checkCancelled() {
        if (cancelled.get()) throw BackupCancelled()
    }

    private fun copyFromUri(uri: Uri, dest: File) {
        val input = contentResolver?.openInputStream(uri)
            ?: throw BackupException(BackupError.Corrupt, "cannot open the backup")
        input.use { source -> dest.outputStream().use { source.copyTo(it, 64 * 1024) } }
    }

    /** 只在文档是空的时候删;查询失败/非空都保留,避免误删上一份备份。 */
    private fun isDocumentEmpty(uri: Uri): Boolean = runCatching {
        val size = contentResolver?.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        } ?: return@runCatching false
        shouldDeleteEmptyDocument(size)
    }.getOrDefault(false)

    private fun deleteDocument(uri: Uri) {
        runCatching { contentResolver?.let { DocumentsContract.deleteDocument(it, uri) } }
    }

    private fun requireSpace(bytes: Long) {
        if (cacheDir.usableSpace < bytes) {
            throw BackupException(BackupError.NoSpace, "not enough space")
        }
    }

    private fun mapIo(t: Throwable): Throwable {
        if (t is BackupException || t is BackupCancelled) return t
        if (t is IOException) {
            val text = t.message.orEmpty()
            if (cacheDir.usableSpace < (64L shl 20) || text.contains("space", true) || text.contains("ENOSPC", true)) {
                return BackupException(BackupError.NoSpace, "not enough space", t)
            }
        }
        return t
    }

    companion object {
        @Volatile private var instance: BackupRuntime? = null

        fun get(context: Context): BackupRuntime = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val filesDir = app.filesDir
                val feature = app.getSharedPreferences(FeatureFlags.PREFS, Context.MODE_PRIVATE)
                val language = app.getSharedPreferences(PrefsJson.LANGUAGE_PREFS, Context.MODE_PRIVATE)
                cleanupStale(app.cacheDir)
                BackupRuntime(
                    filesDir = filesDir,
                    cacheDir = app.cacheDir,
                    secrets = SecretVault(app),
                    featurePrefs = SharedBackupPrefs(feature),
                    languagePrefs = SharedBackupPrefs(language),
                    triggers = FileBackupTriggers(File(filesDir, "triggers-v3.json")) { TriggerRuntime.rearm(app) },
                    memoryCipher = KeystoreKeyCipher("mika-memory"),
                    busyWithTasks = { (app as? MikaApp)?.taskState?.busy?.value == true },
                    contentResolver = app.contentResolver,
                ).also { instance = it }
            }
        }

        /** 进程被杀/OOM 留下的临时文件:内层明文包、解密后的 zip、导出的半成品。启动时清一次。 */
        fun cleanupStale(cacheDir: File) {
            cacheDir.listFiles()?.forEach { f ->
                val name = f.name
                if (name.startsWith("mika-backup-work-") || name.startsWith("mika-restore-work-") ||
                    (name.startsWith("mika-backup-") && name.endsWith(".zip")) ||
                    (name.startsWith("mika-restore-") && name.endsWith(".zip")) ||
                    (name.startsWith("mika-inspect-") && name.endsWith(".zip"))
                ) {
                    runCatching { f.deleteRecursively() }
                }
            }
        }

        /** 失败时只删本次新建的空文档;非空(可能是用户选的同名旧备份)保留。 */
        internal fun shouldDeleteEmptyDocument(size: Long?): Boolean = size == 0L
    }
}
