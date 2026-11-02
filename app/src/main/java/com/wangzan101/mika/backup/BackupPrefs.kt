package com.wangzan101.mika.backup

import android.content.SharedPreferences

/**
 * 备份只用到的偏好设置操作。生产实现是 [SharedPreferences];JVM 测试用内存实现,
 * 这样 RestoreApplier / BackupCollector 不需要 Android Context。
 */
interface BackupPrefs {
    fun contains(key: String): Boolean
    fun all(): Map<String, Any?>
    fun put(key: String, value: Any?)
    fun remove(key: String)
}

class SharedBackupPrefs(private val prefs: SharedPreferences) : BackupPrefs {
    override fun contains(key: String): Boolean = prefs.contains(key)

    override fun all(): Map<String, Any?> = prefs.all as Map<String, Any?>

    override fun put(key: String, value: Any?) {
        val edit = prefs.edit()
        when (value) {
            null -> edit.remove(key)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is String -> edit.putString(key, value)
            is Set<*> -> edit.putStringSet(key, value.mapNotNull { it as? String }.toSet())
        }
        edit.commit()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).commit()
    }
}
