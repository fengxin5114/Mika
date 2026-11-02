package com.wangzan101.mika.backup

import com.wangzan101.mika.core.backup.BackupError
import com.wangzan101.mika.core.backup.BackupException
import com.wangzan101.mika.core.backup.BackupFormat
import com.wangzan101.mika.core.backup.SectionReport
import com.wangzan101.mika.core.backup.ZipWriter
import com.wangzan101.mika.core.memory.MemoryItem
import com.wangzan101.mika.core.memory.ProfileEntry
import com.wangzan101.mika.core.trigger.TriggerSnapshot
import com.wangzan101.mika.memory.EncryptedMemoryFile
import com.wangzan101.mika.memory.FileProfileBackend
import com.wangzan101.mika.settings.KeyCipher
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files

data class CollectResult(
    val counts: Map<String, Long>,
    val sections: List<SectionReport>,
    val warnings: List<String> = emptyList(),
)

/**
 * 按 [BackupOptions] 把 App 私有目录里的数据写进内层 zip。只读不写;读不出的密钥由 [SecretVault] 单独报告。
 * 工作区里可能有沙箱创建的文件,复制时跳过符号链接,不跟着链接跑到别处。
 *
 * 依赖都从构造参数来(filesDir / 偏好设置 / 定时任务 / 记忆密钥),JVM 测试可以用临时目录直接跑。
 */
