package com.wangzan101.mika.backup

import android.content.Context
import com.wangzan101.mika.core.backup.BackupFormat
import com.wangzan101.mika.core.backup.SectionReport
import com.wangzan101.mika.core.backup.ZipWriter
import com.wangzan101.mika.core.memory.MemoryItem
import com.wangzan101.mika.core.memory.ProfileEntry
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.memory.EncryptedMemoryFile
import com.wangzan101.mika.memory.FileProfileBackend
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.KeystoreKeyCipher
import com.wangzan101.mika.task.FileTriggerStore
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
 */
class BackupCollector(
    private val context: Context,
    private val options: BackupOptions,
    private val onProgress: (BackupProgress) -> Unit = {},
) {
    private val filesDir: File = context.filesDir
    private val settingsDir = File(filesDir, "settings")
    private val warnings = mutableListOf<String>()

    fun collect(zip: ZipWriter, secrets: SecretVault?): CollectResult {
        val counts = LinkedHashMap<String, Long>()
        val sections = mutableListOf<SectionReport>()

        if (options.includeSettings) {
            onProgress(BackupProgress(BackupStage.Packing))
            val n = collectSettings(zip)
            counts[BackupFormat.SETTINGS] = n.toLong()
            sections += SectionReport(BackupFormat.SETTINGS, added = n)
        }
        if (options.includeSecrets && secrets != null) {
            val exported = secrets.export()
            zip.addBytes("secrets.json", exported.json.toString().toByteArray(Charsets.UTF_8))
            val n = exported.json["providers"]?.jsonObject?.size ?: 0
            counts[BackupFormat.SECRETS] = n.toLong()
            sections += SectionReport(BackupFormat.SECRETS, added = n, note = exported.unavailable.takeIf { it.isNotEmpty() }?.joinToString(","))
            exported.unavailable.forEach { warnings += it }
        }
        if (options.includeConversations) {
            onProgress(BackupProgress(BackupStage.Packing))
            val n = collectConversations(zip)
            counts[BackupFormat.CONVERSATIONS] = n.toLong()
            sections += SectionReport(BackupFormat.CONVERSATIONS, added = n)
        }
        if (options.includeMemory) {
            onProgress(BackupProgress(BackupStage.Packing))
            val n = collectMemory(zip)
            counts["memoryItems"] = n.toLong()
            sections += SectionReport(BackupFormat.MEMORY, added = n)
        }
        if (options.includeSkills) {
            onProgress(BackupProgress(BackupStage.Packing))
            val n = collectSkills(zip)
            counts[BackupFormat.SKILLS] = n.toLong()
            sections += SectionReport(BackupFormat.SKILLS, added = n)
        }
        if (options.includeKnowledge) {
            onProgress(BackupProgress(BackupStage.Packing))
            val n = collectKnowledge(zip)
            counts[BackupFormat.KNOWLEDGE] = n.toLong()
            sections += SectionReport(BackupFormat.KNOWLEDGE, added = n)
        }
        if (options.includeAutomation) {
            onProgress(BackupProgress(BackupStage.Packing))
            val n = collectAutomation(zip)
            counts[BackupFormat.AUTOMATION] = n.toLong()
            sections += SectionReport(BackupFormat.AUTOMATION, added = n)
        }
        if (options.includeWeb) {
            val n = collectWeb(zip)
            counts[BackupFormat.WEB] = n.toLong()
            sections += SectionReport(BackupFormat.WEB, added = n, note = if (n > 0) "logged-out" else null)
        }
        if (options.includeWorkspace) {
            onProgress(BackupProgress(BackupStage.Packing))
            val bytes = collectWorkspace(zip)
            counts["workspaceBytes"] = bytes
            sections += SectionReport(BackupFormat.WORKSPACE, added = bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
        return CollectResult(counts, sections, warnings)
    }

    private fun collectSettings(zip: ZipWriter): Int {
        var n = 0
        settingsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name != "provider-keys.json" }
            .orEmpty().sortedBy { it.name }.forEach { f ->
                zip.addFile("data/settings/${f.name}", f)
                n++
            }
        listOf("memory_settings.json", "sandbox-settings.json").forEach { name ->
            val f = File(filesDir, name)
            if (f.isFile) {
                zip.addFile("data/settings/$name", f)
                n++
            }
        }
        val feature = context.getSharedPreferences(FeatureFlags.PREFS, Context.MODE_PRIVATE)
        zip.addBytes("data/settings/prefs_feature_flags.json", PrefsJson.export(feature).toString().toByteArray())
        zip.addBytes("data/settings/prefs_app_language.json", PrefsJson.exportLanguage(featureLanguage()).toString().toByteArray())
        return n + 2
    }

    private fun featureLanguage() = context.getSharedPreferences(PrefsJson.LANGUAGE_PREFS, Context.MODE_PRIVATE)

    private fun collectConversations(zip: ZipWriter): Int {
        val dir = File(filesDir, "conversations")
        var n = 0
        dir.listFiles { f -> f.isFile }.orEmpty().sortedBy { it.name }.forEach { f ->
            val name = f.name
            if (name.endsWith(".tmp") || name.contains(".corrupt-") || name.contains(".broken-")) return@forEach
            if (name == "current" || name.endsWith(".json") || name.endsWith(".title") ||
                name.endsWith(".pin") || name.endsWith(".page")
            ) {
                zip.addFile("data/conversations/$name", f)
                if (name.endsWith(".json")) n++
            }
        }
        return n
    }

    private fun collectMemory(zip: ZipWriter): Int {
        return try {
            val cipher = KeystoreKeyCipher("mika-memory")
            val items = EncryptedMemoryFile(filesDir, cipher, System::currentTimeMillis).load()
            zip.addBytes(
                "data/memory/items.json",
                Json.encodeToString(ListSerializer(MemoryItem.serializer()), items).toByteArray(Charsets.UTF_8),
            )
            val profile = FileProfileBackend(File(filesDir, "memory/profile.bin"), cipher).read()
            zip.addBytes(
                "data/memory/profile.json",
                Json.encodeToString(ListSerializer(ProfileEntry.serializer()), profile).toByteArray(Charsets.UTF_8),
            )
            items.size
        } catch (e: Exception) {
            warnings += "memory"
            0
        }
    }

    private fun collectSkills(zip: ZipWriter): Int {
        val root = File(filesDir, "skills")
        copyTree(root, "data/skills", zip)
        return File(root, "personal").listFiles { f -> f.isDirectory }.orEmpty().size
    }

    private fun collectKnowledge(zip: ZipWriter): Int {
        val drafts = File(filesDir, "knowledge/app-drafts")
        copyTree(drafts, "data/knowledge/app-drafts", zip)
        copyTree(File(filesDir, "personal"), "data/knowledge/personal", zip)
        return drafts.listFiles { f -> f.isDirectory }.orEmpty().count { it.name != "archive" }
    }

    private fun collectAutomation(zip: ZipWriter): Int {
        val triggers = FileTriggerStore(File(filesDir, "triggers-v3.json")).all()
        zip.addBytes(
            "data/automation/triggers.json",
            Json.encodeToString(ListSerializer(TriggerDefinition.serializer()), triggers).toByteArray(Charsets.UTF_8),
        )
        copyTree(File(filesDir, "routes"), "data/automation/routes", zip)
        listOf("approvals.json", "app-access.json").forEach { name ->
            val f = File(filesDir, name)
            if (f.isFile) zip.addFile("data/automation/$name", f)
        }
        val routes = File(filesDir, "routes")
            .walkTopDown().count { it.isFile && it.name.endsWith(".json") }
        return triggers.size + routes
    }

    private fun collectWeb(zip: ZipWriter): Int {
        val f = File(filesDir, "web/sites.json")
        if (!f.isFile) return 0
        zip.addFile("data/web/sites.json", f)
        return runCatching {
            (Json.parseToJsonElement(f.readText()) as? JsonArray)?.size ?: 0
        }.getOrDefault(0)
    }

    private fun collectWorkspace(zip: ZipWriter): Long =
        copyTree(File(filesDir, "sandbox/work"), "data/workspace", zip)

    /** 把 [root] 下的普通文件写进 zip;跳过符号链接和临时文件。返回写出的字节数。 */
    private fun copyTree(root: File, prefix: String, zip: ZipWriter): Long {
        if (!root.isDirectory) return 0
        var bytes = 0L
        val base = root.toPath()
        Files.walk(base).use { stream ->
            stream.forEach { path ->
                if (Files.isSymbolicLink(path)) return@forEach
                val f = path.toFile()
                if (!f.isFile) return@forEach
                val rel = base.relativize(path).toString().replace(File.separatorChar, '/')
                if (rel.endsWith(".tmp")) return@forEach
                val name = "$prefix/$rel"
                runCatching { zip.addFile(name, f) }
                    .onFailure { warnings += name }
                    .onSuccess { bytes += f.length() }
            }
        }
        return bytes
    }
}
