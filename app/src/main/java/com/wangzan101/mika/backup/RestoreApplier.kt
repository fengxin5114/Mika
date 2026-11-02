package com.wangzan101.mika.backup

import com.wangzan101.mika.core.backup.BackupFormat
import com.wangzan101.mika.core.backup.BackupManifest
import com.wangzan101.mika.core.backup.ImportReport
import com.wangzan101.mika.core.backup.ImportReportBuilder
import com.wangzan101.mika.core.backup.MergeAction
import com.wangzan101.mika.core.backup.MergePlan
import com.wangzan101.mika.core.memory.MemoryItem
import com.wangzan101.mika.core.memory.ProfileEntry
import com.wangzan101.mika.core.replay.RouteCodec
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.TriggerSnapshot
import com.wangzan101.mika.core.trigger.nextCalendarAt
import com.wangzan101.mika.memory.EncryptedMemoryFile
import com.wangzan101.mika.memory.FileProfileBackend
import com.wangzan101.mika.replay.FileRouteStore
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.settings.KeystoreKeyCipher
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 把解包后的 `data/` 按分类合并进 App 私有目录。只改文件,不碰进程里的单例;恢复完成后必须重启进程。
 *
 * 安全相关的分类([BackupFormat.AUTOMATION] 里的审批与 App 访问、[BackupFormat.WEB])按「更严格的一方保留」合并,
 * 恢复不会放宽用户当前设下的限制。
 *
 * 依赖从构造参数来(filesDir / 偏好设置 / 定时任务 / 记忆密钥),JVM 测试用临时目录直接跑。
 */
