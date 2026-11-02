package com.wangzan101.mika.backup

import com.wangzan101.mika.core.backup.BackupFormat
import com.wangzan101.mika.core.trigger.TriggerSnapshot
import com.wangzan101.mika.core.trigger.TriggerWindow
import com.wangzan101.mika.core.trigger.TriggerWindowState
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PackageCounterTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun countsEverySectionAndMismatches() {
        val root = tmp.newFolder("extracted")
        fun write(rel: String, text: String) {
            File(root, rel).apply { parentFile.mkdirs() }.writeText(text)
        }
        write("data/settings/providers.json", "{}")
        write("data/settings/memory_settings.json", "{}")
        write("data/settings/prefs_feature_flags.json", """{"a":1,"b":2}""")
        write("data/settings/prefs_app_language.json", """{"tag":"zh"}""")
        write("secrets.json", """{"version":1,"providers":{"p1":"k1","p2":"k2"},"mcpToken":"x"}""")
        write("data/conversations/a.json", "[]")
        write("data/conversations/b.json", "[]")
        write("data/memory/items.json", "[{},{}]")
        write("data/memory/profile.json", "[{}]")
        File(root, "data/skills/personal/s1").mkdirs()
        File(root, "data/skills/pending/p1").mkdirs()
        File(root, "data/skills/archive/a1").mkdirs()
        File(root, "data/knowledge/app-drafts/d1").mkdirs()
        write("data/knowledge/personal/apps/x/NOTES.md", "note")
        val snapshot = TriggerSnapshot(
            listOf(trigger("t1"), trigger("t2")),
            listOf(TriggerWindow("t1", 1, 2, state = TriggerWindowState.Delivered)),
        )
        write("data/automation/triggers.json", Json.encodeToString(TriggerSnapshot.serializer(), snapshot))
        write("data/automation/routes/com.x/r1.json", "{}")
        write("data/automation/approvals.json", "{}")
        write("data/automation/app-access.json", "{}")
        write("data/web/sites.json", """[{"host":"a"},{"host":"b"}]""")
        write("data/workspace/c-1/file.txt", "hello")
        write("data/workspace/c-1/sub/file2.txt", "world!")

        val counts = PackageCounter.count(root)
        assertEquals(2L, counts[BackupFormat.COUNT_SETTINGS_FILES])
        assertEquals(2L, counts[BackupFormat.COUNT_PREFS_FEATURE_KEYS])
        assertEquals(1L, counts[BackupFormat.COUNT_PREFS_LANGUAGE])
        assertEquals(2L, counts[BackupFormat.COUNT_PROVIDER_KEYS])
        assertEquals(1L, counts[BackupFormat.COUNT_SECRET_ITEMS])
        assertEquals(2L, counts[BackupFormat.COUNT_CONVERSATIONS])
        assertEquals(2L, counts[BackupFormat.COUNT_MEMORY_ITEMS])
        assertEquals(1L, counts[BackupFormat.COUNT_PROFILE_ENTRIES])
        assertEquals(1L, counts[BackupFormat.COUNT_PERSONAL_SKILLS])
        assertEquals(1L, counts[BackupFormat.COUNT_PENDING_SKILLS])
        assertEquals(1L, counts[BackupFormat.COUNT_SKILL_ARCHIVES])
        assertEquals(1L, counts[BackupFormat.COUNT_KNOWLEDGE_DRAFTS])
        assertEquals(1L, counts[BackupFormat.COUNT_KNOWLEDGE_NOTES])
        assertEquals(2L, counts[BackupFormat.COUNT_TRIGGERS])
        assertEquals(1L, counts[BackupFormat.COUNT_TRIGGER_WINDOWS])
        assertEquals(1L, counts[BackupFormat.COUNT_ROUTES])
        assertEquals(1L, counts[BackupFormat.COUNT_APPROVALS])
        assertEquals(1L, counts[BackupFormat.COUNT_APP_ACCESS])
        assertEquals(2L, counts[BackupFormat.COUNT_WEB_SITES])
        assertEquals(2L, counts[BackupFormat.COUNT_WORKSPACE_FILES])
        assertEquals(11L, counts[BackupFormat.COUNT_WORKSPACE_BYTES])

        val expected = counts + (BackupFormat.COUNT_CONVERSATIONS to 3L)
        assertEquals(listOf(BackupFormat.COUNT_CONVERSATIONS), PackageCounter.mismatches(expected, counts))
    }
}
