package com.wangzan101.mika.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 恢复前的快照:把这次会动的目录/文件复制到 `files/pre-restore/<时间>/`。失败时整份复制回去。
 *
 * 快照只覆盖非工作区分类;工作区勾选时才包含 `sandbox/work`。`runs/`、`sandbox/rootfs` 从不进快照。
 */
class RestoreSnapshot(
    private val filesDir: File,
    val dir: File,
    private val includeWorkspace: Boolean,
    private val featurePrefs: BackupPrefs,
    private val languagePrefs: BackupPrefs,
) {
    private val paths: List<String> = buildList {
        add("settings")
        add("conversations")
        add("memory")
        add("skills")
        add("knowledge")
        add("personal")
        add("routes")
        add("triggers-v3.json")
        add("approvals.json")
        add("app-access.json")
        add("memory_settings.json")
        add("sandbox-settings.json")
        add("web/sites.json")
        if (includeWorkspace) add("sandbox/work")
    }

    fun save() {
        dir.mkdirs()
        for (rel in paths) {
            val src = File(filesDir, rel)
            if (src.exists()) copyTree(src, File(dir, rel))
        }
        writePrefs()
    }

    fun restore() {
        for (rel in paths) {
            val target = File(filesDir, rel)
            deleteTree(target)
            val saved = File(dir, rel)
            if (saved.exists()) copyTree(saved, target)
        }
        restorePrefs()
    }

    private fun writePrefs() {
        File(dir, PREFS_FEATURE).writeText(PrefsJson.export(featurePrefs).toString())
        File(dir, PREFS_LANGUAGE).writeText(PrefsJson.exportLanguage(languagePrefs).toString())
    }

    private fun restorePrefs() {
        runCatching {
            val json = Json.parseToJsonElement(File(dir, PREFS_FEATURE).readText()) as JsonObject
            PrefsJson.replace(featurePrefs, json)
        }
        runCatching {
            val json = Json.parseToJsonElement(File(dir, PREFS_LANGUAGE).readText()) as JsonObject
            PrefsJson.replaceLanguage(languagePrefs, json)
        }
    }

    private fun copyTree(src: File, dst: File) {
        if (Files.isSymbolicLink(src.toPath())) return
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { copyTree(it, File(dst, it.name)) }
        } else if (src.isFile) {
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
        }
    }

    private fun deleteTree(file: File) {
        if (Files.isSymbolicLink(file.toPath())) {
            file.delete()
            return
        }
        if (file.isDirectory) file.listFiles()?.forEach(::deleteTree)
        file.delete()
    }

    companion object {
        const val ROOT = "pre-restore"
        private const val PREFS_FEATURE = "prefs_feature_flags.json"
        private const val PREFS_LANGUAGE = "prefs_app_language.json"

        fun newDir(filesDir: File): File {
            val root = File(filesDir, ROOT)
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            var dir = File(root, stamp)
            var n = 1
            while (dir.exists()) dir = File(root, "$stamp-${n++}")
            return dir
        }

        /** 最近一份快照;没有为 null。 */
        fun latest(filesDir: File): File? =
            File(filesDir, ROOT).listFiles { f -> f.isDirectory && f.name.any(Char::isDigit) }
                ?.maxByOrNull { it.name }

        /** 只留最近 [keep] 份,更早的删掉。 */
        fun prune(filesDir: File, keep: Int = 2) {
            val dirs = File(filesDir, ROOT).listFiles { f -> f.isDirectory && f.name.any(Char::isDigit) }
                ?.sortedByDescending { it.name }.orEmpty()
            dirs.drop(keep).forEach { it.deleteRecursively() }
        }
    }
}
