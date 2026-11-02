package com.wangzan101.mika.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RestoreSnapshotTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun restoresFilesAndPrefsAndRemovesImportedExtras() {
        val filesDir = tmp.newFolder("files")
        File(filesDir, "settings").mkdirs()
        File(filesDir, "settings/providers.json").writeText("old")
        File(filesDir, "conversations").mkdirs()
        File(filesDir, "conversations/a.json").writeText("[]")
        val feature = FakePrefs(mapOf("theme_mode" to "Light"))
        val language = FakePrefs(mapOf("tag" to "zh"))
        val snapshot = RestoreSnapshot(
            filesDir, RestoreSnapshot.newDir(filesDir), includeWorkspace = false, feature, language,
        )
        snapshot.save()

        File(filesDir, "settings/providers.json").writeText("new")
        File(filesDir, "conversations/b.json").writeText("[]")
        feature.put("theme_mode", "Dark")
        feature.put("capsule_mode", "Always")
        language.put("tag", "en")

        snapshot.restore()

        assertEquals("old", File(filesDir, "settings/providers.json").readText())
        assertTrue(File(filesDir, "conversations/a.json").isFile)
        assertFalse(File(filesDir, "conversations/b.json").exists())
        assertEquals("Light", feature.map["theme_mode"])
        assertFalse(feature.map.containsKey("capsule_mode"))
        assertEquals("zh", language.map["tag"])
    }

    @Test
    fun workspaceIsOnlySnapshottedWhenSelected() {
        val filesDir = tmp.newFolder("files-ws")
        File(filesDir, "sandbox/work/c-1").mkdirs()
        File(filesDir, "sandbox/work/c-1/file.txt").writeText("old")
        val feature = FakePrefs()
        val language = FakePrefs()
        val snapshot = RestoreSnapshot(
            filesDir, RestoreSnapshot.newDir(filesDir), includeWorkspace = true, feature, language,
        )
        snapshot.save()
        File(filesDir, "sandbox/work/c-1/file.txt").writeText("new")
        snapshot.restore()
        assertEquals("old", File(filesDir, "sandbox/work/c-1/file.txt").readText())
    }
}