class BackupCollector(
    private val filesDir: File,
    private val featurePrefs: BackupPrefs,
    private val languagePrefs: BackupPrefs,
    private val triggers: BackupTriggers,
    private val memoryCipher: KeyCipher,
    private val options: BackupOptions,
    private val onProgress: (BackupProgress) -> Unit = {},
    private val checkCancelled: () -> Unit = {},
) {
    private val settingsDir = File(filesDir, "settings")
    private val warnings = mutableListOf<String>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun collect(zip: ZipWriter, secrets: SecretVault?): CollectResult {
        checkCancelled()
        val counts = LinkedHashMap<String, Long>()
        val sections = mutableListOf<SectionReport>()

        if (options.includeSettings) {
            onProgress(BackupProgress(BackupStage.Packing))
            val n = collectSettings(zip, counts)
            sections += SectionReport(BackupFormat.SETTINGS, added = n)
        }
        if (options.includeSecrets && secrets != null) {
            onProgress(BackupProgress(BackupStage.Packing))
            val n = collectSecrets(zip, secrets, counts)
            sections += SectionReport(BackupFormat.SECRETS, added = n)
        }
        if (options.includeConversations) {
            onProgress(BackupProgress(BackupStage.Packing))
            val n = collectConversations(zip, counts)
            sections += SectionReport(BackupFormat.CONVERSATIONS, added = n)
        }
        if (options.includeMemory) {
            onProgress(BackupProgress(BackupStage.Packing))
            val n = collectMemory(zip, counts)
            sections += SectionReport(BackupFormat.MEMORY, added = n)
        }
        if (options.includeSkills) {
            onProgress(BackupProgress(BackupStage.Packing))
            val n = collectSkills(zip, counts)
            sections += SectionReport(BackupFormat.SKILLS, added = n)
        }
        if (options.includeKnowledge) {
            onProgress(BackupProgress(BackupStage.Packing))
            val n = collectKnowledge(zip, counts)
            sections += SectionReport(BackupFormat.KNOWLEDGE, added = n)
        }
        if (options.includeAutomation) {
            onProgress(BackupProgress(BackupStage.Packing))
            val n = collectAutomation(zip, counts)
            sections += SectionReport(BackupFormat.AUTOMATION, added = n)
        }
        if (options.includeWeb) {
            val n = collectWeb(zip, counts)
            sections += SectionReport(BackupFormat.WEB, added = n, note = if (n > 0) "logged-out" else null)
        }
        if (options.includeWorkspace) {
            onProgress(BackupProgress(BackupStage.Packing))
            val n = collectWorkspace(zip, counts)
            sections += SectionReport(BackupFormat.WORKSPACE, added = n)
        }
        return CollectResult(counts, sections, warnings)
    }

    private fun collectSettings(zip: ZipWriter, counts: MutableMap<String, Long>): Int {
        var files = 0
        settingsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name != "provider-keys.json" }
            .orEmpty().sortedBy { it.name }.forEach {
                checkCancelled()
                zip.addFile("data/settings/${it.name}", it)
                files++
            }
        listOf("memory_settings.json", "sandbox-settings.json").forEach { name ->
            val f = File(filesDir, name)
            if (f.isFile) {
                checkCancelled()
                zip.addFile("data/settings/$name", f)
                files++
            }
        }
        val feature = PrefsJson.export(featurePrefs)
        if (feature.isNotEmpty()) {
            zip.addBytes("data/settings/prefs_feature_flags.json", feature.toString().toByteArray(Charsets.UTF_8))
        }
        val language = PrefsJson.exportLanguage(languagePrefs)
        if (language.isNotEmpty()) {
            zip.addBytes("data/settings/prefs_app_language.json", language.toString().toByteArray(Charsets.UTF_8))
        }
        counts[BackupFormat.COUNT_SETTINGS_FILES] = files.toLong()
        counts[BackupFormat.COUNT_PREFS_FEATURE_KEYS] = feature.size.toLong()
        counts[BackupFormat.COUNT_PREFS_LANGUAGE] = if (language.isEmpty()) 0 else 1
        return files
    }

    private fun collectSecrets(zip: ZipWriter, secrets: SecretVault, counts: MutableMap<String, Long>): Int {
        val exported = secrets.export()
        zip.addBytes("secrets.json", exported.json.toString().toByteArray(Charsets.UTF_8))
        val providers = exported.json["providers"]?.jsonObject?.size ?: 0
        counts[BackupFormat.COUNT_PROVIDER_KEYS] = providers.toLong()
        counts[BackupFormat.COUNT_SECRET_ITEMS] = exported.json.keys.count { it !in setOf("version", "providers") }.toLong()
        exported.unavailable.forEach { warnings += it }
        return providers
    }

    private fun collectConversations(zip: ZipWriter, counts: MutableMap<String, Long>): Int {
        val dir = File(filesDir, "conversations")
        var n = 0
        dir.listFiles { f -> f.isFile }.orEmpty().sortedBy { it.name }.forEach { f ->
            checkCancelled()
            val name = f.name
            if (name == "current" || name.endsWith(".tmp") || name.contains(".corrupt-") || name.contains(".broken-")) return@forEach
            when {
                name.endsWith(".json") -> {
                    // 任务进行中可能正在写对话:先解析,坏文件跳过,别把半截 JSON 带进包里
                    val bytes = runCatching { f.readBytes() }.getOrNull()
                        ?: run { warnings += "conversation:$name"; return@forEach }
                    val parsed = runCatching {
                        Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonArray
                    }.getOrNull()
                    if (parsed == null) {
                        warnings += "conversation:$name"
                        return@forEach
                    }
                    zip.addBytes("data/conversations/$name", bytes, f.lastModified())
                    n++
                }
                name.endsWith(".title") || name.endsWith(".pin") || name.endsWith(".page") -> {
                    runCatching { zip.addBytes("data/conversations/$name", f.readBytes(), f.lastModified()) }
                        .onFailure { warnings += "conversation:$name" }
                }
            }
        }
        counts[BackupFormat.COUNT_CONVERSATIONS] = n.toLong()
        return n
    }

    private fun collectMemory(zip: ZipWriter, counts: MutableMap<String, Long>): Int {
        var items = 0
        var profile = 0
        val memoryFile = File(filesDir, "memory/memory.bin")
        if (memoryFile.isFile) {
            try {
                val file = EncryptedMemoryFile(filesDir, memoryCipher, System::currentTimeMillis)
                val list = file.load()
                if (file.brokenNotice.value != null) throw BackupException(BackupError.UnreadableData, "memory")
                items = list.size
                zip.addBytes(
                    "data/memory/items.json",
                    json.encodeToString(ListSerializer(MemoryItem.serializer()), list).toByteArray(Charsets.UTF_8),
                )
            } catch (e: BackupException) {
                throw e
            } catch (e: Exception) {
                throw BackupException(BackupError.UnreadableData, "memory", e)
            }
        }
        val profileFile = File(filesDir, "memory/profile.bin")
        if (profileFile.isFile) {
            try {
                val entries = FileProfileBackend(profileFile, memoryCipher).read()
                profile = entries.size
                zip.addBytes(
                    "data/memory/profile.json",
                    json.encodeToString(ListSerializer(ProfileEntry.serializer()), entries).toByteArray(Charsets.UTF_8),
                )
            } catch (e: Exception) {
                throw BackupException(BackupError.UnreadableData, "profile", e)
            }
        }
        counts[BackupFormat.COUNT_MEMORY_ITEMS] = items.toLong()
        counts[BackupFormat.COUNT_PROFILE_ENTRIES] = profile.toLong()
        return items
    }

    private fun collectSkills(zip: ZipWriter, counts: MutableMap<String, Long>): Int {
        val root = File(filesDir, "skills")
        val personal = File(root, "personal").listFiles { f -> f.isDirectory }.orEmpty().size
        val pending = File(root, "pending").listFiles { f -> f.isDirectory }.orEmpty().size
        val archive = File(root, "archive").listFiles { f -> f.isDirectory }.orEmpty().size
        copyTree(root, "data/skills", zip)
        counts[BackupFormat.COUNT_PERSONAL_SKILLS] = personal.toLong()
        counts[BackupFormat.COUNT_PENDING_SKILLS] = pending.toLong()
        counts[BackupFormat.COUNT_SKILL_ARCHIVES] = archive.toLong()
        return personal
    }

    private fun collectKnowledge(zip: ZipWriter, counts: MutableMap<String, Long>): Int {
        val drafts = File(filesDir, "knowledge/app-drafts")
        val personal = File(filesDir, "personal")
        copyTree(drafts, "data/knowledge/app-drafts", zip)
        copyTree(personal, "data/knowledge/personal", zip)
        val draftCount = drafts.listFiles { f -> f.isDirectory }.orEmpty().count { it.name != "archive" }
        val notes = personal.walkTopDown().count { it.isFile && it.name == "NOTES.md" }
        counts[BackupFormat.COUNT_KNOWLEDGE_DRAFTS] = draftCount.toLong()
        counts[BackupFormat.COUNT_KNOWLEDGE_NOTES] = notes.toLong()
        return draftCount
    }

    private fun collectAutomation(zip: ZipWriter, counts: MutableMap<String, Long>): Int {
        val snapshot = triggers.snapshot()
        zip.addBytes(
            "data/automation/triggers.json",
            json.encodeToString(TriggerSnapshot.serializer(), snapshot).toByteArray(Charsets.UTF_8),
        )
        copyTree(File(filesDir, "routes"), "data/automation/routes", zip)
        var approvals = 0
        var appAccess = 0
        listOf("approvals.json" to 0, "app-access.json" to 1).forEach { (name, index) ->
            val f = File(filesDir, name)
            if (f.isFile) {
                checkCancelled()
                zip.addFile("data/automation/$name", f)
                if (index == 0) approvals = 1 else appAccess = 1
            }
        }
        val routes = File(filesDir, "routes").walkTopDown().count { it.isFile && it.name.endsWith(".json") }
        counts[BackupFormat.COUNT_TRIGGERS] = snapshot.triggers.size.toLong()
        counts[BackupFormat.COUNT_TRIGGER_WINDOWS] = snapshot.windows.size.toLong()
        counts[BackupFormat.COUNT_ROUTES] = routes.toLong()
        counts[BackupFormat.COUNT_APPROVALS] = approvals.toLong()
        counts[BackupFormat.COUNT_APP_ACCESS] = appAccess.toLong()
        return snapshot.triggers.size + routes
    }

    private fun collectWeb(zip: ZipWriter, counts: MutableMap<String, Long>): Int {
        val f = File(filesDir, "web/sites.json")
        if (!f.isFile) {
            counts[BackupFormat.COUNT_WEB_SITES] = 0
            return 0
        }
        checkCancelled()
        zip.addFile("data/web/sites.json", f)
        val n = runCatching { (Json.parseToJsonElement(f.readText()) as? JsonArray)?.size ?: 0 }.getOrDefault(0)
        counts[BackupFormat.COUNT_WEB_SITES] = n.toLong()
        return n
    }

    private fun collectWorkspace(zip: ZipWriter, counts: MutableMap<String, Long>): Int {
        val stats = copyTree(File(filesDir, "sandbox/work"), "data/workspace", zip)
        counts[BackupFormat.COUNT_WORKSPACE_FILES] = stats.files.toLong()
        counts[BackupFormat.COUNT_WORKSPACE_BYTES] = stats.bytes
        return stats.files
    }

    private data class TreeStats(val files: Int = 0, val bytes: Long = 0)

    /** 把 [root] 下的普通文件写进 zip;跳过符号链接和临时文件。 */
    private fun copyTree(root: File, prefix: String, zip: ZipWriter): TreeStats {
        if (!root.isDirectory) return TreeStats()
        var files = 0
        var bytes = 0L
        val base = root.toPath()
        Files.walk(base).use { stream ->
            stream.forEach { path ->
                checkCancelled()
                if (Files.isSymbolicLink(path)) return@forEach
                val f = path.toFile()
                if (!f.isFile) return@forEach
                val rel = base.relativize(path).toString().replace(File.separatorChar, '/')
                if (rel.endsWith(".tmp")) return@forEach
                val name = "$prefix/$rel"
                runCatching { zip.addFile(name, f) }
                    .onFailure { warnings += name }
                    .onSuccess {
                        files++
                        bytes += f.length()
                    }
            }
        }
        return TreeStats(files, bytes)
    }

    companion object {
        /** 导出前粗略估算源数据大小;实际写出还要再留出一份容器/临时文件的空间。 */
        fun estimateBytes(filesDir: File, options: BackupOptions): Long {
            var total = 0L
            fun add(f: File) {
                if (f.isFile) { total += f.length(); return }
                if (!f.isDirectory) return
                Files.walk(f.toPath()).use { stream ->
                    stream.forEach { path ->
                        if (!Files.isSymbolicLink(path) && path.toFile().isFile) total += path.toFile().length()
                    }
                }
            }
            if (options.includeSettings) {
                File(filesDir, "settings").listFiles()?.forEach(::add)
                add(File(filesDir, "memory_settings.json"))
                add(File(filesDir, "sandbox-settings.json"))
            }
            if (options.includeSecrets) add(File(filesDir, "settings"))
            if (options.includeConversations) add(File(filesDir, "conversations"))
            if (options.includeMemory) add(File(filesDir, "memory"))
            if (options.includeSkills) add(File(filesDir, "skills"))
            if (options.includeKnowledge) {
                add(File(filesDir, "knowledge"))
                add(File(filesDir, "personal"))
            }
            if (options.includeAutomation) {
                add(File(filesDir, "triggers-v3.json"))
                add(File(filesDir, "routes"))
                add(File(filesDir, "approvals.json"))
                add(File(filesDir, "app-access.json"))
            }
            if (options.includeWeb) add(File(filesDir, "web/sites.json"))
            if (options.includeWorkspace) add(File(filesDir, "sandbox/work"))
            return total
        }
    }
}
