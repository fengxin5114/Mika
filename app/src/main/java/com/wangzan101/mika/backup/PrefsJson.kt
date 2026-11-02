package com.wangzan101.mika.backup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * SharedPreferences 的备份白名单。只带用户主动选过的设置;语音识别试错状态、升级标记这些跟着设备走,不带。
 * `speech_doubao_credentials_encrypted` 由 [SecretVault] 单独解密,不在这里出现。
 */
object PrefsJson {
    const val LANGUAGE_PREFS = "app_language"
    const val LANGUAGE_TAG = "tag"

    val FEATURE_KEYS = linkedSetOf(
        "speed_flags", "parallel_max", "keep_screen_on", "mute_target", "operating_pace",
        "self_config", "theme_mode", "capsule_mode", "capsule_side", "capsule_height",
        "capsule_card_height", "speech_choice",
    )

    fun export(prefs: BackupPrefs): JsonObject = buildJsonObject {
        val all = prefs.all()
        for (key in FEATURE_KEYS) {
            val value = all[key] ?: continue
            val json = valueOf(value) ?: continue
            put(key, json)
        }
    }

    /** 只补目标里没有的键;用户当前选过的一律保留。 */
    fun applyMissing(prefs: BackupPrefs, json: JsonObject) {
        for ((key, value) in json) {
            if (key !in FEATURE_KEYS || prefs.contains(key)) continue
            prefs.put(key, valueOf(value))
        }
    }

    /** 撤销导入时用:先把白名单键全部去掉,再按快照写回。 */
    fun replace(prefs: BackupPrefs, json: JsonObject) {
        for (key in FEATURE_KEYS) prefs.remove(key)
        for ((key, value) in json) if (key in FEATURE_KEYS) prefs.put(key, valueOf(value))
    }

    fun exportLanguage(prefs: BackupPrefs): JsonObject = buildJsonObject {
        prefs.all()[LANGUAGE_TAG]?.let { put(LANGUAGE_TAG, it.toString()) }
    }

    fun applyLanguageMissing(prefs: BackupPrefs, json: JsonObject) {
        if (prefs.contains(LANGUAGE_TAG)) return
        json[LANGUAGE_TAG]?.let { prefs.put(LANGUAGE_TAG, it.toStringValue()) }
    }

    fun replaceLanguage(prefs: BackupPrefs, json: JsonObject) {
        prefs.remove(LANGUAGE_TAG)
        json[LANGUAGE_TAG]?.let { prefs.put(LANGUAGE_TAG, it.toStringValue()) }
    }

    private fun JsonElement.toStringValue(): String =
        (this as? JsonPrimitive)?.content.orEmpty()

    private fun valueOf(value: Any?): JsonElement? = when (value) {
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is Set<*> -> JsonArray(value.mapNotNull { it as? String }.sorted().map(::JsonPrimitive))
        else -> null
    }

    /** 把 JSON 还原成 SharedPreferences 能放的类型。 */
    private fun valueOf(value: JsonElement): Any? {
        if (value is JsonArray) return value.mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
        val primitive = value as? JsonPrimitive ?: return null
        return when {
            primitive.isString -> primitive.content
            primitive.booleanOrNull != null -> primitive.booleanOrNull
            primitive.intOrNull != null -> primitive.intOrNull
            primitive.longOrNull != null -> primitive.longOrNull
            primitive.doubleOrNull != null -> primitive.doubleOrNull!!.toFloat()
            else -> null
        }
    }
}
