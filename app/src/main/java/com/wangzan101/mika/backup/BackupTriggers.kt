package com.wangzan101.mika.backup

import com.wangzan101.mika.core.trigger.TriggerSnapshot
import com.wangzan101.mika.task.FileTriggerStore
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 定时任务的读写;生产走 [FileTriggerStore],测试用内存实现。 */
interface BackupTriggers {
    fun snapshot(): TriggerSnapshot
    fun restore(snapshot: TriggerSnapshot)
}

/**
 * `triggers-v3.json` 的整份快照:定义和时段账本一起读写。恢复时保留已投递/已认领的窗口,
 * 避免「每天 09:10」当天恢复后又跑一次。写完重新布置闹钟。
 */
class FileBackupTriggers(
    private val file: File,
    private val rearm: () -> Unit,
) : BackupTriggers {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun snapshot(): TriggerSnapshot {
        val store = FileTriggerStore(file)
        return TriggerSnapshot(store.all(), store.windows())
    }

    override fun restore(snapshot: TriggerSnapshot) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".backup-tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(json.encodeToString(TriggerSnapshot.serializer(), snapshot).toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            tmp.delete()
        }
        rearm()
    }
}