class RestoreApplier(
    private val filesDir: File,
    private val extracted: File,
    private val manifest: BackupManifest,
    private val vault: SecretVault,
    private val featurePrefs: BackupPrefs,
    private val languagePrefs: BackupPrefs,
    private val triggers: BackupTriggers,
    private val memoryCipher: KeyCipher = KeystoreKeyCipher("mika-memory"),
    private val report: ImportReportBuilder = ImportReportBuilder(),
    private val checkCancelled: () -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private data class Counts(var added: Int = 0, var replaced: Int = 0, var kept: Int = 0)

    fun apply(): ImportReport {
        if (has(BackupFormat.SETTINGS)) { checkCancelled(); applySettings() }
        if (has(BackupFormat.SECRETS)) { checkCancelled(); applySecrets() }
        if (has(BackupFormat.CONVERSATIONS)) { checkCancelled(); applyConversations() }
        if (has(BackupFormat.MEMORY)) { checkCancelled(); applyMemory() }
        if (has(BackupFormat.SKILLS)) { checkCancelled(); applySkills() }
        if (has(BackupFormat.KNOWLEDGE)) { checkCancelled(); applyKnowledge() }
        if (has(BackupFormat.AUTOMATION)) { checkCancelled(); applyAutomation() }
        if (has(BackupFormat.WEB)) { checkCancelled(); applyWeb() }
        if (has(BackupFormat.WORKSPACE)) { checkCancelled(); applyWorkspace() }
        return report.build()
    }

    private fun has(section: String) = section in manifest.sections

    private fun data(rel: String) = File(extracted, rel)

    // ---------- 设置 ----------

    private fun applySettings() {
        val counts = Counts()

        val providers = data("data/settings/providers.json")
        if (providers.isFile) {
            val target = File(filesDir, "settings/providers.json")
            if (!target.isFile) {
                copyAtomic(providers, target)
                counts.added += countServices(providers)
            } else {
                val imported = runCatching { json.parseToJsonElement(providers.readText()).jsonObject }.getOrNull()
                val existing = runCatching { json.parseToJsonElement(target.readText()).jsonObject }.getOrNull()
                if (imported != null && existing != null) {
                    val (merged, providerCounts) = mergeProviders(existing, imported)
                    writeAtomic(target, merged.toString().toByteArray(Charsets.UTF_8))
                    counts.added += providerCounts.added
                    counts.replaced += providerCounts.replaced
                    counts.kept += providerCounts.kept
                } else {
                    report.warn("providers")
                    counts.kept++
                }
            }
        }

        val settingsRoot = data("data/settings")
        settingsRoot.listFiles { f -> f.isFile }.orEmpty().sortedBy { it.name }.forEach { source ->
            checkCancelled()
            val name = source.name
            if (name == "providers.json" || name.startsWith("prefs_")) return@forEach
            val target = when (name) {
                "memory_settings.json", "sandbox-settings.json" -> File(filesDir, name)
                else -> File(filesDir, "settings/$name")
            }
            if (!target.exists()) {
                copyAtomic(source, target)
                counts.added++
            } else {
                counts.kept++
            }
        }

        data("data/settings/prefs_feature_flags.json").takeIf { it.isFile }?.let { source ->
            val imported = runCatching { json.parseToJsonElement(source.readText()).jsonObject }.getOrNull()
            if (imported != null) {
                counts.added += imported.keys.count { it in PrefsJson.FEATURE_KEYS && !featurePrefs.contains(it) }
                counts.kept += imported.keys.count { it in PrefsJson.FEATURE_KEYS && featurePrefs.contains(it) }
                PrefsJson.applyMissing(featurePrefs, imported)
            }
        }
        data("data/settings/prefs_app_language.json").takeIf { it.isFile }?.let { source ->
            val imported = runCatching { json.parseToJsonElement(source.readText()).jsonObject }.getOrNull()
            if (imported != null) {
                if (languagePrefs.contains(PrefsJson.LANGUAGE_TAG)) counts.kept++ else counts.added++
                PrefsJson.applyLanguageMissing(languagePrefs, imported)
            }
        }
        report.add(BackupFormat.SETTINGS, counts.added, counts.replaced, counts.kept)
    }

    private fun countServices(file: File): Int =
        runCatching {
            val root = json.parseToJsonElement(file.readText()).jsonObject
            (root["services"] as? JsonArray)?.size ?: 0
        }.getOrDefault(0)

    /** 服务商和模型配置都按 id 合并,导入的胜出;`uses`/`roles` 只补目标里没有的用途。 */
    private fun mergeProviders(existing: JsonObject, imported: JsonObject): Pair<JsonObject, Counts> {
        val counts = Counts()
        fun merge(key: String): JsonArray {
            val current = (existing[key] as? JsonArray) ?: JsonArray(emptyList())
            val incoming = (imported[key] as? JsonArray) ?: return current
            val byId = LinkedHashMap<String, JsonObject>()
            val order = mutableListOf<String>()
            current.forEach { e ->
                val o = e as? JsonObject ?: return@forEach
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                byId[id] = o
                order += id
            }
            incoming.forEach { e ->
                val o = e as? JsonObject ?: return@forEach
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                if (byId.containsKey(id)) {
                    byId[id] = o
                    counts.replaced++
                } else {
                    byId[id] = o
                    order += id
                    counts.added++
                }
            }
            return JsonArray(order.mapNotNull { byId[it] })
        }
        val services = merge("services")
        val providers = merge("providers")
        val out = buildJsonObject {
            existing.forEach { (k, v) -> if (k !in setOf("services", "providers", "uses", "roles")) put(k, v) }
            put("services", services)
            put("providers", providers)
            listOf("uses", "roles").forEach { key ->
                val current = existing[key] as? JsonObject
                val incoming = imported[key] as? JsonObject
                if (current != null && incoming != null) {
                    put(key, buildJsonObject {
                        current.forEach { (k, v) -> put(k, v) }
                        incoming.forEach { (k, v) -> if (k !in current) put(k, v) }
                    })
                } else (current ?: incoming)?.let { put(key, it) }
            }
        }
        return out to counts
    }

    // ---------- 密钥 ----------

    private fun applySecrets() {
        val source = data("secrets.json")
        if (!source.isFile) {
            report.warn("secrets")
            return
        }
        val secrets = runCatching { json.parseToJsonElement(source.readText()).jsonObject }.getOrNull()
        if (secrets == null) {
            report.warn("secrets")
            return
        }
        val result = vault.restore(secrets)
        result.unavailable.forEach(report::unavailable)
        val other = listOf("mcpToken", "relay", "netProxy", "deviceAccess", "speechDoubao").count { secrets.containsKey(it) }
        report.add(
            BackupFormat.SECRETS,
            added = result.imported + other,
            kept = result.keptExisting,
            note = result.unavailable.takeIf { it.isNotEmpty() }?.joinToString(","),
        )
    }

    // ---------- 对话 ----------

    private fun applyConversations() {
        var added = 0
        var replaced = 0
        var kept = 0
        val source = data("data/conversations")
        val targetDir = File(filesDir, "conversations")
        source.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }.forEach { sourceJson ->
            checkCancelled()
            val id = sourceJson.name.removeSuffix(".json")
            val target = File(targetDir, sourceJson.name)
            val existed = target.exists()
            if (!existed || sourceJson.lastModified() > target.lastModified()) {
                copyAtomic(sourceJson, target)
                listOf(".title", ".pin", ".page").forEach { ext ->
                    val side = File(source, "$id$ext")
                    if (side.isFile) copyAtomic(side, File(targetDir, "$id$ext"))
                }
                if (sourceJson.lastModified() > 0) target.setLastModified(sourceJson.lastModified())
                if (existed) replaced++ else added++
            } else {
                kept++
            }
        }
        report.add(BackupFormat.CONVERSATIONS, added, replaced, kept)
    }

    // ---------- 记忆 ----------

    private fun applyMemory() {
        val file = EncryptedMemoryFile(filesDir, memoryCipher, System::currentTimeMillis)
        val itemsSource = data("data/memory/items.json")
        if (itemsSource.isFile) {
            val imported = runCatching { json.decodeFromString(ListSerializer(MemoryItem.serializer()), itemsSource.readText()) }.getOrNull()
            val existing = runCatching { file.load() }.getOrElse { emptyList() }
            if (imported != null) {
                val decisions = MergePlan.byId(imported, existing, { it.id }) { i, e -> i.updatedAt > e.updatedAt }
                val merged = existing.toMutableList()
                decisions.forEach { d ->
                    when (d.action) {
                        MergeAction.Add -> merged += d.imported
                        MergeAction.Replace -> {
                            val i = merged.indexOfFirst { it.id == d.imported.id }
                            if (i >= 0) merged[i] = d.imported
                        }
                        else -> Unit
                    }
                }
                runCatching { file.save(merged) }.onFailure { report.warn("memory") }
                report.add(
                    BackupFormat.MEMORY,
                    added = decisions.count { it.action == MergeAction.Add },
                    replaced = decisions.count { it.action == MergeAction.Replace },
                    kept = decisions.count { it.action == MergeAction.Keep },
                )
            }
        }
        val profileSource = data("data/memory/profile.json")
        if (profileSource.isFile) {
            val backend = FileProfileBackend(File(filesDir, "memory/profile.bin"), memoryCipher)
            val imported = runCatching { json.decodeFromString(ListSerializer(ProfileEntry.serializer()), profileSource.readText()) }.getOrNull()
            val existing = runCatching { backend.read() }.getOrElse { emptyList() }
            if (imported != null) {
                val decisions = MergePlan.byId(imported, existing, { it.id }) { i, e -> i.atMs >= e.atMs }
                val merged = existing.toMutableList()
                decisions.forEach { d ->
                    when (d.action) {
                        MergeAction.Add -> merged += d.imported
                        MergeAction.Replace -> {
                            val i = merged.indexOfFirst { it.id == d.imported.id }
                            if (i >= 0) merged[i] = d.imported
                        }
                        else -> Unit
                    }
                }
                runCatching { backend.write(merged) }.onFailure { report.warn("profile") }
            }
        }
    }

    // ---------- 技能 ----------

    private fun applySkills() {
        var added = 0
        var replaced = 0
        var kept = 0
        val source = data("data/skills")
        val target = File(filesDir, "skills")

        File(source, "personal").listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }.forEach { dir ->
            checkCancelled()
            val dst = File(target, "personal/${dir.name}")
            val newer = File(dir, "meta.json").lastModified() > File(dst, "meta.json").lastModified()
            if (!dst.exists()) {
                copyTree(dir, dst)
                added++
            } else if (newer) {
                deleteTree(dst)
                copyTree(dir, dst)
                replaced++
            } else {
                kept++
            }
        }
        added += copyMissingTree(File(source, "pending"), File(target, "pending"))
        added += copyMissingTree(File(source, "archive"), File(target, "archive"))

        runCatching {
            val targetJournal = File(target, "changes.jsonl")
            val existing = targetJournal.takeIf { it.isFile }?.readLines()?.toHashSet() ?: HashSet()
            val incoming = File(source, "changes.jsonl").takeIf { it.isFile }?.readLines().orEmpty()
                .filter { it.isNotBlank() && existing.add(it) }
            if (incoming.isNotEmpty()) {
                targetJournal.parentFile?.mkdirs()
                targetJournal.appendText(incoming.joinToString("\n") + "\n")
            }
        }
        report.add(BackupFormat.SKILLS, added, replaced, kept)
    }

    private fun applyKnowledge() {
        var added = 0
        added += copyMissingTree(data("data/knowledge/app-drafts"), File(filesDir, "knowledge/app-drafts"))
        added += copyMissingTree(data("data/knowledge/personal"), File(filesDir, "personal"))
        report.add(BackupFormat.KNOWLEDGE, added = added)
    }

    // ---------- 自动化 ----------

    private fun applyAutomation() {
        applyTriggers()
        applyRoutes()
        applyApprovals()
        applyAppAccess()
    }

    private fun applyTriggers() {
        val source = data("data/automation/triggers.json")
        if (!source.isFile) return
        val importedSnapshot = parseTriggerSnapshot(source) ?: return
        val existing = triggers.snapshot()
        val existingIds = existing.triggers.map { it.id }.toHashSet()
        val now = System.currentTimeMillis()
        val mergedTriggers = existing.triggers.toMutableList()
        val mergedWindows = existing.windows.toMutableList()
        val windowKeys = mergedWindows.map { it.key }.toHashSet()
        var added = 0
        var kept = 0
        var skipped = 0
        importedSnapshot.triggers.forEach { trigger ->
            checkCancelled()
            if (trigger.id in existingIds) {
                kept++
                return@forEach
            }
            val calendarTime = trigger.calendarTime
            val calendarZone = trigger.calendarZone
            val interval = trigger.intervalMs
            val next = when {
                !trigger.enabled -> maxOf(trigger.nextAtMs, now)
                calendarTime != null && calendarZone != null ->
                    runCatching { nextCalendarAt(calendarTime, calendarZone, trigger.calendarDays, now) }.getOrNull()
                interval != null -> now + interval
                trigger.nextAtMs > now -> trigger.nextAtMs
                else -> null
            }
            if (next == null) {
                skipped++
                return@forEach
            }
            mergedTriggers += trigger.copy(nextAtMs = next)
            importedSnapshot.windows.filter { it.triggerId == trigger.id }.forEach { window ->
                if (windowKeys.add(window.key)) mergedWindows += window
            }
            added++
        }
        if (added > 0) triggers.restore(TriggerSnapshot(mergedTriggers, mergedWindows))
        report.add(BackupFormat.AUTOMATION, added, kept = kept, skipped = skipped, note = "triggers")
    }

    private fun parseTriggerSnapshot(file: File): TriggerSnapshot? {
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        runCatching { json.decodeFromString(TriggerSnapshot.serializer(), text) }.getOrNull()?.let { return it }
        val list = runCatching { json.decodeFromString(ListSerializer(TriggerDefinition.serializer()), text) }.getOrNull()
        return list?.let { TriggerSnapshot(it, emptyList()) }
    }

    private fun applyRoutes() {
        val source = data("data/automation/routes")
        if (!source.isDirectory) return
        val existing = FileRouteStore(File(filesDir, "routes")).all().map { it.id }.toHashSet()
        val target = File(filesDir, "routes")
        var added = 0
        var kept = 0
        source.walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.forEach { file ->
            checkCancelled()
            val route = runCatching { RouteCodec.decode(file.readText()) }.getOrNull()
            if (route == null) {
                report.warn("route")
                return@forEach
            }
            if (route.id in existing) {
                kept++
                return@forEach
            }
            val rel = source.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
            copyAtomic(file, File(target, rel))
            existing += route.id
            added++
        }
        report.add(BackupFormat.AUTOMATION, added = added, kept = kept, note = "routes")
    }

    private fun applyApprovals() {
        val source = data("data/automation/approvals.json")
        if (!source.isFile) return
        val imported = runCatching { json.parseToJsonElement(source.readText()).jsonObject }.getOrNull() ?: return
        val target = File(filesDir, "approvals.json")
        if (!target.isFile) {
            copyAtomic(source, target)
            report.add(BackupFormat.AUTOMATION, added = 1, note = "approvals")
            return
        }
        val existing = runCatching { json.parseToJsonElement(target.readText()).jsonObject }.getOrNull()
        val current = existing?.get("autonomous")?.jsonPrimitive?.booleanOrNull ?: false
        val incoming = imported["autonomous"]?.jsonPrimitive?.booleanOrNull ?: false
        writeAtomic(target, buildJsonObject { put("autonomous", MergePlan.stricter(incoming, current)) }.toString().toByteArray())
        report.add(BackupFormat.AUTOMATION, kept = 1, note = "approvals")
    }

    private fun applyAppAccess() {
        val source = data("data/automation/app-access.json")
        if (!source.isFile) return
        val target = File(filesDir, "app-access.json")
        if (!target.isFile) {
            copyAtomic(source, target)
            report.add(BackupFormat.AUTOMATION, added = 1, note = "app-access")
            return
        }
        val imported = runCatching { json.parseToJsonElement(source.readText()).jsonObject }.getOrNull() ?: return
        val existing = runCatching { json.parseToJsonElement(target.readText()).jsonObject }.getOrNull() ?: return
        fun set(o: JsonObject, key: String): Set<String> =
            (o[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet().orEmpty()
        val blocked = MergePlan.union(set(imported, "blocked"), set(existing, "blocked"))
        val shareAllowed = MergePlan.intersect(set(imported, "shareAllowed"), set(existing, "shareAllowed"))
        val merged = buildJsonObject {
            put("version", 1)
            put("blocked", buildJsonArray { blocked.sorted().forEach { add(JsonPrimitive(it)) } })
            put("shareAllowed", buildJsonArray { shareAllowed.sorted().forEach { add(JsonPrimitive(it)) } })
            val classes = LinkedHashMap<String, JsonElement>()
            (existing["classifications"] as? JsonObject)?.forEach { (k, v) -> classes[k] = v }
            (imported["classifications"] as? JsonObject)?.forEach { (k, v) ->
                val old = classes[k] as? JsonObject
                if (old == null || old["manual"]?.jsonPrimitive?.booleanOrNull != true) classes[k] = v
            }
            put("classifications", JsonObject(classes))
        }
        writeAtomic(target, merged.toString().toByteArray())
        report.add(BackupFormat.AUTOMATION, kept = 1, note = "app-access")
    }

    // ---------- 站点 ----------

    private fun applyWeb() {
        val source = data("data/web/sites.json")
        if (!source.isFile) return
        val imported = runCatching { json.parseToJsonElement(source.readText()) as? JsonArray }.getOrNull() ?: return
        val target = File(filesDir, "web/sites.json")
        val existing = target.takeIf { it.isFile }?.let { runCatching { json.parseToJsonElement(it.readText()) as? JsonArray }.getOrNull() }
            ?: JsonArray(emptyList())
        val byHost = LinkedHashMap<String, JsonObject>()
        existing.forEach { e ->
            val o = e as? JsonObject ?: return@forEach
            o["host"]?.jsonPrimitive?.contentOrNull?.let { byHost[it] = o }
        }
        imported.forEach { e ->
            val o = e as? JsonObject ?: return@forEach
            val host = o["host"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val old = byHost[host]
            val last = maxOf(old?.get("lastVisitMs")?.jsonPrimitive?.longOrNull ?: 0L, o["lastVisitMs"]?.jsonPrimitive?.longOrNull ?: 0L)
            // 以已有对象/导入对象为底,只覆盖这三个键;challengedAt / challenge 保留
            val base = old ?: o
            byHost[host] = buildJsonObject {
                base.forEach { (k, v) -> if (k !in setOf("host", "lastVisitMs", "loggedIn")) put(k, v) }
                put("host", host)
                put("lastVisitMs", last)
                // Cookie 不随包走,恢复后一律当作未登录
                put("loggedIn", false)
            }
        }
        target.parentFile?.mkdirs()
        writeAtomic(target, JsonArray(byHost.values.toList()).toString().toByteArray())
        report.add(BackupFormat.WEB, added = imported.size, note = "logged-out")
    }

    // ---------- 工作区 ----------

    private fun applyWorkspace() {
        val source = data("data/workspace")
        if (!source.isDirectory) return
        var files = 0
        var bytes = 0L
        val target = File(filesDir, "sandbox/work")
        source.walkTopDown().onEnter { it == source || !Files.isSymbolicLink(it.toPath()) }.forEach { f ->
            checkCancelled()
            if (!f.isFile || Files.isSymbolicLink(f.toPath())) return@forEach
            val rel = source.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/')
            val dst = File(target, rel)
            if (!dst.exists()) {
                copyAtomic(f, dst)
                files++
                bytes += f.length()
            }
        }
        report.add(BackupFormat.WORKSPACE, added = files, note = bytes.toString())
    }

    // ---------- 文件工具 ----------

    private fun copyAtomic(source: File, target: File) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".backup-tmp")
        try {
            source.copyTo(tmp, overwrite = true)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            tmp.delete()
        }
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".backup-tmp")
        try {
            FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            tmp.delete()
        }
    }

    private fun copyTree(source: File, target: File) {
        checkCancelled()
        if (Files.isSymbolicLink(source.toPath())) return
        if (source.isDirectory) {
            target.mkdirs()
            source.listFiles()?.forEach { copyTree(it, File(target, it.name)) }
        } else if (source.isFile) {
            copyAtomic(source, target)
        }
    }

    /** 只补目标里没有的文件;已有的不动。返回新增文件数。 */
    private fun copyMissingTree(source: File, target: File): Int {
        if (!source.exists()) return 0
        var n = 0
        source.walkTopDown().onEnter { it == source || !Files.isSymbolicLink(it.toPath()) }.forEach { f ->
            checkCancelled()
            if (!f.isFile || Files.isSymbolicLink(f.toPath())) return@forEach
            val rel = source.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/')
            val dst = File(target, rel)
            if (!dst.exists()) {
                copyAtomic(f, dst)
                n++
            }
        }
        return n
    }

    private fun deleteTree(file: File) {
        if (Files.isSymbolicLink(file.toPath())) {
            file.delete()
            return
        }
        if (file.isDirectory) file.listFiles()?.forEach(::deleteTree)
        file.delete()
    }
}
