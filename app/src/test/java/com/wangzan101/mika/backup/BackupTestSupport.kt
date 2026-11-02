package com.wangzan101.mika.backup

import com.wangzan101.mika.core.backup.BackupCipher
import com.wangzan101.mika.core.backup.BackupKdf
import com.wangzan101.mika.core.backup.BackupManifest
import com.wangzan101.mika.core.backup.BackupPayload
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.TriggerSnapshot
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.KeyCipher
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class FakePrefs(initial: Map<String, Any?> = emptyMap()) : BackupPrefs {
    val map = LinkedHashMap<String, Any?>(initial)
    override fun contains(key: String) = map.containsKey(key)
    override fun all(): Map<String, Any?> = map.toMap()
    override fun put(key: String, value: Any?) {
        if (value == null) map.remove(key) else map[key] = value
    }
    override fun remove(key: String) { map.remove(key) }
}

class MemoryTriggers(var value: TriggerSnapshot = TriggerSnapshot(emptyList(), emptyList())) : BackupTriggers {
    var restores = 0
    override fun snapshot(): TriggerSnapshot = value
    override fun restore(snapshot: TriggerSnapshot) {
        value = snapshot
        restores++
    }
}

fun testKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

fun testCipher(key: SecretKey): KeyCipher = AesGcmKeyCipher { key }

fun testManifest(
    sections: List<String>,
    counts: Map<String, Long> = emptyMap(),
    countsMac: String = "",
) = BackupManifest(
    appVersion = "0.1.33",
    exportedAt = "2026-11-02T00:00:00Z",
    kdf = BackupKdf(salt = Base64.getEncoder().encodeToString(ByteArray(16))),
    cipher = BackupCipher(iv = Base64.getEncoder().encodeToString(ByteArray(12))),
    sections = sections,
    counts = counts,
    countsMac = countsMac,
    payload = BackupPayload(bytes = 0, sha256 = "0".repeat(64)),
)

fun trigger(id: String, nextAtMs: Long = System.currentTimeMillis() + 60_000): TriggerDefinition =
    TriggerDefinition(id = id, threadId = "thread-$id", goal = "goal-$id", nextAtMs = nextAtMs)
