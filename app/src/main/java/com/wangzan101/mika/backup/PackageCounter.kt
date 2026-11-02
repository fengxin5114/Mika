package com.wangzan101.mika.backup

import com.wangzan101.mika.core.backup.BackupFormat
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.TriggerSnapshot
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 从解包后的 `data/` 数一遍实际条目数。导入前和 manifest.counts 对账,
 * 也用于验证 countsMac:预览页的数字和包内真实内容必须一致。
 */
object PackageCounter {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun count(extracted: File): Map<String, Long> {
        val counts = LinkedHashMap<String, Long>()
        val settings = File(extracted, "data/settings")
        counts[BackupFormat.COUNT_SETTINGS_FILES] =
            settings.listFiles { f -> f.isFile && !f.name.startsWith("prefs_") }.orEmpty().size.toLong()
        counts[BackupFormat.COUNT_PREFS_FEATURE_KEYS] = objectSize(File(settings, "prefs_feature_flags.json"))
        counts[BackupFormat.COUNT_PREFS_LANGUAGE] = if (File(settings, "prefs_app_language.json").isFile) 1 else 0
        val secrets = objectOf(File(extracted, "secrets.json"))
        counts[BackupFormat.COUNT_PROVIDER_KEYS] = (secrets?.get("providers") as? JsonObject)?.size?.toLong() ?: 0
        counts[BackupFormat.COUNT_SECRET_ITEMS] =
            secrets?.keys?.count { it != "version" && it != "providers" }?.toLong() ?: 0
        counts[BackupFormat.COUNT_CONVERSATIONS] =
            File(extracted, "data/conversations").listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().size.toLong()
        counts[BackupFormat.COUNT_MEMORY_ITEMS] = arraySize(File(extracted, "data/memory/items.json"))
        counts[BackupFormat.COUNT_PROFILE_ENTRIES] = arraySize(File(extracted, "data/memory/profile.json"))
        counts[BackupFormat.COUNT_PERSONAL_SKILLS] = dirs(File(extracted, "data/skills/personal"))
        counts[BackupFormat.COUNT_PENDING_SKILLS] = dirs(File(extracted, "data/skills/pending"))
        counts[BackupFormat.COUNT_SKILL_ARCHIVES] = dirs(File(extracted, "data/skills/archive"))
        val drafts = File(extracted, "data/knowledge/app-drafts")
        counts[BackupFormat.COUNT_KNOWLEDGE_DRAFTS] = drafts.listFiles { f -> f.isDirectory }.orEmpty().count { it.name != "archive" }.toLong()
        counts[BackupFormat.COUNT_KNOWLEDGE_NOTES] =
            File(extracted, "data/knowledge/personal").walkTopDown().count { it.isFile && it.name == "NOTES.md" }.toLong()
        val triggerSnapshot = triggerSnapshot(File(extracted, "data/automation/triggers.json"))
        counts[BackupFormat.COUNT_TRIGGERS] = (triggerSnapshot?.triggers?.size ?: 0).toLong()
        counts[BackupFormat.COUNT_TRIGGER_WINDOWS] = (triggerSnapshot?.windows?.size ?: 0).toLong()
        counts[BackupFormat.COUNT_ROUTES] =
            File(extracted, "data/automation/routes").walkTopDown().count { it.isFile && it.name.endsWith(".json") }.toLong()
        counts[BackupFormat.COUNT_APPROVALS] = if (File(extracted, "data/automation/approvals.json").isFile) 1 else 0
        counts[BackupFormat.COUNT_APP_ACCESS] = if (File(extracted, "data/automation/app-access.json").isFile) 1 else 0
        counts[BackupFormat.COUNT_WEB_SITES] = arraySize(File(extracted, "data/web/sites.json"))
        val workspace = File(extracted, "data/workspace")
        val files = workspace.walkTopDown().filter { it.isFile }.toList()
        counts[BackupFormat.COUNT_WORKSPACE_FILES] = files.size.toLong()
        counts[BackupFormat.COUNT_WORKSPACE_BYTES] = files.sumOf { it.length() }
        return counts
    }

    /** manifest 里写了、但和包内实际数对不上的键。 */
    fun mismatches(expected: Map<String, Long>, actual: Map<String, Long>): List<String> =
        expected.filter { (key, value) -> actual[key] != value }.keys.sorted()

    private fun objectSize(file: File): Long = objectOf(file)?.size?.toLong() ?: 0

    private fun objectOf(file: File): JsonObject? =
        if (file.isFile) runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull() else null

    private fun arraySize(file: File): Long =
        if (file.isFile) runCatching { (Json.parseToJsonElement(file.readText()) as? JsonArray)?.size?.toLong() ?: 0 }.getOrDefault(0) else 0

    private fun dirs(file: File): Long = file.listFiles { f -> f.isDirectory }.orEmpty().size.toLong()

    private fun triggerSnapshot(file: File): TriggerSnapshot? {
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        runCatching { json.decodeFromString(TriggerSnapshot.serializer(), text) }.getOrNull()?.let { return it }
        // 兼容修复前导出的旧包:只有 trigger 列表
        val list = runCatching { json.decodeFromString(ListSerializer(TriggerDefinition.serializer()), text) }.getOrNull()
        return list?.let { TriggerSnapshot(it, emptyList()) }
    }
}
