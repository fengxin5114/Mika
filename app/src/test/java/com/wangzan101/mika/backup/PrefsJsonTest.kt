package com.wangzan101.mika.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsJsonTest {
    @Test
    fun exportsOnlyWhitelistedKeys() {
        val prefs = FakePrefs(mapOf(
            "theme_mode" to "Dark",
            "capsule_height" to 0.4f,
            "parallel_max" to 2,
            "speech_doubao_credentials_encrypted" to "secret",
            "onboarding_done" to true,
        ))
        val json = PrefsJson.export(prefs)
        assertTrue(json.containsKey("theme_mode"))
        assertTrue(json.containsKey("capsule_height"))
        assertTrue(json.containsKey("parallel_max"))
        assertFalse(json.containsKey("speech_doubao_credentials_encrypted"))
        assertFalse(json.containsKey("onboarding_done"))
    }

    @Test
    fun applyMissingKeepsExistingValues() {
        val prefs = FakePrefs(mapOf("theme_mode" to "Light", "keep_screen_on" to false))
        val imported = PrefsJson.export(FakePrefs(mapOf(
            "theme_mode" to "Dark",
            "keep_screen_on" to true,
            "capsule_mode" to "Always",
        )))
        PrefsJson.applyMissing(prefs, imported)
        assertEquals("Light", prefs.map["theme_mode"])
        assertEquals(false, prefs.map["keep_screen_on"])
        assertEquals("Always", prefs.map["capsule_mode"])
    }

    @Test
    fun replaceRestoresSnapshotExactly() {
        val prefs = FakePrefs(mapOf("theme_mode" to "Light", "capsule_mode" to "Off"))
        val snapshot = PrefsJson.export(FakePrefs(mapOf("theme_mode" to "Dark")))
        PrefsJson.replace(prefs, snapshot)
        assertEquals("Dark", prefs.map["theme_mode"])
        assertFalse(prefs.map.containsKey("capsule_mode"))
    }

    @Test
    fun languageRoundTrip() {
        val prefs = FakePrefs(mapOf("tag" to "zh-Hant"))
        val json = PrefsJson.exportLanguage(prefs)
        assertEquals("zh-Hant", json["tag"].toString().trim('"'))
        val target = FakePrefs()
        PrefsJson.applyLanguageMissing(target, json)
        assertEquals("zh-Hant", target.map["tag"])
    }
}
